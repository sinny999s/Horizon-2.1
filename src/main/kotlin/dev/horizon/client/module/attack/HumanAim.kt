package dev.horizon.client.module.attack

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The maths behind Aura's LEGIT `aim-model` HUMAN, kept free of the game so it can be simulated in
 * tests. Aiming with a mouse is a chain of ballistic submovements, not a spring:
 * - your own movement you feel at once, so it's made up for by a smooth, steady turn; only the target's own movement is
 *   seen late;
 * - each submovement is planned from what you saw a moment ago (`perception-delay`), predicted forward a bit, then run
 *   without looking (open loop) over a time that grows with the distance as Fitts's law says, on a minimum-jerk
 *   (bell-shaped) speed curve, so a big flick is fast in the middle and slow at both ends, never a constant speed;
 * - it usually lands a little short (undershoot), sometimes past (`overshoot`), scattered by an error that grows with the
 *   distance (`aim-error`), along a slightly curved path (`curve`), and a corrective submovement follows;
 * - on a moving target the hand also partly matches its speed (`tracking`), lagging as a person's does;
 * - once the crosshair rests on the box, small errors are left alone (the dead zone, from `hitbox-relax`);
 * - hand tremor is band-limited random noise, not a repeating wave, and its amplitude is capped;
 * - when the target is gone the hand glides to a stop instead of halting dead.
 *
 * Positions are in blocks (interpolated to the frame), angles in degrees, times in seconds. [step] returns how far to
 * turn this frame; the caller turns it into whole mouse counts. For Aura's `follow-mouse` ([FollowMouse]) the look can
 * also be turned from outside while a movement runs ([credit]), turned onto something that isn't a target ([onto]),
 * or left alone for a while with the hand only watching ([watch]).
 */
class HumanAim(private val rng: Random) {
    class Params(
        /** The fastest the hand turns (deg/s), as min/max-aim-speed: picked per target between the two. */
        val speedLo: Double = 240.0,
        val speedHi: Double = 420.0,
        /** Fitts's law: movement time = a + b * log2(1 + distance / width). `smoothness` sets b. */
        val fittsA: Double = 0.075,
        val fittsB: Double = 0.11,
        val perceptionDelay: Double = 0.10,
        /** Chance (0..1) a submovement of more than a few degrees overshoots. */
        val overshoot: Double = 0.2,
        /** Endpoint scatter as a fraction of the distance. */
        val aimError: Double = 0.06,
        /** Sideways bow of a path as a fraction of its length (standard deviation). */
        val curve: Double = 0.06,
        /** How much of the target's own angular speed the hand matches (0..1); also how far it predicts ahead. */
        val tracking: Double = 0.8,
        /** Pitch's share of the correction (0..1, pitch-assist). */
        val pitchScale: Double = 0.65,
        /** The dead zone while on the box, as a fraction of the box's half width (hitbox-relax). */
        val relax: Double = 0.7,
        /** Tremor (as Aura's `tremor`), in about deg/s of noise; 0 is none. */
        val tremor: Double = 0.35,
    )

    private class Sub(var dYaw: Double, var dPitch: Double, val duration: Double, val bow: Double) {
        var t = 0.0
    }

    /** A point or a velocity in the world (blocks, blocks/s), so this stays free of the game's classes. */
    class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = V(x * k, y * k, z * k)
        fun length() = sqrt(x * x + y * y + z * z)
    }

    /** Where the target's box centre was at a time. */
    private class Sample(val t: Double, val c: V)

    private val history = ArrayDeque<Sample>()
    private var sub: Sub? = null
    private var pause = 0.0
    private var topSpeed = 0.0

    /** The last frame's turn speed (deg/s), what [release] glides out from. */
    var velYaw = 0.0
        private set
    var velPitch = 0.0
        private set
    private var tremYaw = 0.0
    private var ffYawNow = 0.0
    private var ffPitchNow = 0.0
    private var tremPitch = 0.0
    private var clickKick = 0.0

    /** How far off the aim the look is, as the eye reports it this frame ([perceive]). */
    private var errYaw = 0.0
    private var errPitch = 0.0

    /** Submovements started so far (for tests and traces). */
    var submovements = 0
        private set

    /** A new target: forget what was seen of the last one, pick this one's hand speed. */
    fun retarget(p: Params) {
        history.clear()
        sub = null
        pause = 0.0
        ffYawNow = 0.0
        ffPitchNow = 0.0
        val lo = min(p.speedLo, p.speedHi)
        val hi = max(p.speedLo, p.speedHi)
        topSpeed = lo + (hi - lo) * rng.nextDouble()
    }

    /** A click: the hand jolts a little. */
    fun click() {
        clickKick = 1.0
    }

    /** Stop dead (Aura off, a screen opened). */
    fun reset() {
        history.clear()
        sub = null
        velYaw = 0.0
        velPitch = 0.0
        tremYaw = 0.0
        tremPitch = 0.0
        ffYawNow = 0.0
        ffPitchNow = 0.0
    }

    /** No target: the hand's last motion dies away over about a tenth of a second. Returns the turn this frame. */
    fun release(dt: Double): DoubleArray {
        sub = null
        history.clear()
        val k = exp(-dt / GLIDE)
        val nYaw = velYaw * k
        val nPitch = velPitch * k
        // The distance covered while slowing from v to v*k is the integral of the exponential.
        val out = doubleArrayOf((velYaw - nYaw) * GLIDE, (velPitch - nPitch) * GLIDE)
        velYaw = if (abs(nYaw) < 0.5) 0.0 else nYaw
        velPitch = if (abs(nPitch) < 0.5) 0.0 else nPitch
        return out
    }

    /**
     * One frame. [now] is a clock in seconds, [yaw]/[pitch] where the camera looks, [eye] the eye this frame and [eyeVel]
     * its speed, [spot] the aim spot and [center] the target box's centre this frame (all interpolated to the frame, not
     * the tick), [width]/[height] the box's angular size (deg), [onBox] whether the crosshair is on it. [size] is the
     * target size Fitts's law times a movement by (the width unless given).
     *
     * Your own movement is known at once (you feel it), so it is made up for smoothly and fully; only the target's own
     * movement is seen late and predicted. A target standing still while you strafe round it is followed by a steady
     * turn, not by catch-up flicks.
     */
    fun step(
        now: Double, dt: Double, yaw: Double, pitch: Double, eye: V, eyeVel: V, spot: V, center: V,
        width: Double, height: Double, onBox: Boolean, p: Params, size: Double = width,
    ): DoubleArray {
        if (topSpeed <= 0.0) retarget(p)
        record(now, center)
        if (dt <= 0.0) return doubleArrayOf(0.0, 0.0)
        perceive(now, dt, yaw, pitch, eye, eyeVel, spot, center, p)
        var outYaw = ffYawNow * dt
        var outPitch = ffPitchNow * dt

        // Plan the next submovement once the last has run and the short gap after it is over.
        if (sub == null) {
            pause -= dt
            if (pause <= 0.0) {
                val dist = hypot(errYaw, errPitch)
                // The dead zone is the box's shape: a player is three times as tall as wide, so a few degrees up or
                // down on them is still on them.
                val dw = if (onBox) max(MIN_DEAD, p.relax * width * 0.5) else MIN_DEAD
                val dh = if (onBox) max(MIN_DEAD, p.relax * height * 0.5 * p.pitchScale) else MIN_DEAD
                val outside = (errYaw / dw) * (errYaw / dw) + (errPitch / dh) * (errPitch / dh) > 1.0
                if (outside) sub = plan(errYaw, errPitch, dist, size, p)
            }
        }
        val moved = advance(dt)
        outYaw += moved[0]
        outPitch += moved[1]

        // Tremor: band-limited noise (an Ornstein-Uhlenbeck speed), a touch stronger just after a click.
        clickKick *= exp(-dt * 28.0)
        val sigma = p.tremor * TREMOR_GAIN * (1.0 + clickKick * 0.65)
        val decay = exp(-dt / TREMOR_TAU)
        val kick = sigma * sqrt(1.0 - decay * decay)
        tremYaw = tremYaw * decay + kick * rng.nextGaussian()
        tremPitch = tremPitch * decay + kick * rng.nextGaussian() * max(p.pitchScale, 0.25)
        val cap = sigma * 2.5
        tremYaw = tremYaw.coerceIn(-cap, cap)
        tremPitch = tremPitch.coerceIn(-cap, cap)
        outYaw += tremYaw * dt
        outPitch += tremPitch * dt
        return capped(outYaw, outPitch, dt)
    }

    /**
     * A frame in which the hand leaves the look alone (your own crosshair is on the target, [FollowMouse]): it keeps
     * watching the target as in [step], so that it follows at once when it takes over.
     */
    fun watch(now: Double, dt: Double, yaw: Double, pitch: Double, eye: V, eyeVel: V, spot: V, center: V, p: Params) {
        if (topSpeed <= 0.0) retarget(p)
        record(now, center)
        if (dt > 0.0) perceive(now, dt, yaw, pitch, eye, eyeVel, spot, center, p)
    }

    /**
     * One frame of turning onto something that is your own and not a target (the camera, for Aura's hidden look),
     * [errYaw], [errPitch] away now: nothing to see late or to follow, only submovements, timed for something [size]
     * degrees wide. A movement still under way is finished first.
     */
    fun onto(dt: Double, errYaw: Double, errPitch: Double, size: Double, p: Params): DoubleArray {
        if (topSpeed <= 0.0) retarget(p)
        if (dt <= 0.0) return doubleArrayOf(0.0, 0.0)
        if (sub == null) {
            pause -= dt
            val dist = hypot(errYaw, errPitch)
            if (pause <= 0.0 && dist > MIN_DEAD) sub = plan(errYaw, errPitch, dist, size, p)
        }
        val moved = advance(dt)
        return capped(moved[0], moved[1], dt)
    }

    /**
     * The look was turned [dYaw], [dPitch] towards the aim from outside (your own hand, [FollowMouse]): that much less
     * is left of the movement under way, which ends early when nothing is.
     */
    fun credit(dYaw: Double, dPitch: Double) {
        val s = sub ?: return
        val left = 1.0 - minJerk(s.t / s.duration)
        if (left < 1e-3) return
        val yawLeft = less(s.dYaw * left, dYaw)
        val pitchLeft = less(s.dPitch * left, dPitch)
        if (hypot(yawLeft, pitchLeft) < MIN_DEAD) {
            sub = null
            pause = GAP_LO + (GAP_HI - GAP_LO) * rng.nextDouble()
            return
        }
        s.dYaw = yawLeft / left
        s.dPitch = pitchLeft / left
    }

    /** The look is where it should be without the hand's doing ([FollowMouse]): nothing is under way, nothing to glide out. */
    fun rest() {
        sub = null
        pause = 0.0
        velYaw = 0.0
        velPitch = 0.0
    }

    /** [left] with [by] taken off it when they point the same way, never past zero. */
    private fun less(left: Double, by: Double) = if (left * by <= 0.0) left else if (abs(by) >= abs(left)) 0.0 else left - by

    /** Runs the submovement under way for [dt]; returns its turn. */
    private fun advance(dt: Double): DoubleArray {
        val s = sub ?: return doubleArrayOf(0.0, 0.0)
        val t0 = s.t / s.duration
        s.t = min(s.duration, s.t + dt)
        val t1 = s.t / s.duration
        val ds = minJerk(t1) - minJerk(t0)
        val db = bowShape(t1) - bowShape(t0)
        val out = doubleArrayOf(s.dYaw * ds - s.dPitch * s.bow * db, s.dPitch * ds + s.dYaw * s.bow * db)
        if (s.t >= s.duration) {
            sub = null
            pause = GAP_LO + (GAP_HI - GAP_LO) * rng.nextDouble()
        }
        return out
    }

    /** The frame's turn, never faster than the hand can go; its speed is what [release] glides out from. */
    private fun capped(yaw: Double, pitch: Double, dt: Double): DoubleArray {
        var outYaw = yaw
        var outPitch = pitch
        val speed = hypot(outYaw, outPitch) / dt
        val limit = topSpeed * 1.08
        if (speed > limit) {
            outYaw *= limit / speed
            outPitch *= limit / speed
        }
        velYaw = outYaw / dt
        velPitch = outPitch / dt
        return doubleArrayOf(outYaw, outPitch)
    }

    /**
     * What the eye reports this frame: how far off the aim the look is ([errYaw], [errPitch]), and the steady turn that
     * follows you and the target ([ffYawNow], [ffPitchNow]).
     */
    private fun perceive(now: Double, dt: Double, yaw: Double, pitch: Double, eye: V, eyeVel: V, spot: V, center: V, p: Params) {
        // What the eye reports of the target: where it was perceptionDelay ago, with its speed and that speed's change,
        // carried forward to now as far as `tracking` predicts (people read a strafe's rhythm, so not just its speed).
        val d = p.perceptionDelay
        val seen = sampleAt(now - d)
        val mid = sampleAt(now - d - VEL_WINDOW)
        val old = sampleAt(now - d - 2 * VEL_WINDOW)
        val span1 = seen.t - mid.t
        val span0 = mid.t - old.t
        val v1 = if (span1 > 1e-4) (seen.c - mid.c) * (1.0 / span1) else ZERO
        var acc = ZERO
        if (span1 > 1e-4 && span0 > 1e-4) {
            val a = (v1 - (mid.c - old.c) * (1.0 / span0)) * (2.0 / (span0 + span1))
            val al = a.length()
            acc = if (al > MAX_ACCEL) a * (MAX_ACCEL / al) else a
        }
        // The speed at the moment seen (v1 is the average over the window before it).
        var seenVel = v1 + acc * (span1 / 2)
        val sv = seenVel.length()
        if (sv > MAX_SPEED) seenVel = seenVel * (MAX_SPEED / sv)
        val lead = p.tracking * d
        val predCenter = seen.c + (seenVel + acc * (lead / 2)) * lead
        val tVel = (seenVel + acc * lead) * p.tracking
        // The spot on the box is your own choice, so it isn't seen late: it rides on the perceived box.
        val aim = predCenter + (spot - center)

        val wantYaw = yawTo(eye, aim)
        val wantPitch = pitchTo(eye, aim)
        errYaw = wrap(wantYaw - yaw)
        errPitch = (wantPitch - pitch) * p.pitchScale

        // The turn that keeps the crosshair where it is on the target as you and it move: all of your own movement,
        // `tracking` of the target's as seen.
        val h = 0.05
        var ffYaw = wrap(yawTo(eye + eyeVel * h, aim + tVel * h) - wantYaw) / h
        var ffPitch = (pitchTo(eye + eyeVel * h, aim + tVel * h) - wantPitch) / h * p.pitchScale
        val ff = hypot(ffYaw, ffPitch)
        if (ff > topSpeed) {
            ffYaw *= topSpeed / ff
            ffPitch *= topSpeed / ff
        }

        // A hand takes a moment to match a change of speed (a jump, a landing, a strafe turning round): no step changes.
        val follow = 1.0 - exp(-dt / FOLLOW_TAU)
        ffYawNow += (ffYaw - ffYawNow) * follow
        ffPitchNow += (ffPitch - ffPitchNow) * follow
    }

    private fun plan(errYaw: Double, errPitch: Double, dist: Double, width: Double, p: Params): Sub {
        submovements++
        // Primary gain: a little short as a rule, past now and then (only worth it on a real flick).
        val gain = if (dist > 3.0 && rng.nextDouble() < p.overshoot) 1.03 + 0.09 * rng.nextDouble()
        else (0.94 + 0.035 * rng.nextGaussian()).coerceIn(0.82, 1.0)
        val ux = errYaw / dist
        val uy = errPitch / dist
        val along = dist * (gain + p.aimError * rng.nextGaussian().coerceIn(-2.5, 2.5))
        val across = dist * p.aimError * 0.5 * rng.nextGaussian().coerceIn(-2.5, 2.5)
        val dYaw = ux * along - uy * across
        val dPitch = uy * along + ux * across
        val len = hypot(dYaw, dPitch)
        val w = max(width, 0.5)
        var duration = (p.fittsA + p.fittsB * log2(1.0 + len / w)) * (0.9 + 0.2 * rng.nextDouble())
        // A minimum-jerk curve peaks at 1.875x its average speed: lengthen the movement to stay under the hand's top.
        duration = max(duration, 1.875 * len / max(topSpeed, 1.0))
        duration = max(duration, MIN_DURATION)
        val bow = (p.curve * rng.nextGaussian()).coerceIn(-3.0 * p.curve, 3.0 * p.curve)
        return Sub(dYaw, dPitch, duration, bow)
    }

    /** Keeps the box centre's track for the last [HISTORY] seconds. */
    private fun record(now: Double, center: V) {
        if (history.isNotEmpty() && now <= history.last().t) history.removeLast()
        history.addLast(Sample(now, center))
        while (history.size > 2 && history[1].t < now - HISTORY) history.removeFirst()
    }

    private fun sampleAt(t: Double): Sample {
        val first = history.first()
        if (t <= first.t) return first
        for (i in history.size - 1 downTo 1) {
            val a = history[i - 1]
            val b = history[i]
            if (t >= a.t) {
                if (t >= b.t) return b
                val f = (t - a.t) / (b.t - a.t)
                return Sample(t, a.c + (b.c - a.c) * f)
            }
        }
        return first
    }

    companion object {
        const val GLIDE = 0.09
        const val FOLLOW_TAU = 0.03
        const val GAP_LO = 0.015
        const val GAP_HI = 0.07
        const val MIN_DURATION = 0.06
        const val MIN_DEAD = 0.35
        const val VEL_WINDOW = 0.05
        /** The target's own acceleration and speed as perceived, at most (blocks/s^2, blocks/s): knockback is faster. */
        const val MAX_ACCEL = 25.0
        const val MAX_SPEED = 12.0
        private val ZERO = V(0.0, 0.0, 0.0)
        const val HISTORY = 0.6
        const val TREMOR_TAU = 0.035
        const val TREMOR_GAIN = 2.2

        /** Minimum-jerk position along a movement: 10t^3 - 15t^4 + 6t^5. */
        fun minJerk(t: Double): Double {
            val x = t.coerceIn(0.0, 1.0)
            return x * x * x * (10.0 + x * (-15.0 + 6.0 * x))
        }

        /** The sideways bow, zero at both ends: half a sine wave over the movement's progress. */
        fun bowShape(t: Double): Double = sin(PI * minJerk(t))

        fun wrap(d: Double): Double {
            var x = d % 360.0
            if (x >= 180.0) x -= 360.0
            if (x < -180.0) x += 360.0
            return x
        }

        /** The camera yaw that looks from [from] to [to], as Minecraft counts it. */
        fun yawTo(from: V, to: V): Double = Math.toDegrees(atan2(to.z - from.z, to.x - from.x)) - 90.0

        fun pitchTo(from: V, to: V): Double = -Math.toDegrees(atan2(to.y - from.y, hypot(to.x - from.x, to.z - from.z)))

        private fun log2(x: Double) = ln(x) / ln(2.0)
    }
}
