package dev.horizon.client.render

import com.github.weisj.jsvg.attributes.ViewBox
import com.github.weisj.jsvg.parser.SVGLoader
import dev.horizon.client.HorizonClient
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil

/**
 * An SVG icon: assets/horizon/vector/<name>.svg rasterised with JSVG at `size * guiScale`, drawn as a
 * textured quad tinted by vertex colours. The icons' fill is #e3e3e3, so a tint comes out at 89%. The picture goes on
 * the shared pages ([Atlas]) so it batches with what's round it; one that doesn't fit there gets a texture of its own.
 */
class Icon(private val name: String, private var w: Float = 0f, private var h: Float = 0f) {
    /** The picture: its pixels ([pw] by [ph], row by row from the top) and where they are (an atlas [slot], or [texture]). */
    private var pixels: IntArray? = null
    private var pw = 0
    private var ph = 0
    private var slot: Atlas.Slot? = null
    private var texture: DynamicTexture? = null
    private var rasterScale = -1

    private fun raster(pw: Float, ph: Float) {
        texture?.close()
        texture = null
        slot = null
        pixels = try {
            val doc = Icon::class.java.getResourceAsStream("/assets/${HorizonClient.ID}/vector/$name.svg")!!.use { SVGLoader().load(it) }!!
            val img = BufferedImage(ceil(pw).toInt(), ceil(ph).toInt(), BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            doc.render(null, g, ViewBox(pw, ph))
            g.dispose()
            this.pw = img.width
            this.ph = img.height
            img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        } catch (t: Throwable) {
            HorizonClient.LOG.warn("Couldn't rasterise icon {}", name, t)
            null
        }
    }

    /**
     * Draws at ([x] + 0.5, [y] + 2), [primary] on the bottom-left/top-right corners and [secondary] on the
     * others, alpha times [alpha]. Drawing at a size other than the constructed one re-rasterises at 1x (on purpose).
     */
    fun draw(x: Float, y: Float, dw: Float = w, dh: Float = h, primary: Int = -1, secondary: Int = primary, alpha: Float = 1f) {
        val scale = Minecraft.getInstance().window.guiScale
        if (rasterScale != scale || pixels == null) {
            rasterScale = scale
            raster(w * scale, h * scale)
        }
        if (w != ceil(dw) || h != ceil(dh)) {
            w = ceil(dw)
            h = ceil(dh)
            raster(w, h)
        }
        val image = pixels ?: return
        // Onto a page (again, if the one it was on has been wiped since), or else a texture of its own.
        if (texture == null && slot?.valid() != true) {
            slot = Atlas.put(image, pw, ph)
            if (slot == null) texture = Textures.upload("horizon icon $name", BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB).also {
                it.setRGB(0, 0, pw, ph, image, 0, pw)
            })
        }
        val a = alpha * Colors.alpha(primary) / 255f
        val p = Colors.withAlpha(primary, a)
        val s = Colors.withAlpha(secondary, a)
        val at = slot
        if (at != null) {
            at.use()
            Canvas.texture(at.page.view, x + 0.5f, y + 2f, ceil(dw), ceil(dh), p, s, p, s, at.u0, at.v0, at.u1, at.v1)
        } else {
            Canvas.texture(texture?.textureView ?: return, x + 0.5f, y + 2f, ceil(dw), ceil(dh), p, s, p, s)
        }
    }
}
