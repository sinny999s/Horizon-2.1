package dev.horizon.client.gui.menu

import dev.horizon.client.HorizonClient
import dev.horizon.client.render.Textures
import dev.horizon.client.render.Wordmark
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The look of the resource loading screen (vanilla's LoadingOverlay, through LoadingOverlayMixin), matching the
 * pre-launch splash: near black, the wordmark in the middle, its horizon line filling up with the progress. Grey only,
 * like the title screen. It is drawn in window pixels, so the wordmark stays sharp at any GUI scale, and only with
 * vanilla's GUI pipelines, which work before the resources have loaded.
 */
object LoadingScreen {
    const val BACKGROUND = 0xFF0E0E10.toInt()

    private val ID = Identifier.fromNamespaceAndPath(HorizonClient.ID, "loading/wordmark")
    private const val LINES_ALPHA = 0xA3 / 255f
    private const val TRACK_ALPHA = 0x14
    private var rasterW = 0
    private var rasterH = 0
    private var failed = false

    /** Where everything goes, in window pixels. */
    private class Layout(val x: Int, val y: Int, val w: Int, val h: Int, val lineX: Int, val lineY: Int, val lineW: Int, val lineH: Int)

    private fun layout(): Layout {
        val win = Minecraft.getInstance().window
        val w = min(win.width * 0.28f, win.height * 0.7f).roundToInt().coerceAtLeast(40)
        val h = (w * Wordmark.H / Wordmark.W).roundToInt()
        val x = (win.width - w) / 2
        val y = (win.height - h) / 2
        val lineX = x + (w * Wordmark.LINE_X0).roundToInt()
        val lineW = x + (w * Wordmark.LINE_X1).roundToInt() - lineX
        val lineH = max(2, (h / 64f).roundToInt())
        return Layout(x, y, w, h, lineX, y + (h * Wordmark.LINE_Y).roundToInt(), lineW, lineH)
    }

    /** Runs [draw] with the pose scaled from GUI units to window pixels. */
    private inline fun pixels(g: GuiGraphicsExtractor, draw: () -> Unit) {
        val s = Minecraft.getInstance().window.width.toFloat() / g.guiWidth()
        g.pose().pushMatrix()
        g.pose().scale(1f / s, 1f / s)
        try {
            draw()
        } finally {
            g.pose().popMatrix()
        }
    }

    /** The wordmark and the empty progress line, at [alpha]; in place of the Mojang Studios logo. */
    fun logo(g: GuiGraphicsExtractor, alpha: Float) {
        if (alpha <= 0f) return
        val l = layout()
        pixels(g) {
            if (texture(l.w, l.h)) g.blit(RenderPipelines.GUI_TEXTURED, ID, l.x, l.y, 0f, 0f, l.w, l.h, l.w, l.h, l.w, l.h, ARGB.white(alpha))
            g.fill(l.lineX, l.lineY, l.lineX + l.lineW, l.lineY + l.lineH, ARGB.color((TRACK_ALPHA * alpha).roundToInt(), 255, 255, 255))
        }
    }

    /** The progress line filled to [progress], at [fade]; in place of the progress bar. Drawn after [logo]. */
    fun progress(g: GuiGraphicsExtractor, progress: Float, fade: Float) {
        if (fade <= 0f) return
        val l = layout()
        val fw = (l.lineW * progress.coerceIn(0f, 1f)).roundToInt()
        if (fw <= 0) return
        pixels(g) {
            g.fill(l.lineX, l.lineY, l.lineX + fw, l.lineY + l.lineH, ARGB.color((255 * fade).roundToInt(), 0xF5, 0xF5, 0xF7))
        }
    }

    /** Rasterises the wordmark at [w] x [h] pixels when that size is new; false if it can't. */
    private fun texture(w: Int, h: Int): Boolean {
        if (failed) return false
        if (w == rasterW && h == rasterH) return true
        return try {
            Minecraft.getInstance().textureManager.register(ID, Textures.upload("horizon loading wordmark", Wordmark.raster(w, h, LINES_ALPHA)))
            rasterW = w
            rasterH = h
            true
        } catch (t: Throwable) {
            HorizonClient.LOG.warn("Couldn't draw the wordmark for the loading screen", t)
            failed = true
            false
        }
    }
}
