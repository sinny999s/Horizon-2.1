package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.combat.AtlasSprite
import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Particle3D
import dev.horizon.client.combat.Particles3D
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turning it on throws a little firecracker along the look direction (fuse sound, a
 * trail of star sparks); it bounces along the ground and after 100 ticks explodes in a burst of particles and turns the
 * module off.
 */
class ThrowPetard : Module("throw-petard", Categories.COSMETIC) {
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var prevX = 0f
    private var prevY = 0f
    private var prevZ = 0f
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var ticks = 0

    init {
        on<PlayerUpdateEvent> { tick() }
        on<Render3DEvent> { render() }
    }

    override fun onEnable() {
        val p = mc.player ?: return
        ticks = 0
        // The fuse plays at the previous petard's position (the origin the first time), on purpose.
        mc.level?.playLocalSound(x.toDouble(), y + 0.5, z.toDouble(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 0.2f, 1.0f, false)
        x = p.eyePosition.x.toFloat()
        y = p.eyePosition.y.toFloat()
        z = p.eyePosition.z.toFloat()
        vx = p.lookAngle.x.toFloat()
        vy = p.lookAngle.y.toFloat() + 0.42f + p.deltaMovement.y.toFloat()
        vz = p.lookAngle.z.toFloat()
    }

    private fun tick() {
        prevX = x
        prevY = y
        prevZ = z
        x += vx
        y += vy
        z += vz
        vx *= 0.98f
        vy *= 0.98f
        vz *= 0.98f
        // Cast backwards from the new position. Any hit counts as landing.
        val hit = Explosions.raycast(Vec3(x.toDouble(), y.toDouble(), z.toDouble()),
            Vec3(prevX.toDouble(), prevY.toDouble(), prevZ.toDouble()), false, emptyList(), emptyList())
        if (hit != null) {
            x = prevX
            y = hit.blockPos.above().y.toFloat()
            z = prevZ
            vx *= 0.81f
            vz *= 0.81f
        } else {
            vy -= 0.08f
        }
        ticks++
        val at = Vec3(x.toDouble(), y + 0.5, z.toDouble())
        repeat(3) {
            Particles3D.add(Particle3D(AtlasSprite.STAR, at, (Math.random() * 50f).toFloat(), 0.2f, 5,
                Vec3(Math.random() / 4f - 0.125f, Math.random() / 2f, Math.random() / 4f - 0.125f)))
        }
        if (ticks <= 100) return
        disable()
        mc.level?.playLocalSound(x.toDouble(), y + 0.5, z.toDouble(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.2f, 1.0f, false)
        repeat(100) {
            Particles3D.add(Particle3D(AtlasSprite.STAR, at, (Math.random() * 50f).toFloat(), 0.5f, 20,
                Vec3((Math.random() - 0.5) * 1.5f, Math.random(), (Math.random() - 0.5) * 1.5f)))
            Particles3D.add(Particle3D(AtlasSprite.FIREFLY, at, (Math.random() * 50f).toFloat(), 0.3f, 20,
                Vec3((Math.random() - 0.5) * 1.5f, Math.random(), (Math.random() - 0.5) * 1.5f)))
        }
    }

    private fun render() {
        val pt = World3D.partialTick
        val cam = mc.gameRenderer.mainCamera().position()
        val m = Matrix4f(World3D.view).translate((Mth.lerp(pt, prevX, x) - cam.x).toFloat(), (Mth.lerp(pt, prevY, y) - cam.y).toFloat(),
            (Mth.lerp(pt, prevZ, z) - cam.z).toFloat())
        // The vertices are transformed by the pose and then drawn with that same pose as the model-view, so the petard
        // ends up transformed twice (on purpose).
        World3D.draw(Pipelines.WORLD_FILL, mc.gameRenderer.mainRenderTarget().colorTextureView!!, null, m) { vc -> petard(vc, m) }
    }

    /** A dark grey tube (16 sides, r 0.12, 0.5 tall) with a white band and a black fuse. */
    private fun petard(vc: VertexConsumer, m: Matrix4f) {
        val tmp = Vector3f()
        fun v(px: Float, py: Float, pz: Float, c: Int) {
            m.transformPosition(px, py, pz, tmp)
            vc.addVertex(tmp.x, tmp.y, tmp.z).setColor(c)
        }
        val r = 0.12f
        val h = 0.5f
        val band = 0.2f
        val sides = 16
        val darkGray = 0xFF404040.toInt()
        for (i in 0 until sides) {
            val a1 = (Math.PI * 2 * i / sides).toFloat()
            val a2 = (Math.PI * 2 * (i + 1) / sides).toFloat()
            val x1 = cos(a1) * r
            val z1 = sin(a1) * r
            val x2 = cos(a2) * r
            val z2 = sin(a2) * r
            v(x1, 0f, z1, darkGray)
            v(x1, h, z1, darkGray)
            v(x2, h, z2, darkGray)
            v(x2, 0f, z2, darkGray)
        }
        val by = h / 2 - band / 2
        for (i in 0 until sides) {
            val a1 = (Math.PI * 2 * i / sides).toFloat()
            val a2 = (Math.PI * 2 * (i + 1) / sides).toFloat()
            val x1 = cos(a1) * (r + 0.01f)
            val z1 = sin(a1) * (r + 0.01f)
            val x2 = cos(a2) * (r + 0.01f)
            val z2 = sin(a2) * (r + 0.01f)
            v(x1, by, z1, -1)
            v(x1, by + band, z1, -1)
            v(x2, by + band, z2, -1)
            v(x2, by, z2, -1)
        }
        val fuse = 0.06f
        val w = 0.03f
        val black = 0xFF000000.toInt()
        v(-w, h + fuse, -w, black)
        v(-w, h, -w, black)
        v(w, h, w, black)
        v(w, h + fuse, w, black)
    }
}
