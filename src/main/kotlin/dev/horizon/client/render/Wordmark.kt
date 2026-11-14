package dev.horizon.client.render

import com.github.weisj.jsvg.attributes.ViewBox
import com.github.weisj.jsvg.parser.SVGLoader
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The Horizon wordmark (vector/wordmark.svg and wordmark-rules.svg) as an AWT image, for the screens that show it while
 * the game is starting: the pre-launch splash and the loading screen. Both draw its lower horizon line themselves, as
 * their progress line, so it is left out here. Plain AWT and JSVG, no game classes: the splash draws it before the game
 * has loaded.
 */
object Wordmark {
    // Both SVGs share an 834 x 196 box; the lower line spans x 80..906 and y 222..224 of it (the box starts at 76, 32).
    const val W = 834f
    const val H = 196f
    const val LINE_X0 = 4f / W
    const val LINE_X1 = 830f / W
    const val LINE_Y = 190f / H

    /** The letters in white and the upper line at [linesAlpha], [pw] x [ph] pixels. */
    fun raster(pw: Int, ph: Int, linesAlpha: Float): BufferedImage {
        val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.drawImage(svg("wordmark", pw, ph), 0, 0, null)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, linesAlpha)
        g.clipRect(0, 0, pw, ph / 2)
        g.drawImage(svg("wordmark-rules", pw, ph), 0, 0, null)
        g.dispose()
        return img
    }

    private fun svg(name: String, pw: Int, ph: Int): BufferedImage {
        val doc = Wordmark::class.java.getResourceAsStream("/assets/horizon/vector/$name.svg")!!.use { SVGLoader().load(it) }!!
        val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        doc.render(null, g, ViewBox(pw.toFloat(), ph.toFloat()))
        g.dispose()
        return img
    }
}
