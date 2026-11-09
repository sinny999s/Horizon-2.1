package dev.horizon.client.render

import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.attributes.ViewBox
import dev.horizon.client.HorizonClient
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Vector2f
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An SVG drawn as a browser draws it, for screens matched to an HTML mockup. [Icon] has its
 * quirks (a (0.5, 2) offset, and a fractional size re-rasterised at 1x, so a 6.5 icon comes out at half resolution);
 * this one is rasterised at the screen's pixel size for any size and drawn exactly where asked, on whole pixels.
 * With [mask] only the shape is kept (every pixel white), as a CSS mask-image: the widget icons are #e8eaed.
 */
class VectorIcon(private val name: String, private val size: Float, private val mask: Boolean = false) {
    private var texture: DynamicTexture? = null
    private var px = 0
    private var scale = -1

    fun draw(x: Float, y: Float, color: Int, second: Int = color) {
        val s = Minecraft.getInstance().window.guiScale
        if (s != scale) {
            scale = s
            texture?.close()
            px = (size * s).roundToInt().coerceAtLeast(1)
            texture = raster(px)
        }
        val tex = texture ?: return
        var dx = x
        var dy = y
        val pose = Canvas.pose
        if (abs(pose.m00 - 1f) < 1e-4f && abs(pose.m11 - 1f) < 1e-4f) {
            val p = pose.transformPosition(Vector2f(x, y))
            dx += ((p.x * s).roundToInt() - p.x * s) / s
            dy += ((p.y * s).roundToInt() - p.y * s) / s
        }
        val d = px / s.toFloat()
        Canvas.texture(tex.textureView, dx, dy, d, d, color, second, color, second)
    }

    private fun raster(px: Int): DynamicTexture? = try {
        val doc = VectorIcon::class.java.getResourceAsStream("/assets/${HorizonClient.ID}/vector/$name.svg")!!.use { SVGLoader().load(it) }!!
        val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        doc.render(null, g, ViewBox(px.toFloat(), px.toFloat()))
        g.dispose()
        if (mask) for (yy in 0 until px) for (xx in 0 until px) img.setRGB(xx, yy, img.getRGB(xx, yy) or 0xFFFFFF)
        Textures.upload("horizon icon $name", img)
    } catch (t: Throwable) {
        HorizonClient.LOG.warn("Couldn't rasterise icon {}", name, t)
        null
    }
}
