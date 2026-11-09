package dev.horizon.client.event

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult

/**
 * A key (`KeyboardHandler.keyPress` HEAD, only with a level and a player) or a mouse button
 * (`MouseHandler.onButton` HEAD) before vanilla handles it; cancelling drops it.
 */
class InputEvent(val key: Int, val device: Device, val action: Action) : Event() {
    enum class Device { Mouse, Keyboard }

    enum class Action { Press, Release, Hold }
}

/** `ClientPacketListener.handleMovePlayer` RETURN, on the main thread, after the teleport was applied. */
class FlagEvent(val packet: ClientboundPlayerPositionPacket) : Event()

/** `ItemStack.finishUsingItem` HEAD for the local player. */
class FinishUsingItemEvent(val item: ItemStack) : Event()

/** The local player's `getBoundingBox` HEAD; cancelling returns [value] instead. */
class BoxContractEvent : Event() {
    lateinit var value: AABB
}

/** `MultiPlayerGameMode.useItemOn` HEAD; cancelling makes it return PASS at once. */
class InteractBlockEvent(val hand: InteractionHand, val hitResult: BlockHitResult) : Event()

/** `Minecraft.startAttack` HEAD; cancelling makes it return false at once. */
class AttackEvent : Event()
