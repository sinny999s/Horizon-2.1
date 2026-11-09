package dev.horizon.client.combat

import dev.horizon.client.HorizonClient
import dev.horizon.client.compat.HypixelApi
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.TickEvent
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.level.GameType
import net.minecraft.world.scores.PlayerTeam

/**
 * Where you are in a game of Bedwars, read off the sidebar the way a player reads it. A match's
 * sidebar lists the teams ("R Red: ✔", "B Blue: 2", ...); a lobby's (the Bedwars lobby, a game's waiting room) has a
 * Bedwars title and no team lines. On Hypixel with its Mod API installed ([HypixelApi]), any lobby Hypixel names is a
 * lobby, and so is a Bedwars server in adventure mode (a game's waiting room: the match itself is survival). Anything
 * else (no sidebar, another game, an anarchy server) is [Phase.NONE]. Each settled change is logged with what it was
 * read from.
 *
 * It also finds your own bed where every bed is one colour (Hypixel's, seen by a new client, are all red): a match starts
 * with you on your island, so the first bed you stand near once it starts, and after a respawn, is yours ([BedTeams.learnOwn]).
 */
object Bedwars {
    enum class Phase { NONE, LOBBY, MATCH }

    private val mc: Minecraft get() = Minecraft.getInstance()

    /** The phase the sidebar shows this tick. */
    var raw = Phase.NONE
        private set
    /** [raw] once it's held for [SETTLE] ticks: a sidebar is sent line by line, and a server switch clears it. */
    var phase = Phase.NONE
        private set
    private var rawTicks = 0

    /**
     * In a lobby now: the sidebar says so this tick, or it's empty (between servers) after a lobby. A match's sidebar
     * counts at once, without waiting for it to settle.
     */
    val inLobby get() = raw == Phase.LOBBY || (raw == Phase.NONE && phase == Phase.LOBBY)

    /** Ticks left to find your bed in, after a match starts or you respawn. */
    private var lookForBed = 0

    fun init() {
        EventBus.on<TickEvent>(this) { tick() }
        EventBus.on<AfterReceivePacketEvent>(this) { e ->
            val pk = e.packet as? ClientboundSystemChatPacket ?: return@on
            if (pk.overlay()) return@on
            val text = pk.content().string.lowercase()
            // Hypixel: "Protect your bed and destroy the enemy beds." at the start, "You have respawned!" after a death.
            if ("protect your bed" in text || "respawned" in text) lookForBed = LOOK_TICKS
        }
    }

    private fun tick() {
        val now = read()
        if (now != raw) {
            raw = now
            rawTicks = 0
        } else if (rawTicks < SETTLE) {
            rawTicks++
            if (rawTicks == SETTLE && phase != now) {
                if (now == Phase.MATCH) lookForBed = LOOK_TICKS
                phase = now
                val bar = sidebar()
                HorizonClient.LOG.info("Bedwars: {} (sidebar '{}' {}; hypixel {}; {})", now, bar?.first, bar?.second,
                    HypixelApi.location, mc.gameMode?.playerMode)
            }
        }
        if (lookForBed > 0) {
            lookForBed--
            if (phase == Phase.MATCH && findOwnBed()) lookForBed = 0
        }
    }

    /** Standing on your island: the nearest bed close by is yours. */
    private fun findOwnBed(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (!p.onGround() || p.isSpectator) return false
        val at = p.position()
        val bed = BedTeams.scan(p, level, OWN_RANGE).minByOrNull {
            minOf(net.minecraft.world.phys.Vec3.atCenterOf(it.head).distanceToSqr(at), net.minecraft.world.phys.Vec3.atCenterOf(it.foot).distanceToSqr(at))
        } ?: return false
        if (BedTeams.learnOwn(bed.head)) moduleMessage("Bedwars", "your bed: ${bed.head.x}, ${bed.head.y}, ${bed.head.z}")
        return true
    }

    /** The sidebar's title and lines as plain text, or null with no sidebar. */
    fun sidebar(): Pair<String, List<String>>? {
        val p = mc.player ?: return null
        val board = mc.level?.scoreboard ?: return null
        val team = board.getPlayersTeam(p.scoreboardName)?.color
        val o = team?.takeIf { it.isPresent }?.let { board.getDisplayObjective(it.get().displaySlot()) }
            ?: board.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        val lines = board.listPlayerScores(o).filter { !it.isHidden }.map {
            plain(PlayerTeam.formatNameForTeam(board.getPlayersTeam(it.owner()), it.ownerName()).string)
        }
        return plain(o.displayName.string) to lines
    }

    private fun read(): Phase {
        val bar = sidebar()
        if (bar != null && bar.second.count { TEAM_LINE.containsMatchIn(it) } >= 2) return Phase.MATCH
        val hypixel = HypixelApi.location
        if (hypixel?.lobby != null) return Phase.LOBBY
        // Letters only: a server may put symbols or spacing in its title.
        val bedwars = bar != null && "BEDWARS" in bar.first.uppercase().filter { it.isLetter() } || hypixel?.type == "BEDWARS"
        if (!bedwars) return Phase.NONE
        // A game's waiting room is adventure mode; with no title (the Mod API alone) only that tells it from a match.
        if (mc.gameMode?.playerMode == GameType.ADVENTURE || bar != null) return Phase.LOBBY
        return Phase.NONE
    }

    /** Text without `§` codes. */
    private fun plain(s: String) = s.replace(FORMAT, "")

    private val FORMAT = Regex("§.")
    /** A team's line in a match: its letter and name, then its state (Hypixel: "R Red: ✔", "G Green: 2 YOU"). */
    private val TEAM_LINE = Regex("(?<![A-Za-z])[A-Z] (Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Grey|Cyan|Orange|Lime|Purple|Black|Brown|Magenta):")
    private const val SETTLE = 20
    private const val LOOK_TICKS = 200
    private const val OWN_RANGE = 16
}
