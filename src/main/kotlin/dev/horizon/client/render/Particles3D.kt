package dev.horizon.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f

/**
 * World particle system: camera-facing atlas sprites with velocity, gravity and drag, ticked every
 * player tick and drawn additively (depth-tested) every frame. Always on once something was added.
 */
object Particles3D {
    private val mc get() = Minecraft.getInstance()

    /**
     * A sprite [tex] (u0, v0, u1, v1 on the atlas) of [size] blocks living [life] ticks, spinning [spin]
     * degrees a tick. A [color] of 0 (`Color.BLACK`) means the theme gradient. With [fade] it shrinks and
     * fades out over its life.
     */
    class Particle(val tex: FloatArray, var pos: Vec3, val spin: Float, val size: Float, var life: Int, var velocity: Vec3, val color: Int,
                   val gravity: Float = 0.03f, val drag: Float = 0.99f, val fade: Boolean = true) {
        private val maxLife = life
        private var prevPos = pos
        private var rot = 0f
        private var prevRot = 0f

        /** Drawn from the shared RNG and never used (on purpose). */
        @Suppress("unused")
        private val variant = Rng.int(0, 3)

        /** One tick; true once its life ran out. */
        fun tick(): Boolean {
            life--
            if (life < 0) return true
            prevPos = pos
            pos = pos.add(velocity)
            velocity = velocity.scale(drag.toDouble())
            velocity = velocity.add(0.0, -gravity.toDouble(), 0.0)
            prevRot = rot
            rot += spin
            return false
        }

        fun render(stack: PoseStack, base: Quaternionfc, vc: VertexConsumer, t: Vector3f) {
            val pt = World3D.partialTick
            stack.pushPose()
            val k = if (fade) Mth.lerp(pt, life + 1.0f, life.toFloat()) / maxLife else 1.0f
            val s = size * k
            val cam = mc.gameRenderer.mainCamera()
            val c = if (color == BLACK) Theme.gradient(life * 2) else color
            val rel = Vec3(Mth.lerp(pt.toDouble(), prevPos.x, pos.x), Mth.lerp(pt.toDouble(), prevPos.y, pos.y),
                Mth.lerp(pt.toDouble(), prevPos.z, pos.z)).subtract(cam.position())
            stack.translate(rel.x, rel.y, rel.z)
            stack.rotate(base)
            if (rot != 0f && prevRot != 0f) stack.rotateDegrees(Axis.ZP, Mth.lerp(pt, prevRot, rot))
            val m = stack.last().pose()
            val col = if (!fade) c else Colors.withAlpha(c, k)
            stack.translate(-s / 2.0, -s / 2.0, -0.01)
            fun v(x: Float, y: Float, u: Float, vv: Float) {
                m.transformPosition(x, y, 0f, t)
                vc.addVertex(t.x, t.y, t.z).setUv(u, vv).setColor(col)
            }
            v(0f, s, tex[2], tex[1])
            v(s, s, tex[0], tex[1])
            v(s, 0f, tex[0], tex[3])
            v(0f, 0f, tex[2], tex[3])
            stack.popPose()
        }
    }

    /** `Color.BLACK`, which particles take as "use the theme gradient". */
    const val BLACK = 0xFF000000.toInt()

    private val particles = ArrayList<Particle>()

    init {
        EventBus.on<Render3DEvent>(this) { e -> render(e) }
        EventBus.on<PlayerUpdateEvent>(this) { particles.removeIf { it.tick() } }
    }

    fun add(p: Particle) {
        particles += p
    }

    /**
     * Every particle whose position is on screen (a point box),
     * in one additive depth-tested draw.
     */
    private fun render(e: Render3DEvent) {
        if (particles.isEmpty()) return
        val cam = mc.gameRenderer.mainCamera()
        val yaw = Math.toRadians(cam.yRot().toDouble()).toFloat()
        val yaw180 = Math.toRadians((cam.yRot() + 180.0f).toDouble()).toFloat()
        val pitch = Math.toRadians(cam.xRot().toDouble()).toFloat()
        val modelView = Matrix4f(e.view).rotate(Axis.YP.rotation(-yaw180)).rotate(Axis.XP.rotation(-pitch))
        val stack = PoseStack()
        stack.rotate(Axis.XP.rotation(pitch))
        stack.rotate(Axis.YP.rotation(yaw180))
        val base = Quaternionf().rotateYXZ(-yaw, pitch, 0.0f)
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH, main.colorTextureView!!, main.depthTextureView, modelView, Skins.atlas()) { vc ->
            val t = Vector3f()
            for (p in particles) if (World3D.isVisible(AABB(p.pos, p.pos))) p.render(stack, base, vc, t)
        }
    }
}
