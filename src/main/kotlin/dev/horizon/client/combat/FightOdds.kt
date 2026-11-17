package dev.horizon.client.combat

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Who is winning a fight, from one look at both sides every tick (Target Info's Winning / Even / Losing).
 *
 * Each side's time to kill the other is the other's health (absorption included) over the damage it is dealing per
 * second, and the score is the log of the two times' ratio: above 0 you would kill them first. The damage rate is the
 * health each side actually lost over the last few seconds (a fading window), mixed with what the gear predicts (each
 * weapon's hit through the other side's armour, protection and resistance), so before anyone has landed a hit the
 * better geared side already leads, and once hits land they decide. A server that hides other players' health (it never
 * moves while hits land) gets the gear's damage per landed hit instead.
 *
 * The score is smoothed, and the verdict only changes when the score passes a margin and the new verdict has held for a
 * moment, so a single hit doesn't flip it back and forth.
 */
class FightOdds {
    enum class Verdict { WINNING, EVEN, LOSING }

    /** One tick of the fight. */
    data class Sample(
        /** Your health plus absorption. */
        val myHp: Float,
        /** The target's health plus absorption. */
        val theirHp: Float,
        /** The share of a hit that gets through your armour, protection and resistance (0 to 1). */
        val myTaken: Float = 1f,
        /** The same for the target. */
        val theirTaken: Float = 1f,
        /** Your hit before armour: weapon, sharpness, strength. */
        val myHit: Float = 1f,
        /** The target's hit before armour. */
        val theirHit: Float = 1f,
        /** One of your hits landed on the target this tick (it started flashing red). */
        val landed: Boolean = false,
        /** The target's max health: where the guess starts on a server that hides its health. */
        val theirMax: Float = 20f,
    )

    /** What it says now. */
    var verdict = Verdict.EVEN
        private set

    /** The smoothed score: the log of (their time to kill you) / (your time to kill them). */
    var score = 0f
        private set

    /** The unsmoothed score of the last sample. */
    var raw = 0f
        private set

    private var started = false
    private var lastMine = 0f
    private var lastTheirs = 0f

    /** Damage dealt and taken in the fading window, and the window's (faded) length in seconds. */
    private var dealt = 0f
    private var taken = 0f
    private var seconds = 0f

    /** Hits landed, and whether the target's health ever moved: it never does on a server that hides it. */
    private var hits = 0
    private var theirsMoved = false

    /**
     * Until the target's health moves, every landed hit counts at the gear's damage: the part of [dealt] that is such a
     * guess (taken out again once the real health shows), and the guessed health lost in all.
     */
    private var guessedDealt = 0f
    private var guessedLoss = 0f

    /** The candidate verdict and for how many ticks it has been the score's. */
    private var pending = Verdict.EVEN
    private var pendingTicks = 0

    fun reset() {
        verdict = Verdict.EVEN
        score = 0f
        raw = 0f
        started = false
        dealt = 0f
        taken = 0f
        seconds = 0f
        hits = 0
        theirsMoved = false
        guessedDealt = 0f
        guessedLoss = 0f
        pending = Verdict.EVEN
        pendingTicks = 0
    }

    /** The target's health looks hidden: it hasn't moved while [HIDDEN_HITS] of your hits landed. */
    val healthHidden: Boolean get() = !theirsMoved && hits >= HIDDEN_HITS

    fun update(s: Sample) {
        val mine = s.myHp.finite()
        val theirs = s.theirHp.finite()
        val hitOnThem = s.myHit * s.theirTaken.coerceIn(0f, 1f)
        val hitOnMe = s.theirHit * s.myTaken.coerceIn(0f, 1f)
        val fade = exp(-1f / WINDOW_TICKS)
        dealt *= fade
        taken *= fade
        guessedDealt *= fade
        seconds = seconds * fade + 1f / 20f
        if (s.landed) hits++
        if (started) {
            if (theirs != lastTheirs && !theirsMoved) {
                // The real health shows (often a tick after the hit flashed): the guesses make way for it.
                theirsMoved = true
                dealt = max(0f, dealt - guessedDealt)
                guessedDealt = 0f
            }
            taken += max(0f, lastMine - mine)
            dealt += max(0f, lastTheirs - theirs)
        }
        started = true
        if (!theirsMoved && s.landed) {
            dealt += hitOnThem
            guessedDealt += hitOnThem
            guessedLoss += hitOnThem
        }
        lastMine = mine
        lastTheirs = theirs
        // Once a hit landed and the health still hasn't moved, what the server shows means nothing yet: count down
        // from full health.
        val theirHealth = if (theirsMoved || hits == 0) theirs else max(0.5f, s.theirMax.finite() - guessedLoss)

        // A kill or a death is final at once; anything else is smoothed and held.
        val over = theirHealth <= 0f || mine <= 0f
        raw = when {
            theirHealth <= 0f -> MAX_SCORE
            mine <= 0f -> -MAX_SCORE
            else -> {
                val dealtRate = (dealt + hitOnThem * PRIOR_HITS_PER_SECOND * PRIOR_SECONDS) / (seconds + PRIOR_SECONDS)
                val takenRate = (taken + hitOnMe * PRIOR_HITS_PER_SECOND * PRIOR_SECONDS) / (seconds + PRIOR_SECONDS)
                val myTtk = theirHealth / max(dealtRate, MIN_RATE)
                val theirTtk = mine / max(takenRate, MIN_RATE)
                ln(theirTtk / myTtk).coerceIn(-MAX_SCORE, MAX_SCORE)
            }
        }
        score = if (over) raw else score + (raw - score) * (1f - exp(-1f / SMOOTH_TICKS))

        val want = when {
            score >= ENTER -> Verdict.WINNING
            score <= -ENTER -> Verdict.LOSING
            // Between the margins: stay unless the score is clearly back near even.
            verdict == Verdict.WINNING && score > EXIT -> Verdict.WINNING
            verdict == Verdict.LOSING && score < -EXIT -> Verdict.LOSING
            else -> Verdict.EVEN
        }
        if (want == verdict) {
            pendingTicks = 0
            return
        }
        if (want != pending) {
            pending = want
            pendingTicks = 0
        }
        pendingTicks++
        if (pendingTicks >= HOLD_TICKS || over) {
            verdict = want
            pendingTicks = 0
        }
    }

    private fun Float.finite() = if (isFinite()) max(0f, this) else 0f

    companion object {
        /** How fast old damage fades out of the window (its time constant): 4 s. */
        const val WINDOW_TICKS = 80f

        /** How many seconds of fighting the gear's prediction counts as. */
        const val PRIOR_SECONDS = 1f

        /** Hits per second the gear's prediction assumes for both sides. */
        const val PRIOR_HITS_PER_SECOND = 1f

        /** The slowest damage rate counted, in health per second (keeps the times to kill finite). */
        const val MIN_RATE = 0.05f

        /** The score's smoothing time constant: half a second. */
        const val SMOOTH_TICKS = 10f

        /** Winning or Losing from this far out (a time-to-kill ratio of about 1.3), back to Even within [EXIT]. */
        const val ENTER = 0.25f
        const val EXIT = 0.1f

        /** How long a new verdict must hold before it shows. */
        const val HOLD_TICKS = 10

        /** The score is clamped to this (a kill or a death). */
        const val MAX_SCORE = 3f

        /** Landed hits without the target's health moving before it counts as hidden. */
        const val HIDDEN_HITS = 3
    }
}
