package dev.horizon.client.module.legit

import dev.horizon.client.combat.Bedwars
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.scores.PlayerTeam
import java.lang.ref.WeakReference
import java.util.Optional
import java.util.UUID

/**
 * Not in the original: universal multi-server teammate detection. Combines scoreboard team membership, scoreboard
 * `TeamColor` / prefix colour (for servers that give every player a unique scoreboard team for nametag health/ranks),
 * tab-list / display-name colour on the username itself, dyed leather armour colour (BedWars / Bridge / WoolWars), and
 * `[TAG]` clan prefixes. Never calls `Entity.getTeamColor()`, which `GlowEsp` overrides.
 *
 * A Bedwars teammate who respawns reappears a moment before his armour and team colour do, and the fighting modules
 * would swing at him in that gap. So [remember] keeps him a teammate while no check can tell ([TeamMemory]), and
 * [spawnGrace] has them leave anyone who just appeared alone for a few ticks ([isTeammateOrNew]).
 */
class Teams : Module("teams", Categories.LEGIT, listOf("team-check", "teammates", "allies")) {
    enum class ArmorSlot { HELMET_OR_CHEST, ANY_MATCH, HELMET_ONLY, CHEST_ONLY }

    val checks = group("checks", GroupMode.BOOLS)
    val scoreboardTeam = bool("scoreboard-team", true, parent = checks.value)
    val teamColor = bool("team-color", true, parent = checks.value)
    val nameColor = bool("name-color", true, parent = checks.value)
    val armorColor = bool("armor-color", true, parent = checks.value)
    val prefixTag = bool("prefix-tag", false, parent = checks.value)

    val armorSlot = enum("armor-slot", ArmorSlot.HELMET_OR_CHEST) { armorColor.value }
    val armorTolerance = int("armor-tolerance", 16, 0..64) { armorColor.value }
    val ignoreDefaultLeather = bool("ignore-default-leather", true) { armorColor.value }
    val ignoreWhite = bool("ignore-white", true) { teamColor.value || nameColor.value }
    /** A teammate stays one while no check can tell (no team colour or dyed armour yet), e.g. just after he respawns. */
    val remember = bool("remember-teammates", true)
    /**
     * Ticks Aura, Trigger Bot, Aim Assist, Auto Clicker and the shared targeting leave a player alone after he appears
     * (spawns or respawns in view), before his armour and team colour arrive. 0 turns it off.
     */
    val spawnGrace = int("spawn-grace", 10, 0..40)
    val espAllyColor = bool("esp-ally-color", true)

    private val memory = TeamMemory<UUID>()
    private var memoryLevel = WeakReference<Level>(null)
    private var memoryPhase = Bedwars.Phase.NONE

    /**
     * What the turned-on checks say about [other]: [TeamMemory.Signal.MATCH] if any finds your team, else
     * [TeamMemory.Signal.MISMATCH] if a colour or tag check sees two different teams, else [TeamMemory.Signal.UNKNOWN].
     * Scoreboard teams that aren't allied say nothing either way: some servers give every player a team of his own.
     */
    fun evaluate(self: Player, other: Player): TeamMemory.Signal {
        if (self === other) return TeamMemory.Signal.MATCH
        val noWhite = ignoreWhite.value
        var differ = false
        // Whether two colours match; when both are known and don't, the teams differ.
        fun compare(a: Int?, b: Int?, tolerance: Int = 0, ignoreWhite: Boolean = false): Boolean {
            if (TeamMatcher.colorsMatch(a, b, tolerance, ignoreWhite)) return true
            if (a != null && b != null) differ = true
            return false
        }

        if (scoreboardTeam.value) {
            val st = self.team
            val ot = other.team
            if (st != null && ot != null && st.isAlliedTo(ot)) return TeamMemory.Signal.MATCH
        }
        if (teamColor.value && compare(rawScoreboardColor(self, noWhite), rawScoreboardColor(other, noWhite), ignoreWhite = noWhite)) {
            return TeamMemory.Signal.MATCH
        }
        if (nameColor.value && compare(displayColor(self, noWhite), displayColor(other, noWhite), ignoreWhite = noWhite)) {
            return TeamMemory.Signal.MATCH
        }
        if (armorColor.value && armorMatches(self, other, ::compare)) return TeamMemory.Signal.MATCH
        if (prefixTag.value) {
            val st = playerTag(self)
            val ot = playerTag(other)
            if (st != null && st == ot) return TeamMemory.Signal.MATCH
            if (st != null && ot != null) differ = true
        }
        return if (differ) TeamMemory.Signal.MISMATCH else TeamMemory.Signal.UNKNOWN
    }

    private fun armorMatches(self: Player, other: Player, compare: (Int?, Int?, Int, Boolean) -> Boolean): Boolean {
        val tol = armorTolerance.value
        val noPlain = ignoreDefaultLeather.value
        val slots = when (armorSlot.value) {
            ArmorSlot.HELMET_ONLY -> listOf(EquipmentSlot.HEAD)
            ArmorSlot.CHEST_ONLY -> listOf(EquipmentSlot.CHEST)
            ArmorSlot.HELMET_OR_CHEST -> listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST)
            ArmorSlot.ANY_MATCH -> ALL_ARMOR_SLOTS
        }
        for (slot in slots) {
            val sc = dyedSlotColor(self, slot, noPlain) ?: continue
            val oc = dyedSlotColor(other, slot, noPlain) ?: continue
            if (compare(sc, oc, tol, false)) return true
        }
        if (armorSlot.value == ArmorSlot.ANY_MATCH) {
            val selfColors = slots.mapNotNull { dyedSlotColor(self, it, noPlain) }
            if (selfColors.isEmpty()) return false
            val otherColors = slots.mapNotNull { dyedSlotColor(other, it, noPlain) }
            for (sc in selfColors) for (oc in otherColors) {
                if (TeamMatcher.colorsMatch(sc, oc, tolerance = tol)) return true
            }
        }
        return false
    }

    /** A new world (another server or game) or a Bedwars match starting or ending: teams from before mean nothing. */
    private fun forgetOnNewGame() {
        val level = mc.level
        val phase = Bedwars.phase
        if (level !== memoryLevel.get() || phase != memoryPhase) {
            memory.clear()
            memoryLevel = WeakReference(level)
            memoryPhase = phase
        }
    }

    companion object {
        private val ALL_ARMOR_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        private val module by lazy { Modules.get<Teams>() }

        /** True when [Teams] is enabled and [player] is on the local player's team (or was, while nothing shows: [remember]). */
        fun isTeammate(player: Player): Boolean {
            val m = module
            if (!m.enabled) return false
            val self = m.mc.player ?: return false
            if (player === self) return false
            m.forgetOnNewGame()
            return m.memory.verdict(player.uuid, m.evaluate(self, player), m.remember.value)
        }

        /**
         * Who the fighting modules leave alone: a teammate, or (with [Teams] on) a player who appeared less than
         * `spawn-grace` ticks ago. A respawning teammate shows up a moment before his armour and team colour do.
         */
        fun isTeammateOrNew(player: Player): Boolean {
            if (isTeammate(player)) return true
            val m = module
            return m.enabled && player !== m.mc.player && player.tickCount < m.spawnGrace.value
        }

        /** True when [Teams] is enabled with `esp-ally-color` on and [player] is a teammate. */
        fun isEspAlly(player: Player): Boolean = module.espAllyColor.value && isTeammate(player)

        /** Scoreboard team colour comparison safe from `GlowEsp`'s `Entity.getTeamColor` hook. */
        fun rawScoreboardSameColor(a: Player, b: Player): Boolean {
            val ca = rawScoreboardColor(a, ignoreWhite = true) ?: return false
            val cb = rawScoreboardColor(b, ignoreWhite = true) ?: return false
            return ca == cb
        }

        /**
         * Resolves [player]'s 24-bit RGB server team colour (scoreboard `TeamColor` -> team prefix -> tab/display name ->
         * dyed helmet/chestplate), or `null` if unteamed. Safe to call inside `GlowEsp`.
         */
        fun resolveColor(player: Player, ignoreWhite: Boolean = true): Int? {
            rawScoreboardColor(player, ignoreWhite)?.let { return it }
            displayColor(player, ignoreWhite)?.let { return it }
            val noPlain = if (module.enabled) module.ignoreDefaultLeather.value else true
            dyedSlotColor(player, EquipmentSlot.HEAD, noPlain)?.let { return it }
            dyedSlotColor(player, EquipmentSlot.CHEST, noPlain)?.let { return it }
            return null
        }

        private fun rawScoreboardColor(player: Player, ignoreWhite: Boolean): Int? {
            val team = player.team ?: return null
            val tc = team.color.orElse(null)
            if (tc != null) {
                val rgb = tc.rgb() and 0xFFFFFF
                if (!ignoreWhite || rgb != TeamMatcher.WHITE_RGB) return rgb
            }
            componentColor(team.playerPrefix, "", ignoreWhite)?.let { return it }
            return null
        }

        private fun displayColor(player: Player, ignoreWhite: Boolean): Int? {
            val uname = player.gameProfile.name()
            val tab = module.mc.connection?.getPlayerInfo(player.uuid)?.tabListDisplayName
            if (tab != null) {
                componentColor(tab, uname, ignoreWhite)?.let { return it }
            }
            return componentColor(player.displayName, uname, ignoreWhite)
        }

        private fun playerTag(player: Player): String? {
            val uname = player.gameProfile.name()
            val tab = module.mc.connection?.getPlayerInfo(player.uuid)?.tabListDisplayName
            if (tab != null) {
                TeamMatcher.extractPrefixTag(tab.string, uname)?.let { return it }
            }
            val team = player.team
            if (team != null) {
                TeamMatcher.extractPrefixTag(team.playerPrefix.string, "")?.let { return it }
            }
            return TeamMatcher.extractPrefixTag(player.displayName.string, uname)
        }

        private fun dyedSlotColor(player: Player, slot: EquipmentSlot, ignoreDefaultLeather: Boolean): Int? {
            val dyed = player.getItemBySlot(slot).get(DataComponents.DYED_COLOR) ?: return null
            return TeamMatcher.normalizeArmorColor(dyed.rgb(), ignoreDefaultLeather)
        }

        private fun componentColor(component: Component, playerName: String, ignoreWhite: Boolean): Int? {
            val spans = ArrayList<TeamMatcher.Span>(4)
            component.visit({ style: Style, text: String ->
                if (text.isNotEmpty()) spans += TeamMatcher.Span(text, style.color?.value)
                Optional.empty<Unit>()
            }, Style.EMPTY)
            return TeamMatcher.extractNameColor(spans, playerName, ignoreWhite)
        }
    }
}
