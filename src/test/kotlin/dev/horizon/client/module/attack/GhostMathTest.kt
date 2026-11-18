package dev.horizon.client.module.attack

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Aura's `ghost` sums: fight pressure, strength, sensible misses and the void-knockback aim offset. */
class GhostMathTest {
    @Test
    fun pressureFollowsTheOdds() {
        val even = GhostMath.pressure(0.0, 0)
        assertEquals(0.5, even, 1e-9)
        assertTrue(GhostMath.pressure(1.0, 0) < 0.05, "well ahead: next to no pressure")
        assertTrue(GhostMath.pressure(-1.0, 0) > 0.95, "well behind: full pressure")
        assertTrue(GhostMath.pressure(1.0, 2) > GhostMath.pressure(1.0, 0) + 0.35, "two more enemies close press you even when ahead")
        assertEquals(0.9, GhostMath.pressure(0.0, 5), 1e-9, "only two others count")
    }

    @Test
    fun strengthRunsFromEasyToFull() {
        assertEquals(0.65, GhostMath.strength(0.65, 0.0), 1e-9)
        assertEquals(1.0, GhostMath.strength(0.65, 1.0), 1e-9)
        assertEquals(1.0, GhostMath.strength(1.0, 0.3), 1e-9)
        // Full strength changes nothing.
        assertEquals(1.0, GhostMath.speedScale(1.0), 1e-9)
        assertEquals(1.0, GhostMath.lagScale(1.0), 1e-9)
        assertEquals(1.0, GhostMath.errorScale(1.0), 1e-9)
        assertEquals(1.0, GhostMath.trackScale(1.0), 1e-9)
        assertEquals(1.0, GhostMath.cpsScale(1.0), 1e-9)
        assertTrue(GhostMath.speedScale(0.5) < 1.0 && GhostMath.lagScale(0.5) > 1.0 && GhostMath.cpsScale(0.5) < 1.0)
    }

    @Test
    fun followRisesFastAndFallsSlowly() {
        var up = 0.0
        var down = 1.0
        repeat(10) { // half a second of ticks
            up = GhostMath.follow(up, 1.0, 0.05)
            down = GhostMath.follow(down, 0.0, 0.05)
        }
        assertTrue(up > 0.7, "up after 0.5 s: $up")
        assertTrue(down > 0.75, "still mostly up after 0.5 s of easing off: $down")
    }

    @Test
    fun missesWherePeopleMiss() {
        val base = 0.08
        val still = GhostMath.missChance(base, 1.5, 3.0, 0.0, 0.0, false, 1.0)
        val edge = GhostMath.missChance(base, 2.95, 3.0, 0.0, 0.0, false, 1.0)
        val strafe = GhostMath.missChance(base, 1.5, 3.0, 150.0, 0.0, false, 1.0)
        val flick = GhostMath.missChance(base, 1.5, 3.0, 0.0, 60.0, false, 1.0)
        val air = GhostMath.missChance(base, 1.5, 3.0, 0.0, 0.0, true, 1.0)
        val easy = GhostMath.missChance(base, 1.5, 3.0, 0.0, 0.0, false, 0.6)
        assertTrue(still < 0.03, "a still target close in front is hardly ever missed: $still")
        for ((what, m) in listOf("edge" to edge, "strafe" to strafe, "flick" to flick, "air" to air, "easy" to easy)) {
            assertTrue(m > still, "$what misses more than a still target: $m vs $still")
        }
        val worst = GhostMath.missChance(base, 3.0, 3.0, 400.0, 90.0, true, 0.3)
        assertTrue(worst <= 0.6, "capped: $worst")
        assertEquals(0.0, GhostMath.missChance(0.0, 3.0, 3.0, 400.0, 90.0, true, 0.3), 1e-9)
    }

    @Test
    fun missRateAveragesNearTheSetting() {
        // Ordinary fight moments: mid to edge of reach, some strafing, some flicks, now and then in the air.
        val rnd = kotlin.random.Random(7)
        var sum = 0.0
        val n = 20000
        repeat(n) {
            sum += GhostMath.missChance(
                0.06, rnd.nextDouble(1.5, 3.0), 3.0, rnd.nextDouble(0.0, 140.0), rnd.nextDouble(0.0, 30.0), rnd.nextDouble() < 0.3, 1.0
            )
        }
        val avg = sum / n
        assertTrue(avg in 0.04..0.10, "average miss chance $avg for a 6% setting")
    }

    @Test
    fun voidOffsetLeansTowardsTheDrop() {
        val half = 5.7 // a player's half-width at 3 blocks
        // Drop 40 degrees to the right of where the hit pushes, a block away: aim right, within the box.
        val right = GhostMath.voidOffset(0.0, 40.0, 1.0, half)
        assertTrue(right > 0.0 && right <= half * 0.7 + 1e-9, "right: $right")
        val left = GhostMath.voidOffset(0.0, -40.0, 1.0, half)
        assertEquals(-right, left, 1e-9)
        // Behind them (you'd push them away from it) or straight ahead: leave the aim alone.
        assertEquals(0.0, GhostMath.voidOffset(0.0, 150.0, 1.0, half), 1e-9)
        assertEquals(0.0, GhostMath.voidOffset(0.0, 0.5, 1.0, half), 1e-9)
        // A small angle is matched, not overshot.
        assertEquals(2.0, GhostMath.voidOffset(10.0, 12.0, 0.6, half), 1e-9)
        // Further drops lean less.
        assertTrue(abs(GhostMath.voidOffset(0.0, 40.0, 3.6, half)) < abs(right))
        // Across the wrap at 180.
        assertTrue(GhostMath.voidOffset(170.0, -170.0, 1.0, half) > 0.0)
    }

    @Test
    fun sprintSyncWaitsOnlyWhenItHelps() {
        val calm = 0.99 // a roll that never misjudges
        assertEquals(GhostMath.Hold.WAIT, GhostMath.holdForSprint(true, false, true, 0, 2, 0, calm))
        // Nothing to wait for: they can't be hurt, you're sprinting, you aren't trying to, or the waits are used up.
        assertEquals(GhostMath.Hold.NO, GhostMath.holdForSprint(false, false, true, 0, 2, 0, calm))
        assertEquals(GhostMath.Hold.NO, GhostMath.holdForSprint(true, true, true, 0, 2, 0, calm))
        assertEquals(GhostMath.Hold.NO, GhostMath.holdForSprint(true, false, false, 0, 2, 0, calm))
        assertEquals(GhostMath.Hold.NO, GhostMath.holdForSprint(true, false, true, 2, 2, 0, calm))
        assertEquals(GhostMath.Hold.NO, GhostMath.holdForSprint(true, false, true, 0, 0, 0, calm))
        // They're about to hit you: hit now. Noticed only once it has held for two ticks.
        assertEquals(GhostMath.Hold.THREAT, GhostMath.holdForSprint(true, false, true, 0, 2, 2, calm))
        assertEquals(GhostMath.Hold.WAIT, GhostMath.holdForSprint(true, false, true, 0, 2, 1, calm))
        // A misread flips it either way.
        assertEquals(GhostMath.Hold.WAIT, GhostMath.holdForSprint(true, false, true, 0, 2, 3, 0.01))
        assertEquals(GhostMath.Hold.THREAT, GhostMath.holdForSprint(true, false, true, 0, 2, 0, 0.01))
        // Over many reads of a real threat, it's caught about 85% of the time.
        val rnd = kotlin.random.Random(3)
        val caught = (1..10000).count { GhostMath.holdForSprint(true, false, true, 0, 2, 4, rnd.nextDouble()) == GhostMath.Hold.THREAT }
        assertTrue(caught in 8200..8800, "caught $caught of 10000")
    }

    @Test
    fun yawMatchesMinecraft() {
        assertEquals(0.0, GhostMath.yawOf(0.0, 1.0), 1e-9)   // +z
        assertEquals(90.0, GhostMath.wrap(GhostMath.yawOf(-1.0, 0.0)), 1e-9) // -x
        assertEquals(-90.0, GhostMath.wrap(GhostMath.yawOf(1.0, 0.0)), 1e-9) // +x
    }
}
