package dev.horizon.client.hud.widgets

import dev.horizon.client.HorizonClient
import dev.horizon.client.compat.HypixelApi
import dev.horizon.client.compat.HypixelPlayerApi
import dev.horizon.client.compat.HypixelPlayerApi.Stats
import dev.horizon.client.config.Storage
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import java.nio.file.Files
import java.util.UUID
import kotlin.math.max

/**
 * Your own Hypixel stats, from Hypixel's public API ([HypixelPlayerApi]): network level, Bedwars
 * stars (in the prestige's colour), FKDR, W/L, beds broken/lost, final kills, wins and winstreak. With Session Gain on,
 * what you've gained since the game started is shown after a total ("+12").
 *
 * The API needs a key, free from developer.hypixel.net, set once with `^hypixel key <key>` (kept in
 * `horizon/hypixel-stats/key.txt`). Asked for while you're on Hypixel: on arriving, every [REFRESH_MS], and a few seconds
 * after a game ends ("VICTORY!" / "GAME OVER!"); `^hypixel refresh` asks now. The last answer is kept
 * (`horizon/hypixel-stats/stats.json`), so the widget shows it from the start, on Hypixel or not.
 */
object HypixelStatsWidget : HudWidget("hypixel-stats-widget") {
    val showLevel = bool("network-level", true)
    val showStars = bool("bedwars-stars", true)
    val showFkdr = bool("fkdr", true)
    val showWlr = bool("wlr", true)
    val showBblr = bool("bblr", false)
    val showFinals = bool("final-kills", true)
    val showWins = bool("wins", true)
    val showWinstreak = bool("winstreak", false)
    /** "+n" after a total: what's been gained since the game started. */
    val showGain = bool("session-gain", true)

    @Volatile
    var stats: Stats? = null
        private set

    /** The first stats seen this game session (for the "+n"). */
    @Volatile
    private var baseline: Stats? = null

    /** Why the last ask failed (shown under the header until one works). */
    @Volatile
    var problem: String? = null
        private set

    @Volatile
    private var asking = false
    private var nextAsk = 0L
    private var loaded = false
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    private val dir get() = Storage.dir("hypixel-stats")

    @Volatile
    private var savedKey: String? = null

    /** The API key, or null when not set. */
    var key: String?
        get() {
            load()
            return savedKey
        }
        set(v) {
            load()
            val f = dir.resolve("key.txt")
            val k = v?.trim()?.takeIf { it.isNotEmpty() }
            if (k == null) Files.deleteIfExists(f) else Files.writeString(f, k)
            savedKey = k
            problem = null
            nextAsk = 0
        }

    init {
        EventBus.on<AfterReceivePacketEvent>(this) { e ->
            val pk = e.packet as? ClientboundSetTitleTextPacket ?: return@on
            if (HypixelApi.location == null) return@on
            val t = pk.text().string.replace(FORMAT, "").trim().uppercase()
            // Hypixel's API has the game's result a few seconds after its end.
            if (t == "VICTORY!" || t == "GAME OVER!") nextAsk = minOf(nextAsk, System.currentTimeMillis() + 8_000)
        }
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        stats = Storage.read(dir.resolve("stats.json"), Stats.serializer())
        savedKey = runCatching { Files.readString(dir.resolve("key.txt")).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Asks now (in the background), unless it's already asking. False when there's no key. */
    fun refresh(done: (String?) -> Unit = {}): Boolean {
        val k = key ?: run {
            problem = "no API key: ^hypixel key <key>"
            return false
        }
        if (asking) return true
        asking = true
        nextAsk = System.currentTimeMillis() + REFRESH_MS
        val uuid: UUID = mc.user.profileId
        Thread({
            val failure = try {
                when (val r = HypixelPlayerApi.fetch(k, uuid)) {
                    is HypixelPlayerApi.Result.Ok -> {
                        stats = r.stats
                        if (baseline == null) baseline = r.stats
                        problem = null
                        Storage.write(dir.resolve("stats.json"), Stats.serializer(), r.stats)
                        null
                    }
                    is HypixelPlayerApi.Result.Failed -> r.reason.also {
                        problem = it
                        HorizonClient.LOG.warn("Hypixel Stats: {}", it)
                    }
                }
            } finally {
                asking = false
            }
            done(failure)
        }, "Horizon-HypixelStats").apply { isDaemon = true }.start()
        return true
    }

    override fun tick() {
        load()
        if (HypixelApi.location == null || key == null || asking) return
        if (System.currentTimeMillis() >= nextAsk) refresh()
    }

    override fun render() {
        load()
        val title = "Hypixel Stats"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (enabled || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)

        val s = stats
        val base = baseline
        val rows = ArrayList<Row>()
        if (s == null) {
            rows += Row("Stats", problem ?: if (key == null) "^hypixel key <key>" else "waiting for Hypixel", Tok.T3)
        } else {
            fun gain(now: Int, then: Int?) = if (showGain.value && then != null && now > then) "+${now - then}" else null
            if (showLevel.value) rows += Row("Level", "%.2f".format(s.networkLevel), Tok.T1)
            if (showStars.value) rows += Row("Bedwars", "${s.bedwarsStars}✫", HypixelPlayerApi.prestigeColor(s.bedwarsStars),
                gain(s.bedwarsStars, base?.bedwarsStars))
            if (showFkdr.value) rows += Row("FKDR", "%.2f".format(s.fkdr), Tok.T1)
            if (showWlr.value) rows += Row("W/L", "%.2f".format(s.wlr), Tok.T1)
            if (showBblr.value) rows += Row("BBLR", "%.2f".format(s.bblr), Tok.T1)
            if (showFinals.value) rows += Row("Finals", "%,d".format(s.finalKills), Tok.T1, gain(s.finalKills, base?.finalKills))
            if (showWins.value) rows += Row("Wins", "%,d".format(s.wins), Tok.T1, gain(s.wins, base?.wins))
            if (showWinstreak.value) rows += Row("Winstreak", s.winstreak?.toString() ?: "hidden", Tok.T1)
            problem?.let { rows += Row("!", it, Tok.T3) }
        }

        var width = headerWidth(title)
        for (r in rows) width = max(width, rowWidth(r))
        var y = 17f
        for (r in rows) {
            val totalW = rowWidth(r)
            val rx = if (alignRight) left + width - totalW else left
            val lw = cellWidth(r.label)
            cell(r.label, rx, top + y, color = Tok.T3)
            cell(r.value, rx + lw + 2f, top + y, color = r.color)
            r.gain?.let { cell(it, rx + lw + 2f + cellWidth(r.value) + 2f, top + y, color = GAIN) }
            y += 14f
        }
        size(width, y - 1)
    }

    private class Row(val label: String, val value: String, val color: Int, val gain: String? = null)

    private fun rowWidth(r: Row) = cellWidth(r.label) + 2f + cellWidth(r.value) + (r.gain?.let { 2f + cellWidth(it) } ?: 0f)

    private const val REFRESH_MS = 5 * 60 * 1000L
    private val GAIN = 0xFF55FF55.toInt()
    private val FORMAT = Regex("§.")
}
