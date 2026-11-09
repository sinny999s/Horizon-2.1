package dev.horizon.client.gui.modern

import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.lunar.GeoModel
import dev.horizon.client.lunar.LunarCosmetics
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
        val p = if (spin == null) cache.getOrPut(id) { project(t.quads, restYaw(type), t.cull) } else project(t.quads, restYaw(type) + spin, t.cull)
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

    private fun project(q: FloatArray, yawDeg: Float, cull: Boolean): Projected {
        val n = q.size / QF
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until n) for (k in 0 until 4) {
            val o = i * QF + k * 5
            x0 = minOf(x0, q[o]); x1 = maxOf(x1, q[o])
            y0 = minOf(y0, q[o + 1]); y1 = maxOf(y1, q[o + 1])
            z0 = minOf(z0, q[o + 2]); z1 = maxOf(z1, q[o + 2])
        }
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val cz = (z0 + z1) / 2f
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
        val size = max(max(2f * r, (y1 - y0) * cp + 2f * r * sp), 1e-3f) * 1.1f

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
