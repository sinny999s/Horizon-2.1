package dev.horizon.client.module.esp

import dev.horizon.client.config.Friends
import dev.horizon.client.event.GlowEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.legit.Teams
import dev.horizon.client.setting.GroupMode
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import java.awt.Color

/**
 * Minecraft's own glowing outline (a spectral arrow's, seen through walls) on the kinds of entity
 * chosen, each in its own colour: players (friends apart), hostile mobs, neutral ones (wolves, endermen, piglins,
 * bees, golems...), passive ones (animals, villagers, fish, bats), dropped items, and the rest (boats, carts, armour
 * stands, crystals...); within [range] blocks, never yourself. Cheap: it's vanilla's outline pass.
 */
class GlowEsp : Module("glow-esp", Categories.ESP) {
    val targets = group("targets", GroupMode.BOOLS)
    val players = bool("players", false, parent = targets.value)
    val hostile = bool("hostile", true, parent = targets.value)
    val neutral = bool("neutral", true, parent = targets.value)
    val passive = bool("passive", true, parent = targets.value)
    val items = bool("items", false, parent = targets.value)
    val other = bool("other", false, parent = targets.value)
    val range = int("range", 128, 8..512)
    val ignoreTeammates = bool("ignore-teammates", false) { players.value }
    val useServerTeamColor = bool("use-server-team-color", false) { players.value }
    val playerColor = color("player-color", Color(0xFFFFFFFF.toInt(), true)) { players.value }
    val friendColor = color("friend-color", Color(0xFF55FFFF.toInt(), true)) { players.value }
    val teamColor = color("team-color", Color(0xFF55FF55.toInt(), true)) { players.value && !ignoreTeammates.value }
    val hostileColor = color("hostile-color", Color(0xFFFF5555.toInt(), true)) { hostile.value }
    val neutralColor = color("neutral-color", Color(0xFFFFAA00.toInt(), true)) { neutral.value }
    val passiveColor = color("passive-color", Color(0xFF55FF55.toInt(), true)) { passive.value }
    val itemColor = color("item-color", Color(0xFFFFFF55.toInt(), true)) { items.value }
    val otherColor = color("other-color", Color(0xFFAAAAAA.toInt(), true)) { other.value }

    init {
        on<GlowEvent> { e ->
            val color = colorOf(e.entity ?: return@on) ?: return@on
            e.glow = true
            e.color = color.rgb and 0xFFFFFF
        }
    }

    /** The colour [entity] glows in, or null for none. Neutral comes before hostile: endermen and piglins are both. */
    private fun colorOf(entity: Entity): Color? {
        val self = mc.player ?: return null
        if (entity === self || entity.distanceToSqr(self) > range.value.toDouble() * range.value) return null
        return when (entity) {
            is Player -> playerGlow(entity)
            is ItemEntity -> itemColor.value.takeIf { items.value }
            is NeutralMob -> neutralColor.value.takeIf { neutral.value }
            is Enemy -> hostileColor.value.takeIf { hostile.value }
            is Mob -> passiveColor.value.takeIf { passive.value }
            else -> otherColor.value.takeIf { other.value }
        }
    }

    private fun playerGlow(player: Player): Color? {
        if (!players.value) return null
        if (Friends.isFriend(player.name.string)) return friendColor.value
        val teammate = Teams.isTeammate(player)
        if (teammate && ignoreTeammates.value) return null
        if (useServerTeamColor.value) {
            Teams.resolveColor(player)?.let { return Color(it) }
        }
        return if (teammate) teamColor.value else playerColor.value
    }
}
