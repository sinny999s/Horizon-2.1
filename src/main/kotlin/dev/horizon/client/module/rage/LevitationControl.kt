package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.effect.MobEffects

/**
 * With Levitation, NCP and DEFAULT replace the vertical move (jump rises, sneak
 * sinks, else hover); ANTI removes the effect client-side. MoveEvent priority 0.
 */
class LevitationControl : Module("levitation-control", Categories.RAGE) {
    enum class Mode { NCP, DEFAULT, ANTI }

    val mode = enum("mode", Mode.NCP)

    init {
        on<MoveEvent> { e ->
            val p = mc.player ?: return@on
            if (!p.hasEffect(MobEffects.LEVITATION)) return@on
            val amplifier = p.getEffect(MobEffects.LEVITATION)!!.amplifier
            val o = mc.options
            when (mode.value) {
                Mode.NCP -> {
                    if (o.keyJump.isDown) {
                        e.y = e.y + (0.05 * (amplifier + 1) - e.y) * 0.2
                    } else if (o.keyShift.isDown) {
                        e.y = -0.08
                        e.x = 0.0
                        e.z = 0.0
                    } else if (!Movement.moving() && !(ServerInfo.horizontalSpeed > 0f)) {
                        e.y = 0.0
                    } else {
                        e.y = 0.004
                    }
                    e.cancel()
                }
                Mode.DEFAULT -> {
                    e.y = if (o.keyJump.isDown) 0.1 else if (o.keyShift.isDown) -0.1 else 0.0
                    e.cancel()
                }
                Mode.ANTI -> p.removeEffect(MobEffects.LEVITATION)
            }
        }
    }
}
