package dev.horizon.client.render

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MappableRingBuffer
import java.util.Optional

/**
 * Background blur: three Kawase downscale passes (screen -> 1/2 -> 1/4 -> 1/8) and three upscale passes
 * back to full size, offset 5 half-texels of each pass's destination, NEAREST sampling. Captured at most once per
 * monitor refresh, before any client 2D is drawn; [Canvas.glass] samples the result under each fragment.
 */
object Blur {
    private val textures = arrayOfNulls<GpuTexture>(6)
    private val views = arrayOfNulls<GpuTextureView>(6)
    private var width = 0
    private var height = 0
    private var last = 0L
    private var ubo: MappableRingBuffer? = null

    /** The blurred screen. */
    fun texture(): GpuTextureView {
        if (views[0] == null) create()
        return views[0]!!
    }

    private fun create() {
        val window = Minecraft.getInstance().window
        val device = RenderSystem.getDevice()
        for (i in 0 until 6) {
            views[i]?.close()
            textures[i]?.close()
            val f = 1 / Math.pow(2.0, i.toDouble())
            // usage 15: copy src/dst, texture binding, render attachment.
            val t = device.createTexture({ "horizon-blur-$i" }, 15, GpuFormat.RGBA8_UNORM,
                maxOf(1, (window.width * f).toInt()), maxOf(1, (window.height * f).toInt()), 1, 1)
            textures[i] = t
            views[i] = device.createTextureView(t)
        }
    }

    private fun refreshRate(): Int {
        val mode = Minecraft.getInstance().window.findBestMonitor()?.currentMode
        return mode?.refreshRate?.toInt()?.takeIf { it > 0 } ?: 60
    }

    /** Called at the start of the client's 2D layer, in a world only. */
    fun capture() {
        val now = System.currentTimeMillis()
        if (now - last <= 1000.0 / refreshRate()) return
        if (views[0] == null) create()
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val ring = ubo ?: MappableRingBuffer({ "horizon blur UBO" }, 128 or 2, 256 * 8).also { ubo = it }
        var slot = 0
        fun pass(dst: GpuTextureView, src: GpuTextureView, pipeline: RenderPipeline) {
            val slice = ring.currentBuffer().slice(slot++ * 256L, 256L)
            slice.map(false, true).use { view ->
                Std140Builder.intoBuffer(view.data()).putVec2(0.5f / dst.getWidth(0), 0.5f / dst.getHeight(0)).putFloat(5f)
            }
            RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "horizon blur" }, dst, Optional.empty()).use { rp ->
                rp.setPipeline(RenderSystem.getCompiledPipeline(pipeline))
                RenderSystem.bindDefaultUniforms(rp)
                rp.setUniform("UBO", slice)
                rp.setUniform("Sampler0", src, Canvas.sampler())
                rp.draw(3, 1, 0, 0)
            }
        }
        pass(views[1]!!, main.colorTextureView!!, Pipelines.KAWASE_DOWN)
        pass(views[2]!!, views[1]!!, Pipelines.KAWASE_DOWN)
        pass(views[3]!!, views[2]!!, Pipelines.KAWASE_DOWN)
        pass(views[2]!!, views[3]!!, Pipelines.KAWASE_UP)
        pass(views[1]!!, views[2]!!, Pipelines.KAWASE_UP)
        pass(views[0]!!, views[1]!!, Pipelines.KAWASE_UP)
        ring.rotate()
        // A resize is picked up after the passes, so the first frame after it still uses the old sizes (on purpose).
        if (main.width != width || main.height != height) {
            width = main.width
            height = main.height
            create()
        }
        last = now
    }
}
