package dev.horizon.client.gui.modern

import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.lunar.GeoModel
import dev.horizon.client.lunar.LunarCosmetics
import dev.horizon.client.lunar.LunarEmotes
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.PlayerModelType
import dev.horizon.client.render.Canvas
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The locker's tile pictures for model cosmetics (hats, glasses, wings, backpacks, pets...): the
 * model alone, turned three-quarters (from behind for things worn on the back), drawn flat with [Canvas] back to front
 * and shaded by its faces' normals. A hovered tile turns slowly. Sized by the model's bounds around its vertical axis,
 * so turning doesn't change its size.
 */
object CosmeticThumbs {
    private const val PITCH = 18f
    private const val QF = GeoModel.QUAD_FLOATS

    /** Corners in a unit box (0..1), uv, a shade per quad, back to front. */
    private class Projected(val xy: FloatArray, val uv: FloatArray, val tints: IntArray, val count: Int)

    /**
     * An emote's picture, the frame it was fitted to (centre x, y, z and size) so that it plays in place when hovered,
     * and where the still figure lies in the unit box (its middle and its size), to fill the tile with it.
     */
    private class EmoteThumb(val still: Projected, val fit: FloatArray, val cx: Float, val cy: Float, val w: Float, val h: Float)

    /** Each emote's picture, by emote and skin shape. */
    private val emotes = HashMap<Long, EmoteThumb>()

    /** New emote pictures left to make this frame: each poses and sorts a player mesh. */
    private var budget = 0

    /** Once a frame, before the tiles are drawn. */
    fun frame() {
        budget = 3
    }

    /**
     * Draws emote [id] in the box as your own player in the emote's most telling pose, or playing it from [tick]
     * (ticks into it; for the hovered tile). False when there is no picture to draw (yet): the emotes are still
     * loading, the emote has no animation, or this frame's pictures are used up.
     */
    fun drawEmote(id: Int, x: Float, y: Float, w: Float, h: Float, tick: Float?): Boolean {
        val p = Minecraft.getInstance().player ?: return false
        val slim = p.skin.model() == PlayerModelType.SLIM
        val key = (id.toLong() shl 1) or (if (slim) 1L else 0L)
        var t = emotes[key]
        if (t == null) {
            if (budget <= 0) return false
            val q = LunarEmotes.posed(id, slim, null) ?: return false
            budget--
            val fit = FloatArray(4)
            val still = project(upright(q), EMOTE_YAW, true, null, fit)
            var x0 = 1f; var y0 = 1f; var x1 = 0f; var y1 = 0f
            for (i in 0 until still.count * 4) {
                x0 = minOf(x0, still.xy[i * 2]); x1 = maxOf(x1, still.xy[i * 2])
                y0 = minOf(y0, still.xy[i * 2 + 1]); y1 = maxOf(y1, still.xy[i * 2 + 1])
            }
            t = EmoteThumb(still, fit, (x0 + x1) / 2f, (y0 + y1) / 2f, max(x1 - x0, 0.05f), max(y1 - y0, 0.05f))
            emotes[key] = t
        }
        val shown = if (tick == null) t.still
        else LunarEmotes.posed(id, slim, tick % LunarEmotes.ticks(id).coerceAtLeast(1f))?.let { project(upright(it), EMOTE_YAW, true, t.fit, null) } ?: t.still
        if (shown.count == 0) return false
        // The still figure, whatever its pose, takes up EMOTE_FILL of the box; playing, it keeps that size and place.
        val s = EMOTE_FILL * minOf(w / t.w, h / t.h)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(x + w / 2f, y + h / 2f)
        pose.scale(s, s)
        pose.translate(-t.cx, -t.cy)
        Canvas.texturedQuads(Skins.skin(p), shown.xy, shown.uv, shown.tints, shown.count)
        pose.popMatrix()
        return true
    }

    /**
     * The emote mesh's quads (y up from the feet) turned the way the cosmetics' are (y down): half a turn about the
     * line of sight, which keeps left and right (a flip would mirror the player).
     */
    private fun upright(q: FloatArray): FloatArray {
        var i = 0
        while (i + QF <= q.size) {
            for (k in 0 until 4) {
                q[i + k * 5] = -q[i + k * 5]
                q[i + k * 5 + 1] = -q[i + k * 5 + 1]
            }
            q[i + 20] = -q[i + 20]
            q[i + 21] = -q[i + 21]
            i += QF
        }
        return q
    }

    /** The angle the player is seen from in an emote's picture: three-quarters, from the front (the mesh faces +z). */
    private const val EMOTE_YAW = 210f
    private const val EMOTE_FILL = 0.86f

    /** Each cosmetic at its resting angle. */
    private val cache = HashMap<Int, Projected>()

    /** The angle a [type] of cosmetic is shown from: its back for what's worn on the back. */
    fun restYaw(type: String): Float = when (type) {
        "backpack", "bodywear", "wings", "dragon_wings", "cloak", "shields" -> 150f
        else -> 30f
    }

    /** Draws cosmetic [id] in the box; false when there's no model to draw (yet). [spin] turns it from its resting angle. */
    fun draw(id: Int, type: String, x: Float, y: Float, w: Float, h: Float, spin: Float?): Boolean {
        val t = LunarCosmetics.thumb(id) ?: return false
        if (t.quads.isEmpty()) return false
        val p = if (spin == null) cache.getOrPut(id) { project(t.quads, restYaw(type), t.cull, null, null) }
        else project(t.quads, restYaw(type) + spin, t.cull, null, null)
        if (p.count == 0) return false
        val s = minOf(w, h)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(x + (w - s) / 2f, y + (h - s) / 2f)
        pose.scale(s, s)
        Canvas.texturedQuads(Skins.texture(t.texture), p.xy, p.uv, p.tints, p.count)
        pose.popMatrix()
        return true
    }

    /**
     * [q] seen from [yawDeg], fitted to the unit box: by its own bounds, or to [fit] (centre x, y, z and size) when
     * given, so that something moving stays the size and place it was first fitted to. [fitOut] takes the fit used.
     */
    private fun project(q: FloatArray, yawDeg: Float, cull: Boolean, fit: FloatArray?, fitOut: FloatArray?): Projected {
        val n = q.size / QF
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until n) for (k in 0 until 4) {
            val o = i * QF + k * 5
            x0 = minOf(x0, q[o]); x1 = maxOf(x1, q[o])
            y0 = minOf(y0, q[o + 1]); y1 = maxOf(y1, q[o + 1])
            z0 = minOf(z0, q[o + 2]); z1 = maxOf(z1, q[o + 2])
        }
        val cx = fit?.get(0) ?: ((x0 + x1) / 2f)
        val cy = fit?.get(1) ?: ((y0 + y1) / 2f)
        val cz = fit?.get(2) ?: ((z0 + z1) / 2f)
        var r = 0f
        for (i in 0 until n) for (k in 0 until 4) {
            val o = i * QF + k * 5
            val dx = q[o] - cx
            val dz = q[o + 2] - cz
            r = max(r, sqrt(dx * dx + dz * dz))
        }
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(PITCH.toDouble())
        val cy0 = cos(yaw).toFloat(); val sy0 = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()
        // The same scale at every angle: the turning width, and the height seen at this pitch.
        val size = fit?.get(3) ?: (max(max(2f * r, (y1 - y0) * cp + 2f * r * sp), 1e-3f) * 1.1f)
        fitOut?.let { it[0] = cx; it[1] = cy; it[2] = cz; it[3] = size }

        val xy = FloatArray(n * 8)
        val uv = FloatArray(n * 8)
        val depth = FloatArray(n)
        val shade = IntArray(n)
        var count = 0
        // Light from the viewer's upper left (y is down, the viewer at −z).
        val lx = -0.35f; val ly = -0.7f; val lz = -0.62f
        for (i in 0 until n) {
            val b = i * QF
            // The normal, turned the same way.
            val nx0 = q[b + 20]; val ny0 = q[b + 21]; val nz0 = q[b + 22]
            val nx = nx0 * cy0 + nz0 * sy0
            val nzA = -nx0 * sy0 + nz0 * cy0
            val ny = ny0 * cp - nzA * sp
            val nz = ny0 * sp + nzA * cp
            if (cull && nz > 0.02f) continue
            var d = 0f
            for (k in 0 until 4) {
                val o = b + k * 5
                val px = q[o] - cx; val py = q[o + 1] - cy; val pz = q[o + 2] - cz
                val rx = px * cy0 + pz * sy0
                val rzA = -px * sy0 + pz * cy0
                val ry = py * cp - rzA * sp
                val rz = py * sp + rzA * cp
                xy[count * 8 + k * 2] = 0.5f + rx / size
                xy[count * 8 + k * 2 + 1] = 0.5f + ry / size
                uv[count * 8 + k * 2] = q[o + 3]
                uv[count * 8 + k * 2 + 1] = q[o + 4]
                d += rz
            }
            depth[count] = d
            val lit = (0.62f + 0.38f * max(0f, nx * lx + ny * ly + nz * lz)).coerceAtMost(1f)
            val c = (lit * 255).toInt()
            shade[count] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            count++
        }
        // Back to front.
        val order = (0 until count).sortedByDescending { depth[it] }
        val sxy = FloatArray(count * 8)
        val suv = FloatArray(count * 8)
        val st = IntArray(count)
        for ((j, i) in order.withIndex()) {
            System.arraycopy(xy, i * 8, sxy, j * 8, 8)
            System.arraycopy(uv, i * 8, suv, j * 8, 8)
            st[j] = shade[i]
        }
        return Projected(sxy, suv, st, count)
    }
}
