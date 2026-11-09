package dev.horizon.client.combat

import kotlin.math.roundToInt

/** The one seeded random generator the combat code draws from. */
object Rng {
    val random = java.util.Random(88005553535L)

    fun float(a: Float, b: Float): Float = a + (b - a) * random.nextDouble().toFloat()

    fun double(a: Double, b: Double): Double = a + (b - a) * random.nextDouble()

    /** `Math.round(float)`, so both ends come up half as often. */
    fun int(a: Int, b: Int): Int = (a + (b - a) * random.nextDouble().toFloat()).roundToInt()

    fun gaussian(): Double = random.nextGaussian()
}
