package dev.horizon.client.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Easing curves, with quirks kept on purpose: IN_OUT_SINE is the
 * same as IN_SINE and OUT_ELASTIC's wobble is halved. Input and output are 0..1 (sigmoids stop short of the ends).
 */
enum class Easing(private val f: (Double) -> Double) {
    LINEAR({ it }),
    IN_QUAD({ it.pow(2) }),
    OUT_QUAD({ it * (2 - it) }),
    IN_OUT_QUAD({ if (it < 0.5) 2 * it.pow(2) else -1 + (4 - 2 * it) * it }),
    IN_CUBIC({ it * it * it }),
    OUT_CUBIC({ (it - 1).pow(3) + 1 }),
    IN_QUART({ it.pow(4) }),
    OUT_QUART({ 1 - (it - 1).pow(4) }),
    IN_OUT_QUART({ if (it < 0.5) 8 * it.pow(4) else 1 - (-2 * it + 2).pow(4) / 2 }),
    IN_QUINT({ it.pow(5) }),
    OUT_QUINT({ 1 + (it - 1).pow(5) }),
    IN_OUT_QUINT({ if (it < 0.5) 16 * it.pow(5) else 1 - (-2 * it + 2).pow(5) / 2 }),
    IN_SINE({ 1 - cos(it * PI / 2) }),
    OUT_SINE({ sin(it * PI / 2) }),
    IN_OUT_SINE({ 1 - cos(PI * it / 2) }),
    IN_EXPO({ if (it == 0.0) 0.0 else 2.0.pow(10 * it - 10) }),
    OUT_EXPO({ if (it == 1.0) 1.0 else 1 - 2.0.pow(-10 * it) }),
    IN_OUT_EXPO({
        when {
            it == 0.0 -> 0.0
            it == 1.0 -> 1.0
            it < 0.5 -> 2.0.pow(20 * it - 10) / 2
            else -> (2 - 2.0.pow(-20 * it + 10)) / 2
        }
    }),
    IN_CIRC({ 1 - sqrt(1 - it.pow(2)) }),
    OUT_CIRC({ sqrt(1 - (it - 1).pow(2)) }),
    IN_OUT_CIRC({ if (it < 0.5) (1 - sqrt(1 - (2 * it).pow(2))) / 2 else (sqrt(1 - (-2 * it + 2).pow(2)) + 1) / 2 }),
    SIGMOID(sigmoid(10.0)),
    SIGMOID_2(sigmoid(8.0)),
    SIGMOID_3(sigmoid(12.0)),
    SIGMOID_4(sigmoid(6.0)),
    SIGMOID_5(sigmoid(14.0)),
    SIGMOID_6(sigmoid(4.0)),
    SIGMOID_7(sigmoid(16.0)),
    OUT_ELASTIC({
        when (it) {
            0.0 -> 0.0
            1.0 -> 1.0
            else -> 2.0.pow(-10 * it) * sin((10 * it - 0.75) * (2 * PI / 3)) * 0.5 + 1
        }
    }),
    IN_BACK({ 2.70158 * it * it * it - 1.70158 * it * it }),
    OUT_BACK({ 1 + 2.70158 * (it - 1).pow(3) + 1.70158 * (it - 1).pow(2) }),
    DECELERATE({ 1 - (it - 1).pow(2) }),
    IN_OUT_CUBIC({ if (it < 0.5) 4 * it * it * it else 1 - (-2 * it + 2).pow(3) / 2 });

    fun apply(t: Double): Double = f(t)
}

private fun sigmoid(k: Double): (Double) -> Double = { 1 / (1 + exp(-k * (it - 0.5))) }
