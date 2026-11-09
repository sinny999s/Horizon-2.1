package dev.horizon.client.render

import com.mojang.math.Axis
import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.hud.widgets.Skins
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** World sprites and TECHNO walls, drawn straight away during Render3D. */
object WorldEsp {
    private val mc get() = Minecraft.getInstance()

    /** The FIREFLY glow on the atlas (u0, v0, u1, v1). */
    private val FIREFLY = floatArrayOf(0.8906f, 0.1875f, 0.9531f, 0.25f)
    private val BLOCK: Identifier = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/exclude/block.png")

    private class Sprite(val p: Vec3, val s: Float, val color: Int)

    /** A sprite batch: camera-facing quads at camera-relative points, additive, depth-tested only when [seen]. */
    private fun sprites(list: List<Sprite>, seen: Boolean) {
        if (list.isEmpty()) return
        val cam = mc.gameRenderer.mainCamera()
        val xr = cam.xRot()
        val yr = cam.yRot()
        val main = mc.gameRenderer.mainRenderTarget()
        val modelView = Matrix4f(World3D.view).rotate(Axis.YP.rotationDegrees(-yr + 180f)).rotate(Axis.XP.rotationDegrees(-xr))
        val pipeline = if (seen) Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH else Pipelines.COLORED_TEXTURE_ADDITIVE
        val u0 = FIREFLY[0]
        val v0 = FIREFLY[1]
        val u1 = FIREFLY[2]
        val v1 = FIREFLY[3]
        World3D.draw(pipeline, main.colorTextureView!!, if (seen) main.depthTextureView else null, modelView, Skins.atlas()) { vc ->
            val t = Vector3f()
            for (sp in list) {
                val q = Matrix4f().rotate(Axis.XP.rotationDegrees(xr)).rotate(Axis.YP.rotationDegrees(yr + 180f))
                    .translate(sp.p.x.toFloat(), sp.p.y.toFloat(), sp.p.z.toFloat())
                    .rotate(Axis.YP.rotationDegrees(-yr)).rotate(Axis.XP.rotationDegrees(xr))
                val s = sp.s
                fun vert(x: Float, y: Float, u: Float, v: Float) {
                    q.transformPosition(x, y, 0f, t)
                    vc.addVertex(t.x, t.y, t.z).setUv(u, v).setColor(sp.color)
                }
                vert(-s, s, u0, v1)
                vert(s, s, u1, v1)
                vert(s, -s, u1, v0)
                vert(-s, -s, u0, v0)
            }
        }
    }

    private fun base(target: Entity): Pair<Vec3, Double> {
        val pt = World3D.partialTick
        val cam = World3D.camera
        val p = Vec3(Mth.lerp(pt.toDouble(), target.xo, target.x) - cam.x, Mth.lerp(pt.toDouble(), target.yo, target.y) - cam.y,
            Mth.lerp(pt.toDouble(), target.zo, target.z) - cam.z)
        val t = Mth.lerp(pt.toDouble(), target.tickCount - 1.0, target.tickCount.toDouble())
        return p to t
    }

    /** Three comets of 15 glows circling the target. */
    fun comets(target: Entity, alpha: Float) {
        val (p0, t) = base(target)
        val bbW = target.bbWidth.toDouble()
        val list = ArrayList<Sprite>(45)
        for (j in 0..2) for (i in 0..14) {
            val ang = Math.toRadians(((i / 1.5f + t) * 8 + j * 120) % 2880)
            val yOff = sin(Math.toRadians(t * 2.5f + i * (j + 1)) * 3.0f) / 1.8f
            val frac = i / 14f
            val p = Vec3(p0.x + cos(ang) * bbW, p0.y + 1 + yOff, p0.z + sin(ang) * bbW)
            val col = PlaceBoxes.withAlpha(Theme.gradient((180 * frac).toInt()), frac * alpha)
            list += Sprite(p, max((0.24f * frac).toDouble(), 0.2).toFloat(), col)
        }
        sprites(list, mc.player!!.hasLineOfSight(target))
    }

    /** Eight trailing rings of 67 glows around the target. */
    fun bloomCircle(target: Entity, alpha: Float) {
        val (p0, t) = base(target)
        val bbW = target.bbWidth.toDouble()
        val list = ArrayList<Sprite>(536)
        for (k in 0..7) for (i in 0..66) {
            val th = i / 67.0 * 2 * Math.PI
            val yOff = sin(Math.toRadians((t - k / 2.0f) * 2.5f) * 3.0f) / 1.2f
            val frac = i.toFloat() / 68
            val p = Vec3(p0.x + cos(th) * bbW, p0.y + 1 + yOff, p0.z + sin(th) * bbW)
            list += Sprite(p, 0.12f, PlaceBoxes.withAlpha(Theme.gradient((180 * frac).toInt()), alpha / (k + 1)))
        }
        sprites(list, mc.player!!.hasLineOfSight(target))
    }

    /** TECHNO's scrolling walls, glowing top and ripples on the base [box]. */
    fun techno(box: AABB, ripples: List<IntArray>) {
        val p = mc.player ?: return
        val bottom = PlaceBoxes.withAlpha(Theme.gradient(0), 0.1f)
        val top = PlaceBoxes.withAlpha(Theme.gradient(180), 1.0f)
        if (!World3D.isVisible(box)) return
        val cam = World3D.camera
        val x0 = (box.minX - cam.x).toFloat(); val y0 = (box.minY - cam.y).toFloat(); val z0 = (box.minZ - cam.z).toFloat()
        val x1 = (box.maxX - cam.x).toFloat(); val y1 = (box.maxY - cam.y).toFloat(); val z1 = (box.maxZ - cam.z).toFloat()
        val pt = World3D.partialTick
        val s = (Mth.lerp(pt, p.tickCount - 1f, p.tickCount.toFloat()) % 80) / 80f
        val main = mc.gameRenderer.mainRenderTarget()
        val texture = Skins.texture(BLOCK)
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE, main.colorTextureView!!, null, World3D.view, texture) { vc ->
            fun v(x: Float, y: Float, z: Float, u: Float, vv: Float, c: Int) {
                vc.addVertex(x, y, z).setUv(u, vv).setColor(c)
            }
            v(x0, y0, z0, s, 1f, bottom); v(x0, y1, z0, s, 0f, top); v(x1, y1, z0, .32f + s, 0f, top); v(x1, y0, z0, .32f + s, 1f, bottom)
            v(x1, y0, z0, .32f + s, 1f, bottom); v(x1, y1, z0, .32f + s, 0f, top); v(x1, y1, z1, .64f + s, 0f, top); v(x1, y0, z1, .64f + s, 1f, bottom)
            v(x1, y0, z1, .64f + s, 1f, bottom); v(x0, y0, z1, .96f + s, 1f, bottom); v(x0, y1, z1, .96f + s, 0f, top); v(x1, y1, z1, .64f + s, 0f, top)
            v(x0, y0, z1, .96f + s, 1f, bottom); v(x0, y0, z0, 1.28f + s, 1f, bottom); v(x0, y1, z0, 1.28f + s, 0f, top); v(x0, y1, z1, .96f + s, 0f, top)
        }
        val topFace = PlaceBoxes.withAlpha(top, 0.4f)
        World3D.faceFill(box, Direction.UP, topFace)
        World3D.faceOutline(box, Direction.UP, topFace)
        for (g in ripples) {
            val v = 0.5f + Mth.clamp((g[0] + 1 - pt) / 8f, 0f, 0.5f)
            val r = box.setMaxY(box.maxY - (1 - v)).inflate(-v.toDouble(), 0.0, -v.toDouble())
            World3D.faceOutline(r, Direction.UP, PlaceBoxes.withAlpha(top, v))
        }
    }
}
