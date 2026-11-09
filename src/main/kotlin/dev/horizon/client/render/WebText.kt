package dev.horizon.client.render

import dev.horizon.client.HorizonClient
import dev.horizon.client.module.misc.NameProtect
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Matrix3x2f
import org.joml.Vector2f
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.text.AttributedString
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Text laid out as a browser lays it out, for screens matched to an HTML mockup (the new ClickGUI).
 * [TextRenderer] advances every glyph by its bounds rounded up to a whole pixel, without kerning, which runs
 * text 5-8% wider than the same font in a browser. Here a string is laid out by AWT with kerning and fractional
 * advances and rasterised whole at the screen's pixel size, its pen started at the right quarter pixel (browsers
 * position glyphs in quarter pixels too) and its baseline on a whole pixel, then kept as one texture. Coverage is
 * thickened a little to match the browser's heavier strokes. `§` codes are not read.
 */
class WebText(file: String, private val size: Float, letterSpacing: Float = 0f) {
    /** [letterSpacing] is the CSS letter-spacing in the mockup's pixels (half a GUI unit each). */
    private val base: Font = load(file).deriveFont(mapOf(TextAttribute.SIZE to size, TextAttribute.KERNING to TextAttribute.KERNING_ON,
        TextAttribute.LIGATURES to TextAttribute.LIGATURES_ON, TextAttribute.TRACKING to letterSpacing / (size * 2f)))
    private var font = base
    private var scale = -1
    private val frc = FontRenderContext(null, true, true)

    private class Raster(val texture: DynamicTexture, val w: Int, val h: Int, val originX: Int, val baseline: Int) {
        var used = System.currentTimeMillis()
    }

    /** By text and the pen's quarter pixel; the least recently drawn are dropped past [MAX] (once unused for 2 s). */
    private val rasters = LinkedHashMap<String, Raster>(64, 0.75f, true)
    private val widths = HashMap<String, Float>()
    private var capPx = 0f

    private fun checkScale() {
        val s = Minecraft.getInstance().window.guiScale
        if (s == scale) return
        rasters.values.forEach { it.texture.close() }
        rasters.clear()
        widths.clear()
        scale = s
        font = base.deriveFont(size * s)
        capPx = -font.createGlyphVector(frc, "H").visualBounds.y.toFloat()
    }

    /** The advance of [text] in GUI units. */
    fun width(text: String): Float {
        checkScale()
        if (text.isEmpty()) return 0f
        val t = NameProtect.protect(text)
        widths[t]?.let { return it }
        if (widths.size > 4096) widths.clear()
        return (layout(t).advance / scale).also { widths[t] = it }
    }

    /** [text] from [x], its capitals centred on [cy]. */
    fun drawMiddle(text: String, x: Float, cy: Float, color: Int) = draw(text, x, cy, color, color)

    /** [text] centred on [cx], its capitals centred on [cy]. */
    fun drawMiddleCentered(text: String, cx: Float, cy: Float, color: Int) = draw(text, cx - width(text) / 2f, cy, color, color)

    /** [text] from [x], its capitals centred on [cy], in a gradient from [left] to [right] across its width. */
    fun drawGradientMiddle(text: String, x: Float, cy: Float, left: Int, right: Int) = draw(text, x, cy, left, right)

    private fun draw(raw: String, x: Float, cy: Float, left: Int, right: Int) {
        checkScale()
        if (raw.isEmpty() || (left ushr 24 == 0 && right ushr 24 == 0)) return
        val text = NameProtect.protect(raw)
        val pose = Canvas.pose
        val upright = abs(pose.m00 - 1f) < 1e-4f && abs(pose.m11 - 1f) < 1e-4f && pose.m01 == 0f && pose.m10 == 0f
        val m: Matrix3x2f
        val r: Raster
        val dx: Float
        val dy: Float
        if (upright) {
            // The pen in screen pixels: x to a quarter pixel, the baseline to a whole one.
            val p = pose.transformPosition(Vector2f(x, cy))
            val px = p.x * scale
            val baseline = (p.y * scale + capPx / 2f).roundToInt()
            var whole = floor(px)
            var quarter = ((px - whole) * 4f).roundToInt()
            if (quarter == 4) {
                whole += 1f
                quarter = 0
            }
            r = raster(text, quarter)
            m = Matrix3x2f().scale(1f / scale, 1f / scale)
            dx = whole - r.originX
            dy = (baseline - r.baseline).toFloat()
        } else {
            // Scaled (a window shrunk to fit): no pixel grid to keep to.
            r = raster(text, 0)
            m = Matrix3x2f(pose).translate(x, cy).scale(1f / scale, 1f / scale)
            dx = -r.originX.toFloat()
            dy = capPx / 2f - r.baseline
        }
        r.used = System.currentTimeMillis()
        val scissor = Canvas.currentScissor()
        if (left == right) {
            Canvas.glyph(r.texture.textureView, scissor, m, dx, dy, r.w.toFloat(), r.h.toFloat(), 0f, 0f, 1f, 1f, left)
        } else {
            Canvas.glyph(r.texture.textureView, scissor, m, dx, dy, r.w.toFloat(), r.h.toFloat(), 0f, 0f, 1f, 1f, left, right)
        }
    }

    /**
     * [text] laid out in the font, any character it has no glyph for (a server's ✔, ❤, ⚔) taken from a system font that
     * has one, at the same size, instead of drawn as a box.
     */
    private fun layout(text: String): TextLayout {
        if (font.canDisplayUpTo(text) == -1) return TextLayout(text, font, frc)
        val s = AttributedString(text)
        s.addAttribute(TextAttribute.FONT, font)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (!font.canDisplay(cp)) fallback(cp)?.let { s.addAttribute(TextAttribute.FONT, it.deriveFont(font.size2D), i, i + n) }
            i += n
        }
        return TextLayout(s.iterator, frc)
    }

    private fun raster(text: String, quarter: Int): Raster {
        val key = "$quarter$text"
        rasters[key]?.let { return it }
        val layout = layout(text)
        val q = quarter / 4f
        val b = layout.getPixelBounds(frc, q, 0f)
        val pad = 1
        val left = min(0, b.x) - pad
        val top = min(-ceil(layout.ascent).toInt(), b.y) - pad
        val right = max(ceil(layout.advance + q).toInt(), b.x + b.width) + pad
        val bottom = max(ceil(layout.descent).toInt(), b.y + b.height) + pad
        val w = max(1, right - left)
        val h = max(1, bottom - top)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color.WHITE
        // The outlines filled, not the glyphs drawn: AWT hints glyph bitmaps to the pixel grid, which at fractional
        // positions leaves uneven gaps inside words; browsers don't hint across.
        g.fill(layout.getOutline(AffineTransform.getTranslateInstance((-left + q).toDouble(), -top.toDouble())))
        g.dispose()
        for (yy in 0 until h) for (xx in 0 until w) {
            val a = img.getRGB(xx, yy) ushr 24
            if (a != 0) img.setRGB(xx, yy, (BOOST[a] shl 24) or 0xFFFFFF)
        }
        val r = Raster(Textures.upload("horizon web text", img), w, h, -left, -top)
        rasters[key] = r
        if (rasters.size > MAX) {
            val now = System.currentTimeMillis()
            val it = rasters.entries.iterator()
            while (rasters.size > MAX && it.hasNext()) {
                val e = it.next()
                if (now - e.value.used < 2000) break
                e.value.texture.close()
                it.remove()
            }
        }
        return r
    }

    companion object {
        private const val MAX = 400

        /** Coverage thickened towards the browser's text (DirectWrite and Skia draw light-on-dark text heavier). */
        private val BOOST = IntArray(256) { (255f * (it / 255f).pow(0.62f)).roundToInt() }

        private val fallbacks = HashMap<Int, Font?>()

        /** A system font with a glyph for [cp]: Windows' symbol fonts first, then anything installed. */
        private fun fallback(cp: Int): Font? = fallbacks.getOrPut(cp) {
            FALLBACK_NAMES.map { Font(it, Font.PLAIN, 12) }.firstOrNull { it.family == it.name && it.canDisplay(cp) }
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.firstOrNull { it.canDisplay(cp) }?.deriveFont(12f)
        }

        private val FALLBACK_NAMES = listOf("Segoe UI Symbol", "Segoe UI Emoji", "Segoe UI", "Arial Unicode MS", "DejaVu Sans")

        private fun load(name: String): Font =
            WebText::class.java.getResourceAsStream("/assets/${HorizonClient.ID}/fonts/$name.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }
}
