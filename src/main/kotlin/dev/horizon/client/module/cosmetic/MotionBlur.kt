package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.HorizonClient
import dev.horizon.client.compat.Sulkan
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.MotionBlurMath
import dev.horizon.client.render.World3D
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.Optional

/**
 * Per-pixel camera motion blur. Every pixel of the world is taken back, through the depth buffer, to where the camera
 * saw it one exposure ago, and the frame is averaged along that path. The exposure is a shutter angle of a 60 fps
 * frame, and the earlier camera is the recorded one closest to that long ago, so the blur covers the same motion at
 * any frame rate. It runs on the finished world before the hand is drawn (with a Sulkan pack, after its composite,
 * masking the hand out by its depth), so the hand, the HUD and screens stay sharp. Nothing is drawn while the camera
 * holds still.
 */
class MotionBlur : Module("motion-blur", Categories.COSMETIC) {
    enum class Motion { FULL, ROTATION }

    /** The share of a 60 fps frame the shutter is open: 180° is 1/120 s, 360° is 1/60 s. */
    private val shutterAngle = int("shutter-angle", 180, 10..360)

    /** The most samples per pixel; a short blur takes fewer (about one per pixel of length). */
    private val samples = int("samples", 16, 4..48)

    /** FULL: turning and moving (walking, falling, view bobbing). ROTATION: only the camera turning (and orbiting in third person). */
    private val motion = enum("motion", Motion.FULL)

    /** The longest blur, in percent of the screen width (a flick or a lag spike is cut to this). */
    private val maxBlur = int("max-blur", 15, 1..50)

    private class Frame(val time: Long, val view: Matrix4f, val projection: Matrix4f, val base: Matrix4f, val camera: Vec3, val anchor: Vec3)

    /** The last frames' cameras, oldest first. */
    private val history = ArrayDeque<Frame>()
    private var level: Any? = null
    private var cameraType: Any? = null
    private var width = 0
    private var height = 0

    // This frame's blur, planned at Render3D and drawn by the two GameRenderer hooks.
    private var planned = false
    private var useDepth = false
    private val reproject = Matrix4f()
    private var scale = 0f
    private var handDepth: GpuTextureView? = null
    private var frames = 0L

    private var colorCopy: GpuTexture? = null
    private var colorCopyView: GpuTextureView? = null
    private var depthCopy: GpuTexture? = null
    private var depthCopyView: GpuTextureView? = null
    private var ubo: MappableRingBuffer? = null

    init {
        instance = this
        on<Render3DEvent> { e -> plan(e.partialTick) }
    }

    override fun onDisable() {
        history.clear()
        planned = false
    }

    /** Records this frame's camera and works out the blur: the matrix back to the earlier camera and its scale. */
    private fun plan(partialTick: Float) {
        planned = false
        val main = mc.gameRenderer.mainRenderTarget()
        val now = System.nanoTime()
        val camera = World3D.camera
        val anchor = mc.cameraEntity?.getPosition(partialTick) ?: camera
        if (mc.level !== level || mc.options.cameraType !== cameraType || main.width != width || main.height != height) {
            history.clear()
            level = mc.level
            cameraType = mc.options.cameraType
            width = main.width
            height = main.height
        }
        val frame = Frame(now, Matrix4f(World3D.view), Matrix4f(World3D.projection), Matrix4f(World3D.baseProjection), camera, anchor)
        val exposure = MotionBlurMath.exposureNs(shutterAngle.value.toDouble())
        val i = MotionBlurMath.closest(history.size, now - exposure.toLong()) { history[it].time }
        val prev = if (i < 0) null else history[i]
        history.addLast(frame)
        if (history.size > 128) history.removeFirst()
        if (prev == null) return
        val span = now - prev.time
        if (span <= 0 || span > 250_000_000L) return
        val rotation = motion.value == Motion.ROTATION
        // ROTATION keeps only the camera's motion around the player (third person orbits), not the player's own.
        val move = if (rotation) camera.subtract(anchor).subtract(prev.camera.subtract(prev.anchor)) else camera.subtract(prev.camera)
        if (move.length() / (span / 1e9) > 150) {
            // A teleport, a respawn: start over from here.
            history.clear()
            history.addLast(frame)
            return
        }
        // ROTATION holds the projection too: no bobbing, hurt tilt or field-of-view change.
        reproject.set(MotionBlurMath.reproject(if (rotation) frame.base else frame.projection, frame.view,
            if (rotation) frame.base else prev.projection, prev.view, move.x, move.y, move.z,
            RenderSystem.getDevice().deviceInfo.isZZeroToOne))
        scale = (exposure / span).toFloat()
        useDepth = move.lengthSqr() > 1e-12
        if (!useDepth && MotionBlurMath.maxShiftPx(reproject, 0.5f, scale, width, height) < 0.25) return
        planned = true
    }

    /**
     * Before the hand: [view] is the depth the hand will be drawn into (just cleared). Without a shader pack the world
     * is finished here, so it is blurred now and the hand, its chams and the screen effects are drawn over it, sharp.
     * A Sulkan pack only puts its picture (hand included) in the main target when renderLevel returns: then the
     * world's depth is kept for [onAfterLevel], which masks the hand out by its depth.
     */
    private fun onBeforeHand(view: GpuTextureView) {
        if (!planned) return
        val main = mc.gameRenderer.mainRenderTarget()
        if (main.width != width || main.height != height) return
        if (Sulkan.present && Sulkan.drawingScene()) {
            handDepth = view
            if (useDepth) {
                ensureTextures(main, true)
                RenderSystem.getDevice().createCommandEncoder()
                    .copyTextureToTexture(main.depthTexture!!, depthCopy!!, 0, 0, 0, 0, 0, main.width, main.height)
            }
            return
        }
        planned = false
        blur(main, main.depthTextureView!!, null)
    }

    /** After renderLevel, when [onBeforeHand] left the blur for after a shader pack's composite. */
    private fun onAfterLevel() {
        val hand = handDepth ?: return
        handDepth = null
        if (!planned) return
        planned = false
        val main = mc.gameRenderer.mainRenderTarget()
        if (main.width != width || main.height != height) return
        blur(main, if (useDepth) depthCopyView!! else hand, hand)
    }

    /** The blur onto the main target: the world's [depth], and the hand's depth as a [mask] (null: nothing to leave out). */
    private fun blur(main: RenderTarget, depth: GpuTextureView, mask: GpuTextureView?) {
        ensureTextures(main, false)
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(main.colorTexture!!, colorCopy!!, 0, 0, 0, 0, 0, main.width, main.height)
        val ring = ubo ?: MappableRingBuffer({ "horizon motion blur UBO" }, 128 or 2, 256).also { ubo = it }
        val slice = ring.currentBuffer().slice(0L, 256L)
        slice.map(false, true).use { v ->
            Std140Builder.intoBuffer(v.data()).putMat4f(reproject).putVec2(main.width.toFloat(), main.height.toFloat())
                .putFloat(scale).putFloat(maxBlur.value / 100f * main.width).putFloat(samples.value.toFloat())
                .putFloat(((frames++ * 0.618034) % 1.0).toFloat()).putFloat(if (useDepth) 1f else 0f)
                .putFloat(if (mask != null) 1f else 0f)
        }
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        encoder.createRenderPass({ "horizon motion blur" }, main.colorTextureView!!, Optional.empty()).use { rp ->
            rp.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE))
            RenderSystem.bindDefaultUniforms(rp)
            rp.setUniform("UBO", slice)
            rp.setUniform("Sampler0", colorCopyView!!, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
            rp.setUniform("Sampler1", depth, nearest)
            rp.setUniform("Sampler2", mask ?: depth, nearest)
            rp.draw(3, 1, 0, 0)
        }
        ring.rotate()
    }

    /** The copy of the main color (and with [withDepth] of its depth), at its size and formats. */
    private fun ensureTextures(main: RenderTarget, withDepth: Boolean) {
        val color = main.colorTexture!!
        val depth = main.depthTexture!!
        val device = RenderSystem.getDevice()
        val c = colorCopy
        if (c == null || c.getWidth(0) != main.width || c.getHeight(0) != main.height || c.format != color.format) {
            colorCopyView?.close()
            c?.close()
            // usage 15: copy src/dst, texture binding, render attachment.
            colorCopy = device.createTexture({ "horizon motion blur color" }, 15, color.format, main.width, main.height, 1, 1)
            colorCopyView = device.createTextureView(colorCopy!!)
        }
        val d = depthCopy
        if (withDepth && (d == null || d.getWidth(0) != main.width || d.getHeight(0) != main.height || d.format != depth.format)) {
            depthCopyView?.close()
            d?.close()
            depthCopy = device.createTexture({ "horizon motion blur depth" }, 15, depth.format, main.width, main.height, 1, 1)
            depthCopyView = device.createTextureView(depthCopy!!)
        }
    }

    companion object {
        private var instance: MotionBlur? = null

        /** Compiled on first use, like the other full-screen passes. */
        private val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(HorizonClient.ID, "pipeline/motion_blur"))
            .withVertexShader(Identifier.fromNamespaceAndPath(HorizonClient.ID, "core/kawase"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(HorizonClient.ID, "core/motion_blur"))
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("UBO", UniformType.UNIFORM_BUFFER)
                .build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build()

        /** GameRenderer.render3dHud, before the hand's depth is cleared. */
        @JvmStatic
        fun beforeHand(handDepth: GpuTextureView) {
            instance?.takeIf { it.enabled }?.onBeforeHand(handDepth)
        }

        /** GameRenderer.render, right after renderLevel. */
        @JvmStatic
        fun afterLevel() {
            instance?.takeIf { it.enabled }?.onAfterLevel()
        }

        private var spin = 0.0
        private var spinAt = 0L

        /**
         * Test hook (ShotBot `call`): "spin <degrees per second>" turns the camera at that steady rate every frame (0 stops),
         * "fps <limit>" sets the frame rate limit (260 is unlimited).
         */
        @JvmStatic
        fun test(spec: String): String {
            val a = spec.trim().split(Regex("\\s+"))
            when (a[0]) {
                "spin" -> {
                    if (spinAt == 0L) EventBus.on<MouseTurnEvent>(this) {
                        val now = System.nanoTime()
                        val player = net.minecraft.client.Minecraft.getInstance().player
                        if (spin != 0.0 && player != null) player.turn(spin * (now - spinAt) / 1e9 / 0.15, 0.0)
                        spinAt = now
                    }
                    spinAt = System.nanoTime()
                    spin = a[1].toDouble()
                }
                "fps" -> net.minecraft.client.Minecraft.getInstance().options.framerateLimit().set(a[1].toInt())
            }
            return spec
        }
    }
}
