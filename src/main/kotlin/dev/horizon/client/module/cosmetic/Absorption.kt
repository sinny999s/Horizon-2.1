package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.combat.Cosmetic1Util
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.Color
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A shell of hearts around every other player, one heart per 16/60 of absorption up to
 * 60, circling and blinking out at random.
 */
class Absorption : Module("absorption", Categories.COSMETIC) {
    val colorMode = enum("color-mode", ColorMode.CUSTOM)
    val colorSetting = color("color", Color(0xFFFFC800.toInt(), true)) { colorMode.value == ColorMode.CUSTOM }

    /** One heart; [angle] in degrees around the player, [pitch] its fixed height angle. */
    private class Heart(val rnd: ThreadLocalRandom) {
        var angle = rnd.nextFloat() * 360.0f
        var prevAngle = angle
        val pitch = rnd.nextFloat() * 360.0f
        val radius = 1.0f + rnd.nextFloat() * 0.2f
        var alpha = 0f
        var prevAlpha = 0f
        var fadingIn = true
        val speed = 1.5f + ThreadLocalRandom.current().nextFloat() * 1.0f
        val maxAge = ThreadLocalRandom.current().nextInt(15, 35)
        var age = 0
    }

    /** The hearts of one player, by entity id (never removed, on purpose). */
    private class Group(val id: Int, val hearts: MutableList<Heart>)

    private val groups = ArrayList<Group>()

    init {
        on<PlayerUpdateEvent> { tick() }
        on<Render3DEvent> { e -> render(e) }
    }

    private fun random() = ThreadLocalRandom.current()

    /** Hearts circle, fade in (and out at random once full), fade out after their age. */
    private fun tick() {
        for (g in groups) {
            val it = g.hearts.iterator()
            while (it.hasNext()) {
                val h = it.next()
                h.age++
                h.prevAngle = h.angle
                h.angle += h.speed
                h.prevAlpha = h.alpha
                if (h.fadingIn) {
                    h.alpha += 0.2f
                    if (h.alpha >= 1.0f) {
                        h.alpha = 1.0f
                        if (random().nextInt(1000) < 5) h.fadingIn = false
                    }
                } else {
                    h.alpha -= 0.2f
                    if (h.alpha <= 0f) {
                        it.remove()
                        continue
                    }
                }
                if (h.age > h.maxAge) h.fadingIn = false
            }
        }
    }

    /** Hearts added or aged per player, then all drawn additively (depth-tested) from the atlas. */
    private fun render(e: Render3DEvent) {
        val level = mc.level ?: return
        val cam = mc.gameRenderer.mainCamera()
        for (p in level.players()) {
            if (p == mc.player) continue
            val frac = Mth.clamp(p.absorptionAmount / 16.0f, 0.0f, 1.0f)
            val count = (60 * frac).toInt()
            val g = groups.firstOrNull { it.id == p.id }
            if (g == null) {
                val list = ArrayList<Heart>()
                repeat(count) { list += Heart(random()) }
                groups += Group(p.id, list)
                continue
            }
            if (g.hearts.size < count) {
                g.hearts += Heart(random())
                continue
            }
            if (g.hearts.size <= count) continue
            val excess = g.hearts.size - count
            var i = 0
            for (h in g.hearts) {
                if (i < excess) {
                    h.age += 5
                    i++
                }
            }
        }
        val modelView = Matrix4f(e.view).rotate(Axis.YP.rotationDegrees(-cam.yRot() + 180.0f)).rotate(Axis.XP.rotationDegrees(-cam.xRot()))
        val main = mc.gameRenderer.mainRenderTarget()
        val pt = World3D.partialTick
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH, main.colorTextureView!!, main.depthTextureView, modelView, Skins.atlas()) { vc ->
            val t = Vector3f()
            for (g in groups) {
                val ent = level.getEntity(g.id) ?: continue
                val bx = Mth.lerp(pt.toDouble(), ent.xo, ent.x)
                val by = Mth.lerp(pt.toDouble(), ent.yo, ent.y) + 1.0
                val bz = Mth.lerp(pt.toDouble(), ent.zo, ent.z)
                for (h in g.hearts) {
                    val yawR = Math.toRadians(Mth.lerp(pt, h.prevAngle, h.angle).toDouble())
                    val pitchR = Math.toRadians(h.pitch.toDouble())
                    val x = bx + cos(yawR) * h.radius * cos(pitchR)
                    val y = by + sin(pitchR) * h.radius
                    val z = bz + sin(yawR) * h.radius * cos(pitchR)
                    val dx = x - bx
                    val dy = y - by
                    val dz = z - bz
                    val yaw = (-Math.toDegrees(atan2(dz, dx))).toFloat() + 90.0f
                    val pitch = Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
                    val color = Cosmetic1Util.modeColor(colorMode.value, colorSetting.value.rgb, h.age)
                    val a = Mth.lerp(pt, h.prevAlpha, h.alpha)
                    val size = 0.4f * a
                    val col = Colors.withAlpha(color, a)
                    val ps = PoseStack()
                    ps.pushPose()
                    ps.rotateDegrees(Axis.XP, cam.xRot())
                    ps.rotateDegrees(Axis.YP, cam.yRot() + 180.0f)
                    ps.translate(x - cam.position().x, y - cam.position().y, z - cam.position().z)
                    ps.rotateDegrees(Axis.YP, yaw)
                    ps.rotateDegrees(Axis.XP, -pitch)
                    ps.translate(-size / 2.0, -size / 2.0, 0.0)
                    val m = ps.last().pose()
                    fun v(px: Float, py: Float, u: Float, vv: Float) {
                        m.transformPosition(px, py, 0f, t)
                        vc.addVertex(t.x, t.y, t.z).setUv(u, vv).setColor(col)
                    }
                    v(0f, size, HEART[2], HEART[1])
                    v(size, size, HEART[0], HEART[1])
                    v(size, 0f, HEART[0], HEART[3])
                    v(0f, 0f, HEART[2], HEART[3])
                    ps.popPose()
                }
            }
        }
    }

    private companion object {
        /** The heart's cell on the atlas (u0, v0, u1, v1). */
        val HEART = floatArrayOf(0.4531f, 0.25f, 0.5156f, 0.3125f)
    }
}
