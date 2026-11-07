package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.combat.AtlasSprite
import dev.horizon.client.combat.Particle3D
import dev.horizon.client.combat.Particles3D
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.sprite
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Mode
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The original's "hit-fx" (`r0`): on every attack packet, a puff of smoke (a 9-frame atlas animation, facing the way the
 * player looked) and a burst of particles at the point the last sent rotation hits (else the target's centre).
 */
class HitFx : Module("hit-fx", Categories.COSMETIC) {
    val smoke = bool("smoke", true)
    val particles = bool("particles", true)
    val particle = enum("particle", Mode.HEART) { particles.value }
    val size = float("size", 0.2f, 0.01f..0.5f) { particles.value }
    val duration = int("duration", 15, 5..60) { particles.value }
    val count = int("count", 15, 5..60) { particles.value }
    val gravity = float("gravity", 0.03f, -0.15f..0.15f) { particles.value }
    val horizontalMotion = float("horizontal-motion", 0.25f, 0.01f..2.0f) { particles.value }
    val verticalMotion = float("vertical-motion", 0.5f, 0.01f..2.0f) { particles.value }

    /** The original's `yw`: one smoke puff. */
    private class Smoke(val pos: Vec3, val yaw: Float, val pitch: Float) {
        val born = System.currentTimeMillis()
    }

    private val smokes = ArrayList<Smoke>()

    init {
        on<SendPacketEvent> { e -> mc.executeIfPossible { onSend(e) } }
        on<Render3DEvent> { render() }
    }

    private fun onSend(e: SendPacketEvent) {
        val packet = e.packet as? ServerboundAttackPacket ?: return
        val range = Modules.get<Aura>().reach.value
        fun targetCenter(): Vec3? = mc.level?.getEntity(packet.entityId())?.boundingBox?.center
        var center = rayHit(Rotation.lastYaw, Rotation.lastPitch, range) ?: targetCenter()
        if (center == null) {
            val r = RotationManager.peek()
            center = rayHit(r.yaw, r.pitch, range) ?: targetCenter()
        }
        if (center == null) return
        if (smoke.value) smokes += Smoke(center, -Rotation.lastYaw, Rotation.lastPitch)
        if (!particles.value) return
        repeat(count.value) {
            val h = horizontalMotion.value
            val v = verticalMotion.value
            Particles3D.add(Particle3D(particle.value.sprite(), center, (Math.random() * 50f).toFloat(), size.value, duration.value,
                Vec3(Rng.float(-h, h).toDouble(), Rng.float(-v, v).toDouble(), Rng.float(-h, h).toDouble()), gravity = gravity.value))
        }
    }

    /** The original's `kk.p`: where a [range]-long look along [yaw]/[pitch] from the eyes hits a (non-spectator) entity. */
    private fun rayHit(yaw: Float, pitch: Float, range: Float): Vec3? {
        val p = mc.player ?: return null
        val eye = p.position().add(0.0, p.getEyeHeight(p.pose).toDouble(), 0.0)
        val rangeSq = range.toDouble().pow(2.0).toFloat()
        val rad = (Math.PI / 180.0).toFloat()
        val dir = Vec3((sin(-yaw * rad) * cos(pitch * rad)).toDouble(), (-sin(pitch * rad)).toDouble(),
            (cos(-yaw * rad) * cos(pitch * rad)).toDouble())
        val end = eye.add(dir.x * range, dir.y * range, dir.z * range)
        val box = p.boundingBox.expandTowards(dir.scale(range.toDouble())).inflate(1.0, 1.0, 1.0)
        return ProjectileUtil.getEntityHitResult(p, eye, end, box, { !it.isSpectator }, rangeSq.toDouble())?.location
    }

    private fun render() {
        val list = ArrayList(smokes)
        val main = mc.gameRenderer.mainRenderTarget()
        val s = AtlasSprite.SMOKE
        val du = (s.u1 - s.u0) / 3f
        val dv = (s.v1 - s.v0) / 3f
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE, main.colorTextureView!!, null, World3D.view, Skins.atlas()) { vc ->
            val tmp = Vector3f()
            for (sm in list) {
                val age = System.currentTimeMillis() - sm.born
                val frame = Mth.clamp((age.toFloat() / 40f).toInt(), 0, 8)
                val u = s.u0 + frame % 3 * du
                val v = s.v0 + frame / 3 * dv
                billboard(vc, sm.pos, sm.yaw, sm.pitch, 1f + age.toFloat() / 450f, Colors.withAlpha(Theme.gradient(10), 1f),
                    u, v, u + du, v + dv, tmp)
            }
        }
        val now = System.currentTimeMillis()
        smokes.removeIf { now - it.born >= 360 }
    }

    /** The original's `h3.c` with a fixed facing: a quad at [pos] turned by [yaw] then [pitch]. */
    private fun billboard(vc: VertexConsumer, pos: Vec3, yaw: Float, pitch: Float, size: Float, color: Int,
                          u0: Float, v0: Float, u1: Float, v1: Float, tmp: Vector3f) {
        val cam = mc.gameRenderer.mainCamera().position()
        val ps = PoseStack()
        ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
        ps.rotate(Axis.YP.rotationDegrees(yaw))
        ps.rotate(Axis.XP.rotationDegrees(pitch))
        val m = ps.last().pose()
        ps.translate(-size / 2.0, -size / 2.0, -0.01)
        fun vert(x: Float, y: Float, u: Float, v: Float) {
            m.transformPosition(x, y, 0f, tmp)
            vc.addVertex(tmp.x, tmp.y, tmp.z).setUv(u, v).setColor(color)
        }
        vert(0f, size, u0, v1)
        vert(size, size, u1, v1)
        vert(size, 0f, u1, v0)
        vert(0f, 0f, u0, v0)
    }
}
