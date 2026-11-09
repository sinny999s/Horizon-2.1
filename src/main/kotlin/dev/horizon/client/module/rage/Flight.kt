package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.phys.Vec3

/**
 * Vanilla sets the velocity at PreSync (horizontal along the keys, jump/sneak vertical);
 * Creative uses the abilities' flying; MATRIX_JUMP steers while falling and, after a server teleport, puts the velocity
 * back on the next position+rotation packet and turns off; GRIM_GLIDE glides forward at 0.3 sinking 0.03 and reports
 * on-ground instead of every other movement packet. Anti-kick bobs 0.08 every 15 ticks.
 */
class Flight : Module("flight", Categories.RAGE) {
    @Suppress("EnumEntryName")
    enum class Mode { Vanilla, Creative, MATRIX_JUMP, GRIM_GLIDE }

    val mode = enum("mode", Mode.Vanilla)
    val horizontal = float("horizontal", 1.0f, 0.0f..10.0f)
    val vertical = float("vertical", 0.78f, 0.0f..5.0f)
    val antiKick = bool("anti-kick", false) { (mode.value == Mode.Vanilla || mode.value == Mode.Creative) }

    /** The velocity when the server last teleported us (MATRIX_JUMP). */
    @Volatile
    private var teleportVelocity: Vec3? = null

    init {
        on<PreSyncEvent> { e ->
            val p = mc.player ?: return@on
            when (mode.value) {
                Mode.Vanilla -> {
                    if (Movement.moving()) {
                        val m = Movement.strafe(horizontal.value)
                        p.setDeltaMovement(m[0], 0.0, m[1])
                    } else {
                        p.setDeltaMovement(0.0, 0.0, 0.0)
                    }
                    if (mc.options.keyJump.isDown) p.deltaMovement = p.deltaMovement.add(0.0, vertical.value.toDouble(), 0.0)
                    if (mc.options.keyShift.isDown) p.deltaMovement = p.deltaMovement.add(0.0, -vertical.value.toDouble(), 0.0)
                }
                Mode.GRIM_GLIDE -> {
                    p.isSprinting = true
                    if (p.tickCount % 2 == 0) {
                        e.cancel()
                        Packets.send(ServerboundMovePlayerPacket.StatusOnly(true, false))
                    }
                }
                else -> {}
            }
            if (antiKick.value && p.tickCount % 15 == 0) {
                p.deltaMovement = Vec3(p.deltaMovement.x, if (p.tickCount % 30 == 0) -0.08 else 0.08, p.deltaMovement.z)
            }
        }
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (mode.value == Mode.Creative) {
                p.abilities.flying = true
                p.abilities.flyingSpeed = horizontal.value / 10.0f
            }
            if (mode.value == Mode.MATRIX_JUMP && PlayerState.fallDistance > 0.0) {
                val m = Movement.strafe(horizontal.value)
                val o = mc.options
                p.setDeltaMovement(m[0], if (o.keyJump.isDown) vertical.value.toDouble() else if (o.keyShift.isDown) -vertical.value.toDouble() else 0.0, m[1])
            }
        }
        on<ReceivePacketEvent> { e ->
            if (mode.value == Mode.MATRIX_JUMP && e.packet is ClientboundPlayerPositionPacket) teleportVelocity = mc.player?.deltaMovement
        }
        on<SendPacketEvent> { e ->
            val v = teleportVelocity
            if (mode.value == Mode.MATRIX_JUMP && e.packet is ServerboundMovePlayerPacket.PosRot && v != null) {
                mc.player?.setDeltaMovement(v.x, v.y, v.z)
                disable()
                teleportVelocity = null
            }
        }
        on<MoveEvent> { e ->
            val p = mc.player ?: return@on
            if (mode.value != Mode.GRIM_GLIDE) return@on
            val m = Movement.strafe(0.3f, forward = 1.0f, strafe = 0.0f)
            e.cancel()
            e.y = -0.03
            e.x = m[0]
            e.z = m[1]
            p.setDeltaMovement(e.x, -0.03, e.z)
        }
    }

    override fun onDisable() {
        val p = mc.player ?: return
        p.abilities.flying = false
        p.abilities.flyingSpeed = 0.05f
    }
}
