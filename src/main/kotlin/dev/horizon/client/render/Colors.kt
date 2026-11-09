package dev.horizon.client.render

import java.awt.Color
import kotlin.math.roundToInt

/** Colour helpers on ARGB ints. */
object Colors {
    fun alpha(c: Int) = c ushr 24
    fun alphaF(c: Int) = (c ushr 24) / 255f

    /** Replaces the alpha with `(int)(255 * clamp(a, 0, 1))` (truncated). */
    fun withAlpha(c: Int, a: Float): Int = ((255f * a.coerceIn(0f, 1f)).toInt() shl 24) or (c and 0xFFFFFF)

    /** Each channel, alpha included, `roundToInt(c1 + (c2 - c1) * clamp(t))`. */
    fun lerp(c1: Int, c2: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = c1 ushr shift and 255
            val b = c2 ushr shift and 255
            return (a + (b - a) * k).roundToInt() shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** `java.awt.Color.brighter()` (alpha kept). */
    fun brighter(c: Int): Int = Color(c, true).brighter().let { it.rgb and 0xFFFFFF or (c and -0x1000000) }

    /** `java.awt.Color.darker()` (alpha kept). */
    fun darker(c: Int): Int = Color(c, true).darker().let { it.rgb and 0xFFFFFF or (c and -0x1000000) }
}
