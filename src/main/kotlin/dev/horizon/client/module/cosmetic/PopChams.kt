package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.concurrent.CopyOnWriteArrayList

/**
 * When another player pops a totem, a copy of their pose stays where they stood, rising
 * by [ySpeed] and fading by [alphaSpeed] a tick, in the theme colour: flat faces and edges (DEFAULT) or the skinned
 * model (GHOST).
 */
class PopChams : Module("pop-chams", Categories.COSMETIC) {
    enum class Mode { DEFAULT, GHOST }

    val mode = enum("mode", Mode.DEFAULT)
    val startAlpha = int("start-alpha", 255, 0..255)
    val ySpeed = float("y-speed", 0.1f, -1.0f..1.0f)
    val alphaSpeed = float("alpha-speed", 5.0f, 1.0f..20.0f)

    /** A frozen pose ([state]) of [model] with its own position and alpha (0..255). */
    private class Pop(val model: PlayerModel, val state: AvatarRenderState, var pos: Vec3, val skin: Identifier, var alpha: Float) {
        var prevAlpha = alpha
        var prevPos = pos

        /** True once faded out. */
        fun tick(alphaSpeed: Float, ySpeed: Float): Boolean {
            if (alpha <= 0f) return true
            prevAlpha = alpha
            alpha -= alphaSpeed
            prevPos = pos
            pos = Vec3(pos.x, pos.y + ySpeed, pos.z)
            return false
        }

        /** The interpolated alpha as 0..1. */
        fun alpha(): Float = Mth.clamp(Mth.lerp(World3D.partialTick, prevAlpha, alpha), 0.0f, 255.0f) / 255.0f

        fun pos(): Vec3 = prevPos.lerp(pos, World3D.partialTick.toDouble())
    }

    /** Added from the network thread. */
    private val pops = CopyOnWriteArrayList<Pop>()

    init {
        on<PlayerUpdateEvent> { pops.removeIf { it.tick(alphaSpeed.value, ySpeed.value) } }
        on<Render3DEvent> { e ->
            if (mode.value == Mode.DEFAULT) {
                if (pops.isEmpty()) return@on
                val color = mc.gameRenderer.mainRenderTarget().colorTextureView!!
                World3D.draw(Pipelines.WORLD_FILL, color, null, e.view) { vc ->
                    for (p in pops) flat(e.view, p, Chams.FlatConsumer(vc, true, Colors.withAlpha(Theme.primary, p.alpha())))
                }
                World3D.draw(Pipelines.LINES, color, null, e.view) { vc ->
                    for (p in pops) flat(e.view, p, Chams.FlatConsumer(vc, false, Colors.withAlpha(Theme.primary, p.alpha() * 2.0f)))
                }
                return@on
            }
            for (p in pops) ghost(e.view, p)
        }
        on<ReceivePacketEvent> { e -> onPacket(e) }
    }

    /** A totem pop (entity event 35) of another player freezes their current pose. */
    private fun onPacket(e: ReceivePacketEvent) {
        if (mc.cameraEntity == null || mc.player == null) return
        val level = mc.level ?: return
        if (mc.player!!.tickCount < 10) return
        val packet = e.packet as? ClientboundEntityEventPacket ?: return
        if (packet.eventId.toInt() != 35) return
        if (mc.cameraEntity == null) return
        val player = packet.getEntity(level) as? Player ?: return
        if (player == mc.player) return
        @Suppress("UNCHECKED_CAST")
        val renderer = mc.entityRenderDispatcher.getRenderer(player) as AvatarRenderer<AbstractClientPlayer>
        val model = renderer.model
        val rs = renderer.createRenderState(player as AbstractClientPlayer, World3D.partialTick) as AvatarRenderState
        // Only these are copied; 26.3's swing (currentSwing, swingAnimation) replaces 26.2's attackTime.
        val copy = AvatarRenderState()
        copy.capeFlap = rs.capeFlap
        copy.capeLean = rs.capeLean
        copy.capeLean2 = rs.capeLean2
        copy.x = rs.x
        copy.y = rs.y
        copy.z = rs.z
        copy.bodyRot = rs.bodyRot
        copy.yRot = rs.yRot
        copy.xRot = rs.xRot
        copy.currentSwing = rs.currentSwing
        copy.swingAnimation = rs.swingAnimation
        copy.flyingYRot = rs.flyingYRot
        copy.ageInTicks = rs.ageInTicks
        copy.walkAnimationSpeed = rs.walkAnimationSpeed
        copy.speedValue = rs.speedValue
        copy.walkAnimationPos = rs.walkAnimationPos
        model.setupAnim(rs)
        pops += Pop(model, copy, player.position(), player.skin.body().texturePath(), startAlpha.value.toFloat())
    }

    /** The pose as flat faces or edges, overlay layers hidden, the camera rotation undone in the pose. */
    private fun flat(view: Matrix4f, p: Pop, out: Chams.FlatConsumer) {
        val stack = PoseStack()
        stack.last().pose().set(view)
        stack.pushPose()
        val cam = mc.entityRenderDispatcher.camera!!.position()
        val pos = p.pos()
        val mainCam = mc.gameRenderer.mainCamera()
        stack.rotateDegrees(Axis.YP, -mainCam.yRot() + 180.0f)
        stack.rotateDegrees(Axis.XP, -mainCam.xRot())
        stack.translate((pos.x - cam.x).toFloat(), (pos.y - cam.y).toFloat(), (pos.z - cam.z).toFloat())
        stack.scale(p.state.scale, p.state.scale, p.state.scale)
        Chams.setupRotations(p.state, stack, p.state.bodyRot)
        stack.scale(-1.0f, -1.0f, 1.0f)
        stack.scale(0.9375f, 0.9375f, 0.9375f)
        stack.translate(0.0f, -1.501f, 0.0f)
        val m = p.model
        m.setupAnim(p.state)
        m.jacket.visible = false
        m.leftSleeve.visible = false
        m.rightSleeve.visible = false
        m.leftPants.visible = false
        m.rightPants.visible = false
        m.hat.visible = false
        m.renderToBuffer(stack, out, 15728880, 0, -1)
        stack.popPose()
    }

    /** The skinned pose, unlit, in the theme colour, through walls with back faces culled. */
    private fun ghost(view: Matrix4f, p: Pop) {
        val color = Colors.withAlpha(Theme.primary, p.alpha())
        val stack = PoseStack()
        stack.last().pose().set(view)
        stack.pushPose()
        val pos = p.pos()
        val cam = mc.entityRenderDispatcher.camera!!.position()
        stack.translate((pos.x - cam.x).toFloat(), (pos.y - cam.y).toFloat(), (pos.z - cam.z).toFloat())
        stack.scale(p.state.scale, p.state.scale, p.state.scale)
        Chams.setupRotations(p.state, stack, p.state.bodyRot)
        stack.scale(-1.0f, -1.0f, 1.0f)
        stack.scale(0.9375f, 0.9375f, 0.9375f)
        stack.translate(0.0f, -1.501f, 0.0f)
        p.model.setupAnim(p.state)
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.GHOST, main.colorTextureView!!, null, Matrix4f(), Skins.texture(p.skin)) { vc ->
            p.model.renderToBuffer(stack, Chams.SkinConsumer(vc, color), 15728880, 0, Theme.primary)
        }
        stack.popPose()
    }
}
