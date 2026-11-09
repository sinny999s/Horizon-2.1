package dev.horizon.client.render

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.HorizonClient
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.ShaderMode
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.Optional
import java.util.OptionalDouble
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Chams: entities or the hands are drawn into a mask target, and a post shader puts the
 * mask on the screen as glass (MIRROR), a fill with a soft edge (DEFAULT), a glow (BLOOM), a two-colour glow (DOUBLE)
 * or a camouflage pattern (CAMOUFLAGE). Shared by crystal-chams, hand-chams, player-esp and item-esp.
 */
object Chams {
    private val mc get() = Minecraft.getInstance()

    private fun id(path: String) = Identifier.fromNamespaceAndPath(HorizonClient.ID, path)

    private val ONE_SAMPLER = BindGroupLayout.builder()
        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("UBO", UniformType.UNIFORM_BUFFER)
        .build()
    private val TWO_SAMPLERS = BindGroupLayout.builder()
        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("UBO", UniformType.UNIFORM_BUFFER)
        .build()

    /** A full-screen pass with the `kawase.vsh` triangle as its vertex shader. Compiled on first use. */
    fun post(name: String, twoSamplers: Boolean, blend: BlendFunction = BlendFunction.TRANSLUCENT): RenderPipeline =
        RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/$name"))
            .withVertexShader(id("core/kawase"))
            .withFragmentShader(id("core/$name"))
            .withBindGroupLayout(if (twoSamplers) TWO_SAMPLERS else ONE_SAMPLER)
            .withColorTargetState(ColorTargetState(blend))
            .build()

    private val DEFAULT = post("chams_default", false)
    private val MIRROR = post("chams_mirror", true)
    private val BLOOM = post("chams_bloom", true)
    private val DOUBLEGLOW = post("chams_doubleglow", false)
    private val CAMOUFLAGE = post("chams_camouflage", false)

    private var ring: MappableRingBuffer? = null
    private var slot = 0
    private const val SLOTS = 32

    init {
        EventBus.on<Render3DEvent>(this, 1000) { rotate() }
    }

    private fun rotate() {
        ring?.rotate()
        slot = 0
    }

    /** One full-screen draw of [pipeline] onto [target] with a "UBO" block written by [ubo]. */
    fun pass(pipeline: RenderPipeline, target: GpuTextureView, samplers: Map<String, GpuTextureView>, ubo: (Std140Builder) -> Unit) {
        val r = ring ?: MappableRingBuffer({ "horizon chams UBO" }, 128 or 2, 256 * SLOTS).also { ring = it }
        if (slot == SLOTS) rotate()
        val slice = r.currentBuffer().slice(slot++ * 256L, 256L)
        slice.map(false, true).use { view -> ubo(Std140Builder.intoBuffer(view.data())) }
        RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "horizon chams" }, target, Optional.empty()).use { rp ->
            rp.setPipeline(RenderSystem.getCompiledPipeline(pipeline))
            RenderSystem.bindDefaultUniforms(rp)
            rp.setUniform("UBO", slice)
            for ((name, tex) in samplers) rp.setUniform(name, tex, Canvas.sampler())
            rp.draw(3, 1, 0, 0)
        }
    }

    private fun Std140Builder.color(c: Int): Std140Builder =
        putVec4((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, (c ushr 24) / 255f)

    /**
     * A target of the main target's size (new or resized), then cleared to transparent with depth 0 (the farthest, reversed-Z),
     * so whatever is drawn into it is not hidden by the level.
     */
    fun ensure(t: TextureTarget?, label: String): TextureTarget {
        val m = mc.gameRenderer.mainRenderTarget()
        val target = t ?: TextureTarget(label, m.width, m.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)
        if (target.width != m.width || target.height != m.height) target.resize(m.width, m.height)
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.colorTexture!!, Vector4f(0f, 0f, 0f, 0f),
            target.depthTexture!!, 0.0)
        return target
    }

    /** The bloom weights, rebuilt only when the radius changes (shared by every chams user). */
    private val weights = FloatArray(16)
    private var weightsRadius = 0

    /**
     * The chams of [frameBuffer] (the mask) onto the screen in [mode] (null: nothing).
     * [secondary] is BLOOM's scratch target. Colours are ARGB.
     */
    fun composite(mode: ShaderMode?, color: Int, frameBuffer: TextureTarget, secondary: TextureTarget, glass: Boolean, border: Boolean,
                  radius: Float, distance: Float, glowAlpha: Float, lineWidth: Float, colorMode: Int, color1: Int, color2: Int,
                  glowQuality: Int, glowRadius: Float, glowAlphaMult: Float, fillAlphaMult: Float, saturation: Float, brightness: Float,
                  alpha: Float) {
        val main = mc.gameRenderer.mainRenderTarget().colorTextureView!!
        val mask = frameBuffer.colorTextureView!!
        val texel = 1f / frameBuffer.width to 1f / frameBuffer.height
        when (mode) {
            null -> {}
            // The blurred screen (half a screen lower unless glass) through the mask.
            ShaderMode.MIRROR -> pass(MIRROR, main, mapOf("Sampler0" to mask, "Sampler1" to Blur.texture())) {
                it.putVec2(0f, if (glass) 0f else 0.5f).color(color).putFloat(if (border) 1f else 0f).putVec2(texel.first, texel.second)
                    .putFloat(alpha).putFloat(alpha)
            }
            ShaderMode.DEFAULT -> fill(color, lineWidth, frameBuffer)
            ShaderMode.BLOOM -> bloom(color, frameBuffer, secondary, radius, distance, glowAlpha, lineWidth)
            // Thin outline on.
            ShaderMode.DOUBLE -> pass(DOUBLEGLOW, main, mapOf("Sampler0" to mask)) {
                val p = mc.player!!
                val time = abs(Mth.lerp(World3D.partialTick, (p.tickCount - 1) / 6.0f, p.tickCount / 6.0f))
                it.putFloat(time).color(color1).color(color2).putInt(colorMode).putInt(glowQuality).putFloat(glowRadius).putFloat(glowAlphaMult)
                    .putInt(1).putFloat(fillAlphaMult).putVec2(texel.first, texel.second).putFloat(saturation).putFloat(brightness)
            }
            // The two colours and each twice darker.
            ShaderMode.CAMOUFLAGE -> pass(CAMOUFLAGE, main, mapOf("Sampler0" to mask)) {
                val p = mc.player!!
                val time = abs(Mth.lerp(World3D.partialTick, (p.tickCount - 1) / 1000.0f, p.tickCount / 1000.0f))
                it.putVec2(texel.first, texel.second).color(color1).color(color2).color(Colors.darker(Colors.darker(color1)))
                    .color(Colors.darker(Colors.darker(color2))).putFloat(lineWidth).putFloat(time)
            }
        }
    }

    /** The mask in [color] with a soft [lineWidth] edge. */
    private fun fill(color: Int, lineWidth: Float, frameBuffer: TextureTarget) {
        pass(DEFAULT, mc.gameRenderer.mainRenderTarget().colorTextureView!!, mapOf("Sampler0" to frameBuffer.colorTextureView!!)) {
            it.putVec2(1f / frameBuffer.width, 1f / frameBuffer.height).color(color).putFloat(lineWidth)
        }
    }

    /**
     * DEFAULT first, then a two-pass gaussian glow of the mask (horizontal into [secondary], vertical onto
     * the screen outside the mask). The step is one GUI unit (1 / scaled size), on purpose.
     */
    private fun bloom(color: Int, frameBuffer: TextureTarget, secondary: TextureTarget, radius: Float, distance: Float, glowAlpha: Float,
                      lineWidth: Float) {
        fill(color, lineWidth, frameBuffer)
        val r = radius.toInt()
        if (weightsRadius != r) {
            for (i in r downTo 0) weights[i] = (0.06649038007 * exp(-(i * i) / 72.0)).toFloat()
            weightsRadius = r
        }
        val w = Matrix4f().set(weights)
        val window = mc.window
        val tx = 1.0f / window.guiScaledWidth
        val ty = 1.0f / window.guiScaledHeight
        val mask = frameBuffer.colorTextureView!!
        // The shader reads Sampler0 only in the vertical pass, so binding the mask here is invisible and keeps the
        // pipeline's layout complete.
        pass(BLOOM, secondary.colorTextureView!!, mapOf("Sampler0" to mask, "Sampler1" to mask)) {
            it.putVec2(tx, ty).putVec2(distance, 0f).putFloat(r.toFloat()).putFloat(glowAlpha).putMat4f(w).color(color)
        }
        pass(BLOOM, mc.gameRenderer.mainRenderTarget().colorTextureView!!, mapOf("Sampler0" to mask, "Sampler1" to secondary.colorTextureView!!)) {
            it.putVec2(tx, ty).putVec2(0f, distance).putFloat(r.toFloat()).putFloat(glowAlpha).putMat4f(w).color(color)
        }
    }

    // ---- drawing into a mask ----

    private val projectionBuffer by lazy { ProjectionMatrixBuffer("horizon chams") }

    /** Draws everything [build] submits onto [target] (colour and depth), with the level projection. */
    fun renderInto(target: TextureTarget, build: (SubmitNodeStorage) -> Unit) {
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(World3D.projection), RenderSystem.getProjectionType())
        draw(target.colorTextureView!!, target.depthTextureView!!, build)
        RenderSystem.restoreProjectionMatrix()
    }

    /** Submits through [build], then draws the submissions in one render pass on [color]/[depth]. */
    fun draw(color: GpuTextureView, depth: GpuTextureView, build: (SubmitNodeStorage) -> Unit) {
        val storage = SubmitNodeStorage()
        build(storage)
        mc.gameRenderer.featureRenderDispatcher().prepareFrame(storage).use { frame ->
            RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "horizon chams mask" }, color, Optional.empty(), depth,
                OptionalDouble.empty()).use { pass ->
                RenderSystem.bindDefaultUniforms(pass)
                FeatureRenderDispatcher.renderAllFeatures(pass, frame)
            }
        }
    }

    /**
     * A mask of entities ([target]) and BLOOM's scratch target ([secondary]).
     * [active] is true while drawing into or out of them.
     */
    class EntityMask {
        var target: TextureTarget? = null
            private set
        var secondary: TextureTarget? = null
            private set
        var active = false
            private set

        /** Both targets cleared, then [crystals] (else players) near the camera drawn into [target]. */
        fun render(view: Matrix4f, crystals: Boolean, vDist: Int, hDist: Int) {
            active = true
            target = ensure(target, "horizon chams")
            secondary = ensure(secondary, "horizon chams 2")
            draw(view, crystals, vDist, hDist)
            active = false
        }

        /** Entities submitted with their real render states (shadows off, visible, not glowing). */
        private fun draw(view: Matrix4f, crystals: Boolean, vDist: Int, hDist: Int) {
            val level = mc.level ?: return
            val cam = mc.gameRenderer.mainCamera().position()
            val pt = World3D.partialTick
            val shadows = mc.options.entityShadows().get()
            mc.options.entityShadows().set(false)
            val state = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
            val dispatcher = mc.entityRenderDispatcher
            val stack = PoseStack()
            stack.last().pose().set(view)
            renderInto(target!!) { storage ->
                for (e in level.entitiesForRendering().filter { hypot(cam.x - it.x, cam.z - it.z) - hDist < 0 && abs(cam.y - it.y) - vDist < 0 }) {
                    if (!dispatcher.shouldRender(e, state.cullFrustum, cam.x, cam.y, cam.z, pt)) continue
                    if (e == mc.player && mc.options.cameraType.isFirstPerson) continue
                    if (e !is Player && !crystals) continue
                    if (e !is EndCrystal && crystals) continue
                    val x = Mth.lerp(pt.toDouble(), e.xOld, e.x) - cam.x
                    val y = Mth.lerp(pt.toDouble(), e.yOld, e.y) - cam.y
                    val z = Mth.lerp(pt.toDouble(), e.zOld, e.z) - cam.z
                    val invisible = e.isInvisible
                    val glowing = e.hasGlowingTag()
                    e.isInvisible = false
                    e.setGlowingTag(false)
                    try {
                        val rs = dispatcher.getRenderer(e).createRenderState(e, pt)
                        rs.outlineColor = 0
                        dispatcher.submit(rs, state, x, y, z, stack, storage)
                    } catch (_: Exception) {
                    }
                    e.isInvisible = invisible
                    e.setGlowingTag(glowing)
                }
            }
            mc.options.entityShadows().set(shadows)
        }

        /** The mask's chams on the screen (nothing until the first [render]). */
        fun composite(mode: ShaderMode, color: Int, glass: Boolean, border: Boolean, radius: Float, distance: Float, glowAlpha: Float,
                      lineWidth: Float, colorMode: Int, color1: Int, color2: Int, glowQuality: Int, glowRadius: Float, glowAlphaMult: Float,
                      fillAlphaMult: Float, saturation: Float, brightness: Float, alpha: Float) {
            val t = target ?: return
            val s = secondary ?: return
            active = true
            Chams.composite(mode, color, t, s, glass, border, radius, distance, glowAlpha, lineWidth, colorMode, color1, color2, glowQuality,
                glowRadius, glowAlphaMult, fillAlphaMult, saturation, brightness, alpha)
            active = false
        }
    }

    // ---- flat model geometry ----

    /**
     * Takes a model's (already transformed) vertices four at a time and emits each face as a quad
     * ([fill]) or its four edges as lines, all in [color]; everything else the model sends is ignored.
     */
    class FlatConsumer(private val out: VertexConsumer, private val fill: Boolean, private val color: Int) : VertexConsumer {
        private val xs = FloatArray(4)
        private val ys = FloatArray(4)
        private val zs = FloatArray(4)
        private var n = 0

        private fun v(i: Int) {
            out.addVertex(xs[i], ys[i], zs[i]).setColor(color)
        }

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            xs[n] = x
            ys[n] = y
            zs[n] = z
            n++
            if (n == 4) {
                if (fill) {
                    v(0); v(1); v(2); v(3)
                } else {
                    v(0); v(1); v(1); v(2); v(2); v(3); v(3); v(0)
                }
                n = 0
            }
            return this
        }

        override fun setColor(r: Int, g: Int, b: Int, a: Int) = this
        override fun setColor(argb: Int) = this
        override fun setUv(u: Float, v: Float) = this
        override fun setUv1(u: Int, v: Int) = this
        override fun setUv2(u: Int, v: Int) = this
        override fun setUv3(u: Float, v: Float) = this
        override fun setNormal(x: Float, y: Float, z: Float) = this
        override fun setLineWidth(w: Float) = this
    }

    /** Each vertex's position and UV, in one [color], unlit (the same as [Ghost]'s collector). */
    class SkinConsumer(private val out: VertexConsumer, private val color: Int) : VertexConsumer {
        private var x = 0f
        private var y = 0f
        private var z = 0f

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            this.x = x
            this.y = y
            this.z = z
            return this
        }

        override fun setUv(u: Float, v: Float): VertexConsumer {
            out.addVertex(x, y, z).setUv(u, v).setColor(color)
            return this
        }

        override fun setColor(r: Int, g: Int, b: Int, a: Int) = this
        override fun setColor(argb: Int) = this
        override fun setUv1(u: Int, v: Int) = this
        override fun setUv2(u: Int, v: Int) = this
        override fun setUv3(u: Float, v: Float) = this
        override fun setNormal(x: Float, y: Float, z: Float) = this
        override fun setLineWidth(w: Float) = this
    }

    /** Vanilla's player rotations without death roll, sleeping or shaking (the same as [Ghost]'s). */
    fun setupRotations(rs: AvatarRenderState, stack: PoseStack, bodyRot: Float) {
        if (rs.isFallFlying) {
            stack.rotateDegrees(Axis.YP, 180f - bodyRot)
            if (!rs.isAutoSpinAttack) stack.rotateDegrees(Axis.XP, rs.fallFlyingScale() * (-90f - rs.xRot))
            if (rs.shouldApplyFlyingYRot) stack.rotate(Axis.YP, rs.flyingYRot)
            return
        }
        if (rs.swimAmount > 0f) {
            stack.rotateDegrees(Axis.YP, 180f - bodyRot)
            stack.rotateDegrees(Axis.XP, Mth.lerp(rs.swimAmount, 0f, if (rs.isInWater) -90f - rs.xRot else -90f))
            if (rs.isVisuallySwimming) stack.translate(0f, -1f, 0.3f)
            return
        }
        stack.rotateDegrees(Axis.YP, 180f - bodyRot)
    }
}
