package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.ShouldDismountEvent
import dev.horizon.client.event.TravelEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.vehicle.boat.Boat
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.EntityHitResult
import java.util.concurrent.CopyOnWriteArrayList

/**
 * At the start of the player's travel, drives the ridden vehicle along the keys at `speed`
 * (jump/sneak `y-speed`, sinking `glide-speed`/100 a tick) — Motion through its velocity, Packet by moving it and sending
 * the vehicle position — and skips the player's own travel. Mounts the nearest boat (auto-mount), drops server
 * corrections while steering (cancel) and only lets its own vehicle packets through (limit).
 */
class BoatFly : Module("boat-fly", Categories.RAGE) {
    @Suppress("EnumEntryName")
    enum class Mode { Packet, Motion }

    val mode = enum("mode", Mode.Packet)
    val phase = bool("phase", false)
    val gravity = bool("gravity", false)
    val autoMount = bool("auto-mount", true)
    val allowShift = bool("allow-shift", true)
    val speed = float("speed", 2.0f, 0.0f..25.0f)
    val ySpeed = float("y-speed", 1.0f, 0.0f..10.0f)
    val advanced = group("advanced")
    val glideSpeed = float("glide-speed", 0.0f, 0.0f..10.0f, parent = advanced.value)
    val clickSlot = bool("click-slot", false, parent = advanced.value)
    val limit = bool("limit", true, parent = advanced.value)
    val onGroundPacket = bool("on-ground-packet", false, parent = advanced.value)
    val spoofPackets = bool("spoof-packets", false, parent = advanced.value)
    val jitter = float("jitter", 0.1f, 0.0f..10.0f, parent = advanced.value) { spoofPackets.value }
    val cancelRotations = bool("cancel-rotations", true, parent = advanced.value)
    val cancel = bool("cancel", true, parent = advanced.value)
    val pause = bool("pause", false, parent = advanced.value)
    val enableTicks = int("enable-ticks", 10, 1..100, parent = advanced.value) { pause.value }
    val waitTicks = int("wait-ticks", 10, 1..100, parent = advanced.value) { pause.value }
    val timer = float("timer", 1.0f, 0.1f..5.0f, parent = advanced.value)

    /** Vehicle packets this module sent, let through by `limit`. */
    private val sent = CopyOnWriteArrayList<ServerboundMoveVehiclePacket>()

    /** Ticks driven since the last pause, the pause's ticks left, whether pausing, the jitter side. */
    private var driven = 0
    private var pauseLeft = 0
    @Volatile
    private var pausing = false
    private var jitterUp = false

    init {
        on<ShouldDismountEvent> { e -> if (allowShift.value) e.cancel() }
        on<TravelEvent> { e -> if (e.pre) travel(e) }
        on<ReceivePacketEvent> { e ->
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            if (e.packet is ClientboundDisconnectPacket) disable()
            if (p.isHandsBusy && !pausing && cancel.value) {
                val pk = e.packet
                if (pk is ClientboundMoveVehiclePacket || pk is ClientboundPlayerPositionPacket || pk is ClientboundMoveEntityPacket ||
                    pk is ClientboundSetEntityLinkPacket) e.cancel()
            }
        }
        on<SendPacketEvent> { e ->
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            val pk = e.packet
            if ((pk is ServerboundMovePlayerPacket.Rot && cancelRotations.value || pk is ServerboundPlayerInputPacket) && p.isHandsBusy) e.cancel()
            if (pk is ServerboundPlayerInputPacket && allowShift.value) e.cancel()
            if (p.controlledVehicle != null && !pausing && pk is ServerboundMoveVehiclePacket && limit.value && mode.value == Mode.Packet) {
                if (sent.contains(pk)) sent.remove(pk) else e.cancel()
            }
        }
    }

    override fun onEnable() {
        if (mc.level != null && mc.player != null) {
            if (autoMount.value) mount()
        } else {
            disable()
        }
    }

    override fun onDisable() {
        sent.clear()
        pausing = false
        val p = mc.player ?: return
        if (phase.value && mode.value == Mode.Motion) {
            p.controlledVehicle?.noPhysics = false
            p.noPhysics = false
        }
        p.controlledVehicle?.isNoGravity = false
        p.isNoGravity = false
    }

    private fun travel(e: TravelEvent) {
        val p = mc.player ?: return
        if (mc.level == null) return
        val v = p.controlledVehicle
        if (v == null) {
            if (autoMount.value) mount()
            return
        }
        if (phase.value && mode.value == Mode.Motion) {
            v.noPhysics = true
            v.isNoGravity = true
            p.noPhysics = true
        }
        v.isNoGravity = !gravity.value
        p.isNoGravity = !gravity.value
        if (pause.value) {
            if (driven > enableTicks.value && !pausing) {
                driven = 0
                pausing = true
                pauseLeft = waitTicks.value
            }
            if (pauseLeft > 0 && pausing) {
                pauseLeft--
                return
            }
            if (pauseLeft <= 0) pausing = false
        }
        if (timer.value != 1.0f) TimerManager.factor = timer.value
        v.yRot = p.yRot
        val m = Movement.strafe(speed.value)
        val x = v.x + m[0]
        val z = v.z + m[1]
        var y = v.y
        v.setDeltaMovement(v.deltaMovement.x, (-glideSpeed.value / 100.0f).toDouble(), v.deltaMovement.z)
        if (mode.value == Mode.Motion) v.setDeltaMovement(m[0], v.deltaMovement.y, m[1])
        if (mc.options.keyJump.isDown) {
            if (mode.value == Mode.Motion) v.setDeltaMovement(v.deltaMovement.x, v.deltaMovement.y + ySpeed.value, v.deltaMovement.z) else y += ySpeed.value
        } else if (mc.options.keyShift.isDown) {
            if (mode.value == Mode.Motion) v.setDeltaMovement(v.deltaMovement.x, v.deltaMovement.y - ySpeed.value, v.deltaMovement.z) else y -= ySpeed.value
        }
        if (!Movement.moving()) v.setDeltaMovement(0.0, v.deltaMovement.y, 0.0)
        if (onGroundPacket.value) groundPacket(v)
        if (mode.value == Mode.Packet) {
            v.setPos(x, y, z)
            send(ServerboundMoveVehiclePacket.fromEntity(v))
        }
        if (clickSlot.value) mc.gameMode?.handleContainerInput(p.containerMenu.containerId, 0, 0, ContainerInput.CLONE, p)
        if (spoofPackets.value) {
            jitterUp = !jitterUp
            val spoofed = v.position().add(0.0, (if (jitterUp) jitter.value else -jitter.value).toDouble(), 0.0)
            val yaw = v.yRot
            val pitch = v.xRot
            val pos = v.position()
            v.setPos(spoofed)
            send(ServerboundMoveVehiclePacket.fromEntity(v))
            v.yRot = yaw
            v.xRot = pitch
            v.setPos(pos)
        }
        p.move(MoverType.SELF, p.deltaMovement)
        e.cancel()
        driven++
    }

    private fun send(packet: ServerboundMoveVehiclePacket) {
        sent += packet
        Packets.send(packet)
    }

    /**
     * Sends the vehicle standing on the first solid block (or water) below it. It isn't moved back, so the vehicle stays
     * down there (on purpose).
     */
    private fun groundPacket(v: Entity) {
        val level = mc.level ?: return
        var pos = BlockPos.containing(v.position())
        var n = 0
        while (true) {
            if (n >= 255) return
            if (!level.getBlockState(pos).canBeReplaced()) break
            if (level.getBlockState(pos).block == Blocks.WATER) break
            pos = pos.below()
            n++
        }
        v.setPos(v.x, (pos.y + 1).toDouble(), v.z)
        send(ServerboundMoveVehiclePacket.fromEntity(v))
    }

    /** Interact with the first boat within 5 blocks. */
    private fun mount() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        for (en in level.entitiesForRendering()) {
            if (en is Boat && !(p.distanceToSqr(en) > 25.0)) {
                mc.gameMode?.interact(p, en, EntityHitResult(en, en.boundingBox.center), InteractionHand.MAIN_HAND)
                break
            }
        }
    }
}
