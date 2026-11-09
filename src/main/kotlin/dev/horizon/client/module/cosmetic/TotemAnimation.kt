package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.combat.drawGui
import dev.horizon.client.event.ItemCommandEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.ShowFloatingItemEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Replaces vanilla's pop animation of the floating totem with its own, drawn
 * flat over the HUD in the 2D layer, fading out over 101 − [speed] ticks (FadeOut a quarter of that, Insert half).
 */
class TotemAnimation : Module("totem-animation", Categories.COSMETIC) {
    @Suppress("EnumEntryName")
    enum class Mode { FadeOut, Size, Otkisuli, Insert, Fall, Rocket, Roll, Off }

    val mode = enum("mode", Mode.FadeOut)
    val speed = int("speed", 40, 1..100)

    private var item: ItemStack? = null
    private var ticks = 0

    /** Collects the item's quads (position and uv, one colour) while the item is built. */
    private var collector: Collector? = null

    private class Collector(val color: Int) : VertexConsumer {
        val vertices = ArrayList<FloatArray>()
        private var x = 0f
        private var y = 0f
        private var z = 0f

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            this.x = x
            this.y = y
            this.z = z
            return this
        }

        override fun setUv(u: Float, v: Float): VertexConsumer {
            vertices += floatArrayOf(x, y, z, u, v)
            return this
        }

        override fun setColor(r: Int, g: Int, b: Int, a: Int) = this
        override fun setColor(argb: Int) = this
        override fun setUv1(u: Int, v: Int) = this
        override fun setUv2(u: Int, v: Int) = this
        override fun setUv3(u: Float, v: Float) = this
        override fun setNormal(x: Float, y: Float, z: Float) = this
        override fun setLineWidth(w: Float) = this
    }

    init {
        on<ShowFloatingItemEvent> { e ->
            item = e.item
            ticks = duration()
            e.cancel()
        }
        on<PlayerUpdateEvent> {
            if (ticks <= 0) return@on
            ticks--
            if (ticks == 0) item = null
        }
        on<Render2DEvent> { render() }
        on<ItemCommandEvent> { e ->
            val c = collector ?: return@on
            e.provider = c
            e.cancel()
        }
    }

    /** The animation's length in ticks. */
    private fun duration(): Int {
        val d = 101 - speed.value
        return when (mode.value) {
            Mode.FadeOut -> d / 4
            Mode.Insert -> d / 2
            else -> d
        }
    }

    private fun render() {
        val stack = item ?: return
        if (ticks <= 0 || mode.value == Mode.Off) return
        val level = mc.level ?: return
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val elapsed = duration() - ticks
        val f = (elapsed + pt) / duration()
        val f2 = f * f
        val f3 = f * f2
        val curve = 10.25f * f3 * f2 - 24.95f * f2 * f2 + 25.5f * f3 - 13.8f * f2 + 4.0f * f
        val angle = curve * Math.PI.toFloat()
        val ps = PoseStack()
        ps.pushPose()
        val t = elapsed + pt
        val s = 50.0f + 175.0f * sin(angle)
        val shrink = 200 - t * 1.5f
        when (mode.value) {
            Mode.FadeOut -> {
                val ox = (sin((t * 112 / 180.0f).toDouble()) * 100).toFloat()
                val oy = (cos((t * 112 / 180.0f).toDouble()) * 50).toFloat()
                ps.translate(w / 2 + ox, h / 2 + oy, -50.0f)
                ps.scale(s, -s, s)
            }
            Mode.Size -> {
                ps.translate((w / 2).toFloat(), (h / 2).toFloat(), -50.0f)
                ps.scale(s, -s, s)
            }
            Mode.Otkisuli -> {
                ps.translate((w / 2).toFloat(), (h / 2).toFloat(), -50.0f)
                ps.rotate(Axis.XP.rotationDegrees(t * 2))
                ps.rotate(Axis.ZP.rotationDegrees(t * 2))
                ps.scale(shrink, -200 + t * 1.5f, shrink)
            }
            Mode.Insert -> {
                ps.translate((w / 2).toFloat(), (h / 2).toFloat(), -50.0f)
                ps.rotate(Axis.XP.rotationDegrees(t * 3))
                ps.scale(shrink, -200 + t * 1.5f, shrink)
            }
            Mode.Fall -> {
                val oy = (t.toDouble().pow(3.0) * 0.2f).toFloat()
                ps.translate((w / 2).toFloat(), h / 2 + oy, -50.0f)
                ps.rotate(Axis.ZP.rotationDegrees(t * 5))
                ps.scale(shrink, -200 + t * 1.5f, shrink)
            }
            Mode.Rocket -> {
                val oy = (t.toDouble().pow(3.0) * 0.2f).toFloat() - 20
                ps.translate((w / 2).toFloat(), h / 2 - oy, -50.0f)
                ps.rotate(Axis.YP.rotationDegrees(t * ticks * 2))
                ps.scale(shrink, -200 + t * 1.5f, shrink)
            }
            Mode.Roll -> {
                val ox = (t.toDouble().pow(2.0) * 4.5f).toFloat()
                ps.translate(w / 2 + ox, (h / 2).toFloat(), -50.0f)
                ps.rotate(Axis.ZP.rotationDegrees(t * 40))
                ps.scale(shrink, -200 + t * 1.5f, shrink)
            }
            Mode.Off -> {}
        }
        val c = Collector(Colors.darker(Colors.withAlpha(-1, 1.0f - f)))
        collector = c
        val rs = ItemStackRenderState()
        mc.itemModelResolver.updateForTopItem(rs, stack.item.defaultInstance, ItemDisplayContext.FIXED, level, null, 0)
        val storage = SubmitNodeStorage()
        rs.submit(ps, storage, 15728880, OverlayTexture.NO_OVERLAY, 0)
        // The item feature builds its quads here, into the collector (ItemCommandEvent).
        mc.gameRenderer.featureRenderDispatcher().prepareFrame(storage).close()
        collector = null
        ps.popPose()
        val main = mc.gameRenderer.mainRenderTarget()
        Canvas.flush(main)
        drawGui(Pipelines.COLORED_TEXTURE, mc.textureManager.getTexture(TextureAtlas.LOCATION_ITEMS).textureView) { vc ->
            for (v in c.vertices) vc.addVertex(v[0], v[1], v[2]).setUv(v[3], v[4]).setColor(c.color)
        }
    }
}
