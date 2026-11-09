package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * At PreSync, Motion pushes the velocity down by `motion` while on the ground; Timer
 * runs the client at `timer` while falling faster than 0.1 (jump not held). Not in liquids, gliding, flying or webs.
 */
class ReverseStep : Module("reverse-step", Categories.RAGE) {
    @Suppress("EnumEntryName")
    enum class Mode { Timer, Motion }

    val mode = enum("mode", Mode.Motion)
    val timer = float("timer", 3.0f, 1.0f..5.0f) { mode.value == Mode.Timer }
    val motion = float("motion", 1.0f, 0.1f..2.0f) { mode.value == Mode.Motion }
    val pauseIfShift = bool("pause-if-shift", false)

    init {
        on<PreSyncEvent> {
            val p = mc.player ?: return@on
            if (pauseIfShift.value && mc.options.keyShift.isDown) return@on
            if (p.isInWater || p.isUnderWater || p.isInLava || p.isFallFlying || p.abilities.flying || Movement.inCobweb(p)) return@on
            if (mode.value == Mode.Timer && !mc.options.keyJump.isDown && !p.onGround() && !(p.deltaMovement.y >= -0.1)) {
                TimerManager.factor = timer.value
            }
            if (p.onGround() && mode.value == Mode.Motion) p.deltaMovement = p.deltaMovement.add(0.0, -motion.value.toDouble(), 0.0)
        }
    }
}
