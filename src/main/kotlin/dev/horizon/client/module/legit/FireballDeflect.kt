package dev.horizon.client.module.legit

import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball
import net.minecraft.world.phys.EntityHitResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Hits incoming fireballs back the way a player does. A fireball whose path passes within
 * [THREAT_RADIUS] blocks of you (one you didn't throw) gets a camera flick in whole mouse counts onto where it is
 * heading, and vanilla's own left click once the crosshair is really on it; then, with [returnAim], the camera turns
 * back to where you were looking, unless you move the mouse yourself.
 */
class FireballDeflect : Module("fireball-deflect", Categories.LEGIT) {
    /** How far out an incoming fireball is noticed, in blocks. */
    val detectRange = float("detect-range", 12.0f, 4.0f..30.0f)
    /** Fastest the camera turns, in degrees a second. */
    val turnSpeed = float("turn-speed", 900.0f, 200.0f..2000.0f)
    /** Milliseconds between seeing the fireball and starting to turn. */
    val reaction = int("reaction", 60, 0..300)
    val returnAim = bool("return-aim", true)

    private enum class Phase { IDLE, AIMING, RETURNING }

    private var phase = Phase.IDLE
    private var fireball: Fireball? = null
    private var since = 0L
    private var backYaw = 0f
    private var backPitch = 0f

    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    /** The camera is this module's: Aura and GodBridge leave it alone meanwhile. */
    val busy get() = enabled && phase != Phase.IDLE

    override fun onEnable() = reset()
    override fun onDisable() = reset()

    private fun reset() {
        phase = Phase.IDLE
        fireball = null
        since = 0L
        stopTurning()
    }

    private fun stopTurning() {
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            if (mc.level == null || mc.gui.screen() != null || p.isSpectator) {
                reset()
                return@on
            }
            val current = fireball?.takeIf { phase == Phase.AIMING && threat(p, it) }
            val fb = current ?: findThreat(p)
            if (fb == null) {
                if (phase == Phase.AIMING) startReturn()
                return@on
            }
            if (phase != Phase.AIMING) {
                // From the start (or mid-return) the aim to go back to stays the one from before the first flick.
                if (phase == Phase.IDLE) {
                    backYaw = p.yRot
                    backPitch = p.xRot
                }
                phase = Phase.AIMING
                since = System.nanoTime()
                stopTurning()
            }
            fireball = fb
            if ((System.nanoTime() - since) / 1e6 < reaction.value) return@on
            // The crosshair as vanilla's own left click would see it this tick.
            (mc as MinecraftAccessor).horizon_pick(1f)
            val hit = mc.hitResult as? EntityHitResult ?: return@on
            if (hit.entity !is Fireball || !threat(p, hit.entity as Fireball)) return@on
            if ((mc as MinecraftAccessor).horizon_startAttack()) startReturn()
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            if (phase == Phase.IDLE) return@on
            // Saving a fall matters more than a fireball: a clutch or a pearl throw has the camera first.
            if (Modules.get<Clutch>().busy || Modules.get<PearlSave>().busy) return@on
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now
            val wantYaw: Double
            val wantPitch: Double
            if (phase == Phase.AIMING) {
                val fb = fireball ?: return@on
                if ((now - since) / 1e6 < reaction.value) return@on
                val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
                val eye = p.getEyePosition(pt)
                // Where it will be a tick from now: the click lands on the next tick.
                val to = fb.getPosition(pt).add(0.0, fb.bbHeight / 2.0, 0.0).add(fb.deltaMovement)
                val dx = to.x - eye.x
                val dz = to.z - eye.z
                wantYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
                wantPitch = -Math.toDegrees(atan2(to.y - eye.y, hypot(dx, dz)))
            } else {
                // Your own mouse ends the return: you've taken the aim back.
                if (MouseTurnEvent.movedWithinMs(80) || (now - since) / 1e6 > RETURN_MS) {
                    reset()
                    return@on
                }
                wantYaw = backYaw.toDouble()
                wantPitch = backPitch.toDouble()
            }
            if (turn(p, wantYaw, wantPitch, dt) && phase == Phase.RETURNING) reset()
        }
    }

    private fun startReturn() {
        if (!returnAim.value) {
            reset()
            return
        }
        phase = Phase.RETURNING
        fireball = null
        since = System.nanoTime()
        stopTurning()
    }

    /** One frame of turning towards the angles, in whole mouse counts. Returns whether it is there. */
    private fun turn(p: Player, wantYaw: Double, wantPitch: Double, dt: Double): Boolean {
        var errYaw = Mth.wrapDegrees(wantYaw - p.yRot)
        var errPitch = wantPitch - p.xRot
        val f = mc.options.sensitivity().get() * 0.6 + 0.2
        val perCount = f * f * f * 8.0
        val step = perCount * 0.15
        val there = abs(errYaw) < step && abs(errPitch) < step
        if (abs(errYaw) < step) {
            errYaw = 0.0
            vYaw = 0.0
            carryX = 0.0
        }
        if (abs(errPitch) < step) {
            errPitch = 0.0
            vPitch = 0.0
            carryY = 0.0
        }
        val maxSpeed = turnSpeed.value.toDouble()
        val stiffness = 900.0
        val c = 2.0 * sqrt(stiffness) * 0.9
        vYaw = (vYaw + (stiffness * errYaw - c * vYaw) * dt).coerceIn(-maxSpeed, maxSpeed)
        vPitch = (vPitch + (stiffness * errPitch - c * vPitch) * dt).coerceIn(-maxSpeed, maxSpeed)
        carryX += vYaw * dt / step
        carryY += vPitch * dt / step
        val countsX = carryX.toInt()
        val countsY = carryY.toInt()
        carryX -= countsX
        carryY -= countsY
        if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
        return there
    }

    private fun findThreat(p: Player): Fireball? {
        val level = mc.level ?: return null
        var best: Fireball? = null
        var bestDist = Double.MAX_VALUE
        for (e in level.entitiesForRendering()) {
            if (e !is Fireball || !threat(p, e)) continue
            val d = e.distanceToSqr(p)
            if (d < bestDist) {
                bestDist = d
                best = e
            }
        }
        return best
    }

    /**
     * Whether [fb] is coming at the player: alive, not theirs, within [detectRange], closing in, and its straight path
     * passing within [THREAT_RADIUS] of the middle of their box over the next [LOOK_AHEAD] ticks.
     */
    private fun threat(p: Player, fb: Fireball): Boolean {
        if (!fb.isAlive || fb.owner === p) return false
        val center = p.boundingBox.center
        val rel = center.subtract(fb.position())
        if (rel.length() > detectRange.value) return false
        val v = fb.deltaMovement
        if (v.lengthSqr() < 1e-6 || rel.dot(v) <= 0) return false
        // Nearest approach along the path, within the look-ahead.
        val t = (rel.dot(v) / v.lengthSqr()).coerceIn(0.0, LOOK_AHEAD.toDouble())
        val closest = fb.position().add(v.scale(t))
        return closest.distanceTo(center) <= THREAT_RADIUS
    }

    private companion object {
        const val THREAT_RADIUS = 1.8
        const val LOOK_AHEAD = 40
        /** Longest the camera spends turning back. */
        const val RETURN_MS = 600L
    }
}
