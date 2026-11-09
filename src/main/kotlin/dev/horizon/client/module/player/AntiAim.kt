package dev.horizon.client.module.player

import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * Every tick (not while attacking or using, and with Off Ground Only not on the ground) a
 * server-side rotation from the yaw and pitch modes, queued at priority 0 so any other rotation wins.
 *
 * The two delays are swapped on purpose: the yaw modes count "pitch-delay" and the pitch modes "yaw-delay".
 */
class AntiAim : Module("anti-aim", Categories.PLAYER) {
    enum class Yaw { OFF, RANDOM, SPIN, SIN, FIX, STATIC, JITTER }

    val yaw = enum("yaw", Yaw.OFF)
    val pitch = enum("pitch", Yaw.OFF)
    val pitchDelay = int("pitch-delay", 1, 1..180)
    val yawDelay = int("yaw-delay", 1, 1..180)
    val yawDelta = float("yaw-delta", 60.0f, -360.0f..360.0f)
    val pitchDelta = float("pitch-delta", 10.0f, -90.0f..90.0f)
    val yawOffset = float("yaw-offset", 0.0f, -180.0f..180.0f)
    val offGroundOnly = bool("off-ground-only", true)

    // These survive turning the module off and on (on purpose).
    private var yawValue = 0f
    private var pitchValue = 0f
    private var yawPhase = 0f
    private var pitchPhase = 0f

    init {
        on<PlayerUpdateEvent>(10) {
            val p = mc.player ?: return@on
            if (mc.options.keyAttack.isDown) return@on
            if (mc.options.keyUse.isDown) return@on
            if (p.onGround() && offGroundOnly.value) return@on
            val yawMode = yaw.value
            val pitchMode = pitch.value
            val yawTicks = pitchDelay.value
            val pitchTicks = yawDelay.value
            val tick = p.tickCount
            if (pitchMode == Yaw.RANDOM && tick % pitchTicks == 0) pitchValue = Rng.float(90f, -90f)
            if (yawMode == Yaw.RANDOM && tick % yawTicks == 0) yawValue = Rng.float(0f, 360f)
            if (yawMode == Yaw.SPIN && tick % yawTicks == 0) {
                yawValue += yawDelta.value
                if (yawValue > 360f) yawValue = 0f
                if (yawValue < 0f) yawValue = 360f
            }
            if (pitchMode == Yaw.SPIN && tick % pitchTicks == 0) {
                pitchValue += pitchDelta.value
                if (pitchValue > 90f) pitchValue = -90f
                if (pitchValue < -90f) pitchValue = 90f
            }
            if (pitchMode == Yaw.SIN) {
                pitchPhase += pitchTicks / 10f
                pitchValue = (p.xRot + pitchDelta.value * Math.sin(pitchPhase.toDouble())).toFloat().coerceIn(-90f, 90f)
            }
            if (yawMode == Yaw.SIN) {
                yawPhase += yawTicks / 10f
                yawValue = (p.yRot + yawDelta.value * Math.sin(yawPhase.toDouble()) + yawOffset.value).toFloat()
            }
            if (pitchMode == Yaw.FIX) pitchValue = pitchDelta.value
            if (yawMode == Yaw.FIX) yawValue = yawDelta.value
            if (pitchMode == Yaw.STATIC) pitchValue = (p.xRot + pitchDelta.value).coerceIn(-90f, 90f)
            if (yawMode == Yaw.STATIC) yawValue = p.yRot % 360 + yawDelta.value
            val yawJitter = tick % (yawTicks * 2)
            val pitchJitter = tick % (pitchTicks * 2)
            if (pitchMode == Yaw.JITTER) {
                if (yawJitter == 0) pitchValue = pitchDelta.value / 2f
                if (yawJitter == pitchTicks) pitchValue = pitchDelta.value / -2f
            }
            if (yawMode == Yaw.JITTER) {
                if (pitchJitter == 0) yawValue = yawDelta.value / 2f + yawOffset.value + p.yRot
                if (pitchJitter == yawTicks) yawValue = yawDelta.value / -2f + yawOffset.value + p.yRot
            }
            val y = if (yawMode != Yaw.OFF) yawValue else p.yRot
            val x = if (pitchMode != Yaw.OFF) pitchValue else p.xRot
            RotationManager.rotate(Rotation(y, x), 0)
        }
    }
}
