package dev.horizon.client.render

import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The game-free half of Motion Blur: how long the shutter is open, which earlier camera the blur reaches back to, and
 * the matrix that takes a pixel of this frame to where that camera saw it.
 */
object MotionBlurMath {
    /** The frame rate a shutter angle is a share of: 180° is 1/120 s of exposure, at every frame rate. */
    const val REFERENCE_FPS = 60.0

    /** The exposure in nanoseconds for a shutter angle in degrees. */
    fun exposureNs(angle: Double): Double = angle / 360.0 / REFERENCE_FPS * 1e9

    /** Of [count] earlier frames whose times (ns) [time] gives, the index of the one closest to [target], or -1 when there are none. */
    fun closest(count: Int, target: Long, time: (Int) -> Long): Int {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in 0 until count) {
            val gap = abs(time(i) - target)
            if (gap < bestGap) {
                best = i
                bestGap = gap
            }
        }
        return best
    }

    /**
     * Takes (u, v, depth, 1) of a pixel now to the earlier camera's clip space in uv units: x/w, y/w is the uv the
     * same point had then. uv and depth go to NDC (with [zeroToOne] the depth is NDC z as it is, else 2d - 1), back
     * through this frame's projection and view (a rotation) to camera-relative space, are moved by ([mx], [my], [mz])
     * (this camera's position minus the earlier one's), then go through the earlier view and projection and back to uv.
     */
    fun reproject(proj: Matrix4fc, view: Matrix4fc, prevProj: Matrix4fc, prevView: Matrix4fc,
                  mx: Double, my: Double, mz: Double, zeroToOne: Boolean): Matrix4f {
        val toNdc = Matrix4d().translation(-1.0, -1.0, if (zeroToOne) 0.0 else -1.0).scale(2.0, 2.0, if (zeroToOne) 1.0 else 2.0)
        val toUv = Matrix4d().translation(0.5, 0.5, 0.0).scale(0.5, 0.5, 1.0)
        val back = Matrix4d(proj).mul(Matrix4d(view)).invert()
        val then = Matrix4d(prevProj).mul(Matrix4d(prevView))
        return Matrix4f(toUv.mul(then).mul(Matrix4d().translation(mx, my, mz)).mul(back).mul(toNdc))
    }

    /** How far [m] says the pixel at ([u], [v]) with [depth] moved, in uv (now minus then); null if it was behind that camera. */
    fun shift(m: Matrix4fc, u: Float, v: Float, depth: Float): FloatArray? {
        val p = m.transform(Vector4f(u, v, depth, 1f))
        if (p.w <= 1e-6f) return null
        return floatArrayOf(u - p.x / p.w, v - p.y / p.w)
    }

    /** The longest shift, in pixels times [scale], of the screen's corners, edge centres and centre at [depth]. */
    fun maxShiftPx(m: Matrix4fc, depth: Float, scale: Float, width: Int, height: Int): Double {
        var max = 0.0
        for (u in floatArrayOf(0f, 0.5f, 1f)) for (v in floatArrayOf(0f, 0.5f, 1f)) {
            val s = shift(m, u, v, depth) ?: return Double.MAX_VALUE
            max = maxOf(max, hypot(s[0].toDouble() * width, s[1].toDouble() * height) * scale)
        }
        return max
    }
}
