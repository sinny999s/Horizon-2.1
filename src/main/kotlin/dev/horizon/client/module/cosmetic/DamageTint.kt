package dev.horizon.client.module.cosmetic

import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import dev.horizon.client.combat.Rotation
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.World3D
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs
import kotlin.math.sin

/**
 * Low health darkens the screen edges towards [tintColor] (pulsing at 4 HP or less),
 * and each hit shows a red arc on a circle around the crosshair pointing at where the hit came from (the explosion,
 * or where the knockback pushed from).
 */
class DamageTint : Module("damage-tint", Categories.COSMETIC) {
    val vector = bool("vector", true)
    val vectorColor = color("vector-color", Color(0xFFFF0000.toInt(), true)) { vector.value }
    val vectorSize = int("vector-size", 100, 10..500) { vector.value }
    val tint = bool("tint", true)
    val tintColor = color("tint-color", Color(0xFFFF0000.toInt(), true)) { tint.value }

    /** Where a hit came from, faded out over a second (OUT_QUINT). */
    private class Hit(val vec: Vec3) {
        val animation = Animation(Easing.OUT_QUINT, 1000)
    }

    private val hits = ArrayList<Hit>()

    /** The last explosion centre and the point the last knockback pushed away from; network thread. */
    @Volatile
    private var explosion: Vec3? = null

    @Volatile
    private var knockback: Vec3? = null

    init {
        on<Render2DEvent> { render() }
        on<ReceivePacketEvent> { e ->
            val p = mc.player ?: return@on
            val packet = e.packet
            if (packet is ClientboundSetEntityMotionPacket && packet.id() == p.id) {
                val m = packet.movement()
                if (m.x != 0.0 && m.z != 0.0) knockback = Vec3(-m.x, m.y, -m.z).add(p.position())
            }
            if (packet is ClientboundExplodePacket) explosion = Vec3(packet.center().x, packet.center().y, packet.center().z)
        }
    }

    private fun render() {
        val p = mc.player ?: return
        if (p.hurtTime > 0) {
            val l = explosion
            if (l != null) {
                hits += Hit(l)
                explosion = null
            } else {
                val d = knockback
                if (d != null) {
                    hits += Hit(d)
                    knockback = null
                }
            }
        }
        if (tint.value) tint(10.0f, 2.0f)
        if (vector.value) {
            for (h in hits) {
                h.animation.update(1.0f)
                val cx = mc.window.guiScaledWidth / 2.0f
                val cy = mc.window.guiScaledHeight / 2.0f
                val angle = Rotation.lookAt(h.vec).yaw - p.yRot
                val pose = Canvas.pose
                pose.pushMatrix()
                pose.translate(cx, cy)
                pose.rotate(Math.toRadians(angle.toDouble()).toFloat())
                pose.translate(-cx, -cy)
                val size = vectorSize.value.toFloat()
                val c = Colors.withAlpha(vectorColor.value.rgb, 1.0f - h.animation.value)
                Canvas.texture(Skins.atlas(), cx - size / 2, cy - size / 2, size, size, c, c, c, c, HALO[0], HALO[1], HALO[2], HALO[3], additive = true)
                pose.popMatrix()
            }
        }
        hits.removeIf { it.animation.finished }
    }

    /**
     * The vignette multiplying the screen by one minus [tintColor]'s complement, scaled by how far
     * health + absorption is below [max] (times [mult]), plus a pulse at 4 HP or less.
     */
    private fun tint(max: Float, mult: Float) {
        val p = mc.player!!
        val hp = p.health + p.absorptionAmount
        var v = abs((if (hp - max <= 0) hp / max else 1.0f) - 1.0f) * mult
        if (hp <= 4.0f) v += abs(0.2f * sin(Math.toRadians(p.tickCount * 16.0 + World3D.partialTick).toFloat().toDouble()).toFloat())
        v = v.coerceIn(0.0f, 1.0f)
        val t = tintColor.value
        val r = ((1.0f - t.red / 255f) * v * 255).toInt()
        val g = ((1.0f - t.green / 255f) * v * 255).toInt()
        val b = ((1.0f - t.blue / 255f) * v * 255).toInt()
        // Drawn now, between what the 2D layer recorded before and after it.
        Canvas.flush(mc.gameRenderer.mainRenderTarget())
        Chams.pass(VIGNETTE, mc.gameRenderer.mainRenderTarget().colorTextureView!!, mapOf("Sampler0" to Skins.texture(VIGNETTE_TEXTURE))) {
            it.putVec4(r / 255f, g / 255f, b / 255f, 1.0f)
        }
    }

    private companion object {
        /** The screen times one minus the colour. */
        val VIGNETTE = Chams.post("vignette", false, BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR, BlendFactor.ONE, BlendFactor.ZERO))
        val VIGNETTE_TEXTURE: Identifier = Identifier.withDefaultNamespace("textures/misc/vignette.png")

        /** The halo's cell on the atlas (u0, v0, u1, v1). */
        val HALO = floatArrayOf(0.8906f, 0.25f, 0.9531f, 0.3096f)
    }
}
