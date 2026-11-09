package dev.horizon.client.hud

import dev.horizon.client.config.Waypoints
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Icon
import dev.horizon.client.render.Theme
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Waypoint labels: every visible waypoint of this server ("localhost" ones everywhere) and dimension
 * within the horizontal field of view gets "<id> (<distance>m)" in bold with a map pin above it. Always on, F1 too.
 */
object WaypointRenderer {
    private val pin by lazy { Icon("point", 20f, 20f) }
    private val projection = Matrix4f()
    private val view = Matrix4f()
    private var camera: Vec3 = Vec3.ZERO
    private var captured = false

    fun init() {
        LevelRenderEvents.END_MAIN.register { ctx ->
            val cam = ctx.levelState().cameraRenderState
            projection.set(cam.projectionMatrix)
            view.set(cam.viewRotationMatrix)
            camera = cam.pos
            captured = true
        }
    }

    /** A world point in GUI coordinates, or null behind the camera. */
    private fun project(x: Double, y: Double, z: Double): FloatArray? {
        if (!captured) return null
        val v = Vector4f((x - camera.x).toFloat(), (y - camera.y).toFloat(), (z - camera.z).toFloat(), 1f)
        view.transform(v)
        projection.transform(v)
        if (v.w <= 0f) return null
        val window = Minecraft.getInstance().window
        val nx = v.x / v.w
        val ny = v.y / v.w
        return floatArrayOf((nx * 0.5f + 0.5f) * window.guiScaledWidth, (1f - (ny * 0.5f + 0.5f)) * window.guiScaledHeight)
    }

    fun render() {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val level = mc.level ?: return
        val ip = mc.currentServer?.ip
        val dim = Waypoints.dimension(level.dimension().identifier().path)
        for (wp in Waypoints.list) {
            if (!wp.visible) continue
            if (wp.server != "localhost" && wp.server != ip) continue
            if (wp.dimension != dim) continue
            val yawTo = Math.toDegrees(atan2(wp.z - p.z, wp.x - p.x)).toFloat() - 90f
            if (abs(Mth.wrapDegrees(yawTo - p.yRot)) > mc.options.fov().get()) continue
            val s = project(wp.x.toDouble(), wp.y.toDouble(), wp.z.toDouble()) ?: continue
            val dist = p.position().distanceTo(Vec3(wp.x.toDouble(), wp.y.toDouble(), wp.z.toDouble())).toInt()
            Fonts.bold8.drawCentered("${wp.id} (${dist}m)", s[0], s[1], Theme.text)
            pin.draw(s[0] - 10f, s[1] - 25f)
        }
    }
}
