package dev.horizon.client.combat

import dev.horizon.client.combat.FightOdds.Sample
import dev.horizon.client.combat.FightOdds.Verdict.EVEN
import dev.horizon.client.combat.FightOdds.Verdict.LOSING
import dev.horizon.client.combat.FightOdds.Verdict.WINNING
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Target Info's Winning / Even / Losing over simulated fights, a tick at a time. */
class FightOddsTest {
    /**
     * Two sides with health, the share of a hit their armour lets through and their weapon's hit. A landed hit takes the
     * weapon's hit through the other side's armour.
     */
    private class Fight(
        var mine: Float, var theirs: Float,
        val myTaken: Float = 0.5f, val theirTaken: Float = 0.5f,
        val myHit: Float = 7f, val theirHit: Float = 7f,
        /** The server always says the target has this much (it hides health). */
        val shownTheirs: Float? = null,
    ) {
        val odds = FightOdds()
        val verdicts = ArrayList<FightOdds.Verdict>()
        private val theirMax = theirs

        fun tick(iHit: Boolean = false, theyHit: Boolean = false) {
            if (iHit) theirs = max(0f, theirs - myHit * theirTaken)
            if (theyHit) mine = max(0f, mine - theirHit * myTaken)
            odds.update(Sample(mine, shownTheirs ?: theirs, myTaken, theirTaken, myHit, theirHit, landed = iHit, theirMax = theirMax))
            verdicts += odds.verdict
        }

        fun run(ticks: Int, iHit: (Int) -> Boolean = { false }, theyHit: (Int) -> Boolean = { false }) {
            val start = verdicts.size
            repeat(ticks) { tick(iHit(start + it), theyHit(start + it)) }
        }

        fun changes() = verdicts.zipWithNext().count { (a, b) -> a != b }
    }

    @Test
    fun evenWithTheSameGearAndNoHits() {
        val f = Fight(20f, 20f)
        f.run(200)
        assertTrue(f.verdicts.all { it == EVEN })
    }

    @Test
    fun winningWhenOnlyYouLandHits() {
        val f = Fight(20f, 40f)
        f.run(100, iHit = { it % 12 == 0 })
        assertEquals(EVEN, f.verdicts[5])
        assertTrue(f.verdicts.drop(40).all { it == WINNING }, f.verdicts.toString())
    }

    @Test
    fun losingWhenOnlyTheyLandHits() {
        val f = Fight(20f, 20f)
        f.run(80, theyHit = { it % 15 == 0 })
        assertTrue(f.verdicts.drop(40).all { it == LOSING }, f.verdicts.toString())
    }

    @Test
    fun betterGearLeadsBeforeAnyHit() {
        // You naked against diamond and protection (a third of a hit gets through), the same swords.
        val naked = Fight(20f, 20f, myTaken = 1f, theirTaken = 0.3f)
        naked.run(40)
        assertEquals(LOSING, naked.verdicts.last())
        val armoured = Fight(20f, 20f, myTaken = 0.3f, theirTaken = 1f)
        armoured.run(40)
        assertEquals(WINNING, armoured.verdicts.last())
        // A sword against a fist.
        val fist = Fight(20f, 20f, myHit = 7f, theirHit = 1f)
        fist.run(40)
        assertEquals(WINNING, fist.verdicts.last())
    }

    @Test
    fun moreHealthWinsAnEvenTrade() {
        val f = Fight(40f, 15f)
        f.run(40, iHit = { it % 10 == 5 }, theyHit = { it % 10 == 5 })
        assertTrue(f.verdicts.drop(15).all { it == WINNING }, f.verdicts.toString())
    }

    @Test
    fun fasterDamageBeatsMoreHealth() {
        // Half their health, but hitting four times as often: you'd kill them in about 100 ticks, they'd need 200.
        val f = Fight(30f, 60f)
        f.run(100, iHit = { it % 6 == 3 }, theyHit = { it % 24 == 3 })
        assertEquals(LOSING, f.verdicts[20], "at the start only the health counts")
        assertEquals(WINNING, f.verdicts[70], f.verdicts.toString())
    }

    @Test
    fun aKillOrADeathShowsAtOnce() {
        val win = Fight(20f, 3f)
        win.run(10)
        win.tick(iHit = true)
        assertEquals(WINNING, win.verdicts.last())
        val loss = Fight(3f, 20f)
        loss.run(10)
        loss.tick(theyHit = true)
        assertEquals(LOSING, loss.verdicts.last())
    }

    @Test
    fun aBigLeadStillWaitsForTheHold() {
        // A boss with 1000 health: Losing, but only after the hold like any other change.
        val f = Fight(20f, 1000f)
        f.run(30)
        assertEquals(EVEN, f.verdicts[5])
        assertEquals(LOSING, f.verdicts.last())
    }

    @Test
    fun hiddenHealthCountsLandedHits() {
        // The server always says 20: from the third hit on, each landed hit counts at the gear's damage.
        val f = Fight(20f, 20f, shownTheirs = 20f)
        f.run(80, iHit = { it % 10 == 0 })
        assertTrue(f.odds.healthHidden)
        assertTrue(f.verdicts.drop(50).all { it == WINNING }, f.verdicts.toString())
        // Trading evenly with hidden health (a quarter of a hit gets through) isn't a loss, whatever the server shows.
        for (shown in listOf(20f, 1f)) {
            val trade = Fight(20f, 20f, myTaken = 0.25f, theirTaken = 0.25f, shownTheirs = shown)
            trade.run(100, iHit = { it % 10 == 0 }, theyHit = { it % 10 == 0 })
            assertTrue(trade.odds.healthHidden)
            assertTrue(LOSING !in trade.verdicts, "shown $shown: ${trade.verdicts}")
            assertEquals(EVEN, trade.verdicts.last(), "shown $shown: ${trade.verdicts}")
        }
    }

    @Test
    fun anEvenTradeStaysEven() {
        val f = Fight(400f, 400f)
        f.run(600, iHit = { it % 10 == 0 }, theyHit = { it % 10 == 5 })
        assertTrue(f.verdicts.all { it == EVEN }, f.verdicts.toString())
    }

    @Test
    fun doesNotFlicker() {
        // Random hits on both sides for 50 seconds: a verdict holds at least half a second, and it changes rarely.
        for (seed in 0 until 20) {
            val r = Random(seed)
            val f = Fight(1000f, 1000f)
            f.run(1000, iHit = { r.nextFloat() < 0.08f }, theyHit = { r.nextFloat() < 0.08f })
            // From the first change on (the opening Even counts its first tick as held already).
            var run = -1
            var shortest = Int.MAX_VALUE
            for ((a, b) in f.verdicts.zipWithNext()) {
                if (run >= 0) run++
                if (a != b) {
                    if (run >= 0) shortest = minOf(shortest, run)
                    run = 0
                }
            }
            assertTrue(shortest >= FightOdds.HOLD_TICKS, "seed $seed: a verdict lasted $shortest ticks")
            assertTrue(f.changes() <= 20, "seed $seed: ${f.changes()} changes")
        }
    }

    @Test
    fun resetForgetsTheLastFight() {
        val f = Fight(20f, 40f)
        f.run(60, iHit = { it % 12 == 0 })
        assertEquals(WINNING, f.odds.verdict)
        f.odds.reset()
        assertEquals(EVEN, f.odds.verdict)
        assertEquals(0f, f.odds.score)
    }
}
