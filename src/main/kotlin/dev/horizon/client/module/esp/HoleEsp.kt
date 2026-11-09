package dev.horizon.client.module.esp

import com.mojang.math.Axis
import dev.horizon.client.combat.Holes
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Every hole in the shared hole cache ([Holes]), as boxes [height] tall in the [safe] or
 * [unsafe] colour, faded out past half of `range--x-y` with [rangeFade]; BLOOM instead runs glows around each hole's rim.
 */
class HoleEsp : Module("hole-esp", Categories.ESP) {
    @Suppress("EnumEntryName")
    enum class Mode { Fade, CubeOutline, CubeFill, CubeBoth, Shader, BLOOM }

    val mode = enum("mode", Mode.CubeOutline)
    val rangeXY = int("range--x-y", 10, 1..32)
    val rangeY = int("range--y", 5, 1..32)
    val height = float("height", 1.0f, 0.01f..5.0f)
    val rangeFade = bool("range-fade", true)
    val safe = color("safe", Color(0xFF00FF51.toInt(), true))
    val unsafe = color("unsafe", Color(0xFF00FF51.toInt(), true))
    val bloomAnimation = bool("bloom-animation", true)
    val skew = float("skew", 0.8f, 0.0f..1.0f) { bloomAnimation.value }
    val distribution = float("distribution", 0.4f, 0.0f..1.0f) { bloomAnimation.value }
    val amount = int("amount", 2, 1..10) { bloomAnimation.value }

    /** The FIREFLY glow on the atlas (u0, v0, u1, v1). */
    private val firefly = floatArrayOf(0.8906f, 0.1875f, 0.9531f, 0.25f)

    init {
        Holes.init()
        on<Render3DEvent> { e ->
            if (mode.value == Mode.BLOOM) {
                bloom(e.partialTick)
                return@on
            }
            for (hole in Holes.holes()) {
                val box = hole.box(height.value)
                val color = (if (hole.safety == Holes.Safety.UNSAFE) unsafe.value else safe.value).rgb
                when (mode.value) {
                    Mode.CubeFill -> World3D.fill(box, faded(box, color))
                    Mode.Shader -> World3D.shaderBox(box, faded(box, color))
                    Mode.CubeOutline -> World3D.outline(box, faded(box, color))
                    Mode.CubeBoth -> {
                        World3D.outline(box, faded(box, color))
                        World3D.fill(box, faded(box, color))
                    }
                    Mode.Fade -> {
                        World3D.fill(box, faded(box, color, 60), faded(box, color, 0))
                        World3D.faceOutline(box, Direction.DOWN, faded(box, color))
                    }
                    Mode.BLOOM -> return@on
                }
            }
        }
    }

    /**
     * [color] with alpha [alpha], times the range fade: full up to half of `range--x-y` (horizontally from the
     * player), then down to 0 at the full range.
     */
    private fun faded(box: AABB, color: Int, alpha: Int = color ushr 24): Int {
        if (!rangeFade.value) return Colors.withAlpha(color, alpha / 255.0f)
        val half = rangeXY.value * 0.5f
        val p = mc.player!!
        val dx = p.x - box.center.x
        val dz = p.z - box.center.z
        val d = sqrt((dx * dx + dz * dz).toFloat().toDouble()).toFloat()
        val f = if (d <= half) 1.0f else Mth.clamp(1.0f - (d - half) / half, 0.0f, 1.0f)
        return Colors.withAlpha(color, (f * (alpha / 255.0)).toFloat())
    }

    /** Six rings of 49 glows around each visible hole's rim, 1/15 apart and dimmer upwards, additive, through walls. */
    private fun bloom(pt: Float) {
        val p = mc.player ?: return
        val cam = mc.gameRenderer.mainCamera()
        val yaw = Math.toRadians(cam.yRot().toDouble()).toFloat()
        val yaw180 = Math.toRadians((cam.yRot() + 180.0f).toDouble()).toFloat()
        val pitch = Math.toRadians(cam.xRot().toDouble()).toFloat()
        val facing = Quaternionf().rotateYXZ(-yaw, pitch, 0f)
        val base = Matrix4f().rotate(Axis.XP.rotation(pitch)).rotate(Axis.YP.rotation(yaw180))
        val s = 0.16f
        val glows = ArrayList<Pair<Matrix4f, Int>>()
        for (hole in Holes.holes()) {
            val box = hole.box(height.value)
            val color = faded(box, (if (hole.safety == Holes.Safety.UNSAFE) unsafe.value else safe.value).rgb)
            if (!World3D.isVisible(box)) continue
            val camPos = cam.position()
            val dx = box.center.x - camPos.x
            val dy = box.minY - camPos.y
            val dz = box.center.z - camPos.z
            for (ring in 0 until 6) for (i in 0..48) {
                var a = Colors.alphaF(color)
                if (bloomAnimation.value) {
                    val t = Mth.lerp(pt, p.tickCount - 1.0f, p.tickCount.toFloat())
                    val period = 49.toFloat() / amount.value.toFloat()
                    var phase = (t + i) / period
                    phase -= floor(phase.toDouble()).toFloat()
                    var d = abs(phase - -skew.value)
                    d = minOf(d, 1.0f - d)
                    val g = d / distribution.value
                    a *= Mth.clamp(exp((-(g * g) / 2.0f).toDouble()).toFloat(), 0.0f, 1.0f)
                }
                if (!(a >= 0.01f)) continue
                val hx = 0.45f * box.xsize.toFloat()
                val hz = 0.45f * box.zsize.toFloat()
                val frac = (((i / 1.5f).toDouble() + 1.0) * 11 % 360.0f.toDouble()) / 360.0f.toDouble()
                val perimeter = 4.0f * (hx + hz)
                var along = frac.toFloat() * perimeter
                val ox: Float
                val oz: Float
                if (along < 2.0f * hx) {
                    ox = -hx + along
                    oz = hz
                } else if (along < 2.0f * hx + 2.0f * hz) {
                    along -= 2.0f * hx
                    ox = hx
                    oz = hz - along
                } else if (along < 4.0f * hx + 2.0f * hz) {
                    along -= 2.0f * hx + 2.0f * hz
                    ox = hx - along
                    oz = -hz
                } else {
                    along -= 4.0f * hx + 2.0f * hz
                    ox = -hx
                    oz = -hz + along
                }
                val m = Matrix4f(base).translate((dx + ox).toFloat(), (dy + (ring / 15.0f).toDouble()).toFloat(), (dz + oz).toFloat())
                    .rotate(facing)
                glows += m to Colors.withAlpha(color, 1.0f / (ring + 1).toFloat() * a)
            }
        }
        val main = mc.gameRenderer.mainRenderTarget()
        val modelView = Matrix4f(World3D.view).rotate(Axis.YP.rotationDegrees(-cam.yRot() + 180.0f)).rotate(Axis.XP.rotationDegrees(-cam.xRot()))
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE, main.colorTextureView!!, null, modelView, Skins.atlas()) { vc ->
            val v = Vector3f()
            for ((m, c) in glows) {
                fun vert(x: Float, y: Float, u: Float, vv: Float) {
                    m.transformPosition(x, y, 0f, v)
                    vc.addVertex(v.x, v.y, v.z).setUv(u, vv).setColor(c)
                }
                vert(-s, s, firefly[0], firefly[3])
                vert(s, s, firefly[2], firefly[3])
                vert(s, -s, firefly[2], firefly[1])
                vert(-s, -s, firefly[0], firefly[1])
            }
        }
    }
}
