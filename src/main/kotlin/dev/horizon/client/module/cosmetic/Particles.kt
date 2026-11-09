package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.combat.AtlasSprite
import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Particle3D
import dev.horizon.client.combat.Particles3D
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.sprite
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode
import dev.horizon.client.module.Mode
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.hypot
import kotlin.math.max

/**
 * Ambient sprites spawned around the player every tick — drifting, falling, shooting
 * stars or rain (rain drops trail a line, splash on the ground with a ring and sparks).
 */
class Particles : Module("particles", Categories.COSMETIC) {
    enum class Physics { DROP, FLY, FALLING_STARS, RAIN }

    val mode = enum("mode", Mode.SNOW_FLAKE)
    val colorMode = enum("color-mode", ColorMode.SYNC)
    val physics = enum("physics", Physics.FLY)
    val colorSetting = color("color", Color(0xFFFFFFFF.toInt(), true)) { colorMode.value == ColorMode.CUSTOM }
    val amount = int("amount", 10, 1..50)
    val time = int("time", 256, 5..80)
    val size = float("size", 1.0f, 0.1f..1.5f)

    /** One particle. */
    private inner class Drop(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var rot: Float,
                             var rotSpeed: Float) {
        var prevX = x
        var prevY = y
        var prevZ = z
        var prevRot = rot
        var life = if (physics.value == Physics.RAIN) 80 else (time.value + time.value * Rng.float(0.0f, 0.5f)).toInt()
        val maxLife = life
        var landed = false
        var visible = false
        private val hue = (360 * Rng.float(0.0f, 1.0f)).toInt()
        val color = when (colorMode.value) {
            ColorMode.CUSTOM -> colorSetting.value.rgb
            ColorMode.SYNC -> Theme.gradient(hue)
            ColorMode.SKY -> Color.getHSBColor((hueStep(hue) / 360.0).toFloat(), 0.5f, 1.0f).rgb
            ColorMode.LIGHT_RGB -> hsb(hue, 0.6f)
            ColorMode.RGB -> hsb(hue, 1.0f)
        }

        /** The block surface under the spawn point (within 128), else the spawn height. */
        val groundY = Explosions.raycast(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), Vec3(x.toDouble(), y - 128.0, z.toDouble()),
            false, emptyList(), emptyList())?.blockPos?.above()?.y?.toFloat() ?: y
    }

    /** A rain drop's splash ring. */
    private class Ring(val x: Float, val y: Float, val z: Float, val color: Int) {
        var age = 0
    }

    private val drops = ArrayList<Drop>()
    private val rings = ArrayList<Ring>()

    init {
        on<PlayerUpdateEvent> { tick() }
        on<Render3DEvent> { render() }
    }

    /** The hue `((now / 18 + offset) % 360)` in degrees. */
    private fun hueStep(offset: Int): Int = ((System.currentTimeMillis() / 18 + offset) % 360).toInt()

    private fun hsb(offset: Int, s: Float): Int = Color(Color.HSBtoRGB(hueStep(offset) / 360.0f, s, 1.0f)).rgb or (255 shl 24)

    private fun tick() {
        val p = mc.player ?: return
        drops.removeIf { tickDrop(it) }
        rings.removeIf { it.age++ > 15 }
        val base = p.position().add(p.deltaMovement.scale(10.0))
        repeat(amount.value) {
            drops += if (physics.value == Physics.RAIN) {
                Drop((base.x + Rng.float(-48.0f, 48.0f)).toFloat(), (base.y + 24 + Rng.float(-2.0f, 2.0f)).toFloat(),
                    (base.z + Rng.float(-48.0f, 48.0f)).toFloat(), 0.0f, Rng.float(-0.3f, -0.1f), 0.0f, Rng.float(0.0f, 360.0f),
                    Rng.float(3.0f, 10.0f))
            } else {
                val x = (base.x + Rng.float(-64.0f, 64.0f) * 2).toFloat()
                val y = (base.y + Rng.float(2.0f, 48.0f)).toFloat()
                val z = (base.z + Rng.float(-64.0f, 64.0f) * 2).toFloat()
                val vx = when (physics.value) {
                    Physics.DROP -> 0.0f
                    Physics.FLY -> Rng.float(-0.4f, 0.4f)
                    else -> Rng.float(0.1f, 0.25f)
                }
                val vy = when (physics.value) {
                    Physics.DROP -> Rng.float(-0.2f, -0.05f)
                    Physics.FLY -> Rng.float(-0.1f, 0.1f)
                    else -> Rng.float(-0.4f, -0.1f)
                }
                val vz = when (physics.value) {
                    Physics.DROP -> 0.0f
                    Physics.FLY -> Rng.float(-0.4f, 0.4f)
                    else -> Rng.float(0.1f, 0.25f)
                }
                Drop(x, y, z, vx, vy, vz, 0.0f, 0.0f)
            }
        }
    }

    /** One tick of a particle; true once it is dead. */
    private fun tickDrop(d: Drop): Boolean {
        val p = mc.player!!
        val rain = physics.value == Physics.RAIN
        if (p.distanceToSqr(d.x.toDouble(), d.y.toDouble(), d.z.toDouble()) > 4096.0 || d.landed) d.life -= 16 else d.life -= 1
        if (d.life < 0) return true
        d.prevX = d.x
        d.prevY = d.y
        d.prevZ = d.z
        d.x += d.vx
        d.y += d.vy
        d.z += d.vz
        d.prevRot = d.rot
        d.rot += d.rotSpeed
        if (physics.value != Physics.FALLING_STARS) {
            d.vx *= 0.9f
            d.vz *= 0.9f
        }
        if (physics.value == Physics.FLY || rain && d.landed) d.vy *= 0.9f
        d.vy -= if (rain && d.landed) 0.08f else if (rain) 0.02f else 0.001f
        if (d.y - size.value < d.groundY && !d.landed && rain) {
            d.vy = -d.vy / 4.0f
            d.landed = true
            d.life = 128
            d.vx = Rng.float(-0.2f, 0.2f)
            d.vz = Rng.float(-0.2f, 0.2f)
            for (i in 0 until 5) {
                Particles3D.add(Particle3D(AtlasSprite.FIREFLY, Vec3(d.x.toDouble(), d.groundY.toDouble(), d.z.toDouble()), 0.0f, 0.1f, 8,
                    Vec3(Math.random() / 2.0f - 0.25f, Math.random() / 4.0f, Math.random() / 2.0f - 0.25f), Theme.gradient(i * 10)))
            }
            d.rotSpeed = Rng.float(-20.0f, 20.0f)
            rings += Ring(d.x, d.groundY, d.z, d.color)
        }
        // Compares a distance with what look like squared distances (32², 48²) on purpose: always visible in practice.
        val dist = hypot(d.x - p.x, d.z - p.z)
        d.visible = dist < if (rain) 1024.0f else 2304.0f
        return false
    }

    private fun render() {
        val cam = mc.gameRenderer.mainCamera()
        val camPos = cam.position()
        val frustum = cam.cullFrustum
        val pt = World3D.partialTick
        val yaw = Math.toRadians(cam.yRot().toDouble()).toFloat()
        val pitch = Math.toRadians(cam.xRot().toDouble()).toFloat()
        val rain = physics.value == Physics.RAIN
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH, main.colorTextureView!!, main.depthTextureView, World3D.view, Skins.atlas()) { vc ->
            val ps = PoseStack()
            val tmp = Vector3f()
            val base = Quaternionf().rotateYXZ(-yaw, pitch, 0.0f)
            val sprite = mode.value.sprite()
            for (d in drops) {
                if (!d.visible || !frustum.pointInFrustum(d.x.toDouble(), d.y.toDouble(), d.z.toDouble())) continue
                val px = Mth.lerp(pt, d.prevX, d.x).toDouble()
                val py = Mth.lerp(pt, d.prevY, d.y).toDouble()
                val pz = Mth.lerp(pt, d.prevZ, d.z).toDouble()
                ps.pushPose()
                ps.translate(px - camPos.x, py - camPos.y, pz - camPos.z)
                ps.rotate(base)
                if (rain) ps.rotate(Axis.ZP.rotationDegrees(Mth.lerp(pt, d.prevRot, d.rot)))
                val m = ps.last().pose()
                val fade = d.life.toFloat() / d.maxLife
                val color = if (rain && !d.landed) d.color else Colors.withAlpha(d.color, fade)
                val s = size.value
                ps.translate(-s / 2.0, -s / 2.0, -0.01)
                vc.sprite(m, s, sprite, color, tmp)
                ps.popPose()
                if (!d.landed && rain) {
                    val a = Mth.clamp(d.life / 20.0f, 0.0f, 1.0f)
                    World3D.depthLine(Vec3(px, py, pz), Vec3(px, py + 3, pz), Colors.withAlpha(color, a / 3.0f), Colors.withAlpha(color, 0.0f))
                }
            }
            val flat = Quaternionf().rotateYXZ(-yaw, 1.5708f, 0.0f)
            for (r in rings) {
                if (!frustum.pointInFrustum(r.x.toDouble(), r.y.toDouble(), r.z.toDouble())) continue
                ps.pushPose()
                ps.translate(r.x - camPos.x, r.y - camPos.y, r.z - camPos.z)
                ps.rotate(flat)
                val m = ps.last().pose()
                val age = Mth.lerp(pt, max(1, r.age - 1).toFloat(), r.age.toFloat())
                val color = Colors.withAlpha(r.color, 1.0f - age / 15.0f)
                val s = 0.5f + 1.0f * (age / 15.0f)
                ps.translate(-s / 2.0, -s / 2.0, -0.01)
                vc.sprite(m, s, AtlasSprite.CIRCLE, color, tmp)
                ps.popPose()
            }
        }
    }
}
