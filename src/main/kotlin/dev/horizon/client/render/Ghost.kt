package dev.horizon.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.hud.widgets.Skins
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/** Player ghost: the player's skinned model, unlit, in one colour, at [pos]. */
object Ghost {
    private val mc get() = Minecraft.getInstance()

    /** Takes each model vertex's (already transformed) position and UV, with one colour for all. */
    private class Collector(val out: VertexConsumer, val color: Int) : VertexConsumer {
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
            out.addVertex(x, y, z).setUv(u, v).setColor(color)
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

    fun draw(pos: Vec3, player: Player, alpha: Float, depthTest: Boolean) {
        val p = player as? AbstractClientPlayer ?: return
        @Suppress("UNCHECKED_CAST")
        val renderer = mc.entityRenderDispatcher.getRenderer(p) as? AvatarRenderer<AbstractClientPlayer> ?: return
        val model: PlayerModel = renderer.model
        val rs = renderer.createRenderState(p, World3D.partialTick) as AvatarRenderState
        val color = if (alpha == 1f) (255 shl 24) or 0xCCCCCC else PlaceBoxes.withAlpha(Theme.primary, alpha)
        val cam = World3D.camera
        val stack = PoseStack()
        stack.last().pose().set(World3D.view)
        stack.pushPose()
        stack.translate((pos.x - cam.x).toFloat(), (pos.y - cam.y).toFloat(), (pos.z - cam.z).toFloat())
        stack.scale(rs.scale, rs.scale, rs.scale)
        setupRotations(rs, stack, rs.bodyRot)
        stack.scale(-1f, -1f, 1f)
        stack.scale(0.9375f, 0.9375f, 0.9375f)
        stack.translate(0f, -1.501f, 0f)
        model.setupAnim(rs)
        val main = mc.gameRenderer.mainRenderTarget()
        val pipeline = if (depthTest) Pipelines.GHOST_DEPTH else Pipelines.GHOST
        World3D.draw(pipeline, main.colorTextureView!!, if (depthTest) main.depthTextureView else null, Matrix4f(),
            Skins.skin(p)) { vc -> model.renderToBuffer(stack, Collector(vc, color), 15728880, 0, color) }
        stack.popPose()
    }

    /** Vanilla's rotations, without the death roll, sleeping, upside-down and freezing. */
    private fun setupRotations(rs: AvatarRenderState, stack: PoseStack, bodyRot: Float) {
        if (rs.isFallFlying) {
            stack.rotateDegrees(Axis.YP, 180f - bodyRot)
            if (!rs.isAutoSpinAttack) stack.rotateDegrees(Axis.XP, rs.fallFlyingScale() * (-90f - rs.xRot))
            if (rs.shouldApplyFlyingYRot) stack.rotate(Axis.YP, rs.flyingYRot)
            return
        }
        if (rs.swimAmount > 0f) {
            stack.rotateDegrees(Axis.YP, 180f - bodyRot)
            stack.rotateDegrees(Axis.XP, Mth.lerp(rs.swimAmount, 0f, if (rs.isInWater) -90f - rs.xRot else -90f))
            if (rs.isVisuallySwimming) stack.translate(0f, -1f, 0.3f)
            return
        }
        stack.rotateDegrees(Axis.YP, 180f - bodyRot)
    }
}
