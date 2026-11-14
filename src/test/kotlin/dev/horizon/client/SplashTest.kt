package dev.horizon.client

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Draws the pre-launch splash off screen; the pictures land in build/splash-preview to look at. */
class SplashTest {
    private val out = File("build/splash-preview").apply { mkdirs() }

    private fun render(name: String, state: Splash.State, scale: Double, desktop: Color): BufferedImage {
        val m = Splash.SHADOW
        val w = ((Splash.W + 2 * m) * scale).toInt()
        val h = ((Splash.H + 2 * m) * scale).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = desktop
        g.fillRect(0, 0, w, h)
        g.scale(scale, scale)
        g.translate(m, m)
        Splash.paint(g, state)
        g.dispose()
        ImageIO.write(img, "png", File(out, "$name.png"))
        return img
    }

    @Test
    fun drawsTheStages() {
        val stages = listOf(
            "Starting up" to 0.04f,
            "Hooking into the game" to 0.30f,
            "Loading commands and menus" to 0.88f,
            "Starting Minecraft" to 0.95f,
        )
        for ((i, s) in stages.withIndex()) {
            render("stage$i", Splash.State(s.first, s.second, "2.1.13"), 1.0, Color(0x20, 0x22, 0x26))
        }
        render("hover", Splash.State("Loading modules", 0.7f, "2.1.13", 1f, 0f), 1.0, Color(0x20, 0x22, 0x26))
        render("hover-close", Splash.State("Loading modules", 0.7f, "2.1.13", 1f, 1f), 1.0, Color(0x20, 0x22, 0x26))
        render("light-desktop", Splash.State("Preparing rendering", 0.5f, "2.1.13"), 1.0, Color(0xE8, 0xEA, 0xEE))
        val big = render("scale150", Splash.State("Starting Minecraft", 0.95f, "2.1.13"), 1.5, Color(0x20, 0x22, 0x26))

        // The wordmark came from its SVG: its letters are white where the H's left stem is (x 80..100 of 834 at the left).
        val m = Splash.SHADOW
        val sx = ((m + (Splash.W - 200) / 2 + 200 * 10.0 / 834) * 1.5).toInt()
        val sy = ((m + 34 + 47 * 0.6) * 1.5).toInt()
        val c = Color(big.getRGB(sx, sy))
        assertTrue(c.red > 200 && c.green > 200 && c.blue > 200, "no white letter at $sx, $sy: $c")
    }
}
