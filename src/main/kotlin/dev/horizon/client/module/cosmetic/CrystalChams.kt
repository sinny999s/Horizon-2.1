package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.event.CrystalCreateEvent
import dev.horizon.client.event.CrystalRenderEvent
import dev.horizon.client.event.CrystalYOffsetEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.SetCrystalScaleEvent
import dev.horizon.client.mixin.cosmetic1.EndCrystalRendererAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode2
import dev.horizon.client.module.Module
import dev.horizon.client.module.RenderMode
import dev.horizon.client.module.ShaderMode
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.renderer.entity.EndCrystalRenderer
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * End crystals drawn as flat models ([default]), boxes ([box]), 2D frames ([frame])
 * and/or through the chams shader ([shader]); the real model can be hidden ([texture] off), scaled, grown in with age
 * and bob at its own [speed] and [amplitude].
 */
class CrystalChams : Module("crystal-chams", Categories.COSMETIC) {
    val types = group("types", GroupMode.BOOLS)
    val default = bool("default", false, parent = types.value)
    val box = bool("box", false, parent = types.value)
    val frame = bool("frame", false, parent = types.value)
    val shader = bool("shader", false, parent = types.value)
    val renderMode = enum("render-mode", RenderMode.BOTH) { default.value || box.value }
    val shaderMode = enum("shader-mode", ShaderMode.DEFAULT) { shader.value }
    val shaderColor = color("shader-color", Color(0xFFFFFFFF.toInt(), true)) { shader.value }
    val fill = color("fill", Color(0x78FFFFFF.toInt(), true))
    val line = color("line", Color(0xFFFFFFFF.toInt(), true))
    val glass = bool("glass", false) { shader.value && shaderMode.value == ShaderMode.MIRROR }
    val border = bool("border", false) { shader.value && shaderMode.value == ShaderMode.MIRROR }
    val radius = float("radius", 6.0f, 0.0f..15.0f) { shader.value && shaderMode.value == ShaderMode.BLOOM }
    val distance = float("distance", 1.3f, 0.0f..15.0f) { shader.value && shaderMode.value == ShaderMode.BLOOM }
    val glowAlpha = float("glow-alpha", 1.7f, 1.0f..5.0f) { shader.value && shaderMode.value == ShaderMode.BLOOM }
    val lineWidth = float("line-width", 4.0f, 1.0f..4.0f) { (shaderMode.value == ShaderMode.DEFAULT || shaderMode.value == ShaderMode.BLOOM) }
    val alpha = float("alpha", 0.2f, 0.0f..2.0f) { shaderMode.value == ShaderMode.MIRROR }
    val secondColor = color("second-color", Color(0xFFFFFFFF.toInt(), true)) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val colorMode = enum("color-mode", ColorMode2.RGB) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val glowQuality = int("glow-quality", 2, 1..4) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val glowRadius = float("glow-radius", 4.0f, 1.0f..16.0f) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val glowAlphaMultiplier = float("glow-alpha-multiplier", 2.0f, 0.0f..4.0f) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val fillAlphaMultiplier = float("fill-alpha-multiplier", 0.6f, 0.0f..1.0f) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val saturation = float("saturation", 0.7f, 0.0f..5.0f) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val brightness = float("brightness", 2.0f, 0.0f..45.0f) { shader.value && shaderMode.value == ShaderMode.DOUBLE }
    val maxHorizontalDistance = int("max-horizontal-distance", 64, 1..256)
    val maxVerticalDistance = int("max-vertical-distance", 32, 1..256)
    val texture = bool("texture", true)
    val scale = float("scale", 1.0f, 0.1f..2.0f)
    val ageScale = bool("age-scale", false) { scale.value != 1.0f }
    val speed = float("speed", 0.2f, 0.0f..1.0f)
    val amplitude = float("amplitude", 0.4f, 0.0f..1.0f)

    /** The crystals' chams mask. */
    private val mask = Chams.EntityMask()

    init {
        on<Render3DEvent> { e ->
            if (shader.value) mask.render(e.view, true, maxVerticalDistance.value, maxHorizontalDistance.value)
            if (box.value) boxes()
            if (default.value) models(e.view)
        }
        on<Render2DEvent> {
            if (frame.value) frames()
            if (shader.value) {
                mask.composite(shaderMode.value, shaderColor.value.rgb, glass.value, border.value, radius.value, distance.value, glowAlpha.value,
                    lineWidth.value, colorMode.value.ordinal, shaderColor.value.rgb, secondColor.value.rgb, glowQuality.value, glowRadius.value,
                    glowAlphaMultiplier.value, fillAlphaMultiplier.value, saturation.value, brightness.value, alpha.value)
            }
        }
        on<CrystalCreateEvent> { e -> if (ageScale.value) e.cancel() }
        on<CrystalRenderEvent> { e -> if (!texture.value && !mask.active) e.cancel() }
        on<SetCrystalScaleEvent> { e ->
            e.scale = scaleAt(e.age)
            e.cancel()
        }
        on<CrystalYOffsetEvent> { e ->
            e.offset = yOffset(e.age)
            e.cancel()
        }
    }

    /** [scale], grown in over the first 10 ticks (OUT_EXPO) with [ageScale]. */
    fun scaleAt(age: Float): Float {
        if (!ageScale.value) return scale.value
        return scale.value * Easing.OUT_EXPO.apply((age / 10.0).coerceIn(0.0, 1.0)).toFloat()
    }

    /** Vanilla's bob height formula with [speed] and [amplitude] instead of 0.2 and 0.4. */
    private fun yOffset(age: Float): Float {
        var f = sin((age * speed.value).toDouble()).toFloat() / 2.0f + 0.5f
        f = (f * f + f) * amplitude.value
        return f - 1.4f
    }

    /** The crystals within the distance settings of the camera entity. */
    private fun nearCrystals(): List<EndCrystal> {
        val level = mc.level ?: return emptyList()
        val origin = (mc.cameraEntity ?: mc.player ?: return emptyList()).position()
        return level.entitiesForRendering().filterIsInstance<EndCrystal>().filter {
            hypot(origin.x - it.x, origin.z - it.z) - maxHorizontalDistance.value < 0 && abs(origin.y - it.y) - maxVerticalDistance.value < 0
        }
    }

    /** Each crystal's bounding box filled and/or outlined (world renderer queue). */
    private fun boxes() {
        val mode = renderMode.value
        for (c in nearCrystals()) {
            if (mode == RenderMode.BOTH || mode == RenderMode.FILL) World3D.fill(c.boundingBox, fill.value.rgb)
            if (mode == RenderMode.BOTH || mode == RenderMode.OUTLINE) World3D.outline(c.boundingBox, line.value.rgb)
        }
    }

    /** Every crystal's model as flat faces (through walls, translucent) and/or 1-pixel edges. */
    private fun models(view: Matrix4f) {
        val crystals = nearCrystals()
        if (crystals.isEmpty()) return
        val mode = renderMode.value
        val color = mc.gameRenderer.mainRenderTarget().colorTextureView!!
        val pt = World3D.partialTick
        if (mode == RenderMode.BOTH || mode == RenderMode.FILL) {
            World3D.draw(Pipelines.WORLD_FILL, color, null, view) { vc ->
                for (c in crystals) model(view, c, Chams.FlatConsumer(vc, true, fill.value.rgb), scaleAt(c.tickCount + pt))
            }
        }
        if (mode == RenderMode.BOTH || mode == RenderMode.OUTLINE) {
            World3D.draw(Pipelines.LINES, color, null, view) { vc ->
                for (c in crystals) model(view, c, Chams.FlatConsumer(vc, false, line.value.rgb), scaleAt(c.tickCount + pt))
            }
        }
    }

    /**
     * The crystal's model at its (not interpolated) position, the camera rotation undone in the pose so
     * the draw's model-view applies it once.
     */
    private fun model(view: Matrix4f, crystal: EndCrystal, out: Chams.FlatConsumer, s: Float) {
        val renderer = mc.entityRenderDispatcher.getRenderer(crystal) as EndCrystalRenderer
        val model = (renderer as EndCrystalRendererAccessor).horizon_model()
        val rs = renderer.createRenderState(crystal, World3D.partialTick) as EndCrystalRenderState
        val cam = mc.entityRenderDispatcher.camera!!.position()
        val mainCam = mc.gameRenderer.mainCamera()
        val stack = PoseStack()
        stack.last().pose().set(view)
        stack.pushPose()
        stack.rotateDegrees(Axis.YP, -mainCam.yRot() + 180.0f)
        stack.rotateDegrees(Axis.XP, -mainCam.xRot())
        stack.translate(crystal.x - cam.x, crystal.y - cam.y, crystal.z - cam.z)
        stack.scale(s, s, s)
        stack.scale(2.0f, 2.0f, 2.0f)
        stack.translate(0.0f, -0.5f, 0.0f)
        model.setupAnim(rs)
        model.renderToBuffer(stack, out, 15728880, 0, -1)
        stack.popPose()
    }

    /** A 2D box (black-edged) around each crystal's on-screen render box, every crystal loaded. */
    private fun frames() {
        val level = mc.level ?: return
        for (c in level.entitiesForRendering()) {
            if (c !is EndCrystal) continue
            val b = renderBox(c)
            var bounds: FloatArray? = null
            for (corner in arrayOf(Vec3(b.minX, b.minY, b.minZ), Vec3(b.minX, b.maxY, b.minZ), Vec3(b.maxX, b.minY, b.minZ),
                Vec3(b.maxX, b.maxY, b.minZ), Vec3(b.minX, b.minY, b.maxZ), Vec3(b.minX, b.maxY, b.maxZ), Vec3(b.maxX, b.minY, b.maxZ),
                Vec3(b.maxX, b.maxY, b.maxZ))) {
                val p = World3D.project(corner) ?: continue
                val r = bounds ?: floatArrayOf(p[0], p[1], p[0], p[1]).also { bounds = it }
                r[0] = min(p[0], r[0])
                r[1] = min(p[1], r[1])
                r[2] = max(p[0], r[2])
                r[3] = max(p[1], r[3])
            }
            val r = bounds ?: continue
            espBox(r[0], r[1], r[2], r[3], line.value.rgb)
        }
    }

    /** The interpolated bounding box, 0.05 wider on each side and 0.15 taller. */
    private fun renderBox(e: Entity): AABB {
        val pt = World3D.partialTick.toDouble()
        val x = Mth.lerp(pt, e.xo, e.x)
        val y = Mth.lerp(pt, e.yo, e.y)
        val z = Mth.lerp(pt, e.zo, e.z)
        val bb = e.boundingBox
        return AABB(bb.minX - e.x + x - 0.05, bb.minY - e.y + y, bb.minZ - e.z + z - 0.05, bb.maxX - e.x + x + 0.05,
            bb.maxY - e.y + y + 0.15, bb.maxZ - e.z + z + 0.05)
    }

    /** Four black bars, then four half-unit bars in [color]. */
    private fun espBox(x: Float, y: Float, x2: Float, y2: Float, color: Int) {
        val black = 0xFF000000.toInt()
        bar(x - 1.0f, y, x + 0.5f, y2 + 0.5f, black)
        bar(x - 1.0f, y - 0.5f, x2 + 0.5f, y + 1.0f, black)
        bar(x2 - 1.0f, y, x2 + 0.5f, y2 + 0.5f, black)
        bar(x - 1.0f, y2 - 1.0f, x2 + 0.5f, y2 + 0.5f, black)
        bar(x - 0.5f, y, x, y2, color)
        bar(x, y2 - 0.5f, x2, y2, color)
        bar(x - 0.5f, y, x2, y + 0.5f, color)
        bar(x2 - 0.5f, y, x2, y2, color)
    }

    /** One flat quad from (x1, y1) to (x2, y2). */
    private fun bar(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) = Canvas.quad(x1, y1, x2 - x1, y2 - y1, color)
}
