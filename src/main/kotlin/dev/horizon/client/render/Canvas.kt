package dev.horizon.client.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.DynamicGpuDataStorage
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.StagedVertexBuffer
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fStack
import org.joml.Matrix4f
import org.joml.Vector2f
import java.nio.ByteBuffer
import java.util.Optional
import java.util.OptionalDouble

/**
 * Immediate 2D renderer, in GUI units: every shape comes out as if drawn in call order. Draws are recorded during the
 * frame and executed by [flush] in one pass on the main target.
 * Each primitive's vertex order and -1/+2 quad padding decide where edges and corners land.
 *
 * Batching: a shape joins an earlier draw of the same kind (pipeline, texture, scissor) when nothing recorded since
 * overlaps it, so it lands on exactly the same pixels in the same order as drawn one at a time. The rounded shapes keep
 * their parameters in an array per draw ([Cmd.params], `Shapes` in rect/box/glow/blur_box.fsh), each vertex saying
 * which shape it is by 2 per shape added to its U ([shapeIndex]). A HUD of a hundred plates, boxes and labels becomes a
 * handful of draws.
 */
object Canvas {
    private val ownPose = Matrix3x2fStack(32)

    /**
     * This frame's GUI graphics: vanilla things the GUI draws (item icons) go through it, sharing its
     * pose and scissor.
     */
    var graphics: GuiGraphicsExtractor? = null
        private set

    /** The GUI pose; every vertex is transformed by it. */
    var pose: Matrix3x2fStack = ownPose
        private set

    fun beginFrame(g: GuiGraphicsExtractor) {
        graphics = g
        pose = g.pose()
    }

    /** The global alpha, applied to vertex alpha (and glass-rect colours). */
    var alpha = 1f

    private val vertices = StagedVertexBuffer({ "HorizonClient canvas" }, 1 shl 16)
    private val uniforms = DynamicGpuDataStorageMapped<Ubo>("HorizonClient UBO", 112, 128, 256)

    /** A batch's shape parameters: [SHAPE_VEC4S] vec4s (the `Shapes` array in the shape shaders). */
    private const val SHAPE_VEC4S = 256
    private val shapeData = DynamicGpuDataStorageMapped<ShapeBlock>("HorizonClient shapes", SHAPE_VEC4S * 16, 128, 16)

    /** How many draws back a shape looks for one to join. */
    private const val MERGE_DEPTH = 48

    /** Off, every shape is its own draw, in call order (for comparing against batching, [testBatching]). */
    private var batching = true

    /** For ShotBot: batching "on" or "off", to screenshot the same frame both ways. */
    @JvmStatic
    fun testBatching(on: String) {
        batching = on != "off"
    }
    private val projection = Projection()
    private val projectionBuffer = ProjectionMatrixBuffer("horizon")
    private val cmds = ArrayList<Cmd>()
    private val scissorStack = ArrayDeque<ScreenRectangle>()
    private val tmp = Vector2f()

    /** NEAREST + clamp, used for every texture. */
    fun sampler(): GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)

    /**
     * One draw. [stride] says how it batches: -1 never (it has its own [ubo]); 0 with any later shape of the same
     * pipeline, texture and scissor; above 0 likewise, each shape adding [stride] vec4s to [params] (written at [flush]).
     * [rects] are its shapes' boxes in GUI units after the pose (a line's glyphs side by side as one), and [x0]..[y1] bound
     * them all.
     */
    private class Cmd(
        val draw: StagedVertexBuffer.Draw,
        val pipeline: RenderPipeline,
        val ubo: GpuBufferSlice?,
        val texture: GpuTextureView?,
        val scissor: IntArray?,
        val stride: Int,
    ) {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var params: FloatArray = if (stride > 0) FloatArray(stride * 4 * 8) else EMPTY
        var shapes = 0
        private var rects = FloatArray(32)
        private var rectCount = 0

        fun overlaps(ax0: Float, ay0: Float, ax1: Float, ay1: Float): Boolean {
            if (!(ax0 < x1 && ax1 > x0 && ay0 < y1 && ay1 > y0)) return false
            var i = 0
            val n = rectCount * 4
            while (i < n) {
                if (ax0 < rects[i + 2] && ax1 > rects[i] && ay0 < rects[i + 3] && ay1 > rects[i + 1]) return true
                i += 4
            }
            return false
        }

        fun include(ax0: Float, ay0: Float, ax1: Float, ay1: Float) {
            if (ax0 < x0) x0 = ax0
            if (ay0 < y0) y0 = ay0
            if (ax1 > x1) x1 = ax1
            if (ay1 > y1) y1 = ay1
            // The next glyph of a line, touching the last box and level with it, widens that box.
            if (rectCount > 0) {
                val j = (rectCount - 1) * 4
                if (ax0 <= rects[j + 2] + 1f && ax1 >= rects[j] && ay0 < rects[j + 3] && ay1 > rects[j + 1]) {
                    rects[j] = minOf(rects[j], ax0)
                    rects[j + 1] = minOf(rects[j + 1], ay0)
                    rects[j + 2] = maxOf(rects[j + 2], ax1)
                    rects[j + 3] = maxOf(rects[j + 3], ay1)
                    return
                }
            }
            if (rects.size < (rectCount + 1) * 4) rects = rects.copyOf(rects.size * 2)
            val j = rectCount * 4
            rects[j] = ax0
            rects[j + 1] = ay0
            rects[j + 2] = ax1
            rects[j + 3] = ay1
            rectCount++
        }

        fun addShape(src: FloatArray) {
            val n = stride * 4
            if (params.size < (shapes + 1) * n) params = params.copyOf(maxOf(params.size * 2, (shapes + 1) * n))
            System.arraycopy(src, 0, params, shapes * n, n)
            shapes++
        }

        companion object {
            val EMPTY = FloatArray(0)
        }
    }

    /** The next draw's bounds ([area]) and, for a batched shape, its index in its draw ([begin]). */
    private var ax0 = 0f
    private var ay0 = 0f
    private var ax1 = 0f
    private var ay1 = 0f
    private var shapeIndex = 0

    /** The next batched shape's parameters, [Cmd.stride] vec4s. */
    private val shapeParams = FloatArray(12)

    private fun params(vararg v: Float) {
        for (i in v.indices) shapeParams[i] = v[i]
    }

    /** The next draw covers the quad ([x0], [y0])-([x1], [y1]) under [m]. */
    private fun area(m: Matrix3x2f, x0: Float, y0: Float, x1: Float, y1: Float) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (k in 0 until 4) {
            m.transformPosition(if (k == 0 || k == 3) x0 else x1, if (k < 2) y0 else y1, tmp)
            if (tmp.x < minX) minX = tmp.x
            if (tmp.x > maxX) maxX = tmp.x
            if (tmp.y < minY) minY = tmp.y
            if (tmp.y > maxY) maxY = tmp.y
        }
        ax0 = minX
        ay0 = minY
        ax1 = maxX
        ay1 = maxY
    }

    /** One draw's "UBO" block, written in std140 layout. */
    private class Ubo(val writer: (Std140Builder) -> Unit) : DynamicGpuDataStorage.DynamicGpuData {
        override fun write(byteBuffer: ByteBuffer) = writer(Std140Builder.intoBuffer(byteBuffer))
    }

    private fun ubo(writer: (Std140Builder) -> Unit): GpuBufferSlice = uniforms.writeData(Ubo(writer))

    // ---- scissor: GuiGraphicsExtractor.enableScissor + a pixel stack ----

    /** The rect is truncated to ints, transformed by the pose, intersected. */
    fun pushScissor(x1: Float, y1: Float, x2: Float, y2: Float) {
        val x0 = x1.toInt()
        val y0 = y1.toInt()
        val rect = ScreenRectangle(x0, y0, x2.toInt() - x0, y2.toInt() - y0).transformAxisAligned(pose)
        val window = Minecraft.getInstance().window
        val parent = scissorStack.lastOrNull() ?: ScreenRectangle(0, 0, window.guiScaledWidth, window.guiScaledHeight)
        scissorStack.addLast(rect.intersection(parent) ?: ScreenRectangle.empty())
        graphics?.enableScissor(x0, y0, x2.toInt(), y2.toInt())
    }

    fun popScissor() {
        scissorStack.removeLastOrNull()
        graphics?.disableScissor()
    }

    /** The current scissor in framebuffer pixels, GL bottom-left origin (x, y, w, h); null when none. */
    fun currentScissor(): IntArray? {
        val r = scissorStack.lastOrNull() ?: return null
        val window = Minecraft.getInstance().window
        val sc = window.guiScale
        val w = window.width
        val h = window.height
        val x0 = (r.left() * sc).coerceIn(0, w)
        val y0 = (h - r.bottom() * sc).coerceIn(0, h)
        val x1 = (r.right() * sc).coerceIn(0, w)
        val y1 = (h - r.top() * sc).coerceIn(0, h)
        return intArrayOf(x0, y0, maxOf(x1 - x0, 0), maxOf(y1 - y0, 0))
    }

    // ---- recording ----

    /**
     * Starts a draw covering the [area] set just before; null when the current scissor is empty (those draws are
     * skipped). It joins an earlier draw it can batch with ([Cmd.stride]) when nothing since overlaps it; a batched shape
     * ([stride] above 0, parameters in [shapeParams]) gets its index in [shapeIndex].
     */
    private fun begin(pipeline: RenderPipeline, ubo: GpuBufferSlice?, texture: GpuTextureView?, scissor: IntArray? = currentScissor(),
                      stride: Int = if (ubo == null) 0 else -1): VertexConsumer? {
        if (scissor != null && (scissor[2] == 0 || scissor[3] == 0)) return null
        var target: Cmd? = null
        if (stride >= 0 && batching) {
            val cap = if (stride > 0) SHAPE_VEC4S / stride else Int.MAX_VALUE
            var i = cmds.size - 1
            var steps = 0
            while (i >= 0 && steps++ < MERGE_DEPTH) {
                val c = cmds[i]
                if (c.stride == stride && c.pipeline === pipeline && c.texture === texture && c.shapes < cap &&
                    c.scissor.contentEquals(scissor)) {
                    target = c
                    break
                }
                if (c.overlaps(ax0, ay0, ax1, ay1)) break
                i--
            }
        }
        val c = target ?: Cmd(vertices.appendDraw(pipeline.getVertexFormatBinding(0)!!, pipeline.primitiveTopology), pipeline, ubo,
            texture, scissor, stride).also { cmds += it }
        c.include(ax0, ay0, ax1, ay1)
        if (stride > 0) {
            shapeIndex = c.shapes
            c.addShape(shapeParams)
        }
        return vertices.getVertexBuilder(c.draw)
    }

    private fun VertexConsumer.at(m: Matrix3x2f, x: Float, y: Float, z: Float = 0f): VertexConsumer {
        m.transformPosition(x, y, tmp)
        return addVertex(tmp.x, tmp.y, z)
    }

    /** Alpha byte times the global alpha, truncated. */
    private fun VertexConsumer.color(argb: Int): VertexConsumer {
        val a = minOf(((argb ushr 24) * alpha).toInt(), 255)
        return setColor(argb shr 16 and 255, argb shr 8 and 255, argb and 255, a)
    }

    /**
     * A rounded rect with a colour per corner ([bl] bottom-left, [tl] top-left, [br] bottom-right,
     * [tr] top-right) and an optional border ([borderWidth] is halved). The border colour is not
     * affected by the global alpha.
     */
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, bl: Int, tl: Int, br: Int, tr: Int,
             borderWidth: Float = 0f, outline: Int = -1) {
        params(w, h, radius, borderWidth / 2f, r(outline), g(outline), b(outline), a(outline))
        area(pose, x - 1, y - 1, x + w + 2, y + h + 2)
        val vc = begin(Pipelines.RECT, null, null, stride = 2) ?: return
        val k = 2f * shapeIndex
        vc.at(pose, x - 1, y - 1).setUv(k, 0f).color(tl)
        vc.at(pose, x - 1, y + h + 2).setUv(k, 1f).color(bl)
        vc.at(pose, x + w + 2, y + h + 2).setUv(k + 1f, 1f).color(br)
        vc.at(pose, x + w + 2, y - 1).setUv(k + 1f, 0f).color(tr)
    }

    /**
     * A rounded rect drawn as a browser draws one, for screens matched to an HTML mockup. Unlike [rect]
     * the box is exactly [w] by [h], [color]'s alpha counts once (rect squares it), the edge fades over one screen pixel,
     * and the border, [borderWidth] wide (not halved), lies inside the box.
     */
    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, borderWidth: Float = 0f, outline: Int = 0) {
        params(w + 2f, h + 2f, minOf(radius, w / 2f, h / 2f), borderWidth, r(outline), g(outline), b(outline), a(outline) * alpha)
        area(pose, x - 1, y - 1, x + w + 1, y + h + 1)
        val vc = begin(Pipelines.BOX, null, null, stride = 2) ?: return
        val k = 2f * shapeIndex
        vc.at(pose, x - 1, y - 1).setUv(k, 0f).color(color)
        vc.at(pose, x - 1, y + h + 1).setUv(k, 1f).color(color)
        vc.at(pose, x + w + 1, y + h + 1).setUv(k + 1f, 1f).color(color)
        vc.at(pose, x + w + 1, y - 1).setUv(k + 1f, 0f).color(color)
    }

    /** [box] with a colour per corner (a CSS linear-gradient along an edge or the diagonal), dithered. */
    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float, tl: Int, tr: Int, br: Int, bl: Int, borderWidth: Float = 0f, outline: Int = 0) {
        params(w + 2f, h + 2f, minOf(radius, w / 2f, h / 2f), borderWidth, r(outline), g(outline), b(outline), a(outline) * alpha)
        area(pose, x - 1, y - 1, x + w + 1, y + h + 1)
        val vc = begin(Pipelines.BOX_DITHER, null, null, stride = 2) ?: return
        val k = 2f * shapeIndex
        vc.at(pose, x - 1, y - 1).setUv(k, 0f).color(tl)
        vc.at(pose, x - 1, y + h + 1).setUv(k, 1f).color(bl)
        vc.at(pose, x + w + 1, y + h + 1).setUv(k + 1f, 1f).color(br)
        vc.at(pose, x + w + 1, y - 1).setUv(k + 1f, 0f).color(tr)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, borderWidth: Float = 0f, outline: Int = -1) =
        rect(x, y, w, h, radius, color, color, color, color, borderWidth, outline)

    /**
     * The "glass" rect, the blurred screen tinted by [color] (mixed towards it by [mix]), with corner
     * radii [r1] top-left, [r2] bottom-left, [r3] bottom-right, [r4] top-right. The global alpha applies to the vertex
     * alpha and to both UBO colours.
     */
    fun glass(x: Float, y: Float, w: Float, h: Float, r1: Float, borderWidth: Float, mix: Float, opacity: Float, color: Int,
              outline: Int, r2: Float = r1, r3: Float = r1, r4: Float = r1) {
        val fill = Colors.withAlpha(color, Colors.alphaF(color) * alpha)
        val border = Colors.withAlpha(outline, Colors.alphaF(outline) * alpha)
        val vertexAlpha = minOf(((opacity.coerceIn(0f, 1f) * 255 + 0.5f).toInt() * alpha).toInt(), 255) / 255f
        val u = ubo {
            it.putVec4(r2, r1, r4, r3).putFloat(borderWidth / 2f)
                .putVec4(r(fill), g(fill), b(fill), a(fill))
                .putVec4(r(border), g(border), b(border), a(border))
                .putFloat(mix)
                .putVec2(w, h)
                .putFloat(vertexAlpha)
        }
        area(pose, x - 1, y - 1, x + w + 2, y + h + 2)
        val vc = begin(Pipelines.BLURRED_RECT, u, Blur.texture()) ?: return
        vc.at(pose, x - 1, y + h + 2).setUv(0f, 0f).setColor(-1)
        vc.at(pose, x - 1, y - 1).setUv(0f, 1f).setColor(-1)
        vc.at(pose, x + w + 2, y - 1).setUv(1f, 1f).setColor(-1)
        vc.at(pose, x + w + 2, y + h + 2).setUv(1f, 0f).setColor(-1)
    }

    /**
     * A soft light or shadow around a rounded box (see glow.fsh): [color] out to [inner] inside the box's
     * edge, gone by [outer] outside it, optionally cut to the rounded box [mask] (x, y, w, h, radius).
     */
    fun glow(x: Float, y: Float, w: Float, h: Float, radius: Float, inner: Float, outer: Float, color: Int, mask: FloatArray? = null) {
        val qw = w + 2f * outer
        val qh = h + 2f * outer
        params(qw, qh, minOf(radius, w / 2f, h / 2f), inner,
            outer, if (mask != null) minOf(mask[4], mask[2] / 2f, mask[3] / 2f) else -1f,
            if (mask != null) mask[0] + mask[2] / 2f - x - w / 2f else 0f, if (mask != null) mask[1] + mask[3] / 2f - y - h / 2f else 0f,
            mask?.get(2) ?: 0f, mask?.get(3) ?: 0f, 0f, 0f)
        glowQuad(x - outer, y - outer, qw, qh, color)
    }

    /**
     * An ellipse of [color] at its middle, fading linearly to nothing at its edge (a CSS radial-gradient),
     * optionally cut to the rounded box [mask] (x, y, w, h, radius).
     */
    fun radial(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, mask: FloatArray? = null) {
        params(2f * rx, 2f * ry, -1f, 0f,
            0f, if (mask != null) minOf(mask[4], mask[2] / 2f, mask[3] / 2f) else -1f,
            if (mask != null) mask[0] + mask[2] / 2f - cx else 0f, if (mask != null) mask[1] + mask[3] / 2f - cy else 0f,
            mask?.get(2) ?: 0f, mask?.get(3) ?: 0f, 0f, 0f)
        glowQuad(cx - rx, cy - ry, 2f * rx, 2f * ry, color)
    }

    /** A glow quad, its parameters already in [shapeParams]. */
    private fun glowQuad(x: Float, y: Float, w: Float, h: Float, color: Int) {
        area(pose, x, y, x + w, y + h)
        val vc = begin(Pipelines.GLOW, null, null, stride = 3) ?: return
        val k = 2f * shapeIndex
        vc.at(pose, x, y).setUv(k, 0f).color(color)
        vc.at(pose, x, y + h).setUv(k, 1f).color(color)
        vc.at(pose, x + w, y + h).setUv(k + 1f, 1f).color(color)
        vc.at(pose, x + w, y).setUv(k + 1f, 0f).color(color)
    }

    /** The blurred screen in exactly the shape [box] draws for the same arguments (see blur_box.fsh). */
    fun blurBox(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        params(w + 2f, h + 2f, minOf(radius, w / 2f, h / 2f), alpha)
        area(pose, x - 1, y - 1, x + w + 1, y + h + 1)
        val vc = begin(Pipelines.BLUR_BOX, null, Blur.texture(), stride = 1) ?: return
        val k = 2f * shapeIndex
        vc.at(pose, x - 1, y - 1).setUv(k, 0f).setColor(-1)
        vc.at(pose, x - 1, y + h + 1).setUv(k, 1f).setColor(-1)
        vc.at(pose, x + w + 1, y + h + 1).setUv(k + 1f, 1f).setColor(-1)
        vc.at(pose, x + w + 1, y - 1).setUv(k + 1f, 0f).setColor(-1)
    }

    /**
     * The blurred screen in the plain quad ([x], [y], [w], [h]), [top] of it (0..1) along its top edge and [bottom]
     * along its bottom: no rounding and no soft edge, so quads side by side leave no seam (see [blurBox]'s shader: with
     * every corner at the box's middle, all of the quad is inside it).
     */
    fun blurQuad(x: Float, y: Float, w: Float, h: Float, top: Float, bottom: Float) {
        params(8f, 8f, 0f, alpha)
        area(pose, x, y, x + w, y + h)
        val vc = begin(Pipelines.BLUR_BOX, null, Blur.texture(), stride = 1) ?: return
        val u = 2f * shapeIndex + 0.5f
        val t = (top.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b = (bottom.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        vc.at(pose, x, y).setUv(u, 0.5f).setColor(255, 255, 255, t)
        vc.at(pose, x, y + h).setUv(u, 0.5f).setColor(255, 255, 255, b)
        vc.at(pose, x + w, y + h).setUv(u, 0.5f).setColor(255, 255, 255, b)
        vc.at(pose, x + w, y).setUv(u, 0.5f).setColor(255, 255, 255, t)
    }

    /** A flat quad, colours bottom-left, bottom-right, top-right, top-left (no anti-aliasing). */
    fun quad(x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int = c0, c2: Int = c0, c3: Int = c0) {
        area(pose, x, y, x + w, y + h)
        val vc = begin(Pipelines.POS_COLOR, null, null) ?: return
        vc.at(pose, x, y + h).color(c0)
        vc.at(pose, x + w, y + h).color(c1)
        vc.at(pose, x + w, y).color(c2)
        vc.at(pose, x, y).color(c3)
    }

    /**
     * [texture] clipped to a rounded rect, tinted by [color] (mixed towards it by [mix]), with an
     * optional border. Not affected by the global alpha.
     */
    fun texturedRect(texture: GpuTextureView, x: Float, y: Float, w: Float, h: Float, radius: Float, borderWidth: Float, mix: Float,
                     opacity: Float, color: Int, outline: Int) {
        val u = ubo {
            it.putVec2(w, h).putFloat(radius).putFloat(opacity)
                .putVec4(r(color), g(color), b(color), a(color))
                .putFloat(borderWidth / 2f)
                .putVec4(r(outline), g(outline), b(outline), a(outline))
                .putFloat(mix)
        }
        area(pose, x - 1, y - 1, x + w + 2, y + h + 2)
        val vc = begin(Pipelines.TEXTURED_RECT, u, texture) ?: return
        vc.at(pose, x - 1, y - 1).setUv(0f, 0f).setColor(-1)
        vc.at(pose, x - 1, y + h + 2).setUv(0f, 1f).setColor(-1)
        vc.at(pose, x + w + 2, y + h + 2).setUv(1f, 1f).setColor(-1)
        vc.at(pose, x + w + 2, y - 1).setUv(1f, 0f).setColor(-1)
    }

    /** A 1-pixel outline rectangle (GL lines, no anti-aliasing). */
    fun outline(x: Float, y: Float, w: Float, h: Float, color: Int) {
        area(pose, x - 1, y - 1, x + w + 1, y + h + 1)
        val vc = begin(Pipelines.LINES, null, null) ?: return
        vc.at(pose, x, y).color(color)
        vc.at(pose, x, y + h).color(color)
        vc.at(pose, x, y + h).color(color)
        vc.at(pose, x + w, y + h).color(color)
        vc.at(pose, x + w, y + h).color(color)
        vc.at(pose, x + w, y).color(color)
        vc.at(pose, x + w, y).color(color)
        vc.at(pose, x, y).color(color)
    }

    /** A 1-pixel line. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        area(pose, minOf(x1, x2) - 1, minOf(y1, y2) - 1, maxOf(x1, x2) + 1, maxOf(y1, y2) + 1)
        val vc = begin(Pipelines.LINES, null, null) ?: return
        vc.at(pose, x1, y1).color(color)
        vc.at(pose, x2, y2).color(color)
    }

    /**
     * A textured quad tinted by vertex colours ([c1] bottom-left, [c2] bottom-right, [c3] top-right,
     * [c4] top-left), uv in 0..1. [additive] blends with (SRC_ALPHA, ONE).
     */
    fun texture(texture: GpuTextureView, x: Float, y: Float, w: Float, h: Float, c1: Int, c2: Int, c3: Int, c4: Int,
                u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f, additive: Boolean = false) {
        area(pose, x, y, x + w, y + h)
        val vc = begin(if (additive) Pipelines.COLORED_TEXTURE_ADDITIVE else Pipelines.COLORED_TEXTURE, null, texture) ?: return
        vc.at(pose, x, y + h).setUv(u0, v1).color(c1)
        vc.at(pose, x + w, y + h).setUv(u1, v1).color(c2)
        vc.at(pose, x + w, y).setUv(u1, v0).color(c3)
        vc.at(pose, x, y).setUv(u0, v0).color(c4)
    }

    /**
     * [count] textured quads with any four corners, in one draw: [xy] and [uv] 8 floats per quad
     * (corners in order), [tints] one colour per quad (the locker's 3D cosmetic thumbnails).
     */
    fun texturedQuads(texture: GpuTextureView, xy: FloatArray, uv: FloatArray, tints: IntArray, count: Int) {
        if (count == 0) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (q in 0 until count * 4) {
            minX = minOf(minX, xy[q * 2])
            maxX = maxOf(maxX, xy[q * 2])
            minY = minOf(minY, xy[q * 2 + 1])
            maxY = maxOf(maxY, xy[q * 2 + 1])
        }
        area(pose, minX, minY, maxX, maxY)
        val vc = begin(Pipelines.COLORED_TEXTURE, null, texture) ?: return
        for (q in 0 until count) {
            val c = tints[q]
            for (k in 0 until 4) {
                val o = q * 8 + k * 2
                vc.at(pose, xy[o], xy[o + 1]).setUv(uv[o], uv[o + 1]).color(c)
            }
        }
    }

    /**
     * One glyph quad in the text's scaled pose [m] (pixel units). Glyphs batch like everything else (one draw per glyph
     * page, wherever nothing overlaps them in between). [argb] is the already-truncated text colour.
     */
    fun glyph(texture: GpuTextureView, scissor: IntArray?, m: Matrix3x2f, x: Float, y: Float, w: Float, h: Float,
              u0: Float, v0: Float, u1: Float, v1: Float, argb: Int) {
        area(m, x, y, x + w, y + h)
        val vc = begin(Pipelines.COLORED_TEXTURE, null, texture, scissor) ?: return
        vc.at(m, x, y + h).setUv(u0, v1).color(argb)
        vc.at(m, x + w, y + h).setUv(u1, v1).color(argb)
        vc.at(m, x + w, y).setUv(u1, v0).color(argb)
        vc.at(m, x, y).setUv(u0, v0).color(argb)
    }

    /** [glyph] in a horizontal gradient, [left] to [right] (WebText's gradient text). */
    fun glyph(texture: GpuTextureView, scissor: IntArray?, m: Matrix3x2f, x: Float, y: Float, w: Float, h: Float,
              u0: Float, v0: Float, u1: Float, v1: Float, left: Int, right: Int) {
        area(m, x, y, x + w, y + h)
        val vc = begin(Pipelines.COLORED_TEXTURE, null, texture, scissor) ?: return
        vc.at(m, x, y + h).setUv(u0, v1).color(left)
        vc.at(m, x + w, y + h).setUv(u1, v1).color(right)
        vc.at(m, x + w, y).setUv(u1, v0).color(right)
        vc.at(m, x, y).setUv(u0, v0).color(left)
    }

    // ---- execution ----

    /** Draws everything recorded so far onto [target] (the main render target). */
    fun flush(target: RenderTarget) {
        if (cmds.isEmpty()) return
        val window = Minecraft.getInstance().window
        for (c in cmds) vertices.requestIndexCount(c.draw)
        vertices.upload()
        RenderSystem.resizeAllAutoStorageIndexBuffers()
        // Projection: ortho(0, guiW, guiH, 0, -100, 100) and model-view = the pose (already applied to the vertices).
        projection.setupOrtho(-100f, 100f, window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat(), true)
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC)
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(Matrix4f())
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "HorizonClient GUI" }, target.colorTextureView!!, Optional.empty(), null, OptionalDouble.empty()
        ).use { pass ->
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            for (c in cmds) {
                val info = vertices.getExecuteInfo(c.draw) ?: continue
                pass.setPipeline(RenderSystem.getCompiledPipeline(c.pipeline))
                pass.setVertexBuffer(0, info.vertexBuffer().slice())
                val s = c.scissor
                if (s != null) pass.enableScissor(s[0], s[1], s[2], s[3]) else pass.disableScissor()
                if (c.ubo != null) pass.setUniform("UBO", c.ubo)
                else if (c.stride > 0) pass.setUniform("UBO", shapeUbo(c))
                if (c.texture != null) pass.setUniform("Sampler0", c.texture, sampler())
                pass.setIndexBuffer(info.indexBuffer(), info.indexType())
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
            }
        }
        RenderSystem.restoreProjectionMatrix()
        vertices.endDraw()
        cmds.clear()
    }

    /** A batch's shape parameters, copied as they are (vec4s back to back, as std140 lays out a vec4 array). */
    private class ShapeBlock(val params: FloatArray, val count: Int) : DynamicGpuDataStorage.DynamicGpuData {
        override fun write(byteBuffer: ByteBuffer) {
            byteBuffer.asFloatBuffer().put(params, 0, count)
        }
    }

    /** A batch's shape parameters as its UBO: [Cmd.shapes] times [Cmd.stride] vec4s. */
    private fun shapeUbo(c: Cmd): GpuBufferSlice = shapeData.writeData(ShapeBlock(c.params, c.shapes * c.stride * 4))

    /** Once a frame, after the last flush. */
    fun endFrame() {
        vertices.endFrame()
        uniforms.endFrame()
        shapeData.endFrame()
        Atlas.endFrame()
        scissorStack.clear()
        ownPose.clear()
        pose = ownPose
        graphics = null
        alpha = 1f
    }

    private fun r(c: Int) = (c shr 16 and 255) / 255f
    private fun g(c: Int) = (c shr 8 and 255) / 255f
    private fun b(c: Int) = (c and 255) / 255f
    private fun a(c: Int) = (c ushr 24) / 255f
}
