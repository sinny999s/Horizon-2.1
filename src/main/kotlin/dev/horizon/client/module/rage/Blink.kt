package dev.horizon.client.module.rage

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.render.Ghost
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.player.ClientInput
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds outgoing packets and sends them all at once later; the server sees us standing at [serverPos] meanwhile. Not in
 * singleplayer.
 */
class Blink : Module("blink", Categories.RAGE) {
    enum class AttackAction { REPEAT, OFF, NONE }

    val limit = int("limit", 15, 1..600)
    val attackAction = enum("attack-action", AttackAction.NONE)
    val limitAction = enum("limit-action", AttackAction.NONE)
    val cancelButton = bind("cancel-button", Bind(InputConstants.KEY_LSHIFT))
    val showPosition = bool("show-position", true)
    val delayOnlyMove = bool("delay-only-move", false)
    val ignoreInventoryActions = bool("ignore-inventory-actions", true) { !delayOnlyMove.value }
    val onlyInRange = bool("only-in-range", false)
    val range = float("range", 5.0f, 2.0f..10.0f) { onlyInRange.value }
    val resetOn = group("reset-on", GroupMode.BOOLS)
    val velocity = bool("velocity", true, parent = resetOn.value)
    val lagBack = bool("lag-back", true, parent = resetOn.value)
    val openScreen = bool("open-screen", true, parent = resetOn.value)

    /** Every held packet, in send order. */
    private val held = ConcurrentLinkedQueue<Packet<*>>()

    /** A second copy of every held pong; only the cancel button sends it (again). */
    private val heldPongs = ConcurrentLinkedQueue<Packet<*>>()

    /** Blink itself is sending held packets. */
    private val flushing = AtomicBoolean(false)

    /** Where the server last saw us (read through `PlayerState.serverPos`). */
    @Volatile
    var serverPos: Vec3? = null

    private var enableVelocity: Vec3 = Vec3.ZERO
    private var enableYaw = 0f
    private var enableSprint = false
    private var enableInput: ClientInput? = null

    /** Attack-action REPEAT flushes at the next move packet (survives toggles, on purpose). */
    private var flushOnNextMove = false

    init {
        on<ReceivePacketEvent> { e ->
            val p = mc.player ?: return@on
            val pk = e.packet
            if (pk is ClientboundPlayerPositionPacket && lagBack.value) disable()
            if (pk is ClientboundSetEntityMotionPacket && pk.id() == p.id && velocity.value) disable()
            if (pk is ClientboundExplodePacket && velocity.value) {
                val kb = pk.playerKnockback().orElse(null)
                if (kb != null && (kb.x != 0.0 || kb.y != 0.0 || kb.z != 0.0)) disable()
            }
            if (pk is ClientboundOpenScreenPacket && openScreen.value) disable()
        }

        on<SendPacketEvent> { e ->
            val p = mc.player ?: return@on
            if (flushing.get()) return@on
            val pk = e.packet

            if (onlyInRange.value) {
                val target = TargetFinder.find(range.value.toDouble(), TargetFinder.Sort.DISTANCE, ignoreNaked = false, ignoreCreative = true)
                if (target == null || !Modules.get<Aura>().enabled) {
                    flush()
                    serverPos = null
                    return@on
                }
                val held0 = serverPos ?: p.position().also { serverPos = it }
                val dHeld = target.position().distanceTo(held0)
                val dReal = target.position().distanceTo(p.position())
                if (dHeld <= dReal || dHeld < 1.0) {
                    flush()
                    serverPos = p.position()
                    return@on
                }
            }

            if (pk is ServerboundMovePlayerPacket) {
                if (flushOnNextMove) {
                    flushOnNextMove = false
                    flush()
                    return@on
                }
            } else if (delayOnlyMove.value) {
                // Only move packets are held, the tick-end packets go out live: a 26.3 server kicks for the flushed
                // run of positions in one tick.
                return@on
            }

            if (ignoreInventoryActions.value && (pk is ServerboundContainerClickPacket || pk is ServerboundUseItemPacket ||
                    (pk is ServerboundPlayerActionPacket && pk.action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) ||
                    pk is ServerboundSetCarriedItemPacket)) return@on

            // Only attacks count: 26.3 has no swing packets.
            if (pk is ServerboundAttackPacket && !Modules.get<ElytraTarget>().enabled) {
                when (attackAction.value) {
                    AttackAction.OFF -> disable() // flushes; this attack is still held below (on purpose)
                    AttackAction.REPEAT -> flushOnNextMove = true
                    AttackAction.NONE -> {}
                }
            }
            if (pk is ServerboundPongPacket) heldPongs.add(pk)
            e.cancel()
            held.add(pk)
        }

        on<TickEvent> {
            val p = mc.player
            if (p == null || mc.level == null || p.isDeadOrDying) disable()
        }

        on<PlayerUpdateEvent> {
            if (physical(cancelButton.value) && serverPos != null) {
                rollback()
                disable()
                return@on
            }
            if (held.size > limit.value) when (limitAction.value) {
                AttackAction.OFF -> disable()
                AttackAction.REPEAT -> flush()
                AttackAction.NONE -> {}
            }
        }

        on<Render3DEvent> {
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            val pos = serverPos ?: return@on
            if (showPosition.value && pos != p.position()) Ghost.draw(pos, p, 0.45f, false)
        }
    }

    override fun onEnable() {
        val p = mc.player
        if (p == null || mc.level == null || mc.hasSingleplayerServer()) {
            disable()
            return
        }
        heldPongs.clear()
        if (!onlyInRange.value) serverPos = p.position()
        enableVelocity = p.deltaMovement
        enableYaw = p.yRot
        enableSprint = p.isSprinting
        flushing.set(false)
        held.clear()
        enableInput = p.input
    }

    override fun onDisable() {
        if (mc.player == null || mc.level == null) return
        while (held.isNotEmpty()) Packets.send(held.poll())
    }

    /** Elytra-target's fake-lag preset (setting writes). */
    fun forceLagSettings() {
        limit.value = 60
        limitAction.value = AttackAction.OFF
    }

    /** Send everything held; the last move packet's position becomes [serverPos]. */
    private fun flush() {
        val p = mc.player ?: return
        if (mc.level == null) return
        flushing.set(true)
        while (held.isNotEmpty()) {
            val pk = held.poll()
            Packets.send(pk)
            if (pk is ServerboundMovePlayerPacket) serverPos = Vec3(pk.getX(p.x), pk.getY(p.y), pk.getZ(p.z))
        }
        flushing.set(false)
        held.clear()
    }

    /** Back to the held position and the enable-time motion; the held packets are dropped, pongs sent. */
    private fun rollback() {
        val p = mc.player ?: return
        val pos = serverPos ?: return
        held.clear()
        p.setPosRaw(pos.x, pos.y, pos.z)
        p.deltaMovement = enableVelocity
        p.yRot = enableYaw
        p.isSprinting = enableSprint
        p.isShiftKeyDown = false
        mc.options.keyShift.isDown = false
        enableInput?.let { p.input = it }
        flushing.set(true)
        while (heldPongs.isNotEmpty()) Packets.send(heldPongs.poll())
        flushing.set(false)
    }

    private fun physical(b: Bind): Boolean {
        if (!RenderSystem.isOnRenderThread() || b.key == -1 || mc.gui.screen() is ChatScreen) return false
        return if (b.mouse) b.key in Keys.heldMouse else Keys.down(b.key)
    }
}
