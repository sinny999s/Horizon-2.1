package dev.horizon.client.module.basic

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.ContainerClickEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.mixin.ClientInputAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec2
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * With a screen open (not chat) the movement keys follow the physical keys. The bypasses
 * change what a click while moving does: strict NCP lifts the player a little first, matrix stops forward movement,
 * LEGIT plays the clicks only on the client and sends them (with the close) once the screen is closed and the player
 * stood still for a tick, and disable-clicks drops them.
 */
class GuiMove : Module("gui-move", Categories.BASIC) {
    enum class Bypass { NONE, DISABLE_CLICKS, STRICT_NCP, STRICT_NCP_2, MATRIX_NCP, LEGIT }

    val bypass = enum("bypass", Bypass.NONE)

    /**
     * Set around the client's own inventory operations: gui-move then leaves
     * their clicks and closes alone.
     */
    @Volatile
    var internalClick = false

    /** A click LEGIT holds back. */
    private class HeldClick(val containerId: Int, val slot: Int, val button: Int, val input: ContainerInput)

    private var keys: List<KeyMapping> = emptyList()
    private val heldClicks = ConcurrentLinkedQueue<HeldClick>()

    /** The held clicks are being sent. */
    private val sending = AtomicBoolean(false)

    /** The screen was closed while clicks were held. */
    @Volatile
    private var closed = false

    /** Strict NCP stopped the sprint for a click; it is resumed after the click packet. */
    private var resprint = false

    /** LEGIT's send steps: 0 idle, 1 stood still for a tick. */
    private var step = 0
    private var wasSprinting = false

    /** The server opened the screen, or a click came while standing still: LEGIT then keeps keys up. */
    @Volatile
    private var stillScreen = false

    /** Menu contents before the first held click, and at send time. */
    private val before = HashMap<Int, ItemStack>()
    private val atSend = HashMap<Int, ItemStack>()

    init {
        on<PlayerUpdateEvent> { tick() }
        on<ContainerClickEvent> { e -> click(e) }
        on<ReceivePacketEvent> { e ->
            if (internalClick) return@on
            when (e.packet) {
                is ClientboundContainerClosePacket -> {
                    stillScreen = false
                    if (bypass.value == Bypass.DISABLE_CLICKS) e.cancel()
                    if (bypass.value == Bypass.LEGIT && !sending.get() && heldClicks.isNotEmpty()) {
                        e.cancel()
                        closed = true
                    }
                }
                is ClientboundOpenScreenPacket -> stillScreen = true
            }
        }
        on<SendPacketEvent> { e ->
            if (internalClick) return@on
            if (e.packet !is ServerboundContainerClosePacket) return@on
            stillScreen = false
            if (bypass.value == Bypass.DISABLE_CLICKS) e.cancel()
            if (bypass.value == Bypass.LEGIT) {
                if (sending.get()) return@on
                if (heldClicks.isNotEmpty()) closed = true
                e.cancel()
            }
        }
        on<AfterSendPacketEvent> { e ->
            if (internalClick) return@on
            if (e.packet !is ServerboundContainerClickPacket) return@on
            if (resprint && bypass.value == Bypass.STRICT_NCP) {
                Packets.send(ServerboundPlayerCommandPacket(mc.player!!, ServerboundPlayerCommandPacket.Action.START_SPRINTING))
                resprint = false
            }
        }
    }

    private fun tick() {
        val p = mc.player ?: return
        val o = mc.options
        if (keys.isEmpty()) keys = listOf(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint)
        if (!(stillScreen && bypass.value == Bypass.LEGIT)) {
            val screen = mc.gui.screen()
            if (screen != null && screen !is ChatScreen) for (k in keys) k.isDown = PlayerState.physical(k)
        }
        if (!(bypass.value == Bypass.LEGIT && heldClicks.isNotEmpty() && closed) && step == 0) return
        when (step) {
            0 -> {
                Modules.get<Sprint>().skipOnce = true
                wasSprinting = p.isSprinting
                p.isSprinting = false
                PlayerState.releaseMovementKeys()
                step = 1
            }
            1 -> {
                Modules.get<Sprint>().skipOnce = true
                sending.set(true)
                save(atSend)
                restore(before)
                while (heldClicks.isNotEmpty()) {
                    val c = heldClicks.poll()
                    mc.gameMode?.handleContainerInput(c.containerId, c.slot, c.button, c.input, p)
                }
                Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
                sending.set(false)
                closed = false
                Modules.get<Sprint>().skipOnce = true
                p.isSprinting = wasSprinting
                PlayerState.resyncMovementKeys()
                step = 0
                restore(atSend)
            }
        }
    }

    /**
     * This event doesn't carry the click's container id, so the
     * open menu's id is used (vanilla ignores clicks for any other id anyway).
     */
    private fun click(e: ContainerClickEvent) {
        if (internalClick || sending.get()) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (!Movement.moving() && !mc.options.keyJump.isDown || p.isFallFlying) {
            stillScreen = true
            return
        }
        val containerId = p.containerMenu.containerId
        when (bypass.value) {
            Bypass.STRICT_NCP, Bypass.STRICT_NCP_2 -> {
                // The click is played here and then again by vanilla (on purpose).
                clickLocally(e, p)
                val lift = if (bypass.value == Bypass.STRICT_NCP) 0.0656 else 2.71875E-7
                if (!p.onGround()) return
                if (level.getBlockCollisions(p, p.boundingBox.move(0.0, lift, 0.0)).iterator().hasNext()) return
                resprint = p.isSprinting
                if (resprint) Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING))
                // An extra position packet is the point of this bypass (a 26.3 server may kick for two in one tick).
                Packets.send(ServerboundMovePlayerPacket.Pos(p.x, p.y + lift, p.z, false, p.horizontalCollision))
            }
            Bypass.MATRIX_NCP -> {
                clickLocally(e, p)
                Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING))
                mc.options.keyUp.isDown = false
                (p.input as ClientInputAccessor).horizon_setMoveVector(Vec2(p.input.moveVector.x, 0.0f))
            }
            Bypass.LEGIT -> {
                if (p.containerMenu.slots.size != 46) return
                if (heldClicks.isEmpty()) save(before)
                clickLocally(e, p)
                heldClicks.add(HeldClick(containerId, e.slotId, e.button, e.input))
                e.cancel()
            }
            Bypass.DISABLE_CLICKS -> e.cancel()
            Bypass.NONE -> {}
        }
    }

    /** The click on the client's menu only. */
    private fun clickLocally(e: ContainerClickEvent, p: Player) {
        p.containerMenu.clicked(e.slotId, e.button, e.input, p)
    }

    /** Copies of the menu's first 46 slots. */
    private fun save(into: HashMap<Int, ItemStack>) {
        val menu = mc.player!!.containerMenu
        for (i in 0 until 46) into[i] = menu.getSlot(i).item.copy()
    }

    private fun restore(from: HashMap<Int, ItemStack>) {
        val menu = mc.player!!.containerMenu
        for (i in 0 until 46) menu.setItem(i, menu.stateId, from[i]!!)
    }
}
