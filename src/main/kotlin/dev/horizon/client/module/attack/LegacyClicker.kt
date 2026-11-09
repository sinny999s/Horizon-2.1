package dev.horizon.client.module.attack

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Millisecond-scheduled 1.8.x spam-click pacing.
 *
 * Scheduling clicks on a continuous millisecond timeline (consumed at most once per 50 ms client tick) avoids the
 * coarse `20 / cps` integer tick division that collapses 11..20 CPS to the same 1-tick interval, while never sending
 * more than one swing/attack packet in a single client tick.
 */
object LegacyClicker {
    enum class Pattern { HUMAN, BUTTERFLY, JITTER, FLAT }

    class State(private val rng: Random = Random()) {
        private var initialized = false
        private var nextClickMs = 0.0
        private var paceDrift = 0.0
        private var burstLeft = 14

        fun reset() {
            initialized = false
            nextClickMs = 0.0
            paceDrift = 0.0
            burstLeft = 10 + rng.nextInt(9)
        }

        /** Due now, or within [lookaheadTicks] 50 ms client ticks. */
        fun ready(nowMs: Long, lookaheadTicks: Int = 0): Boolean {
            if (!initialized) return true
            return nowMs.toDouble() + lookaheadTicks * 50.0 >= nextClickMs
        }

        /** Advance the schedule after a swing or hit at [nowMs]. */
        fun onClick(nowMs: Long, minCps: Int, maxCps: Int, pattern: Pattern): Double {
            val delay = nextDelayMs(minCps, maxCps, pattern)
            val now = nowMs.toDouble()
            nextClickMs = if (!initialized || now - nextClickMs > 150.0) {
                initialized = true
                now + delay
            } else {
                max(nextClickMs + delay, now - 25.0)
            }
            return delay
        }

        /** Next inter-click interval in milliseconds for [minCps]..[maxCps]. */
        fun nextDelayMs(minCps: Int, maxCps: Int, pattern: Pattern): Double {
            val lo = min(minCps, maxCps).coerceIn(1, 20)
            val hi = max(minCps, maxCps).coerceIn(1, 20)
            if (pattern == Pattern.FLAT) {
                val cps = lo + rng.nextDouble() * (hi - lo)
                return (1000.0 / cps).coerceIn(50.0, 1200.0)
            }

            paceDrift = (paceDrift * 0.88 + rng.nextGaussian() * 0.25).coerceIn(-1.0, 1.0)
            val midCps = (lo + hi) * 0.5
            val halfCps = (hi - lo) * 0.5
            val targetCps = (midCps + paceDrift * halfCps * 0.7).coerceIn(lo.toDouble(), hi.toDouble())
            val base = 1000.0 / targetCps
            val span = 1000.0 / lo - 1000.0 / hi

            val raw = when (pattern) {
                Pattern.HUMAN -> {
                    val sigma = max(span * 0.25, base * 0.11)
                    var d = base * 0.97 + rng.nextGaussian() * sigma
                    val roll = rng.nextDouble()
                    if (roll < 0.05) d += 30.0 + rng.nextDouble() * 45.0
                    else if (roll < 0.12) d *= 0.86
                    d
                }
                Pattern.BUTTERFLY -> {
                    if (rng.nextDouble() < 0.18) {
                        38.0 + rng.nextDouble() * 14.0
                    } else {
                        val sigma = max(span * 0.24, base * 0.12)
                        base * 1.08 + rng.nextGaussian() * sigma
                    }
                }
                Pattern.JITTER -> {
                    burstLeft--
                    val sigma = max(span * 0.20, base * 0.08)
                    var d = base * 0.95 + rng.nextGaussian() * sigma
                    if (burstLeft <= 0) {
                        burstLeft = 10 + rng.nextInt(9)
                        d += 45.0 + rng.nextDouble() * 50.0
                    }
                    d
                }
                Pattern.FLAT -> base
            }
            return raw.coerceIn(36.0, 1200.0)
        }
    }
}
