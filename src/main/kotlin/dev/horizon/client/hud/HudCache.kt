package dev.horizon.client.hud

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import dev.horizon.client.config.Storage
import dev.horizon.client.render.Blur
import dev.horizon.client.render.Canvas
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import org.joml.Vector4f

/**
 * Options > Horizon > Performance > HUD Refresh Cap. The game draws far more frames than the monitor shows (500-750 a
 * second on a 320 Hz screen), and the HUD's widgets were laid out, shaped and drawn in every one: about 9% of the render
 * thread in a busy Bedwars scene. With this on, the widgets that draw only through the Canvas are drawn into a layer of
 * their own at most once per monitor refresh (like [Blur]'s capture), and the frames between put that layer on screen
 * with one quad. Widgets that ask vanilla for item renders or late draws ([HudWidget.live]) still draw every frame, over
 * the layer. Only while no screen is open: chat, menus and the HUD editor (hover, dragging) see the HUD as before.
 */
object HudCache {
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var width = 0
    private var height = 0
    private var last = 0L
    private var signature = 0
    private var dirty = true

    /** Test hooks: null follows the option; the layer's vertical flip; refreshes and frames since [report]. */
    private var forced: Boolean? = null
    /** The layer is sampled upside down (render targets are stored bottom row first). */
    var flipV = true
        private set
    private var refreshes = 0
    private var frames = 0

    fun active(): Boolean {
        val mc = Minecraft.getInstance()
        return (forced ?: Storage.shared.hudCache) && mc.gui.screen() == null
    }

    /** The layer must be redrawn this frame: a monitor refresh has passed, the widgets in it changed, or it's stale. */
    fun due(signature: Int): Boolean {
        frames++
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        if (dirty || signature != this.signature || main.width != width || main.height != height) {
            this.signature = signature
            return true
        }
        return System.nanoTime() - last >= 1_000_000_000L / Blur.refreshRate()
    }

    /** Redraws the layer with [draw] (the cached widgets' Canvas draws). */
    fun refresh(draw: () -> Unit) {
        val mc = Minecraft.getInstance()
        val main = mc.gameRenderer.mainRenderTarget()
        // What the overlay drew before the HUD goes onto the screen first, under the layer.
        Canvas.flush(main)
        if (view == null || main.width != width || main.height != height) create(main.width, main.height)
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture!!, CLEAR)
        draw()
        Canvas.flush(view!!)
        last = System.nanoTime()
        dirty = false
        refreshes++
    }

    /** Puts the layer on screen (queued on the Canvas, under the live widgets). */
    fun composite() {
        val v = view ?: return
        val window = Minecraft.getInstance().window
        Canvas.cacheLayer(v, window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat(), flipV)
    }

    /** Redraws the layer next frame. */
    fun invalidate() {
        dirty = true
    }

    private fun create(w: Int, h: Int) {
        view?.close()
        texture?.close()
        val device = RenderSystem.getDevice()
        // usage 15: copy src/dst, texture binding, render attachment (as Blur's targets).
        val t = device.createTexture({ "horizon-hud-cache" }, 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1)
        texture = t
        view = device.createTextureView(t)
        width = w
        height = h
        dirty = true
    }

    private val CLEAR = Vector4f(0f, 0f, 0f, 0f)

    /** Test hook: `call dev.horizon.client.hud.HudCache set true|false|option`. */
    @JvmStatic
    fun set(value: String) {
        forced = when (value.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        dirty = true
    }

    /** Test hook: `call dev.horizon.client.hud.HudCache flip true|false` (which way up the layer is sampled). */
    @JvmStatic
    fun flip(value: String) {
        flipV = value.trim().toBoolean()
    }

    /** Test hook: logs "HUDCACHE refreshes=<n> frames=<n>" since the last report. */
    @JvmStatic
    fun report() {
        println("HUDCACHE refreshes=$refreshes frames=$frames rate=${Blur.refreshRate()}")
        refreshes = 0
        frames = 0
    }
}
