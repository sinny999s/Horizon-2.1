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
 * Immediate 2D renderer, in GUI units: every shape is drawn in call order. Draws are recorded during the frame and
 * executed by [flush] in one pass on the main target, keeping the order.
 * Each primitive's vertex order and -1/+2 quad padding decide where edges and corners land.
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
    private val projection = Projection()
    private val projectionBuffer = ProjectionMatrixBuffer("horizon")
    private val cmds = ArrayList<Cmd>()
    private val scissorStack = ArrayDeque<ScreenRectangle>()
    private val tmp = Vector2f()

    /** NEAREST + clamp, used for every texture. */
    fun sampler(): GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)

    private class Cmd(
        val draw: StagedVertexBuffer.Draw,
        val pipeline: RenderPipeline,
        val ubo: GpuBufferSlice?,
        val texture: GpuTextureView?,
        val scissor: IntArray?,
    )

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

    /** Starts a draw; null when the current scissor is empty (those draws are skipped). */
    private fun begin(pipeline: RenderPipeline, ubo: GpuBufferSlice?, texture: GpuTextureView?, scissor: IntArray? = currentScissor(),
                      merge: Boolean = false): VertexConsumer? {
        if (scissor != null && (scissor[2] == 0 || scissor[3] == 0)) return null
        val last = cmds.lastOrNull()
        if (merge && ubo == null && last != null && last.ubo == null && last.pipeline === pipeline && last.texture === texture &&
            last.scissor.contentEquals(scissor)) {
            return vertices.getVertexBuilder(last.draw)
        }
        val draw = vertices.appendDraw(pipeline.getVertexFormatBinding(0)!!, pipeline.primitiveTopology)
        cmds += Cmd(draw, pipeline, ubo, texture, scissor)
        return vertices.getVertexBuilder(draw)
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
        val u = ubo {
            it.putVec2(w, h).putFloat(radius).putFloat(borderWidth / 2f)
                .putVec4(r(outline), g(outline), b(outline), a(outline))
        }
        val vc = begin(Pipelines.RECT, u, null) ?: return
        vc.at(pose, x - 1, y - 1).setUv(0f, 0f).color(tl)
        vc.at(pose, x - 1, y + h + 2).setUv(0f, 1f).color(bl)
        vc.at(pose, x + w + 2, y + h + 2).setUv(1f, 1f).color(br)
        vc.at(pose, x + w + 2, y - 1).setUv(1f, 0f).color(tr)
    }

    /**
     * A rounded rect drawn as a browser draws one, for screens matched to an HTML mockup. Unlike [rect]
     * the box is exactly [w] by [h], [color]'s alpha counts once (rect squares it), the edge fades over one screen pixel,
     * and the border, [borderWidth] wide (not halved), lies inside the box.
     */
    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, borderWidth: Float = 0f, outline: Int = 0) {
        val u = ubo {
            it.putVec2(w + 2f, h + 2f).putFloat(minOf(radius, w / 2f, h / 2f)).putFloat(borderWidth)
                .putVec4(r(outline), g(outline), b(outline), a(outline) * alpha)
        }
        val vc = begin(Pipelines.BOX, u, null) ?: return
        vc.at(pose, x - 1, y - 1).setUv(0f, 0f).color(color)
        vc.at(pose, x - 1, y + h + 1).setUv(0f, 1f).color(color)
        vc.at(pose, x + w + 1, y + h + 1).setUv(1f, 1f).color(color)
        vc.at(pose, x + w + 1, y - 1).setUv(1f, 0f).color(color)
    }

    /** [box] with a colour per corner (a CSS linear-gradient along an edge or the diagonal), dithered. */
    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float, tl: Int, tr: Int, br: Int, bl: Int, borderWidth: Float = 0f, outline: Int = 0) {
        val u = ubo {
            it.putVec2(w + 2f, h + 2f).putFloat(minOf(radius, w / 2f, h / 2f)).putFloat(borderWidth)
                .putVec4(r(outline), g(outline), b(outline), a(outline) * alpha)
        }
        val vc = begin(Pipelines.BOX_DITHER, u, null) ?: return
        vc.at(pose, x - 1, y - 1).setUv(0f, 0f).color(tl)
        vc.at(pose, x - 1, y + h + 1).setUv(0f, 1f).color(bl)
        vc.at(pose, x + w + 1, y + h + 1).setUv(1f, 1f).color(br)
        vc.at(pose, x + w + 1, y - 1).setUv(1f, 0f).color(tr)
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
        val u = ubo {
            it.putVec2(qw, qh).putFloat(minOf(radius, w / 2f, h / 2f)).putFloat(inner).putFloat(outer)
                .putFloat(if (mask != null) minOf(mask[4], mask[2] / 2f, mask[3] / 2f) else -1f)
                .putVec2(if (mask != null) mask[0] + mask[2] / 2f - x - w / 2f else 0f, if (mask != null) mask[1] + mask[3] / 2f - y - h / 2f else 0f)
                .putVec2(mask?.get(2) ?: 0f, mask?.get(3) ?: 0f)
        }
        glowQuad(u, x - outer, y - outer, qw, qh, color)
    }

    /**
     * An ellipse of [color] at its middle, fading linearly to nothing at its edge (a CSS radial-gradient),
     * optionally cut to the rounded box [mask] (x, y, w, h, radius).
     */
    fun radial(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, mask: FloatArray? = null) {
        val u = ubo {
            it.putVec2(2f * rx, 2f * ry).putFloat(-1f).putFloat(0f).putFloat(0f)
                .putFloat(if (mask != null) minOf(mask[4], mask[2] / 2f, mask[3] / 2f) else -1f)
                .putVec2(if (mask != null) mask[0] + mask[2] / 2f - cx else 0f, if (mask != null) mask[1] + mask[3] / 2f - cy else 0f)
                .putVec2(mask?.get(2) ?: 0f, mask?.get(3) ?: 0f)
        }
        glowQuad(u, cx - rx, cy - ry, 2f * rx, 2f * ry, color)
    }

    private fun glowQuad(u: GpuBufferSlice, x: Float, y: Float, w: Float, h: Float, color: Int) {
        val vc = begin(Pipelines.GLOW, u, null) ?: return
        vc.at(pose, x, y).setUv(0f, 0f).color(color)
        vc.at(pose, x, y + h).setUv(0f, 1f).color(color)
        vc.at(pose, x + w, y + h).setUv(1f, 1f).color(color)
        vc.at(pose, x + w, y).setUv(1f, 0f).color(color)
    }

    /** The blurred screen in exactly the shape [box] draws for the same arguments (see blur_box.fsh). */
    fun blurBox(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        val u = ubo { it.putVec2(w + 2f, h + 2f).putFloat(minOf(radius, w / 2f, h / 2f)).putFloat(alpha) }
        val vc = begin(Pipelines.BLUR_BOX, u, Blur.texture()) ?: return
        vc.at(pose, x - 1, y - 1).setUv(0f, 0f).setColor(-1)
        vc.at(pose, x - 1, y + h + 1).setUv(0f, 1f).setColor(-1)
        vc.at(pose, x + w + 1, y + h + 1).setUv(1f, 1f).setColor(-1)
        vc.at(pose, x + w + 1, y - 1).setUv(1f, 0f).setColor(-1)
    }

    /** A flat quad, colours bottom-left, bottom-right, top-right, top-left (no anti-aliasing). */
    fun quad(x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int = c0, c2: Int = c0, c3: Int = c0) {
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
        val vc = begin(Pipelines.TEXTURED_RECT, u, texture) ?: return
        vc.at(pose, x - 1, y - 1).setUv(0f, 0f).setColor(-1)
        vc.at(pose, x - 1, y + h + 2).setUv(0f, 1f).setColor(-1)
        vc.at(pose, x + w + 2, y + h + 2).setUv(1f, 1f).setColor(-1)
        vc.at(pose, x + w + 2, y - 1).setUv(1f, 0f).setColor(-1)
    }

    /** A 1-pixel outline rectangle (GL lines, no anti-aliasing). */
    fun outline(x: Float, y: Float, w: Float, h: Float, color: Int) {
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
     * One glyph quad in the text's scaled pose [m] (pixel units). Consecutive glyphs with the same texture and scissor
     * merge into one draw (one draw per glyph page). [argb] is the already-truncated text colour.
     */
    fun glyph(texture: GpuTextureView, scissor: IntArray?, m: Matrix3x2f, x: Float, y: Float, w: Float, h: Float,
              u0: Float, v0: Float, u1: Float, v1: Float, argb: Int) {
        val vc = begin(Pipelines.COLORED_TEXTURE, null, texture, scissor, merge = true) ?: return
        vc.at(m, x, y + h).setUv(u0, v1).color(argb)
        vc.at(m, x + w, y + h).setUv(u1, v1).color(argb)
        vc.at(m, x + w, y).setUv(u1, v0).color(argb)
        vc.at(m, x, y).setUv(u0, v0).color(argb)
    }

    /** [glyph] in a horizontal gradient, [left] to [right] (WebText's gradient text). */
    fun glyph(texture: GpuTextureView, scissor: IntArray?, m: Matrix3x2f, x: Float, y: Float, w: Float, h: Float,
              u0: Float, v0: Float, u1: Float, v1: Float, left: Int, right: Int) {
        val vc = begin(Pipelines.COLORED_TEXTURE, null, texture, scissor, merge = true) ?: return
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
                if (c.texture != null) pass.setUniform("Sampler0", c.texture, sampler())
                pass.setIndexBuffer(info.indexBuffer(), info.indexType())
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
            }
        }
        RenderSystem.restoreProjectionMatrix()
        vertices.endDraw()
        cmds.clear()
    }

    /** Once a frame, after the last flush. */
    fun endFrame() {
        vertices.endFrame()
        uniforms.endFrame()
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
