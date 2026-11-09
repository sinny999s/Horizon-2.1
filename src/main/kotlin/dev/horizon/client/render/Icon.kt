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
 * textured quad tinted by vertex colours. The icons' fill is #e3e3e3, so a tint comes out at 89%.
 */
class Icon(private val name: String, private var w: Float = 0f, private var h: Float = 0f) {
    private var texture: DynamicTexture? = null
    private var rasterScale = -1

    private fun raster(pw: Float, ph: Float) {
        texture?.close()
        texture = try {
            val doc = Icon::class.java.getResourceAsStream("/assets/${HorizonClient.ID}/vector/$name.svg")!!.use { SVGLoader().load(it) }!!
            val img = BufferedImage(ceil(pw).toInt(), ceil(ph).toInt(), BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            doc.render(null, g, ViewBox(pw, ph))
            g.dispose()
            Textures.upload("horizon icon $name", img)
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
        if (rasterScale != scale || texture == null) {
            rasterScale = scale
            raster(w * scale, h * scale)
        }
        if (w != ceil(dw) || h != ceil(dh)) {
            w = ceil(dw)
            h = ceil(dh)
            raster(w, h)
        }
        val tex = texture ?: return
        val a = alpha * Colors.alpha(primary) / 255f
        val p = Colors.withAlpha(primary, a)
        val s = Colors.withAlpha(secondary, a)
        Canvas.texture(tex.textureView, x + 0.5f, y + 2f, ceil(dw), ceil(dh), p, s, p, s)
    }
}
