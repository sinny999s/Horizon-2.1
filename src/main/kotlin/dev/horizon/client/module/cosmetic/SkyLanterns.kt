package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.combat.AtlasSprite
import dev.horizon.client.combat.Cosmetic2Pipelines
import dev.horizon.client.combat.Particle3D
import dev.horizon.client.combat.Particles3D
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.World3D
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Keeps [count] paper lanterns drifting up around the player, each a translucent
 * box with a flame of particles, swaying, fading in and out over its 100–200 tick life.
 */
class SkyLanterns : Module("sky-lanterns", Categories.COSMETIC) {
    val fireColor = color("fire-color", Color(0xFFFFFF00.toInt(), true))
    val colorSetting = color("color", Color(0x33FFFF00.toInt(), true))
    val bottomColor = color("bottom-color", Color(0x33FFC800.toInt(), true))
    val count = int("count", 256, 0..512)

    /** One lantern; its starting values are drawn from the shared RNG in this order. */
    private class Lantern(var x: Float, var y: Float, var z: Float) {
        var prevX = x
        var prevY = y
        var prevZ = z
        var vx = Rng.float(-0.1f, 0.1f)
        var vy = Rng.float(0.01f, 0.03f)
        var vz = Rng.float(-0.1f, 0.1f)
        var age = 0
        var yaw = Rng.float(-15.0f, 15.0f)
        val yawSpeed = Rng.float(-0.5f, 0.5f)

        init {
            Rng.int(0, 360) // a value drawn and never used (on purpose)
        }

        val life = Rng.int(100, 200)
    }

    private val lanterns = ArrayList<Lantern>()

    init {
        on<TickEvent> { tick() }
        on<Render3DEvent>(-10) { render() }
    }

    override fun onEnable() = lanterns.clear()

    override fun onDisable() = lanterns.clear()

    private fun spawn() {
        val p = mc.player!!
        val base = p.position().add(p.deltaMovement.scale(10.0))
        lanterns += Lantern((base.x + Rng.float(-48.0f, 48.0f)).toFloat(), (base.y + 4.0f + Rng.float(-2.0f, 2.0f)).toFloat(),
            (base.z + Rng.float(-48.0f, 48.0f)).toFloat())
    }

    private fun tick() {
        val p = mc.player ?: return
        if (mc.level == null || mc.isPaused) return
        while (lanterns.size < count.value) spawn()
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val fire = fireColor.value.rgb
        val it = lanterns.iterator()
        while (it.hasNext()) {
            val l = it.next()
            l.prevX = l.x
            l.prevY = l.y
            l.prevZ = l.z
            l.vx += Rng.float(-0.00125f, 0.00125f)
            l.vy += Rng.float(2.0E-4f, 6.0E-4f)
            l.vz += Rng.float(-0.00125f, 0.00125f)
            l.vx *= 0.99f
            l.vy *= 0.99f
            l.vz *= 0.99f
            l.x += l.vx
            l.y += l.vy
            l.z += l.vz
            l.yaw += l.yawSpeed
            val sway = sin(Mth.lerp(pt, l.age - 1.0f, l.age.toFloat()) / 60.0f)
            val px = Mth.lerp(pt, l.prevX, l.x) + sway
            val py = Mth.lerp(pt, l.prevY, l.y)
            val pz = Mth.lerp(pt, l.prevZ, l.z) - sway
            val fade = if (l.life - l.age - 10.0f >= 0f) 1.0f else (l.life - l.age) / 10.0f
            val flame = Colors.lerp(fire, colorSetting.value.rgb, 0.5f)
            Particles3D.add(Particle3D(AtlasSprite.FIREFLY,
                Vec3(px + (Random.nextFloat() - 0.5f) * 0.06, py + 0.5, pz + (Random.nextFloat() - 0.5f) * 0.06),
                Random.nextFloat() * 20.0f, 0.25f, 12,
                Vec3((Random.nextDouble() - 0.5) * 0.02 + l.vx, Random.nextDouble() * 0.06 + l.vy, (Random.nextDouble() - 0.5) * 0.02 + l.vz),
                Colors.withAlpha(fire, Colors.alphaF(fire) * fade), gravity = 0.0f))
            if (l.age % 2 == 0) {
                Particles3D.add(Particle3D(AtlasSprite.FIREFLY,
                    Vec3(px + Rng.double(-0.03, 0.03), py + 1.0, pz + Rng.double(-0.03, 0.03)),
                    Rng.float(0.0f, 20.0f), 1.5f, 2, Vec3(l.vx.toDouble(), l.vy.toDouble(), l.vz.toDouble()),
                    Colors.withAlpha(flame, 0.5f * fade), gravity = 0.0f, fade = false))
                Particles3D.add(Particle3D(AtlasSprite.FIREFLY,
                    Vec3(px + (Random.nextFloat() - 0.5f) * 0.06, py + 1.0, pz + (Random.nextFloat() - 0.5f) * 0.06),
                    Rng.float(0.0f, 20.0f), 3.0f, 2, Vec3(l.vx.toDouble(), l.vy.toDouble(), l.vz.toDouble()),
                    Colors.withAlpha(flame, 0.2f * fade), gravity = 0.0f, fade = false))
            }
            val age = l.age
            l.age = age + 1
            if (age > l.life || l.y - p.y - 20.0 > 0) it.remove()
        }
    }

    private fun render() {
        if (lanterns.isEmpty()) return
        val cam = mc.gameRenderer.mainCamera()
        val frustum = cam.cullFrustum
        val camPos = cam.position()
        val pt = World3D.partialTick
        val visible = lanterns.filter {
            frustum.pointInFrustum(it.x.toDouble(), it.y + 1.5, it.z.toDouble()) || frustum.pointInFrustum(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())
        }
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Cosmetic2Pipelines.POS_COLOR_DEPTH, main.colorTextureView!!, main.depthTextureView, World3D.view) { vc ->
            val ps = PoseStack()
            val tmp = Vector3f()
            for (l in visible) {
                val age = max(Mth.lerp(pt, l.age - 1.0f, l.age.toFloat()), 0.0f)
                val sway = sin(age / 60.0f)
                val px = Mth.lerp(pt, l.prevX, l.x) + sway
                val py = Mth.lerp(pt, l.prevY, l.y)
                val pz = Mth.lerp(pt, l.prevZ, l.z) - sway
                ps.pushPose()
                ps.translate(px - camPos.x, py - camPos.y, pz - camPos.z)
                val yaw = Mth.lerp(pt, l.yaw - l.yawSpeed, l.yaw)
                ps.rotate(Axis.YP.rotationDegrees(yaw))
                lantern(vc, ps.last().pose(), px, py, pz, yaw, age, l.life.toFloat(), tmp)
                ps.popPose()
            }
        }
    }

    /** The paper box (open-bottomed, a tapered lower rim) and the wire cross under it. */
    private fun lantern(vc: VertexConsumer, m: Matrix4f, x: Float, y: Float, z: Float, yaw: Float, age: Float, life: Float, tmp: Vector3f) {
        val w = 0.33f
        val rim = 0.2f
        val inset = 0.08f
        val y0 = 0.78f
        val y1 = 1.3299999f
        val y2 = 0.78f + rim
        val alpha = when {
            age < 0f -> fadeOut(life, age)
            age <= 10f -> age / 10.0f
            else -> fadeOut(life, age)
        }
        val side = colorSetting.value.let { Colors.withAlpha(it.rgb, it.alpha / 255f * alpha) }
        val bottom = bottomColor.value.let { Colors.withAlpha(it.rgb, it.alpha / 255f * alpha) }
        val wire = Colors.withAlpha(WIRE, alpha)
        fun v(px: Float, py: Float, pz: Float, c: Int) {
            m.transformPosition(px, py, pz, tmp)
            vc.addVertex(tmp.x, tmp.y, tmp.z).setColor(c)
        }
        v(-w, y0, -w, side); v(-w, y1, -w, side); v(w, y1, -w, side); v(w, y0, -w, side)
        v(-w + inset, y0 - rim, -w + inset, bottom); v(-w, y2 - rim, -w, side); v(w, y2 - rim, -w, side); v(w - inset, y0 - rim, -w + inset, bottom)
        v(-w, y0, w, side); v(-w, y1, w, side); v(w, y1, w, side); v(w, y0, w, side)
        v(-w + inset, y0 - rim, w - inset, bottom); v(-w, y2 - rim, w, side); v(w, y2 - rim, w, side); v(w - inset, y0 - rim, w - inset, bottom)
        v(-w, y0, -w, side); v(-w, y1, -w, side); v(-w, y1, w, side); v(-w, y0, w, side)
        v(-w + inset, y0 - rim, -w + inset, bottom); v(-w, y2 - rim, -w, side); v(-w, y2 - rim, w, side); v(-w + inset, y0 - rim, w - inset, bottom)
        v(w, y0, -w, side); v(w, y1, -w, side); v(w, y1, w, side); v(w, y0, w, side)
        v(w - inset, y0 - rim, -w + inset, bottom); v(w, y2 - rim, -w, side); v(w, y2 - rim, w, side); v(w - inset, y0 - rim, w - inset, bottom)
        v(-w, y1, -w, side); v(w, y1, -w, side); v(w, y1, w, side); v(-w, y1, w, side)
        // Queued after this frame's world flush (priority −10), so drawn next frame (on purpose).
        val ang = Math.toRadians(yaw.toDouble())
        val wy = y + 0.58f
        val a = rotated(x - w + inset, wy, z + inset - w, x, wy, z, ang)
        val b = rotated(x + w - inset, wy, z + inset - w, x, wy, z, ang)
        val c = rotated(x - w + inset, wy, z - inset + w, x, wy, z, ang)
        val d = rotated(x + w - inset, wy, z - inset + w, x, wy, z, ang)
        val e = rotated(x - w + inset, wy, z, x, wy, z, ang)
        val f = rotated(x + w - inset, wy, z, x, wy, z, ang)
        val g = rotated(x, wy, z + inset - w, x, wy, z, ang)
        val h = rotated(x, wy, z - inset + w, x, wy, z, ang)
        World3D.depthLine(a, b, wire)
        World3D.depthLine(c, d, wire)
        World3D.depthLine(e, f, wire)
        World3D.depthLine(b, d, wire)
        World3D.depthLine(a, c, wire)
        World3D.depthLine(g, h, wire)
    }

    /** The last 10 ticks: divides by 20 there (on purpose), so the lantern drops to half its alpha before fading. */
    private fun fadeOut(life: Float, age: Float): Float = if (life - age < 10.0f) (life - age) / 20.0f else 1.0f

    /** ([px], [py], [pz]) turned by [ang] radians about the vertical through ([cx], [cz]). */
    private fun rotated(px: Float, py: Float, pz: Float, cx: Float, cy: Float, cz: Float, ang: Double): Vec3 {
        val s = sin(ang)
        val c = cos(ang)
        val dx = px - cx
        val dz = pz - cz
        val dy = py - cy
        return Vec3(cx + (dx * c + dz * s), cy.toDouble() + dy, cz + (-dx * s + dz * c))
    }

    private companion object {
        /** The wire colour, (70, 70, 70). */
        const val WIRE = 0xFF464646.toInt()
    }
}
