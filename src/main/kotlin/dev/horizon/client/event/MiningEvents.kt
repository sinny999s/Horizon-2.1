package dev.horizon.client.event

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.world.level.block.state.BlockState

/** `Connection.send` RETURN (not reached when a [SendPacketEvent] handler cancelled the packet). */
class AfterSendPacketEvent(val packet: Packet<*>) : Event()

/** `MultiPlayerGameMode.startDestroyBlock` HEAD (a click on a block); cancelling skips vanilla's mining start. */
class AttackBlockEvent(val pos: BlockPos, val direction: Direction) : Event()

/** `MultiPlayerGameMode.continueDestroyBlock` HEAD (holding the button on a block); cancelling skips vanilla's progress. */
class BlockBreakProgressEvent(val pos: BlockPos, val direction: Direction) : Event()

/**
 * A block's state as collisions see it (`BlockCollisions.computeNext`); handlers may replace [state]. Only posted while
 * [active] (recomputed every tick in `PlayerState`).
 */
class CollisionEvent(val pos: BlockPos, var state: BlockState) : Event() {
    companion object {
        @JvmField
        @Volatile
        var active = false
    }
}
