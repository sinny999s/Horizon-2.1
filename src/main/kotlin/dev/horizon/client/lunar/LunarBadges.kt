package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.horizon.client.HorizonClient
import dev.horizon.client.render.Textures
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lunar's badges (Lunar+ and event badges a player shows by their name). Which badge is which
 * picture comes from `assets/lunar/badges.json` on Lunar's CDN (id, name, `lunar:badges/<file>.webp`); the pictures are
 * there too (16×16 or 32×32 WebP; an animated one is its frames stacked, with an `.mcmeta` as Minecraft's). Fetched
 * through [LunarAssets] on its pool, uploaded on the game thread.
 */
object LunarBadges {
    private const val INDEX = "assets/lunar/badges.json"
    private const val RETRY_MS = 60_000L

    /** A badge from badges.json: its id, name and picture's asset path. */
    data class Badge(val id: Int, val name: String, val path: String, val animated: Boolean)

    /** A badge picture's current frame: [texture] from v [v0] to [v1] (0..1), the whole width. */
    class Sprite(val texture: Identifier, val v0: Float, val v1: Float)

    private class Picture(val texture: Identifier, val frames: Int, val frametime: Int)
    private class Decoded(val path: String, val image: BufferedImage, val frameHeight: Int, val frametime: Int)

    private object Loading
    private object Failed

    private val mc: Minecraft get() = Minecraft.getInstance()

    @Volatile
    private var badges: Map<Int, Badge>? = null

    @Volatile
    private var loading = false
    private var retryAt = 0L

    /** Pictures by asset path: a [Picture] once uploaded, [Loading] or [Failed]. */
    private val pictures = ConcurrentHashMap<String, Any>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()

    /** badges.json: its array of badges. Game-free. */
    fun parse(json: String): Map<Int, Badge> {
        val out = HashMap<Int, Badge>()
        for (el in JsonParser.parseString(json) as? JsonArray ?: return out) {
            val o = el.asJsonObject
            val id = o.get("id")?.asInt ?: continue
            val res = o.get("resource")?.asString ?: continue
            out[id] = Badge(id, o.get("name")?.asString ?: "#$id", path(res), o.get("animated")?.asBoolean == true)
        }
        return out
    }

    /** A resource name ("lunar:badges/plus.webp") as its asset path ("assets/lunar/badges/plus.webp"). */
    fun path(resource: String): String {
        val ns = resource.substringBefore(':', "minecraft")
        return "assets/$ns/" + resource.substringAfter(':')
    }

    /** Every badge in badges.json (empty until it's in; starts fetching it). */
    fun all(): List<Badge> {
        badges?.let { return it.values.sortedBy { b -> b.id } }
        ensure()
        return emptyList()
    }

    /** Badge [id]'s entry once badges.json is in (starts fetching it). */
    fun badge(id: Int): Badge? {
        if (id == 0) return null
        badges?.let { return it[id] }
        ensure()
        return null
    }

    private fun ensure() {
        if (badges != null || loading || System.currentTimeMillis() < retryAt) return
        if (LunarAssets.assets == null) {
            LunarAssets.ensure()
            return
        }
        loading = true
        LunarAssets.pool.execute {
            try {
                badges = parse(String(LunarAssets.file(INDEX), Charsets.UTF_8)).also {
                    HorizonClient.LOG.info("Lunar badges: {} badges in Lunar's index", it.size)
                }
            } catch (e: Exception) {
                retryAt = System.currentTimeMillis() + RETRY_MS
                HorizonClient.LOG.warn("Lunar badges: couldn't load {}: {}", INDEX, e.toString())
            } finally {
                loading = false
            }
        }
    }

    /** Badge [id]'s picture at its current frame, once it's in (starts fetching it). Game or render thread. */
    fun sprite(id: Int): Sprite? {
        val b = badge(id) ?: return null
        while (true) upload(decoded.poll() ?: break)
        return when (val p = pictures[b.path]) {
            is Picture -> {
                val frame = if (p.frames <= 1) 0 else ((System.currentTimeMillis() / (50L * p.frametime)) % p.frames).toInt()
                Sprite(p.texture, frame.toFloat() / p.frames, (frame + 1f) / p.frames)
            }
            null -> {
                fetch(b)
                null
            }
            else -> null
        }
    }

    private fun fetch(b: Badge) {
        pictures[b.path] = Loading
        LunarAssets.pool.execute {
            try {
                val img = WebP.decode(LunarAssets.file(b.path))
                var frameHeight = img.height
                var frametime = 1
                if (b.animated && LunarAssets.has(b.path + ".mcmeta")) {
                    LunarCosmeticIndex.parseMcmeta(String(LunarAssets.file(b.path + ".mcmeta"), Charsets.UTF_8))?.let { a ->
                        frameHeight = maxOf(1, Math.round(a.height * img.width.toFloat() / a.width))
                        frametime = a.frametime
                    }
                }
                decoded += Decoded(b.path, img, frameHeight, frametime)
            } catch (e: Exception) {
                pictures[b.path] = Failed
                HorizonClient.LOG.warn("Lunar badges: couldn't load {}: {}", b.path, e.toString())
            }
        }
    }

    private fun upload(d: Decoded) {
        val id = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/lunar/" + d.path.lowercase().replace(Regex("[^a-z0-9/._-]"), "_"))
        mc.textureManager.register(id, Textures.upload("lunar ${d.path}", d.image))
        pictures[d.path] = Picture(id, maxOf(1, d.image.height / d.frameHeight), d.frametime)
    }
}
