package dev.horizon.client.module.attack

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.PlacerModule
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import kotlin.math.roundToInt

/**
 * A ladder, vine, item frame or scaffolding into the feet block of an enemy who stands
 * alone in one block column next to walls (phased into a 1x1 gap, or about to be).
 */
class AntiPhase : PlacerModule("anti-phase", Categories.ATTACK, 150) {
    val trapCrawlers = bool("trap-crawlers", false)
    val blocksSetting = group("blocks", GroupMode.BOOLS)
    val ladders = bool("ladders", true, parent = blocksSetting.value)
    val vines = bool("vines", true, parent = blocksSetting.value)
    val frames = bool("frames", true, parent = blocksSetting.value)
    val scaffoldings = bool("scaffoldings", true, parent = blocksSetting.value)

    /** The item goes into the cell the enemy stands in, so no entity check. */
    override val ignoreMode: IgnoreMode get() = IgnoreMode.NONE

    override fun items(): List<Item> {
        val list = ArrayList<Item>(4)
        if (scaffoldings.value) list += Items.SCAFFOLDING
        if (ladders.value) list += Items.LADDER
        if (vines.value) list += Items.VINE
        if (frames.value) list += Items.ITEM_FRAME
        return list
    }

    init {
        on<PlayerUpdateEvent> { placeItems(positions(), false) }
    }

    /** The feet block of each eligible player, in level order (no de-duplication). */
    private fun positions(): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val out = ArrayList<BlockPos>()
        for (p in level.players()) if (eligible(p)) out += BlockPos.containing(p.position())
        return out
    }

    private fun eligible(p: Player): Boolean {
        val self = mc.player ?: return false
        val level = mc.level ?: return false
        if (!p.isAlive) return false
        if (p === self) return false
        if (p.isSwimming) return false
        if (Friends.isFriend(p.gameProfile.name)) return false
        // Walls nearby: lifted off the floor, a block wider on X and Z.
        if (level.noBlockCollision(p, p.boundingBox.move(0.0, 0.1, 0.0).inflate(1.0, 0.0, 1.0))) return false
        if (columns(p).size > 1) return false
        if (hasFrame(AABB(BlockPos.containing(p.position().add(0.0, 0.3, 0.0))))) return false
        if (!trapCrawlers.value && p.isVisuallyCrawling) return false
        return self.distanceTo(p) <= range.value + 3.0f
    }

    /** The block columns the hitbox covers (1, 2 or 4). */
    private fun columns(p: Player): List<BlockPos> {
        val y = BlockPos.containing(p.x, p.y.roundToInt().toDouble(), p.z).y.toDouble()
        val bb = p.boundingBox
        val e = 1.0E-5f.toDouble()
        return linkedSetOf(
            BlockPos.containing(bb.minX, y, bb.minZ),
            BlockPos.containing(bb.minX, y, bb.maxZ - e),
            BlockPos.containing(bb.maxX - e, y, bb.minZ),
            BlockPos.containing(bb.maxX - e, y, bb.maxZ - e),
        ).toList()
    }

    /** An item frame (or glow frame) already hangs in [box]. */
    private fun hasFrame(box: AABB): Boolean =
        mc.level?.entitiesForRendering()?.any { it is ItemFrame && it.boundingBox.intersects(box) } ?: false
}
