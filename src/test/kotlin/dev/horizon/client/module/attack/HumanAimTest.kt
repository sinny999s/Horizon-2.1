package dev.horizon.client.module.attack

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simulates Aura's HUMAN aim model frame by frame against targets moving over time, and you moving too. */
class HumanAimTest {
    /** One run: camera angles and speed each frame. */
    class Trace(val t: DoubleArray, val yaw: DoubleArray, val pitch: DoubleArray, val speed: DoubleArray, val onBox: BooleanArray, val subs: Int)

    private fun width(dist: Double) = Math.toDegrees(2 * atan(0.3 / dist))
    private fun height(dist: Double) = Math.toDegrees(2 * atan(0.9 / dist))

    private val ORIGIN = HumanAim.V(0.0, 0.0, 0.0)

    private fun dir(yaw: Double, pitch: Double): HumanAim.V {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return HumanAim.V(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /**
     * [target] gives the target's (yaw, pitch) at a time, seen from the origin [dist] away; [lostAt] stops aiming then
     * (release). The camera starts at (0, 0) unless [startYaw].
     */
    private fun run(
        seed: Long, seconds: Double, p: HumanAim.Params = HumanAim.Params(), dist: Double = 3.0, startYaw: Double = 0.0,
        lostAt: Double = Double.MAX_VALUE, target: (Double) -> Pair<Double, Double>,
    ): Trace = runWorld(seed, seconds, p, startYaw, 0.0, lostAt, { ORIGIN }) { t ->
        val (y, pt) = target(t)
        dir(y, pt) * dist
    }

    /**
     * The same in the world: [eye] and the target box's centre [target] (blocks) at a time. Frames at about 144 fps with
     * some jitter; the box is a player's (0.6 wide, 1.8 tall).
     */
    private fun runWorld(
        seed: Long, seconds: Double, p: HumanAim.Params, startYaw: Double, startPitch: Double, lostAt: Double,
        eye: (Double) -> HumanAim.V, target: (Double) -> HumanAim.V,
    ): Trace {
        val rng = Random(seed)
        val aim = HumanAim(rng)
        aim.retarget(p)
        val ts = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ps = ArrayList<Double>()
        val ss = ArrayList<Double>()
        val ob = ArrayList<Boolean>()
        var yaw = startYaw
        var pitch = startPitch
        var now = 0.0
        while (now < seconds) {
            val dt = (1.0 / 144) * (0.85 + 0.3 * rng.nextDouble())
            now += dt
            val e = eye(now)
            val eVel = (eye(now + 0.001) - e) * 1000.0
            val c = target(now)
            val dist = (c - e).length()
            val on = abs(HumanAim.wrap(HumanAim.yawTo(e, c) - yaw)) < width(dist) / 2 && abs(HumanAim.pitchTo(e, c) - pitch) < height(dist) / 2
            val d = if (now >= lostAt) aim.release(dt) else aim.step(now, dt, yaw, pitch, e, eVel, c, c, width(dist), height(dist), on, p)
            yaw += d[0]
            pitch += d[1]
            ts += now; ys += yaw; ps += pitch; ss += hypot(d[0], d[1]) / dt; ob += on
        }
        return Trace(ts.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), ss.toDoubleArray(), ob.toBooleanArray(), aim.submovements)
    }

    private fun firstOnBox(tr: Trace, ty: Double, dist: Double): Double {
        val w = width(dist)
        for (i in tr.t.indices) if (abs(HumanAim.wrap(ty - tr.yaw[i])) < w / 2) return tr.t[i]
        return Double.MAX_VALUE
    }

    @Test
    fun flickLandsOnTheBoxInTime() {
        var landed = 0
        val times = ArrayList<Double>()
        repeat(300) { s ->
            val tr = run(s.toLong(), 1.5) { 60.0 to 10.0 }
            if (abs(tr.yaw.last() - 60.0) < width(3.0) / 2) landed++
            times += firstOnBox(tr, 60.0, 3.0)
        }
        times.sort()
        assertTrue(landed >= 297, "landed $landed/300")
        assertTrue(times[150] in 0.12..0.6, "median time to the box ${times[150]}")
    }

    @Test
    fun speedIsBellShapedNotConstant() {
        repeat(100) { s ->
            val p = HumanAim.Params(tremor = 0.0)
            val tr = run(s.toLong(), 0.6, p) { 90.0 to 0.0 }
            val peak = tr.speed.max()
            assertTrue(peak <= p.speedHi * 1.08 + 1e-6, "peak $peak over the hand's top speed")
            // It starts slow: the first frame is a small part of the peak.
            assertTrue(tr.speed[0] < peak * 0.3, "first frame ${tr.speed[0]} vs peak $peak")
            // A speed cap would hold the peak for many frames; a bell curve passes through it.
            val moving = tr.speed.count { it > peak * 0.2 }
            val atPeak = tr.speed.count { it > peak * 0.97 }
            assertTrue(atPeak.toDouble() / moving < 0.25, "seed $s: $atPeak of $moving moving frames at the peak")
        }
    }

    @Test
    fun biggerFlicksAreFasterAndLonger() {
        fun stats(angle: Double): Pair<Double, Double> {
            var peak = 0.0
            var time = 0.0
            repeat(200) { s ->
                val tr = run(1000L + s, 1.2, HumanAim.Params(tremor = 0.0)) { angle to 0.0 }
                peak += tr.speed.max()
                time += firstOnBox(tr, angle, 3.0)
            }
            return peak / 200 to time / 200
        }
        val (peak20, time20) = stats(20.0)
        val (peak90, time90) = stats(90.0)
        assertTrue(peak90 > peak20 * 1.5, "peak speed 20: $peak20, 90: $peak90")
        assertTrue(time90 > time20, "time 20: $time20, 90: $time90")
    }

    @Test
    fun landsOnADifferentSpotEachTime() {
        val ends = (0 until 200).map { s -> run(2000L + s, 0.3, HumanAim.Params(tremor = 0.0)) { 50.0 to 0.0 }.yaw.last() }
        val mean = ends.average()
        val sd = sqrt(ends.sumOf { (it - mean) * (it - mean) } / ends.size)
        assertTrue(sd > 0.5, "endpoint spread $sd")
    }

    @Test
    fun overshootSettingIsRespected() {
        fun rate(o: Double) = (0 until 200).count { s ->
            run(3000L + s, 0.8, HumanAim.Params(overshoot = o, tremor = 0.0)) { 45.0 to 0.0 }.yaw.max() > 45.5
        } / 200.0
        val never = rate(0.0)
        val always = rate(1.0)
        assertTrue(always > 0.8, "overshoot=1: $always")
        assertTrue(never < 0.3, "overshoot=0: $never")
    }

    @Test
    fun pathsCurve() {
        // With pitch error too, a straight path keeps yaw/pitch in a fixed ratio; a bowed one doesn't.
        var bowed = 0
        repeat(100) { s ->
            val tr = run(4000L + s, 0.5, HumanAim.Params(tremor = 0.0, pitchScale = 1.0, aimError = 0.0)) { 60.0 to 30.0 }
            val dev = tr.yaw.indices.maxOf { i -> abs(tr.pitch[i] - tr.yaw[i] * 0.5) }
            if (dev > 1.0) bowed++
        }
        assertTrue(bowed > 30, "bowed $bowed/100")
        repeat(20) { s ->
            val tr = run(4000L + s, 0.12, HumanAim.Params(tremor = 0.0, pitchScale = 1.0, aimError = 0.0, curve = 0.0, perceptionDelay = 0.0)) { 60.0 to 30.0 }
            val dev = tr.yaw.indices.maxOf { i -> abs(tr.pitch[i] - tr.yaw[i] * 0.5) }
            assertTrue(dev < 0.05, "curve=0 first submovement off the line by $dev")
        }
    }

    @Test
    fun tracksAStrafingTargetWithLag() {
        var onShare = 0.0
        var rms = 0.0
        repeat(100) { s ->
            // A/D strafing at 3 blocks: about ±15 degrees, 0.8 Hz (some 75 deg/s at its fastest).
            val tr = run(5000L + s, 4.0) { t -> (30.0 + 15.0 * sin(2 * PI * 0.8 * t)) to 0.0 }
            var on = 0
            var n = 0
            var e2 = 0.0
            for (i in tr.t.indices) if (tr.t[i] > 0.6) {
                n++
                if (tr.onBox[i]) on++
                val e = 30.0 + 15.0 * sin(2 * PI * 0.8 * tr.t[i]) - tr.yaw[i]
                e2 += e * e
            }
            onShare += on.toDouble() / n
            rms += sqrt(e2 / n)
        }
        onShare /= 100
        rms /= 100
        assertTrue(onShare > 0.6, "on the box $onShare of the time")
        assertTrue(rms > 1.0, "rms error $rms: tracking too perfect")
        assertTrue(rms < width(3.0), "rms error $rms: lost the target")
    }

    @Test
    fun catchesUpAfterAbruptStrafeReversals() {
        // A/D at 2.5 blocks as a server shows it: the target steps every tick (50 ms) at 90 deg/s, reversing every 0.45 s.
        fun strafe(t: Double): Double {
            val ph = ((kotlin.math.floor(t * 20) / 20) % 0.9) / 0.9
            return 180.0 + ((if (ph < 0.5) ph * 2 else 2 - ph * 2) - 0.5) * 40.0
        }
        val p = HumanAim.Params()
        var legsHit = 0
        var legs = 0
        repeat(50) { s ->
            val tr = run(10000L + s, 4.05, p, dist = 2.5, startYaw = 160.0) { t -> strafe(t) to 0.0 }
            assertTrue(tr.speed.max() <= p.speedHi * 1.08 + 1e-6, "faster than the hand's top speed")
            // Each leg after the first: the crosshair gets back onto the box at some point in it.
            for (leg in 1 until 9) {
                legs++
                if (tr.t.indices.any { tr.t[it] >= leg * 0.45 && tr.t[it] < (leg + 1) * 0.45 && tr.onBox[it] }) legsHit++
            }
        }
        assertTrue(legsHit.toDouble() / legs > 0.9, "back on the box in $legsHit of $legs legs")
    }

    @Test
    fun followsAStillTargetSmoothlyWhileYouStrafeAndJump() {
        // You A/D strafe (about 5 b/s at the fastest) and jump every 0.8 s, 3 blocks from someone standing still.
        val eye = { t: Double ->
            val ph = (t % 0.8) / 0.8
            HumanAim.V(1.0 * sin(2 * PI * t / 1.2), if (ph < 0.6) 1.25 * sin(PI * ph / 0.6) else 0.0, 0.0)
        }
        val target = { _: Double -> HumanAim.V(0.0, -0.6, 3.0) }
        repeat(50) { s ->
            val tr = runWorld(11000L + s, 6.0, HumanAim.Params(), 0.0, 11.3, Double.MAX_VALUE, eye, target)
            val on = tr.onBox.count { it }.toDouble() / tr.onBox.size
            assertTrue(on > 0.97, "seed $s: on the box only $on of the time")
            // A steady turn, not catch-up flicks: a small correction now and then (at most 2 a second), no more.
            assertTrue(tr.subs <= 12, "seed $s: ${tr.subs} submovements on a target that never moved")
            // Smooth: the turn speed never jumps much from one frame to the next.
            val jump = (1 until tr.speed.size).maxOf { abs(tr.speed[it] - tr.speed[it - 1]) }
            assertTrue(jump < 40.0, "seed $s: speed jumped $jump deg/s in a frame")
        }
    }

    @Test
    fun reactsOnlyAfterThePerceptionDelay() {
        repeat(100) { s ->
            val p = HumanAim.Params(tremor = 0.0, perceptionDelay = 0.15)
            // Already on the target; it starts moving away at 60 deg/s at t = 1.
            val tr = run(6000L + s, 1.8, p, startYaw = 20.0) { t -> (20.0 + max(0.0, t - 1.0) * 60.0) to 0.0 }
            val at = { time: Double -> tr.yaw[tr.t.indexOfFirst { it >= time }] }
            assertTrue(abs(at(1.14) - at(0.95)) < 0.2, "moved ${at(1.14) - at(0.95)} before it could see the target move")
            assertTrue(at(1.8) - at(1.0) > 25.0, "followed only ${at(1.8) - at(1.0)}")
        }
    }

    @Test
    fun releaseGlidesToAStop() {
        repeat(100) { s ->
            val tr = run(7000L + s, 0.8, HumanAim.Params(tremor = 0.0), lostAt = 0.09) { 90.0 to 0.0 }
            val i0 = tr.t.indexOfFirst { it >= 0.09 }
            val before = tr.speed[i0 - 1]
            if (before < 50.0) return@repeat
            assertTrue(tr.speed[i0] > before * 0.5, "dropped from $before to ${tr.speed[i0]} at once")
            for (i in i0 + 1 until tr.speed.size) assertTrue(tr.speed[i] <= tr.speed[i - 1] + 1e-9, "sped up after release")
            val stop = tr.t[tr.speed.indexOfFirst { it == 0.0 }]
            assertTrue(stop - 0.09 < 0.6, "still gliding after ${stop - 0.09}s")
        }
    }

    @Test
    fun restingTremorIsSmallButAlive() {
        repeat(50) { s ->
            val tr = run(8000L + s, 6.0, startYaw = 40.0) { 40.0 to 0.0 }
            val tail = tr.t.indices.filter { tr.t[it] > 1.0 }
            val lo = tail.minOf { tr.yaw[it] }
            val hi = tail.maxOf { tr.yaw[it] }
            assertTrue(hi - lo < 1.5, "resting yaw wandered ${hi - lo}")
            assertTrue(hi - lo > 0.01, "resting yaw dead still")
            assertTrue(tail.all { tr.onBox[it] }, "tremor knocked it off the box")
        }
    }

    @Test
    fun turnsTheShortWayThrough180() {
        repeat(50) { s ->
            val tr = run(9000L + s, 1.0, startYaw = 170.0) { -170.0 to 0.0 }
            val end = HumanAim.wrap(tr.yaw.last())
            assertTrue(abs(HumanAim.wrap(end + 170.0)) < width(3.0) / 2, "ended at $end")
            assertTrue(tr.yaw.max() < 200.0 && tr.yaw.min() > 160.0, "went the long way")
        }
    }

    @Test
    fun noNaNsOnDegenerateInput() {
        val aim = HumanAim(Random(1))
        val p = HumanAim.Params(tremor = 0.0)
        val o = HumanAim.V(0.0, 0.0, 0.0)
        val zero = aim.step(0.0, 0.0, 0.0, 0.0, o, o, o, o, 0.0, 0.0, true, p)
        assertEquals(0.0, zero[0])
        val d = aim.step(0.01, 0.01, 0.0, 0.0, o, o, o, o, 0.0, 0.0, false, p)
        assertTrue(!d[0].isNaN() && !d[1].isNaN())
        val r = aim.release(0.0)
        assertTrue(!r[0].isNaN())
        assertEquals(HumanAim.minJerk(0.0), 0.0)
        assertEquals(HumanAim.minJerk(1.0), 1.0, 1e-12)
    }
}
