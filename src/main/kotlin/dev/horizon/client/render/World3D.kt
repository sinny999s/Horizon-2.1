package dev.horizon.client.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.module.esp.StorageEsp
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import java.util.Optional
import java.util.OptionalDouble
import kotlin.math.abs

/**
 * The world renderer: modules queue boxes, faces and lines during Render3D; at
 * priority −10 the fills, outlines and depth-tested lines go straight onto the screen and the shader boxes into their
 * own target, which the client's 2D layer composites (with lines-mode NEW, the outlines too). All vertices are relative
 * to the camera; the model-view is the camera rotation only.
 */
object World3D {
    private val mc get() = Minecraft.getInstance()

    /** This frame's level projection and camera rotation. */
    val projection = Matrix4f()
    val view = Matrix4f()
    var camera: Vec3 = Vec3.ZERO
        private set
    private var frustum: Frustum? = null
    var partialTick = 0f
        private set

    private class Box(val box: AABB, val c: Int, val c2: Int)
    private class Face(val box: AABB, val color: Int, val side: Direction)
    private class Line(val start: Vec3, val end: Vec3, val c1: Int, val c2: Int, val noCull: Boolean)

    private val fills = ArrayList<Box>()
    private val shaderBoxes = ArrayList<Box>()
    private val outlines = ArrayList<Box>()
    private val faceFills = ArrayList<Face>()
    private val faceOutlines = ArrayList<Face>()
    private val lines = ArrayList<Line>()
    private val depthLines = ArrayList<Line>()

    private var shaderTarget: TextureTarget? = null
    private var linesTarget: TextureTarget? = null
    private var shaderPending = false
    private var linesPending = false

    // Created on first use: the GPU device doesn't exist yet when the client initialises.
    private val vertices by lazy { StagedVertexBuffer({ "HorizonClient world" }, 1 shl 16) }
    private val projectionBuffer by lazy { ProjectionMatrixBuffer("horizon world") }
    private var used = false
    private var ubo: MappableRingBuffer? = null

    fun init() {
        EventBus.on<Render3DEvent>(this, -10) { flush() }
    }

    /** From the GameRenderer hook: capture the frame and fire Render3D. */
    fun fire(cam: CameraRenderState, proj: Matrix4f) {
        if (mc.player == null || mc.level == null) return
        projection.set(proj)
        view.set(cam.viewRotationMatrix)
        camera = mc.gameRenderer.mainCamera().position()
        frustum = cam.cullFrustum
        partialTick = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        EventBus.post(Render3DEvent(Matrix4f(view), Matrix4f(projection), partialTick))
    }

    // ---- queue ----

    /** A box drawn into the shader target (only [c] is used). */
    fun shaderBox(box: AABB, c: Int) {
        shaderBoxes += Box(box, c, c)
    }

    /** A translucent box fill, [c] at the bottom and [c2] at the top, through walls. */
    fun fill(box: AABB, c: Int, c2: Int = c) {
        fills += Box(box, c, c2)
    }

    /** A box's 12 edges. */
    fun outline(box: AABB, c: Int, c2: Int = c) {
        outlines += Box(box, c, c2)
    }

    /** One face filled. */
    fun faceFill(box: AABB, side: Direction, c: Int) {
        faceFills += Face(box, c, side)
    }

    /** One face's 4 edges. */
    fun faceOutline(box: AABB, side: Direction, c: Int) {
        faceOutlines += Face(box, c, side)
    }

    /** A line drawn with the outlines; unless [noCull], skipped when its end is off-screen. */
    fun line(start: Vec3, end: Vec3, c1: Int, c2: Int = c1, noCull: Boolean = false) {
        lines += Line(start, end, c1, c2, noCull)
    }

    /** A depth-tested additive line. */
    fun depthLine(start: Vec3, end: Vec3, c1: Int, c2: Int = c1) {
        depthLines += Line(start, end, c1, c2, false)
    }

    fun isVisible(box: AABB) = frustum?.isVisible(box) ?: true

    // ---- drawing ----

    private fun VertexConsumer.v(x: Float, y: Float, z: Float, argb: Int): VertexConsumer =
        addVertex(x, y, z).setColor(argb shr 16 and 255, argb shr 8 and 255, argb and 255, argb ushr 24)

    private fun rel(b: AABB): FloatArray = floatArrayOf((b.minX - camera.x).toFloat(), (b.minY - camera.y).toFloat(),
        (b.minZ - camera.z).toFloat(), (b.maxX - camera.x).toFloat(), (b.maxY - camera.y).toFloat(), (b.maxZ - camera.z).toFloat())

    /** 6 quads, [c] on the bottom vertices and [c1] on the top ones; off-screen boxes are skipped. */
    fun boxFill(vc: VertexConsumer, box: AABB, c: Int, c1: Int) {
        if (!isVisible(box)) return
        val r = rel(box)
        val x0 = r[0]; val y0 = r[1]; val z0 = r[2]; val x1 = r[3]; val y1 = r[4]; val z1 = r[5]
        vc.v(x0, y0, z0, c); vc.v(x0, y1, z0, c1); vc.v(x1, y1, z0, c1); vc.v(x1, y0, z0, c)
        vc.v(x1, y0, z0, c); vc.v(x1, y1, z0, c1); vc.v(x1, y1, z1, c1); vc.v(x1, y0, z1, c)
        vc.v(x0, y0, z1, c); vc.v(x1, y0, z1, c); vc.v(x1, y1, z1, c1); vc.v(x0, y1, z1, c1)
        vc.v(x0, y0, z0, c); vc.v(x0, y0, z1, c); vc.v(x0, y1, z1, c1); vc.v(x0, y1, z0, c1)
        vc.v(x0, y1, z0, c1); vc.v(x0, y1, z1, c1); vc.v(x1, y1, z1, c1); vc.v(x1, y1, z0, c1)
        vc.v(x0, y0, z0, c); vc.v(x0, y0, z1, c); vc.v(x1, y0, z1, c); vc.v(x1, y0, z0, c)
    }

    /** The four corners of a face. */
    private fun faceCorners(box: AABB, side: Direction): Array<Vector3f> {
        val r = rel(box)
        val x0 = r[0]; val y0 = r[1]; val z0 = r[2]; val x1 = r[3]; val y1 = r[4]; val z1 = r[5]
        return when (side) {
            Direction.UP -> arrayOf(Vector3f(x0, y1, z0), Vector3f(x1, y1, z0), Vector3f(x1, y1, z1), Vector3f(x0, y1, z1))
            Direction.DOWN -> arrayOf(Vector3f(x0, y0, z0), Vector3f(x1, y0, z0), Vector3f(x1, y0, z1), Vector3f(x0, y0, z1))
            Direction.NORTH -> arrayOf(Vector3f(x0, y0, z0), Vector3f(x1, y0, z0), Vector3f(x1, y1, z0), Vector3f(x0, y1, z0))
            Direction.SOUTH -> arrayOf(Vector3f(x0, y0, z1), Vector3f(x1, y0, z1), Vector3f(x1, y1, z1), Vector3f(x0, y1, z1))
            Direction.WEST -> arrayOf(Vector3f(x0, y0, z0), Vector3f(x0, y0, z1), Vector3f(x0, y1, z1), Vector3f(x0, y1, z0))
            Direction.EAST -> arrayOf(Vector3f(x1, y0, z0), Vector3f(x1, y0, z1), Vector3f(x1, y1, z1), Vector3f(x1, y1, z0))
        }
    }

    /** 12 edges, bottom ring in [c], top ring in [c2], verticals from [c] to [c2]. */
    private fun boxOutline(vc: VertexConsumer, box: AABB, c: Int, c2: Int) {
        val r = rel(box)
        val x0 = r[0]; val y0 = r[1]; val z0 = r[2]; val x1 = r[3]; val y1 = r[4]; val z1 = r[5]
        vc.v(x0, y0, z0, c); vc.v(x0, y0, z1, c); vc.v(x0, y0, z1, c); vc.v(x1, y0, z1, c)
        vc.v(x1, y0, z1, c); vc.v(x1, y0, z0, c); vc.v(x1, y0, z0, c); vc.v(x0, y0, z0, c)
        vc.v(x0, y1, z0, c2); vc.v(x0, y1, z1, c2); vc.v(x0, y1, z1, c2); vc.v(x1, y1, z1, c2)
        vc.v(x1, y1, z1, c2); vc.v(x1, y1, z0, c2); vc.v(x1, y1, z0, c2); vc.v(x0, y1, z0, c2)
        vc.v(x0, y0, z0, c); vc.v(x0, y1, z0, c2); vc.v(x1, y0, z0, c); vc.v(x1, y1, z0, c2)
        vc.v(x1, y0, z1, c); vc.v(x1, y1, z1, c2); vc.v(x0, y0, z1, c); vc.v(x0, y1, z1, c2)
    }

    private fun segment(vc: VertexConsumer, l: Line) {
        if (!l.noCull && frustum?.pointInFrustum(l.end.x, l.end.y, l.end.z) == false) return
        vc.v((l.start.x - camera.x).toFloat(), (l.start.y - camera.y).toFloat(), (l.start.z - camera.z).toFloat(), l.c1)
        vc.v((l.end.x - camera.x).toFloat(), (l.end.y - camera.y).toFloat(), (l.end.z - camera.z).toFloat(), l.c2)
    }

    private fun isGl() = RenderSystem.getDevice().deviceInfo.backendName().contains("GL", ignoreCase = true)

    /**
     * One immediate draw with the level projection: onto [color] (with [depth] attached for depth-tested pipelines),
     * model-view [modelView]. [smooth] brackets it with GL line smoothing (OpenGL only).
     */
    fun draw(pipeline: RenderPipeline, color: GpuTextureView, depth: GpuTextureView?, modelView: Matrix4f, texture: GpuTextureView? = null,
             smooth: Boolean = false, build: (VertexConsumer) -> Unit) {
        used = true
        val d = vertices.appendDraw(pipeline.getVertexFormatBinding(0)!!, pipeline.primitiveTopology)
        build(vertices.getVertexBuilder(d))
        vertices.requestIndexCount(d)
        vertices.upload()
        val info = vertices.getExecuteInfo(d)
        if (info == null) {
            vertices.endDraw()
            return
        }
        RenderSystem.resizeAllAutoStorageIndexBuffers()
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE)
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(Matrix4f(modelView))
        RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "HorizonClient world" }, color, Optional.empty(), depth,
            OptionalDouble.empty()).use { pass ->
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline))
            pass.setVertexBuffer(0, info.vertexBuffer().slice())
            if (texture != null) pass.setUniform("Sampler0", texture, Canvas.sampler())
            pass.setIndexBuffer(info.indexBuffer(), info.indexType())
            val gl = smooth && isGl()
            if (gl) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH)
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
            }
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
            if (gl) GL11.glDisable(GL11.GL_LINE_SMOOTH)
        }
        RenderSystem.restoreProjectionMatrix()
        vertices.endDraw()
    }

    private fun main() = mc.gameRenderer.mainRenderTarget()

    /** A target the size of the screen, cleared to transparent before each use. */
    private fun ensure(t: TextureTarget?, label: String): TextureTarget {
        val m = main()
        val target = t ?: TextureTarget(label, m.width, m.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)
        if (target.width != m.width || target.height != m.height) target.resize(m.width, m.height)
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.colorTexture!!, Vector4f(0f, 0f, 0f, 0f),
            target.depthTexture!!, 1.0)
        return target
    }

    /** Runs at Render3D priority −10. */
    private fun flush() {
        PlaceBoxes.render()
        val main = main()
        val color = main.colorTextureView!!
        if (fills.isNotEmpty() || faceFills.isNotEmpty()) {
            draw(Pipelines.WORLD_FILL, color, null, view) { vc ->
                for (b in fills) boxFill(vc, b.box, b.c, b.c2)
                for (f in faceFills) for (p in faceCorners(f.box, f.side)) vc.v(p.x, p.y, p.z, f.color)
            }
        }
        if (outlines.isNotEmpty() || faceOutlines.isNotEmpty() || lines.isNotEmpty()) {
            val newMode = Modules.get<ClickGuiModule>().linesMode.value == ClickGuiModule.LinesMode.NEW
            val target = if (newMode) ensure(linesTarget, "horizon lines").also { linesTarget = it }.colorTextureView!! else color
            draw(Pipelines.LINES, target, null, view, smooth = true) { vc ->
                for (b in outlines) boxOutline(vc, b.box, b.c, b.c2)
                for (f in faceOutlines) {
                    val p = faceCorners(f.box, f.side)
                    for (i in 0..3) {
                        val a = p[i]
                        val b = p[(i + 1) % 4]
                        vc.v(a.x, a.y, a.z, f.color)
                        vc.v(b.x, b.y, b.z, f.color)
                    }
                }
                for (l in lines) segment(vc, l)
            }
            linesPending = newMode
        }
        if (depthLines.isNotEmpty()) {
            draw(Pipelines.WORLD_DEPTH_LINES, color, main.depthTextureView, view, smooth = true) { vc ->
                for (l in depthLines) segment(vc, l)
            }
        }
        if (shaderBoxes.isNotEmpty()) {
            val t = ensure(shaderTarget, "horizon shader boxes").also { shaderTarget = it }
            // Only the first colour counts for shader boxes.
            draw(Pipelines.WORLD_SHADER_FILL, t.colorTextureView!!, null, view) { vc ->
                for (b in shaderBoxes) boxFill(vc, b.box, b.c, b.c)
            }
            shaderPending = true
        }
        fills.clear()
        shaderBoxes.clear()
        outlines.clear()
        faceFills.clear()
        faceOutlines.clear()
        lines.clear()
        depthLines.clear()
    }

    /** The shader boxes, then NEW-mode lines, onto the screen (in the 2D layer, after the blur capture). */
    fun composite() {
        val p = mc.player ?: return
        val ring = ubo ?: MappableRingBuffer({ "horizon world composite UBO" }, 128 or 2, 256 * 4).also { ubo = it }
        var slot = 0
        fun pass(pipeline: RenderPipeline, src: TextureTarget) {
            val slice = ring.currentBuffer().slice(slot++ * 256L, 256L)
            slice.map(false, true).use { view ->
                val time = abs(Mth.lerp(partialTick, (p.tickCount - 1) / 48f, p.tickCount / 48f))
                Std140Builder.intoBuffer(view.data()).putVec2(1f / src.width, 1f / src.height).putFloat(time)
                    .putInt(Modules.get<StorageEsp>().shaderMode.value.ordinal)
            }
            RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "horizon world composite" }, main().colorTextureView!!,
                Optional.empty()).use { rp ->
                rp.setPipeline(RenderSystem.getCompiledPipeline(pipeline))
                RenderSystem.bindDefaultUniforms(rp)
                rp.setUniform("UBO", slice)
                rp.setUniform("Sampler0", src.colorTextureView!!, Canvas.sampler())
                rp.draw(3, 1, 0, 0)
            }
        }
        val s = shaderTarget
        if (s != null && shaderPending) pass(Pipelines.POST_MULTIDEFAULT, s)
        shaderPending = false
        val l = linesTarget
        if (l != null && linesPending) pass(Pipelines.POST_LINES, l)
        linesPending = false
        ring.rotate()
    }

    /**
     * A world point in GUI coordinates (x, y), or null when behind the camera or clipped.
     */
    fun project(pos: Vec3): FloatArray? {
        val rel = Vector4f((pos.x - camera.x).toFloat(), (pos.y - camera.y).toFloat(), (pos.z - camera.z).toFloat(), 1f)
        val eye = view.transform(Vector4f(rel))
        val clip = projection.transform(Vector4f(eye))
        if (clip.w <= 0f) return null
        val window = mc.window
        val w = window.width
        val h = window.height
        val nx = clip.x / clip.w
        val ny = clip.y / clip.w
        val nz = clip.z / clip.w
        val winX = (nx * 0.5f + 0.5f) * w
        val winY = (ny * 0.5f + 0.5f) * h
        val winZ = (1f + nz) * 0.5f
        if (!(winZ > 0f && winZ < 1f)) return null
        val sc = window.guiScale.toFloat()
        return floatArrayOf(winX / sc, (h - winY) / sc)
    }

    /** A label: centred, bold 20, shrinking with distance from the camera entity (gone at 10 blocks). */
    fun label(text: String, pos: Vec3, color: Int) {
        val s = project(pos) ?: return
        val cam = mc.cameraEntity ?: return
        val d = cam.position().distanceTo(pos).toFloat()
        val scale = 1f - Mth.clamp(d, 1f, 10f) / 10f
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(s[0], s[1])
        pose.scale(scale, scale)
        Fonts.bold20.drawCentered(text, 0f, 0f, color)
        pose.popMatrix()
        Canvas.flush(main())
    }

    /** Once a frame, from the 2D layer's end. */
    fun endFrame() {
        if (used) vertices.endFrame()
    }
}
