package dev.horizon.client.render

import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector4f
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Points projected through two cameras: [MotionBlurMath.reproject] must take one screen position to the other. */
class MotionBlurMathTest {
    /** The game's level projection: reversed-Z (near and far swapped), a finite far plane. */
    private fun projection(fov: Float, zeroToOne: Boolean) =
        Matrix4f().setPerspective(Math.toRadians(fov.toDouble()).toFloat(), 16f / 9f, 512f, 0.05f, zeroToOne)

    private fun view(yaw: Float, pitch: Float) =
        Matrix4f().rotationX(Math.toRadians(pitch.toDouble()).toFloat()).rotateY(Math.toRadians(yaw.toDouble()).toFloat() + Math.PI.toFloat())

    /** (u, v, depth) of world point [w] seen from [cam]; null when it is behind the camera or off the depth range. */
    private fun screen(w: Vector3d, cam: Vector3d, proj: Matrix4f, view: Matrix4f, zeroToOne: Boolean): FloatArray? {
        val c = Matrix4f(proj).mul(view).transform(Vector4f((w.x - cam.x).toFloat(), (w.y - cam.y).toFloat(), (w.z - cam.z).toFloat(), 1f))
        if (c.w <= 0f) return null
        val z = c.z / c.w
        val depth = if (zeroToOne) z else (z + 1f) / 2f
        if (depth !in 0f..1f) return null
        return floatArrayOf((c.x / c.w + 1f) / 2f, (c.y / c.w + 1f) / 2f, depth)
    }

    private fun check(zeroToOne: Boolean) {
        val r = Random(7)
        var checked = 0
        repeat(2000) {
            val proj = projection(60f + r.nextFloat() * 50f, zeroToOne)
            val prevProj = projection(60f + r.nextFloat() * 50f, zeroToOne)
            val yaw = r.nextFloat() * 360f
            val pitch = r.nextFloat() * 160f - 80f
            val view = view(yaw, pitch)
            val prevView = view(yaw + r.nextFloat() * 20f - 10f, (pitch + r.nextFloat() * 10f - 5f).coerceIn(-90f, 90f))
            val cam = Vector3d(r.nextDouble() * 1000, 64 + r.nextDouble() * 10, r.nextDouble() * 1000)
            val prevCam = Vector3d(cam).add(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5)
            val w = Vector3d(cam).add(r.nextGaussian() * 15, r.nextGaussian() * 15, r.nextGaussian() * 15)
            val now = screen(w, cam, proj, view, zeroToOne) ?: return@repeat
            val then = screen(w, prevCam, prevProj, prevView, zeroToOne) ?: return@repeat
            val m = MotionBlurMath.reproject(proj, view, prevProj, prevView, cam.x - prevCam.x, cam.y - prevCam.y, cam.z - prevCam.z, zeroToOne)
            val s = assertNotNull(MotionBlurMath.shift(m, now[0], now[1], now[2]))
            // Within a hundredth of a pixel on a 2560 px wide screen.
            assertEquals(now[0] - then[0], s[0], 4e-6f + abs(s[0]) * 1e-3f)
            assertEquals(now[1] - then[1], s[1], 4e-6f + abs(s[1]) * 1e-3f)
            checked++
        }
        assertTrue(checked > 500, "only $checked points were in view")
    }

    @Test
    fun reprojectsWithZeroToOneDepth() = check(true)

    @Test
    fun reprojectsWithMinusOneToOneDepth() = check(false)

    @Test
    fun aTurnWithoutMovingDoesNotDependOnDepth() {
        val proj = projection(70f, true)
        val m = MotionBlurMath.reproject(proj, view(30f, 10f), proj, view(25f, 12f), 0.0, 0.0, 0.0, true)
        for (u in listOf(0.1f, 0.5f, 0.9f)) {
            val near = MotionBlurMath.shift(m, u, 0.3f, 0.99f)!!
            val far = MotionBlurMath.shift(m, u, 0.3f, 0.0001f)!!
            assertEquals(near[0], far[0], 1e-5f)
            assertEquals(near[1], far[1], 1e-5f)
        }
    }

    @Test
    fun standingStillIsNoShift() {
        val proj = projection(70f, false)
        val v = view(123f, -20f)
        val m = MotionBlurMath.reproject(proj, v, proj, v, 0.0, 0.0, 0.0, false)
        assertTrue(MotionBlurMath.maxShiftPx(m, 0.5f, 3f, 2560, 1440) < 0.01)
    }

    @Test
    fun exposureAndClosestFrame() {
        assertEquals(8_333_333.0, MotionBlurMath.exposureNs(180.0), 1.0)
        val times = longArrayOf(100, 200, 300, 400)
        assertEquals(1, MotionBlurMath.closest(times.size, 230) { times[it] })
        assertEquals(0, MotionBlurMath.closest(times.size, 0) { times[it] })
        assertEquals(3, MotionBlurMath.closest(times.size, 999) { times[it] })
        assertEquals(-1, MotionBlurMath.closest(0, 5) { 0 })
    }
}
