package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.EntityRemoveEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Particles3D
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.max

/**
 * Where a splash potion breaks, a glowing ring in the potion's colour spreads on the
 * ground for 30 ticks, with a burst of sparks on its third tick.
 */
class BuffEffect : Module("buff-effect", Categories.COSMETIC) {
    /** One splash, [age] in ticks. */
    private class Splash(val pos: Vec3, val color: Int) {
        var age = 0
    }

    private val splashes = ArrayList<Splash>()

    /** The colour of the last splash-potion level event (written on the network thread). */
    @Volatile
    private var lastColor = 0

    init {
        on<Render3DEvent> { e -> render(e) }
        on<TickEvent> { splashes.removeIf { tick(it) } }
        on<ReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundLevelEventPacket ?: return@on
            if (p.type != 2007 && p.type != 2002) return@on
            val data = p.data
            val c = Color((data shr 16 and 0xFF) / 255.0f, (data shr 8 and 0xFF) / 255.0f, (data and 0xFF) / 255.0f)
            if (c.rgb == -13083194) return@on
            lastColor = c.rgb
        }
        on<EntityRemoveEvent> { e ->
            val potion = e.entity as? ThrownSplashPotion ?: return@on
            val pos = BlockPos.containing(potion.position())
            splashes += Splash(Vec3.atCenterOf(pos).add(0.0, -0.5, 0.0), lastColor)
        }
    }

    /** The sparks on the third tick; done after 30 ticks. */
    private fun tick(s: Splash): Boolean {
        if (s.age == 3) {
            repeat(25) {
                val pos = s.pos.add(Rng.double(-3.0, 3.0), 0.0, Rng.double(-3.0, 3.0))
                val vel = Vec3(Math.random() / 2.0f - 0.25f, Math.random() / 4.0f, Math.random() / 2.0f - 0.25f)
                // `new Color(int)` is opaque: no colour seen yet (0) gives Color.BLACK, the theme gradient.
                Particles3D.add(Particles3D.Particle(FIREFLY, pos, 0.0f, 0.25f, 12, vel, s.color or (255 shl 24)))
            }
            for (i in 0 until 5) {
                Particles3D.add(Particles3D.Particle(FIREFLY, s.pos.add(0.0, -0.3, 0.0), 0.0f, 6.0f, 4, Vec3(0.0, i * 0.15, 0.0),
                    Colors.withAlpha(s.color, 0.5f)))
            }
        }
        return s.age++ > 30
    }

    /** Per splash a faint crack and a growing, then fading circle, flat on the ground. */
    private fun render(e: Render3DEvent) {
        val cam = mc.gameRenderer.mainCamera()
        val modelView = Matrix4f(e.view).rotate(Axis.YP.rotationDegrees(-cam.yRot() + 180.0f)).rotate(Axis.XP.rotationDegrees(-cam.xRot()))
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH, main.colorTextureView!!, main.depthTextureView, modelView, Skins.atlas()) { vc ->
            val t = Vector3f()
            for (s in splashes) {
                val f = Mth.lerp(World3D.partialTick, max(1, s.age - 1).toFloat(), s.age.toFloat())
                val a = when {
                    s.age < 3 -> f / 3.0f
                    s.age > 25 -> 1.0f - (f - 25.0f) / 5.0f
                    else -> 1.0f
                }
                val size = 8.0f * a
                val circle = Colors.withAlpha(s.color, a)
                val crack = Colors.withAlpha(s.color, a * 0.3f)
                val ps = PoseStack()
                ps.pushPose()
                ps.rotateDegrees(Axis.XP, cam.xRot())
                ps.rotateDegrees(Axis.YP, cam.yRot() + 180.0f)
                ps.translate(s.pos.x - cam.position().x, s.pos.y + 0.05f - cam.position().y, s.pos.z - cam.position().z)
                ps.rotateDegrees(Axis.YP, 0.0f)
                ps.rotateDegrees(Axis.XP, -90.0f)
                ps.translate(-4.0, -4.0, 0.0)
                val m = ps.last().pose()
                fun v(x: Float, y: Float, u: Float, vv: Float, c: Int) {
                    m.transformPosition(x, y, 0f, t)
                    vc.addVertex(t.x, t.y, t.z).setUv(u, vv).setColor(c)
                }
                v(0f, 8f, CRACK[2], CRACK[1], crack)
                v(8f, 8f, CRACK[0], CRACK[1], crack)
                v(8f, 0f, CRACK[0], CRACK[3], crack)
                v(0f, 0f, CRACK[2], CRACK[3], crack)
                ps.translate(4.0, 4.0, 0.0)
                ps.translate(-size / 2.0, -size / 2.0, 0.0)
                v(0f, size, CIRCLE[2], CIRCLE[1], circle)
                v(size, size, CIRCLE[0], CIRCLE[1], circle)
                v(size, 0f, CIRCLE[0], CIRCLE[3], circle)
                v(0f, 0f, CIRCLE[2], CIRCLE[3], circle)
                ps.popPose()
            }
        }
    }

    private companion object {
        /** Atlas cells (u0, v0, u1, v1). */
        val FIREFLY = floatArrayOf(0.8906f, 0.1875f, 0.9531f, 0.25f)
        val CRACK = floatArrayOf(0.6406f, 0.0f, 0.8906f, 0.25f)
        val CIRCLE = floatArrayOf(0.3906f, 0.0f, 0.6406f, 0.25f)
    }
}
