package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Lunar's emote music ("jams": song clips some emotes play). Lunar sends a playing emote's
 * music as an MP3 URL (UseEmotePush's `emote_soundtrack_url`); it's streamed and played around the emoting player as
 * Lunar plays it: louder near them, gone past 16 blocks, at Lunar's volumes (others' 0.3, your own 0.5, times Minecraft's
 * master volume), looping while the emote lasts. Your own emotes play their music when a jam is attached to their wheel
 * slot (GetJamPlayback gives its URL, which goes with UseEmote so others hear it). Decoded with JLayer and played through
 * Java's sound output on a thread of its own. Off with the Lunar menu's `emote-music`.
 */
object LunarJams {
    private const val RANGE = 16.0
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    private class Playing(val uuid: UUID, val url: String, val own: Boolean) : Thread("Horizon-LunarJam") {
        @Volatile
        var stopped = false

        /** The gain for the next frames (0..1), set every tick from where the player is. */
        @Volatile
        var gain = 0f

        init {
            isDaemon = true
        }

        override fun run() {
            var line: SourceDataLine? = null
            try {
                var started = System.currentTimeMillis()
                while (!stopped) {
                    val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", LunarJar.USER_AGENT).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream())
                    check(res.statusCode() == 200) { "returned ${res.statusCode()}" }
                    val bits = Bitstream(BufferedInputStream(res.body()))
                    val decoder = Decoder()
                    try {
                        while (!stopped) {
                            val header = bits.readFrame() ?: break
                            val out = decoder.decodeFrame(header, bits) as SampleBuffer
                            if (line == null) {
                                val format = AudioFormat(decoder.outputFrequency.toFloat(), 16, decoder.outputChannels, true, false)
                                line = AudioSystem.getSourceDataLine(format).also { it.open(format); it.start() }
                            }
                            val g = gain
                            val n = out.bufferLength
                            val pcm = out.buffer
                            val bytes = ByteArray(n * 2)
                            for (i in 0 until n) {
                                val s = (pcm[i] * g).toInt().coerceIn(-32768, 32767)
                                bytes[i * 2] = s.toByte()
                                bytes[i * 2 + 1] = (s shr 8).toByte()
                            }
                            line!!.write(bytes, 0, bytes.size)
                            bits.closeFrame()
                        }
                    } finally {
                        bits.close()
                    }
                    // As Lunar: a clip that ended within a second of starting isn't looped.
                    if (System.currentTimeMillis() - started < 1000) break
                    started = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                if (!stopped) HorizonClient.LOG.warn("Lunar jams: couldn't play {}: {}", url, e.toString())
            } finally {
                line?.let { it.stop(); it.close() }
                playing.remove(uuid, this)
            }
        }
    }

    private val playing = ConcurrentHashMap<UUID, Playing>()

    private val on get() = LunarSettings.network.value && LunarSettings.emoteMusic.value

    /** UseEmotePush's soundtrack URL (field 4), or null. Game-free. */
    fun soundtrack(bytes: ByteArray): String? {
        var url: String? = null
        LunarWire.fields(bytes) { f, i, t -> if (f == 4) url = i.readString() else i.skipField(t) }
        return url?.takeIf { it.startsWith("https://") }
    }

    /** An emote push: music starts with a UseEmotePush that has some, and stops with a StopEmotePush (any thread). */
    fun push(type: String, value: ByteArray) {
        val uuid = LunarWire.emotePlayer(value) ?: return
        if (type.endsWith(LunarEmotes.STOP_PUSH)) {
            stop(uuid)
            return
        }
        // Your own emote's music is already playing from playOwn.
        if (uuid == mc.user.profileId && playing[uuid]?.own == true) return
        val url = soundtrack(value) ?: return
        play(uuid, url, own = false)
    }

    fun play(uuid: UUID, url: String, own: Boolean) {
        if (!on) return
        stop(uuid)
        val p = Playing(uuid, url, own)
        playing[uuid] = p
        p.start()
    }

    fun stop(uuid: UUID) {
        playing.remove(uuid)?.stopped = true
    }

    fun stopAll() {
        for (p in playing.values) p.stopped = true
        playing.clear()
    }

    /** Every tick: each one's loudness from how far its player is; music of players gone or no longer emoting stops. */
    fun tick() {
        if (playing.isEmpty()) return
        val level = mc.level
        val me = mc.cameraEntity
        if (level == null || me == null || !on) {
            stopAll()
            return
        }
        val master = mc.options.getSoundSourceVolume(SoundSource.MASTER)
        for ((uuid, p) in playing) {
            val pl = level.getPlayerByUUID(uuid)
            if (pl == null || !LunarEmotes.isPlaying(uuid)) {
                stop(uuid)
                continue
            }
            val d = pl.position().distanceTo(me.position())
            val volume = if (p.own) 0.5f else 0.3f
            p.gain = (volume * master * (1.0 - d / RANGE).coerceIn(0.0, 1.0)).toFloat()
        }
    }

    /** Your emote [id] from wheel slot with jam [jamId]: asks Lunar for the music's URL, plays it, and returns it for UseEmote. */
    fun playOwn(jamId: Int, done: (String?) -> Unit) {
        val sent = LunarNetwork.request(LunarSocialWire.EMOTE_SERVICE, "GetJamPlayback", LunarWire.message { it.writeInt32(1, jamId) }) { out ->
            var url: String? = null
            LunarWire.fields(out) { f, i, t -> if (f == 1) url = i.readString() else i.skipField(t) }
            val u = url?.takeIf { it.startsWith("https://") }
            if (u != null) mc.execute { play(mc.user.profileId, u, own = true) }
            done(u)
        }
        if (!sent) done(null)
    }
}
