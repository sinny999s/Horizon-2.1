package dev.horizon.client.module.attack

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simulates Aura's hidden look with `follow-mouse` frame by frame: a camera you turn yourself, the simulated hand, and
 * the rules that share the turning between them ([FollowMouse]), wired as LegitRotation wires them.
 */
class FollowMouseTest {
    /** The hidden look as LegitRotation keeps it: turned in whole mouse counts, the rest carried over. */
    private class Look(override var yaw: Double, override var pitch: Double) : FollowMouse.Look {
        private var carryX = 0.0
        private var carryY = 0.0

        override fun add(dYaw: Double, dPitch: Double) {
            yaw += dYaw
            pitch += dPitch
        }

        override fun turn(dYaw: Double, dPitch: Double) {
            carryX += dYaw / STEP
            carryY += dPitch / STEP
            val x = carryX.toInt()
            val y = carryY.toInt()
            carryX -= x
            carryY -= y
            yaw += x * STEP
            pitch += y * STEP
        }

        override fun settle(dYaw: Double, dPitch: Double) {
            carryX = 0.0
            carryY = 0.0
            yaw += (dYaw / STEP).roundToLong() * STEP
            pitch += (dPitch / STEP).roundToLong() * STEP
        }
    }

    class Trace(
        val t: DoubleArray, val yaw: DoubleArray, val pitch: DoubleArray, val camYaw: DoubleArray, val camPitch: DoubleArray,
        val onBox: BooleanArray, val camOnBox: BooleanArray, val leading: BooleanArray,
    ) {
        fun firstOn(on: BooleanArray): Double = t.getOrElse(on.indexOfFirst { it }) { Double.MAX_VALUE }
        fun share(on: BooleanArray, from: Double) = t.indices.filter { t[it] >= from }.let { i -> i.count { on[it] }.toDouble() / i.size }
    }

    private fun width(dist: Double) = Math.toDegrees(2 * atan(0.3 / dist))
    private fun height(dist: Double) = Math.toDegrees(2 * atan(0.9 / dist))

    private fun dir(yaw: Double, pitch: Double): HumanAim.V {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return HumanAim.V(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /**
     * [camera] and [target] give where you look and where the target is (yaw, pitch) at a time, the target [dist] blocks
     * away. The hidden look starts on the camera unless [startYaw]. With [follow] off the hand turns it alone. Frames at
     * about 144 fps with some jitter (the same for a seed, whatever [follow] is).
     */
    private fun run(
        seed: Long, seconds: Double, follow: Boolean, p: HumanAim.Params = HumanAim.Params(), dist: Double = 3.0,
        startYaw: Double? = null, camera: (Double) -> Pair<Double, Double>, target: (Double) -> Pair<Double, Double>,
    ): Trace {
        val frames = Random(seed xor 0x5DEECE66DL)
        val hand = HumanAim(Random(seed))
        hand.retarget(p)
        val share = FollowMouse(hand)
        val origin = HumanAim.V(0.0, 0.0, 0.0)
        // A camera turns in whole mouse counts too.
        fun counts(a: Double) = (a / STEP).roundToLong() * STEP
        var camYaw = counts(camera(0.0).first)
        var camPitch = counts(camera(0.0).second)
        val look = Look(startYaw ?: camYaw, if (startYaw == null) camPitch else 0.0)
        val w = width(dist)
        val h = height(dist)
        val ts = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ps = ArrayList<Double>()
        val cys = ArrayList<Double>()
        val cps = ArrayList<Double>()
        val ob = ArrayList<Boolean>()
        val cob = ArrayList<Boolean>()
        val ld = ArrayList<Boolean>()
        var now = 0.0
        while (now < seconds) {
            val dt = (1.0 / 144) * (0.85 + 0.3 * frames.nextDouble())
            now += dt
            val (cy, cp) = camera(now)
            val moveYaw = counts(cy) - camYaw
            val movePitch = counts(cp) - camPitch
            camYaw += moveYaw
            camPitch += movePitch
            val (ty, tp) = target(now)
            val c = dir(ty, tp) * dist
            // On the box, drawn in on every side by `shrink` of its width (less of its height), as LegitRotation tests it.
            fun on(yaw: Double, pitch: Double, shrink: Double) =
                abs(HumanAim.wrap(ty - yaw)) < w / 2 * (1 - 2 * shrink) && abs(tp - pitch) < h / 2 * (1 - 2 * shrink * 0.4)
            var leading = false
            if (follow) {
                leading = share.frame(
                    look, now, dt, moveYaw, movePitch, camYaw, camPitch, on(camYaw, camPitch, 0.2), on(camYaw, camPitch, 0.04),
                    HumanAim.wrap(ty - look.yaw), tp - look.pitch, w, p
                )
                if (leading) hand.watch(now, dt, look.yaw, look.pitch, origin, origin, c, c, p)
            }
            if (!leading) {
                val d = hand.step(now, dt, look.yaw, look.pitch, origin, origin, c, c, w, h, on(look.yaw, look.pitch, 0.0), p)
                look.turn(d[0], d[1])
            }
            ts += now; ys += look.yaw; ps += look.pitch; cys += camYaw; cps += camPitch
            ob += on(look.yaw, look.pitch, 0.0); cob += on(camYaw, camPitch, 0.0); ld += leading
        }
        return Trace(
            ts.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), cys.toDoubleArray(), cps.toDoubleArray(),
            ob.toBooleanArray(), cob.toBooleanArray(), ld.toBooleanArray()
        )
    }

    /** A flick of your own: from 0 to [to] degrees of yaw, starting at [start] and lasting [time], fast in the middle. */
    private fun flick(to: Double, start: Double, time: Double) = { t: Double -> to * HumanAim.minJerk((t - start) / time) to 0.0 }

    private val still = { _: Double -> 0.0 to 0.0 }

    /** A/D strafing at 3 blocks: about ±15 degrees, 0.8 Hz (some 75 deg/s at its fastest). */
    private val strafe = { t: Double -> (30.0 + 15.0 * sin(2 * PI * 0.8 * t)) to 0.0 }

    @Test
    fun yourOwnFlickIsNeverBehind() {
        val alone = ArrayList<Double>()
        val shared = ArrayList<Double>()
        repeat(200) { s ->
            // 60 degrees in 0.14 s: faster than the hand goes (420 deg/s at most, this peaks at 800).
            val tr = run(s.toLong(), 1.0, true, camera = flick(60.0, 0.03, 0.14)) { 60.0 to 0.0 }
            val camOn = tr.firstOn(tr.camOnBox)
            for (i in tr.t.indices) {
                if (tr.t[i] > camOn) break
                val behind = abs(HumanAim.wrap(60.0 - tr.yaw[i])) - abs(HumanAim.wrap(60.0 - tr.camYaw[i]))
                assertTrue(behind < 1.0, "seed $s: ${behind} degrees behind the camera at ${tr.t[i]}")
            }
            assertTrue(tr.firstOn(tr.onBox) <= camOn + 0.012, "seed $s: on the box at ${tr.firstOn(tr.onBox)}, the camera at $camOn")
            assertTrue(tr.onBox.last(), "seed $s: ended off the box at ${tr.yaw.last()}")
            shared += tr.firstOn(tr.onBox)
            val a = run(s.toLong(), 1.0, false, camera = flick(60.0, 0.03, 0.14)) { 60.0 to 0.0 }
            alone += a.firstOn(a.onBox)
        }
        alone.sort()
        shared.sort()
        assertTrue(shared[100] < alone[100] - 0.05, "median time to the box ${shared[100]} with your flick, ${alone[100]} without")
    }

    @Test
    fun aStillCameraChangesNothing() {
        repeat(50) { s ->
            val a = run(s.toLong(), 2.0, false, camera = still, target = strafe)
            val b = run(s.toLong(), 2.0, true, camera = still, target = strafe)
            assertTrue(a.yaw.contentEquals(b.yaw) && a.pitch.contentEquals(b.pitch), "seed $s: the look differs")
        }
    }

    @Test
    fun turningAwayIsLeftAlone() {
        var alone = 0.0
        var shared = 0.0
        repeat(100) { s ->
            // You spin away from them at 300 deg/s while they stand at 60.
            val away = { t: Double -> -300.0 * t to 0.0 }
            val a = run(s.toLong(), 2.0, false, camera = away) { 60.0 to -10.0 }
            val b = run(s.toLong(), 2.0, true, camera = away) { 60.0 to -10.0 }
            alone += a.share(a.onBox, 0.6)
            shared += b.share(b.onBox, 0.6)
            // Only the hand turned it: no faster than the hand goes, and the same way as without you.
            assertEquals(a.firstOn(a.onBox), b.firstOn(b.onBox), 0.03, "seed $s: time to the box")
        }
        assertTrue(shared / 100 > 0.97, "on the box ${shared / 100} of the time")
        assertEquals(alone / 100, shared / 100, 0.01)
    }

    @Test
    fun yourOvershootDoesNotCarryItPast() {
        var alone = 0
        var shared = 0
        repeat(200) { s ->
            // You flick to 80 past someone at 50: through their box in a moment, never resting on it.
            val tr = run(s.toLong(), 1.0, true, camera = flick(80.0, 0.03, 0.15)) { 50.0 to 0.0 }
            assertTrue(tr.leading.none { it }, "seed $s: took the lead from a flick through the box")
            assertTrue(tr.yaw.max() < 60.0, "seed $s: carried to ${tr.yaw.max()}")
            assertTrue(tr.onBox.last(), "seed $s: ended off the box at ${tr.yaw.last()}")
            if (tr.yaw.max() > 50.0 + width(3.0) / 2) shared++
            // The hand alone lands past the box now and then too (its own overshoot and scatter).
            if (run(s.toLong(), 1.0, false, camera = still) { 50.0 to 0.0 }.yaw.max() > 50.0 + width(3.0) / 2) alone++
        }
        assertTrue(shared <= alone + 6, "past the box in $shared of 200 with your flick, $alone with the hand alone")
    }

    /** You track the strafer yourself, a little off and unsteady, as a hand does. */
    private val tracking = { t: Double -> (30.0 + 15.0 * sin(2 * PI * 0.8 * t) + 1.5 * sin(2 * PI * 1.7 * t)) to 2.0 * sin(3.0 * t) }

    @Test
    fun yourCrosshairOnThemTakesTheLead() {
        repeat(100) { s ->
            val tr = run(s.toLong(), 3.0, true, camera = tracking, target = strafe)
            for (i in tr.t.indices) {
                assertTrue(tr.onBox[i], "seed $s: off the box at ${tr.t[i]}")
                if (tr.t[i] < 0.4) continue
                assertTrue(tr.leading[i], "seed $s: not leading at ${tr.t[i]}")
                if (tr.t[i] < 0.8) continue
                val gap = hypot(tr.yaw[i] - tr.camYaw[i], tr.pitch[i] - tr.camPitch[i])
                assertTrue(gap <= FollowMouse.LOCK, "seed $s: $gap degrees from the camera at ${tr.t[i]}")
            }
            // Riding with the camera: turned by exactly what you turn, nothing added.
            val gaps = tr.t.indices.filter { tr.t[it] >= 0.8 }.map { tr.yaw[it] - tr.camYaw[it] }
            assertTrue(gaps.max() - gaps.min() < 1e-6, "seed $s: the gap to the camera moved by ${gaps.max() - gaps.min()}")
        }
    }

    @Test
    fun aLookElsewhereIsBroughtToYourCrosshairLikeAHand() {
        repeat(100) { s ->
            // The hidden look is 70 degrees off (on the last target) while you already track this one.
            val p = HumanAim.Params()
            val tr = run(s.toLong(), 2.5, true, p, startYaw = -40.0, camera = tracking, target = strafe)
            val from = tr.t.indexOfFirst { it >= 1.2 }
            for (i in from until tr.t.size) {
                val gap = hypot(tr.yaw[i] - tr.camYaw[i], tr.pitch[i] - tr.camPitch[i])
                assertTrue(gap <= FollowMouse.LOCK + STEP, "seed $s: $gap degrees from the camera at ${tr.t[i]}")
            }
            // No snap: against the camera it never closes faster than the hand turns.
            for (i in 1 until tr.t.size) {
                val closing = abs((tr.yaw[i] - tr.camYaw[i]) - (tr.yaw[i - 1] - tr.camYaw[i - 1])) / (tr.t[i] - tr.t[i - 1])
                assertTrue(closing <= p.speedHi * 1.08 + 1.0, "seed $s: closed at $closing deg/s")
            }
        }
    }

    @Test
    fun theHandTakesOverWhenYouSlipOff() {
        var alone = 0.0
        var shared = 0.0
        repeat(100) { s ->
            // You track them for 1.5 s, then your hand stops where it is while they strafe on.
            val slipping = { t: Double -> tracking(minOf(t, 1.5)) }
            val a = run(s.toLong(), 5.5, false, camera = slipping, target = strafe)
            val b = run(s.toLong(), 5.5, true, camera = slipping, target = strafe)
            alone += a.share(a.onBox, 1.5)
            shared += b.share(b.onBox, 1.5)
            // Right after the slip: back on them within a third of a second.
            val off = b.t.indices.firstOrNull { b.t[it] > 1.5 && !b.onBox[it] }
            if (off != null) {
                val back = b.t.indices.firstOrNull { it > off && b.onBox[it] }
                assertTrue(back != null && b.t[back] - b.t[off] < 0.35, "seed $s: off the box from ${b.t[off]}")
            }
        }
        alone /= 100
        shared /= 100
        assertTrue(shared > 0.6, "on the box $shared of the time after the slip")
        assertTrue(shared > alone - 0.05, "on the box $shared of the time, $alone with the hand alone")
    }

    /** The share of 20 Hz ticks from [from] on with the look on the box in that tick and the one before: when a hit lands. */
    private fun hits(tr: Trace, from: Double): Double {
        var next = 0.05
        var before = false
        var ticks = 0
        var landed = 0
        for (i in tr.t.indices) {
            if (tr.t[i] < next) continue
            next += 0.05
            if (tr.t[i] >= from) {
                ticks++
                if (tr.onBox[i] && before) landed++
            }
            before = tr.onBox[i]
        }
        return landed.toDouble() / ticks
    }

    @Test
    fun aRoughHandOfYourOwnHelpsInAStrafeFight() {
        // A/D at 2.5 blocks as a server shows it: a step every tick at 90 deg/s, turning round every 0.45 s.
        fun strafer(t: Double): Double {
            val ph = ((kotlin.math.floor(t * 20) / 20) % 0.9) / 0.9
            return 180.0 + ((if (ph < 0.5) ph * 2 else 2 - ph * 2) - 0.5) * 40.0
        }
        // You follow them 0.15 s late and unsteadily: on them about half the time, slipping off at every turn.
        val you = { t: Double -> (strafer(t - 0.15) + 2.0 * sin(2 * PI * 1.3 * t)) to 1.5 * sin(2.1 * t) }
        for (p in listOf(HumanAim.Params(), HumanAim.Params(speedLo = 360.0, speedHi = 620.0, fittsB = 0.077, perceptionDelay = 0.06, tracking = 0.95))) {
            var alone = 0.0
            var shared = 0.0
            repeat(100) { s ->
                val a = run(s.toLong(), 6.0, false, p, dist = 2.5, camera = you) { strafer(it) to 0.0 }
                val b = run(s.toLong(), 6.0, true, p, dist = 2.5, camera = you) { strafer(it) to 0.0 }
                alone += hits(a, 0.6)
                shared += hits(b, 0.6)
            }
            // Measured: 0.38 against 0.09 with the default hand, 0.49 against 0.35 with a fast one.
            assertTrue(shared > alone + 5.0, "hits land in ${shared / 100} of ticks with your hand, ${alone / 100} with the hand alone")
        }
    }

    @Test
    fun someoneRunningThroughYourCrosshairDoesNotTakeTheLead() {
        repeat(50) { s ->
            // Your crosshair rests in the middle of their strafe: they cross it at full speed twice a period.
            val middle = { _: Double -> 30.0 to 0.0 }
            val a = run(s.toLong(), 4.0, false, camera = middle, target = strafe)
            val b = run(s.toLong(), 4.0, true, camera = middle, target = strafe)
            assertTrue(b.leading.none { it }, "seed $s: took the lead")
            assertTrue(a.yaw.contentEquals(b.yaw), "seed $s: the look differs")
        }
    }

    @Test
    fun towardKeepsOnlyWhatHelps() {
        assertEquals(0.0, FollowMouse.toward(5.0, -3.0))
        assertEquals(0.0, FollowMouse.toward(0.0, 3.0))
        assertEquals(2.0, FollowMouse.toward(2.0, 3.0))
        assertEquals(3.0, FollowMouse.toward(5.0, 3.0))
        assertEquals(-3.0, FollowMouse.toward(-5.0, -3.0))
    }

    @Test
    fun ontoIsAMovementOfTheHandNotASnap() {
        repeat(100) { s ->
            val p = HumanAim.Params()
            val hand = HumanAim(Random(s.toLong()))
            hand.retarget(p)
            var yaw = 0.0
            var pitch = 0.0
            var t = 0.0
            val speed = ArrayList<Double>()
            while (t < 1.2) {
                val dt = 1.0 / 144
                t += dt
                val d = hand.onto(dt, 70.0 - yaw, -15.0 - pitch, 6.0, p)
                yaw += d[0]
                pitch += d[1]
                speed += hypot(d[0], d[1]) / dt
            }
            assertTrue(hypot(70.0 - yaw, -15.0 - pitch) < 1.0, "seed $s: ended ${hypot(70.0 - yaw, -15.0 - pitch)} off")
            val peak = speed.max()
            assertTrue(peak <= p.speedHi * 1.08 + 1e-6, "seed $s: peak $peak")
            assertTrue(speed[0] < peak * 0.3, "seed $s: started at ${speed[0]} of a peak of $peak")
        }
    }

    @Test
    fun creditShortensTheMovementUnderWay() {
        repeat(100) { s ->
            val p = HumanAim.Params(tremor = 0.0, overshoot = 0.0, aimError = 0.0, curve = 0.0)
            fun total(helped: Double): Double {
                val hand = HumanAim(Random(s.toLong()))
                hand.retarget(p)
                var yaw = 0.0
                var turned = 0.0
                var t = 0.0
                var given = false
                while (t < 0.6) {
                    val dt = 1.0 / 144
                    t += dt
                    // One movement only: what is left afterwards is not corrected (the error stays as first seen).
                    val d = hand.onto(dt, if (hand.submovements == 0) 60.0 else 0.0, 0.0, 10.0, p)
                    yaw += d[0]
                    turned += d[0]
                    if (!given && hand.submovements == 1 && t > 0.05) {
                        hand.credit(helped, 0.0)
                        given = true
                    }
                }
                return turned
            }
            val whole = total(0.0)
            val less = total(20.0)
            assertEquals(whole - 20.0, less, 0.5, "seed $s")
            // More help than is left: the hand stops, it doesn't turn back.
            assertTrue(total(200.0) in 0.0..whole, "seed $s: ${total(200.0)}")
        }
    }

    private companion object {
        /** Degrees a mouse count turns at the default sensitivity. */
        const val STEP = 0.15
    }
}
