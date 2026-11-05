package dev.horizon.client.module.legit

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plays GodBridge's loop over the void: a 5-block strip at y -61, the player walking off its north end with the camera
 * held still, clicking whenever the crosshair would extend the bridge and jumping when [BridgePlanner] says so.
 */
class BridgePlannerTest {
    private class Run(val placed: Int, val jumps: Int, val fell: Boolean, val levels: Set<Int>, val y: Double = 0.0)

    private fun look(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /**
     * [strafe]/[forward] are vanilla's xxa/zza: S+D is (-0.7071, -0.7071) after 26.3's square-movement scaling.
     * With [dither] the pitch steps that far down and back every 3 ticks, as a mouse held between two counts does;
     * the plan each tick only knows the pitch of that tick.
     */
    private fun bridge(yaw: Double, pitch: Double, strafe: Double, forward: Double, ticks: Int = 600,
                       startZ: Double = 2001.5, clickGap: Int = 1, startX: Double = 2000.5, dither: Double = 0.0,
                       climb: Boolean = false): Run {
        val blocks = HashSet<Long>()
        for (z in 1998..2002) blocks += BridgePlanner.key(2000, -61, z)
        val solid = { x: Int, y: Int, z: Int -> BridgePlanner.key(x, y, z) in blocks }
        val g = BridgePlanner.push(strafe, forward, yaw, 0.1)
        val a = BridgePlanner.push(strafe, forward, yaw, 0.02)
        val s = BridgePlanner.State(startX, -60.0, startZ, 0.0, -0.0784, 0.0, true)
        var placed = 0
        var jumps = 0
        var lastClick = -100
        val levels = HashSet<Int>()
        for (t in 0 until ticks) {
            val l = look(yaw, if (t / 3 % 2 == 0) pitch else pitch - dither)
            val wait = (clickGap - (t - lastClick)).coerceAtLeast(0)
            val p = BridgePlanner.Params(l[0], l[1], l[2], g[0], g[1], a[0], a[1], clickGap = clickGap, nextClickIn = wait, climb = climb)
            if (wait == 0) {
                val target = BridgePlanner.clickTarget(s, p, solid)
                if (target != null) {
                    blocks += target
                    placed++
                    lastClick = t
                    levels += (target shl 52 shr 52).toInt()
                }
            }
            val after = BridgePlanner.Params(l[0], l[1], l[2], g[0], g[1], a[0], a[1], clickGap = clickGap,
                nextClickIn = (clickGap - (t - lastClick)).coerceAtLeast(0), climb = climb)
            val jump = BridgePlanner.shouldJump(s, after, solid)
            if (jump) jumps++
            BridgePlanner.step(s, after, solid, jump)
            // Fallen: more than a block below the highest the bridge got.
            if (s.y < (levels.maxOrNull() ?: -61) - 0.0) return Run(placed, jumps, true, levels, s.y)
        }
        return Run(placed, jumps, false, levels, s.y)
    }

    private fun assertHolds(yaw: Double, pitches: ClosedFloatingPointRange<Double>, strafe: Double, forward: Double,
                            startX: Double = 2000.5, clickGap: Int = 1, dither: Double = 0.0) {
        var pitch = pitches.start
        while (pitch <= pitches.endInclusive) {
            for (start in listOf(2001.5, 2001.3, 2001.1, 2000.85)) {
                val r = bridge(yaw, pitch, strafe, forward, startZ = start, startX = startX, clickGap = clickGap, dither = dither)
                val at = "at yaw $yaw pitch $pitch from x $startX z $start"
                assertFalse(r.fell, "fell $at after ${r.placed} blocks")
                assertTrue(r.placed >= 100, "only ${r.placed} blocks $at")
                assertEquals(setOf(-61), r.levels, "the bridge left its level $at")
                // God bridging jumps every few blocks; jumping every other block would mean the plan is off.
                assertTrue(r.jumps * 2 < r.placed, "${r.jumps} jumps for ${r.placed} blocks $at")
            }
            pitch += 0.5
        }
    }

    /** Facing straight back along the bridge, S only: the classic god bridge, about 79 degrees down. */
    @Test
    fun straightGodBridgeHolds() = assertHolds(0.0, 77.0..81.0, 0.0, -0.98)

    /**
     * Facing 45 degrees off the bridge with S+D: the crosshair drifts sideways as it goes down, so this holds when the
     * player stands on the far half of the block (here 0.8 into it); centred, a jump's clicks run off the side.
     */
    @Test
    fun diagonalGodBridgeHolds() = assertHolds(45.0, 72.0..77.0, -0.7071, -0.7071, startX = 2000.8)

    /**
     * A dev-client run fell this way: a jump planned at pitch 75.1 came up 0.0007 of a block short at 74.95 (the
     * module's steering then flickered between two mouse counts). The plan's margin is what holds these.
     */
    @Test
    fun holdsWhileThePitchFlickersByAMouseCount() {
        assertHolds(0.0, 77.5..80.0, 0.0, -0.98, dither = 0.15)
        assertHolds(45.0, 73.0..77.0, -0.7071, -0.7071, startX = 2000.73, dither = 0.15)
    }

    /** With climb on, every jump also steps the bridge up a block: never more than one at a time, and never a fall. */
    @Test
    fun climbsAStepPerJump() {
        for ((yaw, pitches, strafe, forward, x) in listOf(
            Climb(0.0, 77.5..80.0, 0.0, -0.98, 2000.5),
            Climb(45.0, 73.0..77.0, -0.7071, -0.7071, 2000.8),
        )) {
            var pitch = pitches.start
            while (pitch <= pitches.endInclusive) {
                val r = bridge(yaw, pitch, strafe, forward, startX = x, climb = true)
                val at = "at yaw $yaw pitch $pitch"
                assertFalse(r.fell, "fell $at after ${r.placed} blocks, at y ${r.y}")
                assertTrue(r.placed >= 60, "only ${r.placed} blocks $at")
                val lv = r.levels.sorted()
                assertTrue(lv.size >= 4, "climbed only to ${lv.last()} $at")
                assertEquals((lv.first()..lv.last()).toList(), lv, "skipped a level $at: $lv")
                pitch += 0.5
            }
        }
    }

    private data class Climb(val yaw: Double, val pitches: ClosedFloatingPointRange<Double>, val strafe: Double,
                             val forward: Double, val x: Double)

    @Test
    fun holdsWithAClickEveryOtherTick() {
        assertHolds(0.0, 78.0..80.0, 0.0, -0.98, clickGap = 2)
        assertHolds(45.0, 74.0..76.0, -0.7071, -0.7071, startX = 2000.8, clickGap = 2)
    }

    @Test
    fun neverJumpsOnSolidGround() {
        val solid = { _: Int, y: Int, _: Int -> y <= -61 }
        val l = look(45.0, 74.0)
        val g = BridgePlanner.push(-0.7071, -0.7071, 45.0, 0.1)
        val a = BridgePlanner.push(-0.7071, -0.7071, 45.0, 0.02)
        val p = BridgePlanner.Params(l[0], l[1], l[2], g[0], g[1], a[0], a[1])
        val s = BridgePlanner.State(2000.5, -60.0, 2001.5, 0.0, -0.0784, 0.0, true)
        for (t in 0 until 200) {
            assertFalse(BridgePlanner.shouldJump(s, p, solid), "jumped on flat ground at tick $t")
            BridgePlanner.step(s, p, solid, false)
        }
    }

    @Test
    fun doesNotJumpOffALedgeItCannotBuildFrom() {
        // Looking up: the crosshair never reaches the bridge, so a jump would only carry the player further out.
        val blocks = HashSet<Long>()
        for (z in 1998..2002) blocks += BridgePlanner.key(2000, -61, z)
        val solid = { x: Int, y: Int, z: Int -> BridgePlanner.key(x, y, z) in blocks }
        val l = look(45.0, 20.0)
        val g = BridgePlanner.push(-0.7071, -0.7071, 45.0, 0.1)
        val a = BridgePlanner.push(-0.7071, -0.7071, 45.0, 0.02)
        val p = BridgePlanner.Params(l[0], l[1], l[2], g[0], g[1], a[0], a[1])
        val s = BridgePlanner.State(2000.5, -60.0, 2001.5, 0.0, -0.0784, 0.0, true)
        for (t in 0 until 40) {
            assertFalse(BridgePlanner.shouldJump(s, p, solid), "jumped off the ledge at tick $t")
            BridgePlanner.step(s, p, solid, false)
        }
    }

    @Test
    fun aJumpThatFallsShortHitsTheSideWithWalls() {
        // An island one block higher than the bridge, 3 blocks past its end, and a sprint-jump off the end east: the
        // feet are under the island's top by the time the box reaches it. With walls the player slides down its side;
        // without, the step goes through the side and "lands" inside it.
        val solid = { x: Int, y: Int, _: Int -> x in 4004..4008 && y in -31..-29 }
        val a = BridgePlanner.push(0.0, 1.0, -90.0, 0.026)
        val p = BridgePlanner.Params(0.0, -1.0, 0.0, 0.0, 0.0, a[0], a[1])
        fun play(walls: Boolean): BridgePlanner.State {
            val s = BridgePlanner.State(4001.02, -28.58, 4002.5, 0.217, 0.333, 0.0, false)
            // To where the feet are past the island's middle block, still beside it.
            for (t in 0 until 13) {
                BridgePlanner.step(s, p, solid, false, walls)
                if (s.onGround) break
            }
            return s
        }
        val hit = play(true)
        assertFalse(hit.onGround, "landed at ${hit.x} ${hit.y}")
        assertEquals(4003.7, hit.x, 1e-6)
        assertTrue(play(false).onGround)
    }
}
