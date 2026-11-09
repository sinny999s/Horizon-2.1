package dev.horizon.client.event

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** `MultiPlayerGameMode.handlePickItemFromBlock` HEAD; cancelling skips the pick. */
class PickBlockEvent : Event()

/**
 * `BlockBehaviour.getDestroyProgress` HEAD, for every caller (the integrated
 * server's too, on purpose); cancelling returns [delta].
 */
class BreakDeltaEvent(val pos: BlockPos, val state: BlockState) : Event() {
    var delta = 0f
}
