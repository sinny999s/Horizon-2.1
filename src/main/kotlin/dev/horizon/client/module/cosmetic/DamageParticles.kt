package dev.horizon.client.module.cosmetic

import dev.horizon.client.combat.Rng
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.World3D
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.text.DecimalFormat

/**
 * Each change of a nearby entity's health (absorption included) floats up as a
 * number for [ticks] ticks, green for healing and red for damage. [size] is not read anywhere (on purpose).
 */
class DamageParticles : Module("damage-particles", Categories.COSMETIC) {
    val healColor = color("heal-color", Color(0xFF00FF00.toInt(), true))
    val damageColor = color("damage-color", Color(0xFFFF0000.toInt(), true))
    val size = float("size", 0.5f, 0.1f..3.0f)
    val ticks = int("ticks", 35, 5..60)

    /** A health change [value] at a fixed point. */
    private class Number(val value: Float, val x: Double, val y: Double, val z: Double) {
        var age = 0
    }

    /** The last health + absorption seen per entity id. */
    private val health = HashMap<Int, Float>()
    private val numbers = ArrayList<Number>()
    private val format = DecimalFormat("#.#")

    init {
        on<PlayerUpdateEvent> {
            val level = mc.level ?: return@on
            val p = mc.player ?: return@on
            for (e in level.entitiesForRendering()) {
                if (p.distanceTo(e) - 10.0f > 0) continue
                if (e !is LivingEntity) continue
                val prev = health.getOrDefault(e.id, e.maxHealth)
                val cur = e.health + e.absorptionAmount
                health[e.id] = cur
                if (prev - cur == 0.0f) continue
                numbers += Number(cur - prev, e.x + Rng.double(-0.5, 0.5), e.y + e.boundingBox.ysize / 2.0f, e.z + Rng.double(-0.5, 0.5))
            }
            numbers.removeIf { it.age++ >= ticks.value }
        }
        on<Render3DEvent> {
            for (n in numbers) {
                val half = ticks.value / 2.0f
                val a = if (n.age < half) Mth.clamp(n.age / 4.0f, 0.0f, 1.0f) else 1.0f - (n.age - half) / half
                label(format.format(n.value), Vec3(n.x, n.y, n.z), Colors.withAlpha(if (n.value > 0.0f) healColor.value.rgb else damageColor.value.rgb, a))
            }
        }
    }

    override fun onEnable() {
        numbers.clear()
        health.clear()
    }

    /** [text] with a shadow and the bold 25 font: centred, shrinking with distance, gone at 10 blocks. */
    private fun label(text: String, pos: Vec3, color: Int) {
        val s = World3D.project(pos) ?: return
        val cam = mc.cameraEntity ?: return
        val d = cam.position().distanceTo(pos).toFloat()
        val scale = 1f - Mth.clamp(d, 1f, 10f) / 10f
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(s[0], s[1])
        pose.scale(scale, scale)
        Fonts.bold25.drawCentered(text, 1f, 1f, Colors.withAlpha(0xFF000000.toInt(), Colors.alphaF(color)))
        Fonts.bold25.drawCentered(text, 0f, 0f, color)
        pose.popMatrix()
        Canvas.flush(mc.gameRenderer.mainRenderTarget())
    }
}
