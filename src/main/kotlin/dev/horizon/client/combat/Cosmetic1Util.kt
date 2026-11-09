package dev.horizon.client.combat

import dev.horizon.client.module.ColorMode
import dev.horizon.client.render.Theme
import java.awt.Color

/** The colour helpers the cosmetic modules share and their colour-mode switch. */
object Cosmetic1Util {
    /** A hue cycling with wall-clock time, opaque. */
    fun rainbow(speed: Int, offset: Int, s: Float, b: Float): Int {
        val h = ((System.currentTimeMillis() / speed + offset) % 360).toInt()
        return Color.HSBtoRGB(h / 360.0f, s, b) or (255 shl 24)
    }

    /** The same hue at saturation 0.5. */
    fun sky(speed: Int, offset: Int): Int {
        val h = ((System.currentTimeMillis() / speed + offset) % 360).toInt() % 360
        return Color.getHSBColor((h / 360.0).toFloat(), 0.5f, 1.0f).rgb
    }

    /** The colour-mode switch: [custom] for CUSTOM, otherwise the theme gradient or a rainbow at [offset]. */
    fun modeColor(mode: ColorMode, custom: Int, offset: Int): Int = when (mode) {
        ColorMode.CUSTOM -> custom
        ColorMode.SYNC -> Theme.gradient(offset)
        ColorMode.SKY -> sky(18, offset)
        ColorMode.LIGHT_RGB -> rainbow(18, offset, 0.6f, 1.0f)
        ColorMode.RGB -> rainbow(18, offset, 1.0f, 1.0f)
    }
}
