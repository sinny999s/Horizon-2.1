package dev.horizon.client.render

import dev.horizon.client.HorizonClient
import dev.horizon.client.module.misc.NameProtect
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Matrix3x2f
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Text engine: glyphs rasterised by AWT at `size * guiScale` into 512-character pages, drawn as
 * textured quads at whole pixels from the line's top-left. No kerning, no shadow; `§` colour codes work.
 */
class TextRenderer(file: String, private val size: Float) {
    private val base: Font = load(file).deriveFont(Font.PLAIN, size)
    private val baseFallback: Font = load("noto").deriveFont(Font.PLAIN, size)
    private var font = base
    private var fallback = baseFallback
    private var scale = -1
    private val glyphs = HashMap<Char, Glyph?>()
    private val pages = ArrayList<Page>()
    private val queued = ArrayList<Queued>()

    private class Glyph(val x: Int, val y: Int, val w: Int, val h: Int, val ch: Char, val page: Page)

    private class Queued(val texture: DynamicTexture, val scissor: IntArray?, val m: Matrix3x2f, val x: Float, val y: Float,
                         val w: Float, val h: Float, val u0: Float, val v0: Float, val u1: Float, val v1: Float, val argb: Int)

    /** One 512-character page, rasterised on first use with one font. */
    private class Page(val from: Int, val to: Int, val font: Font) {
        val glyphs = ArrayList<Glyph>()
        var texture: DynamicTexture? = null
        var width = -1
        var height = -1

        fun glyph(c: Char): Glyph? {
            if (texture == null) build()
            return glyphs.firstOrNull { it.ch == c }
        }

        private fun build() {
            val count = to - from - 1
            val perRow = (ceil(sqrt(count.toDouble())) * 1.5).toInt()
            val frc = FontRenderContext(AffineTransform(), true, false)
            val pad = 5
            var x = 0
            var y = 0
            var col = 0
            var rowH = 0
            var maxW = 0
            var maxH = 0
            for (k in 0..count) {
                val ch = (from + k).toChar()
                val b = font.getStringBounds(ch.toString(), frc)
                val w = ceil(b.width).toInt()
                val h = ceil(b.height).toInt()
                // Measured before wrapping (on purpose).
                maxW = max(maxW, x + w)
                maxH = max(maxH, y + h)
                if (col >= perRow) {
                    x = 0
                    y += rowH + pad
                    col = 0
                    rowH = 0
                }
                rowH = max(rowH, h)
                glyphs += Glyph(x, y, w, h, ch, this)
                x += w + pad
                col++
            }
            width = max(maxW + pad, 1)
            height = max(maxH + pad, 1)
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = Color(255, 255, 255, 0)
            g.fillRect(0, 0, width, height)
            g.color = Color.WHITE
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            for (gl in glyphs) {
                g.font = font
                g.drawString(gl.ch.toString(), gl.x, gl.y + g.fontMetrics.ascent)
            }
            g.dispose()
            texture = Textures.upload("horizon glyphs", img)
        }

        fun close() {
            texture?.close()
            texture = null
            glyphs.clear()
            width = -1
            height = -1
        }
    }

    private fun checkScale() {
        val s = Minecraft.getInstance().window.guiScale
        if (s == scale) return
        pages.forEach { it.close() }
        pages.clear()
        glyphs.clear()
        scale = s
        font = base.deriveFont(size * s)
        fallback = baseFallback.deriveFont(size * s)
    }

    private fun glyph(c: Char): Glyph? = glyphs.getOrPut(c) {
        val page = pages.firstOrNull { it.from <= c.code && c.code < it.to }
            ?: run {
                val from = 512 * (c.code / 512)
                when {
                    font.canDisplay(c) -> Page(from, from + 512, font)
                    fallback.canDisplay(c) -> Page(from, from + 512, fallback)
                    else -> null
                }?.also { pages += it }
            }
        page?.glyph(c)
    }

    // ---- drawing ----

    /** [text] with its line box's top-left at ([x], [y]). */
    fun draw(text: String, x: Float, y: Float, color: Int) = run(text, x, y, color, false, 0, false)

    /** Centred on ([cx], [cy]) both ways. */
    fun drawCentered(text: String, cx: Float, cy: Float, color: Int) = draw(text, cx - width(text) / 2f, cy - height(text) / 2f, color)

    /** The theme's moving primary/secondary gradient, [step] apart per character. */
    fun drawGradient(text: String, x: Float, y: Float, step: Int) = run(text, x, y, -1, true, step, false)

    /** [drawGradient] centred horizontally on [cx]. */
    fun drawGradientCentered(text: String, cx: Float, y: Float, step: Int) = drawGradient(text, cx - width(text) / 2f, y, step)

    private var capScale = -1
    private var capTop = 0f
    private var capHeight = 0f
    private var ascentPx = 0
    private val symbols = HashMap<String, java.awt.geom.Rectangle2D>()

    /**
     * The y to [draw] at so the text's capitals are centred on [cy]. [drawCentered] centres the line box
     * (descender room included) and [draw] puts it 1.5 lower than asked, so text sits low; the client's own screens
     * line it up with this instead (the ClickGUI and HUD don't).
     */
    fun middleY(cy: Float): Float {
        checkScale()
        if (capScale != scale) {
            capScale = scale
            val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
            // As the glyph pages are drawn: the baseline sits the (whole-pixel) ascent below the cell's top.
            val ascent = g.getFontMetrics(font).ascent
            g.dispose()
            ascentPx = ascent
            symbols.clear()
            val cap = -font.createGlyphVector(FontRenderContext(AffineTransform(), true, false), "H").visualBounds.y.toFloat()
            capTop = (ascent - cap) / scale
            capHeight = cap / scale
        }
        return cy - capHeight / 2f - capTop - 1.5f
    }

    /** [text] from [x], its capitals centred on [cy]. */
    fun drawMiddle(text: String, x: Float, cy: Float, color: Int) = draw(text, x - 0.5f, middleY(cy), color)

    /** [text] from [x] with its baseline on [baseline], so two sizes can share a line. */
    fun drawBaseline(text: String, x: Float, baseline: Float, color: Int) {
        middleY(0f)
        draw(text, x - 0.5f, baseline - capHeight - capTop - 1.5f, color)
    }

    /** [text], a symbol such as an arrow, centred on ([cx], [cy]) by its own outline. */
    fun drawSymbolCentered(text: String, cx: Float, cy: Float, color: Int) {
        middleY(cy)
        val b = symbols.getOrPut(text) { font.createGlyphVector(FontRenderContext(AffineTransform(), true, false), text).visualBounds }
        val baseline = cy - (b.y + b.height / 2).toFloat() / scale
        draw(text, cx - (b.x + b.width / 2).toFloat() / scale - 0.5f, baseline - ascentPx.toFloat() / scale - 1.5f, color)
    }

    /** [text] centred on ([cx], [cy]), by its capitals vertically. */
    fun drawMiddleCentered(text: String, cx: Float, cy: Float, color: Int) = drawMiddle(text, cx - width(text) / 2f, cy, color)

    /** Batched: drawn at the next [flush], with the scissor that is current now. */
    fun queue(text: String, x: Float, y: Float, color: Int, rainbow: Boolean = false, step: Int = 0) = run(text, x, y, color, rainbow, step, true)

    /** Draws the queued text, grouped by glyph page and scissor. */
    fun flush() {
        if (queued.isEmpty()) return
        val byTexture = LinkedHashMap<DynamicTexture, MutableList<Queued>>()
        for (q in queued) byTexture.getOrPut(q.texture) { ArrayList() } += q
        for ((texture, list) in byTexture) {
            val byScissor = LinkedHashMap<List<Int>?, MutableList<Queued>>()
            for (q in list) byScissor.getOrPut(q.scissor?.toList()) { ArrayList() } += q
            for (group in byScissor.values) {
                for (q in group) Canvas.glyph(texture.textureView, q.scissor, q.m, q.x, q.y, q.w, q.h, q.u0, q.v0, q.u1, q.v1, q.argb)
            }
        }
        queued.clear()
    }

    private fun run(raw: String, x: Float, y: Float, color: Int, rainbow: Boolean, step: Int, batched: Boolean) {
        checkScale()
        val text = NameProtect.protect(raw)
        var r = (color shr 16 and 255) / 255f
        var g = (color shr 8 and 255) / 255f
        var b = (color and 255) / 255f
        var a = (color ushr 24) / 255f
        val m = Matrix3x2f(Canvas.pose).translate(round1(x + 0.5f), round1(y + 1.5f)).scale(1f / scale, 1f / scale)
        val scissor = Canvas.currentScissor()
        // Immediate text draws one batch per page, in the order pages are first used.
        val perPage = if (batched) null else LinkedHashMap<DynamicTexture, MutableList<Queued>>()
        var cx = 0f
        var cy = 0f
        var code = false
        var lineStart = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (code) {
                code = false
                if (c == 'x' || c == 'X') {
                    val hex = hexAt(text, i)
                    if (hex != null) {
                        r = (hex shr 16 and 255) / 255f
                        g = (hex shr 8 and 255) / 255f
                        b = (hex and 255) / 255f
                        i += 12
                    }
                    i++
                    continue
                }
                val u = c.uppercaseChar()
                val cc = COLOR_CODES[u]
                if (cc != null) {
                    r = (cc shr 16 and 255) / 255f
                    g = (cc shr 8 and 255) / 255f
                    b = (cc and 255) / 255f
                } else if (u == 'R') {
                    r = (color shr 16 and 255) / 255f
                    g = (color shr 8 and 255) / 255f
                    b = (color and 255) / 255f
                    a = (color ushr 24) / 255f
                }
                i++
                continue
            }
            if (rainbow) {
                val col = Theme.gradient(i * step)
                r = (col shr 16 and 255) / 255f
                g = (col shr 8 and 255) / 255f
                b = (col and 255) / 255f
                a = (col ushr 24) / 255f
            }
            when (c) {
                '\n' -> {
                    cy += height(text.substring(lineStart, i)) * scale
                    cx = 0f
                    lineStart = i + 1
                }
                '§' -> code = true
                else -> {
                    val gl = glyph(c)
                    if (gl != null) {
                        if (gl.ch != ' ') {
                            val page = gl.page
                            val tw = page.width.toFloat()
                            val th = page.height.toFloat()
                            // Floats truncated to bytes.
                            val argb = ((a * 255).toInt() shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
                            val q = Queued(page.texture!!, scissor, m, cx, cy, gl.w.toFloat(), gl.h.toFloat(),
                                gl.x / tw, gl.y / th, (gl.x + gl.w) / tw, (gl.y + gl.h) / th, argb)
                            if (perPage != null) perPage.getOrPut(q.texture) { ArrayList() } += q else queued += q
                        }
                        cx += gl.w
                    }
                }
            }
            i++
        }
        perPage?.forEach { (texture, list) ->
            for (q in list) Canvas.glyph(texture.textureView, q.scissor, q.m, q.x, q.y, q.w, q.h, q.u0, q.v0, q.u1, q.v1, q.argb)
        }
    }

    // ---- measuring ----

    /** The widest line, in GUI units. */
    fun width(text: String): Float {
        checkScale()
        var cur = 0f
        var best = 0f
        for (c in strip(NameProtect.protect(text))) {
            if (c == '\n') {
                best = max(cur, best)
                cur = 0f
            } else {
                cur += (glyph(c)?.w ?: 0) / scale.toFloat()
            }
        }
        return max(cur, best)
    }

    /** Line heights (tallest glyph cell / scale) added up. */
    fun height(text: String): Float {
        checkScale()
        var chars = strip(NameProtect.protect(text)).toCharArray()
        if (chars.isEmpty()) chars = charArrayOf(' ')
        var lineH = 0f
        var total = 0f
        for (c in chars) {
            if (c == '\n') {
                // NB: this one is not divided by the scale (on purpose).
                if (lineH == 0f) lineH = glyph(' ')?.h?.toFloat() ?: (1f / scale)
                total += lineH
                lineH = 0f
            } else {
                lineH = max((glyph(c)?.h ?: 0) / scale.toFloat(), lineH)
            }
        }
        return lineH + total
    }

    companion object {
        private val COLOR_CODES = mapOf(
            '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA,
            '6' to 0xFFAA00, '7' to 0xAAAAAA, '8' to 0x555555, '9' to 0x5555FF, 'A' to 0x55FF55, 'B' to 0x55FFFF,
            'C' to 0xFF5555, 'D' to 0xFF55FF, 'E' to 0xFFFF55, 'F' to 0xFFFFFF,
        )

        private fun load(name: String): Font =
            TextRenderer::class.java.getResourceAsStream("/assets/${HorizonClient.ID}/fonts/$name.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }

        /** Rounds the text origin to 0.1 GUI. */
        private fun round1(v: Float): Float = if (v.isNaN()) 1f else ((v * 100f).roundToInt() / 100f * 10f).roundToInt() / 10f

        private fun hexDigit(c: Char) = c.digitToIntOrNull(16)

        /** `§x§R§R§G§G§B§B` starting at the 'x' at [i] (the '§' before it already read). */
        private fun hexAt(s: String, i: Int): Int? {
            if (i + 12 >= s.length || s[i - 1] != '§') return null
            var v = 0
            for (k in 0 until 6) {
                if (s[i + 1 + 2 * k] != '§') return null
                v = v * 16 + (hexDigit(s[i + 2 + 2 * k]) ?: return null)
            }
            return v
        }

        /** Removes `§` + one char, or `§x` + 12 chars when a valid hex colour follows. */
        fun strip(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '§') {
                    if (i + 1 >= s.length) break
                    if ((s[i + 1] == 'x' || s[i + 1] == 'X') && hexAt(s, i + 1) != null) i += 14 else i += 2
                    continue
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }
}
