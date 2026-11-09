package dev.horizon.client.combat

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.HorizonClient
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/** Bits the esp2 modules share: box/billboard helpers, the tracer origin and an enchantment check. */
object Esp2 {
    private val mc get() = Minecraft.getInstance()

    /** `horizon:textures/atlas.png` cells (u0, v0, u1, v1). */
    val CIRCLE = floatArrayOf(0.3906f, 0.0f, 0.6406f, 0.25f)
    val FIREFLY = floatArrayOf(0.8906f, 0.1875f, 0.9531f, 0.25f)
    val TRIANGLE1 = floatArrayOf(0.6406f, 0.25f, 0.7031f, 0.3125f)
    val TRIANGLE2 = floatArrayOf(0.7031f, 0.25f, 0.7656f, 0.3125f)
    val TRIANGLE3 = floatArrayOf(0.7656f, 0.25f, 0.8281f, 0.3125f)
    val TRIANGLE4 = floatArrayOf(0.8281f, 0.25f, 0.8906f, 0.3125f)
    val TRIANGLE5 = floatArrayOf(0.3906f, 0.3125f, 0.4531f, 0.3701f)

    /** `pos_color_alt_blend_depth`: depth-tested, depth-writing, additive. */
    val DEPTH_BOX: RenderPipeline = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(HorizonClient.ID, "pipeline/pos_color_alt_blend_depth"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withVertexShader(Identifier.fromNamespaceAndPath(HorizonClient.ID, "core/pos_color"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(HorizonClient.ID, "core/pos_color"))
        .withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
        .withCull(false)
        .build()

    /** [e]'s box at its interpolated position, 0.05 wider on each side and 0.15 taller. */
    fun renderBox(e: Entity): AABB {
        val pt = World3D.partialTick.toDouble()
        val x = Mth.lerp(pt, e.xo, e.x)
        val y = Mth.lerp(pt, e.yo, e.y)
        val z = Mth.lerp(pt, e.zo, e.z)
        val b = e.boundingBox
        return AABB(b.minX - e.x + x - 0.05, b.minY - e.y + y, b.minZ - e.z + z - 0.05,
            b.maxX - e.x + x + 0.05, b.maxY - e.y + y + 0.15, b.maxZ - e.z + z + 0.05)
    }

    /** Where ESP tracers start: 75 blocks straight ahead of the camera. */
    fun tracerStart(): Vec3 {
        val cam = mc.gameRenderer.mainCamera()
        return Vec3(0.0, 0.0, 75.0).xRot(-Math.toRadians(cam.xRot().toDouble()).toFloat())
            .yRot(-Math.toRadians(cam.yRot().toDouble()).toFloat()).add(cam.position())
    }

    /** Is [key] among [stack]'s enchantments. */
    fun hasEnchant(stack: ItemStack, key: ResourceKey<Enchantment>): Boolean {
        val holder = mc.level?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.get(key)?.orElse(null) ?: return false
        return EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet().contains(holder)
    }

    /**
     * Billboard matrix for a [size]-wide quad at [pos]: turned to the camera, [yawOff] added to the
     * yaw, tilted by [pitch] (the camera's pitch when 0), with the quad's corner moved to (-size/2, -size/2, -0.01).
     */
    fun billboard(pos: Vec3, yawOff: Float, pitch: Float, size: Float): Matrix4f {
        val cam = mc.gameRenderer.mainCamera()
        val rel = pos.subtract(cam.position())
        val ps = PoseStack()
        ps.rotateDegrees(Axis.XP, cam.xRot())
        ps.rotateDegrees(Axis.YP, cam.yRot() + yawOff + 180f)
        ps.translate(rel.x, rel.y, rel.z)
        ps.rotateDegrees(Axis.YP, -(cam.yRot() + yawOff))
        ps.rotateDegrees(Axis.XP, if (pitch == 0f) cam.xRot() else pitch)
        ps.translate(-size / 2.0, -size / 2.0, -0.01)
        return ps.last().pose()
    }

    /** The four corners of a billboard quad, as (x, y, u, v). */
    private fun corners(size: Float, uv: FloatArray) = arrayOf(
        floatArrayOf(0f, size, uv[0], uv[3]), floatArrayOf(size, size, uv[2], uv[3]),
        floatArrayOf(size, 0f, uv[2], uv[1]), floatArrayOf(0f, 0f, uv[0], uv[1]))

    /** A billboard without a polygon: one quad drawn straight away with the level projection. */
    fun drawBillboard(texture: GpuTextureView, pos: Vec3, yawOff: Float, pitch: Float, size: Float, color: Int, additive: Boolean,
                      uv: FloatArray) {
        val m = billboard(pos, yawOff, pitch, size)
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(if (additive) Pipelines.COLORED_TEXTURE_ADDITIVE else Pipelines.COLORED_TEXTURE, main.colorTextureView!!, null, m,
            texture) { vc ->
            for (c in corners(size, uv)) vc.addVertex(c[0], c[1], 0f).setUv(c[2], c[3]).setColor(color)
        }
    }

    /** A billboard into a polygon: the quad's corners are transformed now and drawn later by [Quads.draw]. */
    fun billboardInto(out: Quads, pos: Vec3, yawOff: Float, pitch: Float, size: Float, color: Int, uv: FloatArray) {
        val m = billboard(pos, yawOff, pitch, size)
        for (c in corners(size, uv)) out.add(m, c[0], c[1], c[2], c[3], color)
    }

    /** Textured quads whose corners are already in camera space. */
    class Quads {
        private val data = ArrayList<Float>()
        private val colors = ArrayList<Int>()
        private val t = Vector3f()

        fun add(m: Matrix4f, x: Float, y: Float, u: Float, v: Float, color: Int) {
            m.transformPosition(x, y, 0f, t)
            data += t.x; data += t.y; data += t.z; data += u; data += v
            colors += color
        }

        val isEmpty get() = colors.isEmpty()

        fun emit(vc: VertexConsumer) {
            for (i in colors.indices) {
                val o = i * 5
                vc.addVertex(data[o], data[o + 1], data[o + 2]).setUv(data[o + 3], data[o + 4]).setColor(colors[i])
            }
        }

        /** Draws a billboard polygon: model-view = camera rotation · Ry(180 − yaw) · Rx(−pitch). */
        fun draw(pipeline: RenderPipeline, depthTest: Boolean) {
            if (isEmpty) return
            val cam = mc.gameRenderer.mainCamera()
            val ps = PoseStack()
            ps.last().pose().set(World3D.view)
            ps.rotateDegrees(Axis.YP, -cam.yRot() + 180f)
            ps.rotateDegrees(Axis.XP, -cam.xRot())
            val main = mc.gameRenderer.mainRenderTarget()
            World3D.draw(pipeline, main.colorTextureView!!, if (depthTest) main.depthTextureView else null, ps.last().pose(),
                Skins.atlas()) { emit(it) }
        }
    }

    private val guiProjection = Projection()

    /**
     * 2D draws with a 4x4 model-view: the GUI's orthographic projection, [modelView] applied on the
     * GPU, drawn now (after what the 2D layer queued so far).
     */
    fun drawGui(pipeline: RenderPipeline, texture: GpuTextureView?, modelView: Matrix4f, build: (VertexConsumer) -> Unit) {
        val main = mc.gameRenderer.mainRenderTarget()
        Canvas.flush(main)
        val window = mc.window
        guiProjection.setupOrtho(-100f, 100f, window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat(), true)
        val saved = Matrix4f(World3D.projection)
        guiProjection.getMatrix(World3D.projection)
        World3D.draw(pipeline, main.colorTextureView!!, null, modelView, texture, build = build)
        World3D.projection.set(saved)
    }
}

/** A world particle: an atlas sprite that drifts, falls and fades over [life] ticks. */
class WorldParticle(
    private val sprite: FloatArray,
    var pos: Vec3,
    private val spin: Float,
    private val size: Float,
    private var life: Int,
    private var velocity: Vec3,
    private val color: Int,
    private val gravity: Float = 0.03f,
    private val drag: Float = 0.99f,
    private val fade: Boolean = true,
) {
    private val maxLife = life
    private var prevPos = pos
    private var angle = 0f
    private var prevAngle = 0f

    init {
        // Picks a random frame 0..3 here (unused), which advances the shared generator.
        Rng.int(0, 3)
    }

    /** One player tick; true once it is gone. */
    fun tick(): Boolean {
        life--
        if (life < 0) return true
        prevPos = pos
        pos = pos.add(velocity)
        velocity = velocity.scale(drag.toDouble())
        velocity = velocity.add(0.0, -gravity.toDouble(), 0.0)
        prevAngle = angle
        angle += spin
        return false
    }

    /** Its quad, pre-transformed by [stack] · [base], into [out]. */
    fun emit(stack: PoseStack, base: Quaternionf, out: Esp2.Quads) {
        stack.pushPose()
        val pt = World3D.partialTick
        val age = Mth.lerp(pt, life + 1f, life.toFloat())
        val k = if (fade) age / maxLife else 1f
        val s = size * k
        val cam = mc.gameRenderer.mainCamera()
        val c = if (color == BLACK) Theme.gradient(life * 2) else color
        val rel = Vec3(Mth.lerp(pt.toDouble(), prevPos.x, pos.x), Mth.lerp(pt.toDouble(), prevPos.y, pos.y),
            Mth.lerp(pt.toDouble(), prevPos.z, pos.z)).subtract(cam.position())
        stack.translate(rel.x, rel.y, rel.z)
        stack.last().pose().rotate(base)
        if (angle != 0f && prevAngle != 0f) stack.last().pose().rotate(Axis.ZP.rotationDegrees(Mth.lerp(pt, prevAngle, angle)))
        val m = Matrix4f(stack.last().pose()).translate(-s / 2f, -s / 2f, -0.01f)
        val col = if (!fade) c else PlaceBoxes.withAlpha(c, k)
        // The corner order and the (mirrored) UVs are on purpose.
        out.add(m, 0f, s, sprite[2], sprite[1], col)
        out.add(m, s, s, sprite[0], sprite[1], col)
        out.add(m, s, 0f, sprite[0], sprite[3], col)
        out.add(m, 0f, 0f, sprite[2], sprite[3], col)
        stack.popPose()
    }

    private companion object {
        val mc: Minecraft get() = Minecraft.getInstance()
        const val BLACK = 0xFF000000.toInt()
    }
}

/** The world particles some modules spawn, ticked each player update and drawn each frame. */
object WorldParticles {
    private val mc get() = Minecraft.getInstance()
    private val list = ArrayList<WorldParticle>()
    private var plugged = false

    fun add(p: WorldParticle) {
        if (!plugged) {
            plugged = true
            EventBus.on<Render3DEvent>(this) { render() }
            EventBus.on<PlayerUpdateEvent>(this) { list.removeIf { it.tick() } }
        }
        list += p
    }

    /** Every particle in view, one additive depth-tested draw. */
    private fun render() {
        if (list.isEmpty()) return
        val cam = mc.gameRenderer.mainCamera()
        val yaw = Math.toRadians(cam.yRot().toDouble()).toFloat()
        val yaw180 = Math.toRadians((cam.yRot() + 180f).toDouble()).toFloat()
        val pitch = Math.toRadians(cam.xRot().toDouble()).toFloat()
        val out = Esp2.Quads()
        val stack = PoseStack()
        stack.last().pose().rotate(Axis.XP.rotation(pitch))
        stack.last().pose().rotate(Axis.YP.rotation(yaw180))
        val base = Quaternionf().rotateYXZ(-yaw, pitch, 0f)
        // Tests the point: a box of zero size is the same test.
        for (p in list) if (World3D.isVisible(AABB(p.pos, p.pos))) p.emit(stack, base, out)
        if (out.isEmpty) return
        val modelView = Matrix4f(World3D.view).rotate(Axis.YP.rotation(-yaw180)).rotate(Axis.XP.rotation(-pitch))
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH, main.colorTextureView!!, main.depthTextureView, modelView,
            Skins.atlas()) { out.emit(it) }
    }
}
