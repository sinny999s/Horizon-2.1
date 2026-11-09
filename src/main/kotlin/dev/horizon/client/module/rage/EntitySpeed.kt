package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.TravelEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import kotlin.math.min

/**
 * At the start of the player's travel, sets the ridden vehicle's horizontal velocity
 * along the keys (accelerating to `speed`, plus `jitter` every other 25 ticks), with the client at `timer`; stops at
 * unloaded chunks or below y −60 with stop-unloaded. The player's own travel is skipped then.
 */
class EntitySpeed : Module("entity-speed", Categories.RAGE) {
    val accelerate = bool("accelerate", true)
    val accelerateFactor = float("accelerate-factor", 9.0f, 0.0f..20.0f) { accelerate.value }
    val stopUnloaded = bool("stop-unloaded", true)
    val speed = float("speed", 0.77f, 0.1f..5.0f)
    val timer = float("timer", 1.0f, 0.1f..5.0f)
    val jitter = float("jitter", 0.05f, 0.0f..0.5f)

    /** Ticks driven, wrapping after 50 (jitter in the second half). */
    private var ticks = 0

    /** The acceleration ramp, ×100 the speed it allows. */
    @Volatile
    private var ramp = 0f

    init {
        on<TravelEvent> { e ->
            if (!e.pre) return@on
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val v = p.controlledVehicle ?: return@on
            if (!(level.hasChunk(v.position().x.toInt() shr 4, v.position().z.toInt() shr 4) && !(v.position().y < -60.0) || !stopUnloaded.value)) return@on
            if (v.horizontalCollision || p.horizontalCollision) ramp = 0f
            if (timer.value != 1.0f) TimerManager.factor = timer.value
            val m = Movement.strafe(speed())
            val x = v.x + m[0]
            val z = v.z + m[1]
            if (level.hasChunk(x.toInt() shr 4, z.toInt() shr 4) && !(v.position().y < -60.0) || !stopUnloaded.value) {
                if (Movement.moving()) v.setDeltaMovement(m[0], v.deltaMovement.y, m[1]) else v.setDeltaMovement(0.0, v.deltaMovement.y, 0.0)
                if (ticks++ > 50) ticks = 0
                e.cancel()
            }
        }
        on<ReceivePacketEvent> { e ->
            if (e.packet is ClientboundPlayerPositionPacket) ramp = 0f
        }
    }

    override fun onEnable() {
        ticks = 0
        ramp = 0f
    }

    private fun speed(): Float {
        ramp += (20.0f - accelerateFactor.value) / speed.value
        var s = min((ramp / 100.0f).toDouble(), speed.value.toDouble()).toFloat()
        if (!accelerate.value) s = speed.value
        return s + if (ticks > 25) jitter.value else 0.0f
    }
}
