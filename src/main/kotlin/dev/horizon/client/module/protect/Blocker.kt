package dev.horizon.client.module.protect

import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.world.level.block.Blocks

/**
 * Anti-city: when someone starts mining a surround block (or the blocks over the head),
 * keep the blocks around it filled for about 2.6 s, starting at the estimated `trigger-progress` of the break.
 */
class Blocker : PlacerModule("blocker", Categories.PROTECT, 200, showRange = false) {
    val triggerProgress = float("trigger-progress", 0.8f, 0.0f..0.9f)
    val surround = bool("surround", true)
    val head = bool("head", true)

    /** A block someone started mining, ticks since then ("progress"), and whether it's over the head. */
    private class Breaking(val pos: BlockPos, var progress: Int, val head: Boolean)

    /** Never cleared except by expiry, so entries survive a toggle (on purpose). */
    private val tracked = ArrayList<Breaking>()

    init {
        on<AfterReceivePacketEvent> { e ->
            val p = mc.player ?: return@on
            val pk = e.packet as? ClientboundBlockDestructionPacket ?: return@on
            val watch = watchList()
            val feet = BlockPos.containing(p.position())
            if (pk.progress != 0) return@on
            if (surround.value && pk.pos in watch) tracked += Breaking(pk.pos, 0, false)
            if (!head.value) return@on
            if (pk.pos == feet.above(2) || pk.pos == feet.above(3)) tracked += Breaking(pk.pos, 0, true)
        }
        on<PlayerUpdateEvent> {
            if (mc.player == null) return@on
            for (entry in tracked) {
                entry.progress += 1
                if (entry.progress / 40.0f - triggerProgress.value > 0) {
                    placeList(responses(entry), false, entry.head, blocks(), false)
                    entry.progress = maxOf(150, entry.progress)
                }
            }
            tracked.removeIf { it.progress > 200 }
        }
    }

    /** The obsidian / ender chest blocks beside the feet. */
    private fun watchList(): List<BlockPos> {
        val p = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val feet = BlockPos.containing(p.position())
        val out = ArrayList<BlockPos>(4)
        for (d in HORIZONTAL) {
            val n = feet.relative(d)
            val b = level.getBlockState(n).block
            if (b == Blocks.OBSIDIAN || b == Blocks.ENDER_CHEST) out += n
        }
        return out
    }

    /** On top of a head block; around a surround block (all six sides, in Direction order). */
    private fun responses(e: Breaking): List<BlockPos> =
        if (e.head) listOf(e.pos.above()) else Direction.entries.map { e.pos.relative(it) }

    private companion object {
        val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
    }
}
