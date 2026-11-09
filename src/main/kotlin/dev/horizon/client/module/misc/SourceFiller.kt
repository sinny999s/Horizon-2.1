package dev.horizon.client.module.misc

import dev.horizon.client.combat.Placer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Every water and lava source around us gets filled with any hotbar block that isn't
 * on the black-list, nearest first.
 */
class SourceFiller : PlacerModule("source-filler", Categories.MISC, 150) {
    val blackList = blocks("black-list", blockList("block.minecraft.obsidian"))

    /** The black-list: every placement uses the inverted hotbar search. */
    override fun blocks(): List<Block> = blackList.value

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    /** A cube one block wider than the range, around the eye block. */
    private fun tick() {
        val self = mc.player ?: return
        val level = mc.level ?: return
        val c = BlockPos.containing(self.eyePosition)
        val r = ceil(range.value.toDouble()).toFloat().toInt() + 1
        val list = ArrayList<BlockPos>()
        for (x in c.x - r..c.x + r) for (y in c.y - r..c.y + r) for (z in c.z - r..c.z + r) {
            val p = BlockPos(x, y, z)
            val b = level.getBlockState(p).block
            if (b != Blocks.WATER && b != Blocks.LAVA) continue
            if (!level.getFluidState(p).isSource) continue
            if (!canPlace(p)) continue
            list += p
        }
        if (list.isEmpty()) return
        placeList(list.sortedBy { self.distanceToSqr(Vec3.atCenterOf(it)) }, false, false, blocks(), true)
    }

    fun canPlace(p: BlockPos) = Placer.canPlace(p, interact.value, ignoreMode, range.value, wallRange.value)
}
