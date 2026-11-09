package dev.horizon.client.hud.widgets

import dev.horizon.client.combat.Bedwars
import dev.horizon.client.combat.HypixelChat
import dev.horizon.client.compat.HypixelApi
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerDeathEvent
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import kotlin.math.max

/**
 * Session Stats HUD widget showing session playtime, kills, deaths and K/D ratio.
 *
 * On Hypixel (in Bedwars, or wherever the Mod API says it's Hypixel) nobody really dies: you're made a spectator, so a
 * death is only seen in chat. There it reads the server's own lines instead ([HypixelChat]): kills, deaths, final
 * ones and beds, and the "VICTORY!" / "GAME OVER!" titles as a win or a loss. Elsewhere it counts as before: a player dying
 * within 40 blocks is a kill, your health reaching 0 a death.
 */
object SessionStatsWidget : HudWidget("session-stats-widget") {
    val showPlaytime = bool("playtime", true)
    val showKills = bool("kills", true)
    val showDeaths = bool("deaths", true)
    val showKd = bool("kd", true)
    /** Final kills, beds and wins: Bedwars' stats (only shown once there's been Hypixel or one of them). */
    val showBedwars = bool("bedwars", true)

    var kills = 0
    var deaths = 0
    var finalKills = 0
    var finalDeaths = 0
    var beds = 0
    var wins = 0
    var losses = 0
    val sessionStartTime = System.currentTimeMillis()

    private var wasDead = false
    /** Seen Hypixel's lines this session: the Bedwars rows are shown from then on. */
    private var hypixelSeen = false
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    /** Counting from chat now: in Bedwars or on Hypixel. */
    private val fromChat get() = Bedwars.raw != Bedwars.Phase.NONE || Bedwars.phase != Bedwars.Phase.NONE || HypixelApi.location != null

    init {
        EventBus.on<PlayerDeathEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (fromChat) return@on
            if (e.player !== p && e.player.distanceTo(p) < 40f) {
                kills++
            }
        }
        EventBus.on<AfterReceivePacketEvent>(this) { e ->
            when (val pk = e.packet) {
                is ClientboundSystemChatPacket -> if (!pk.overlay() && fromChat) readHypixel(pk.content().string)
                is ClientboundSetTitleTextPacket -> if (fromChat) when (plain(pk.text().string).trim().uppercase()) {
                    "VICTORY!" -> wins++.also { hypixelSeen = true }
                    "GAME OVER!" -> losses++.also { hypixelSeen = true }
                }
                else -> {}
            }
        }
    }

    /** One chat line from the server: a kill, a death, a bed ([HypixelChat]). */
    fun readHypixel(raw: String) {
        val me = mc.player?.gameProfile?.name ?: return
        when (HypixelChat.classify(raw, me)) {
            HypixelChat.Kind.KILL -> kills++
            HypixelChat.Kind.FINAL_KILL -> { kills++; finalKills++ }
            HypixelChat.Kind.DEATH -> deaths++
            HypixelChat.Kind.FINAL_DEATH -> { deaths++; finalDeaths++ }
            HypixelChat.Kind.BED -> beds++
            HypixelChat.Kind.NONE -> return
        }
        hypixelSeen = true
    }

    private fun plain(s: String) = s.replace(FORMAT, "")

    override fun tick() {
        val p = mc.player ?: return
        val dead = p.isDeadOrDying || p.health <= 0f
        if (dead && !wasDead && !fromChat) {
            deaths++
        }
        wasDead = dead
    }

    override fun render() {
        val title = "Session Stats"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (enabled || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)

        val rows = ArrayList<Pair<String, String>>()

        if (showPlaytime.value) {
            val uptime = System.currentTimeMillis() - sessionStartTime
            val hours = uptime / 3600000
            val minutes = (uptime % 3600000) / 60000
            val seconds = (uptime % 60000) / 1000
            val timeStr = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
            rows += "Playtime" to timeStr
        }

        if (showKills.value) {
            rows += "Kills" to kills.toString()
        }

        if (showDeaths.value) {
            rows += "Deaths" to deaths.toString()
        }

        if (showKd.value) {
            val kd = if (deaths == 0) kills.toFloat() else kills.toFloat() / deaths
            rows += "K/D" to String.format("%.2f", kd)
        }

        if (showBedwars.value && (hypixelSeen || editorMode)) {
            rows += "Finals" to finalKills.toString()
            val fkdr = if (finalDeaths == 0) finalKills.toFloat() else finalKills.toFloat() / finalDeaths
            rows += "FKDR" to String.format("%.2f", fkdr)
            rows += "Beds" to beds.toString()
            rows += "W/L" to "$wins/$losses"
        }

        var width = headerWidth(title)
        for ((label, value) in rows) {
            val rowW = cellWidth(label) + 2f + cellWidth(value)
            width = max(width, rowW)
        }

        var y = 17f
        for ((label, value) in rows) {
            val lw = cellWidth(label)
            val vw = cellWidth(value)
            val totalW = lw + 2f + vw
            val rx = if (alignRight) left + width - totalW else left

            cell(label, rx, top + y, color = Tok.T3, alpha = 1f)
            cell(value, rx + lw + 2f, top + y, color = Tok.T1, alpha = 1f)
            y += 14f
        }

        size(width, y - 1)
    }

    private val FORMAT = Regex("§.")
}
