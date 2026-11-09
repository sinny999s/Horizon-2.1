package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import dev.horizon.client.event.PostRenderHandEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.RenderHandEvent
import dev.horizon.client.mixin.cosmetic1.GameRendererAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode2
import dev.horizon.client.module.Module
import dev.horizon.client.module.ShaderMode
import dev.horizon.client.render.Chams
import dev.horizon.client.render.World3D
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.GameType
import org.joml.Matrix4f
import java.awt.Color

/**
 * The first-person hands are also drawn into a mask each frame, and after the hands the
 * mask goes on the screen through the chams shader of [mode]. This module draws the hands itself (vanilla's are skipped
 * once its own were drawn).
 */
class HandChams : Module("hand-chams", Categories.COSMETIC) {
    val mode = enum("mode", ShaderMode.MIRROR)
    val colorSetting = color("color", Color(0xFFC8C8FF.toInt(), true))
    val glass = bool("glass", false) { mode.value == ShaderMode.MIRROR }
    val alpha = float("alpha", 0.2f, 0.0f..2.0f) { mode.value == ShaderMode.MIRROR }
    val border = bool("border", false) { mode.value == ShaderMode.MIRROR }
    val radius = float("radius", 6.0f, 0.0f..15.0f) { mode.value == ShaderMode.BLOOM }
    val distance = float("distance", 1.3f, 0.0f..15.0f) { mode.value == ShaderMode.BLOOM }
    val glowAlpha = float("glow-alpha", 1.7f, 1.0f..5.0f) { mode.value == ShaderMode.BLOOM }
    val lineWidth = float("line-width", 4.0f, 1.0f..4.0f) { (mode.value == ShaderMode.DEFAULT || mode.value == ShaderMode.BLOOM || mode.value == ShaderMode.CAMOUFLAGE) }
    val colorMode = enum("color-mode", ColorMode2.RGB) { mode.value == ShaderMode.DOUBLE }
    val secondColor = color("second-color", Color(0xFFC8C8FF.toInt(), true)) { (mode.value == ShaderMode.DOUBLE || mode.value == ShaderMode.CAMOUFLAGE) }
    val glowQuality = int("glow-quality", 2, 1..4) { mode.value == ShaderMode.DOUBLE }
    val glowRadius = float("glow-radius", 4.0f, 1.0f..16.0f) { mode.value == ShaderMode.DOUBLE }
    val glowAlphaMultiplier = float("glow-alpha-multiplier", 2.0f, 0.0f..4.0f) { mode.value == ShaderMode.DOUBLE }
    val fillAlphaMultiplier = float("fill-alpha-multiplier", 0.6f, 0.0f..1.0f) { mode.value == ShaderMode.DOUBLE }
    val saturation = float("saturation", 0.7f, 0.0f..5.0f) { mode.value == ShaderMode.DOUBLE }
    val brightness = float("brightness", 2.0f, 0.0f..45.0f) { mode.value == ShaderMode.DOUBLE }

    /** The hands' mask and BLOOM's scratch. */
    private var mask: TextureTarget? = null
    private var secondary: TextureTarget? = null

    /** Drawing into or out of the mask. */
    private var drawing = false

    /** True once this module drew the hands, so vanilla's are skipped from then on. */
    private var hidden = false

    init {
        on<RenderHandEvent>(-10) { e -> if (drawing || hidden) e.cancel() }
        on<PostRenderHandEvent>(-10) { afterHands() }
        on<Render3DEvent>(-10) {
            drawing = true
            mask = Chams.ensure(mask, "horizon hand chams")
            secondary = Chams.ensure(secondary, "horizon hand chams 2")
            drawHands(mask)
            drawing = false
        }
    }

    /** The hands drawn for real (on a cleared depth), then their chams over them. */
    private fun afterHands() {
        val m = mask ?: return
        val s = secondary ?: return
        val main = mc.gameRenderer.mainRenderTarget()
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(main.depthTexture!!, 0.0)
        drawHands(null)
        drawing = true
        Chams.composite(mode.value, colorSetting.value.rgb, m, s, glass.value, border.value, radius.value, distance.value, glowAlpha.value,
            lineWidth.value, colorMode.value.ordinal, colorSetting.value.rgb, secondColor.value.rgb, glowQuality.value, glowRadius.value,
            glowAlphaMultiplier.value, fillAlphaMultiplier.value, saturation.value, brightness.value, alpha.value)
        drawing = false
    }

    /** [drawHandsNow] with the projection restored afterwards. */
    private fun drawHands(target: TextureTarget?) {
        RenderSystem.backupProjectionMatrix()
        hidden = false
        drawHandsNow(target)
        hidden = true
        RenderSystem.restoreProjectionMatrix()
    }

    /**
     * Vanilla's first-person hands (hud projection with a far plane of 100, hurt tilt, view bob) onto
     * [target], or onto the screen when null.
     */
    private fun drawHandsNow(target: TextureTarget?) {
        val gr = mc.gameRenderer
        val acc = gr as GameRendererAccessor
        val state = gr.gameRenderState()
        val cam = state.levelRenderState.cameraRenderState
        val window = state.windowRenderState
        val proj = acc.horizon_hudProjection()
        proj.setupPerspective(0.05f, 100.0f, cam.hudFov, window.width.toFloat(), window.height.toFloat())
        RenderSystem.setProjectionMatrix(acc.horizon_hud3dProjectionMatrixBuffer().getBuffer(proj), ProjectionType.PERSPECTIVE)
        val view = cam.viewRotationMatrix
        val stack = PoseStack()
        stack.pushPose()
        stack.mulPose(view.invert(Matrix4f()))
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix().mul(view)
        acc.horizon_bobHurt(cam, stack)
        if (mc.options.bobView().get()) acc.horizon_bobView(cam, stack)
        val sleeping = (mc.cameraEntity as? LivingEntity)?.isSleeping == true
        val main = gr.mainRenderTarget()
        val color = (target ?: main).colorTextureView!!
        val depth = (target ?: main).depthTextureView!!
        Chams.draw(color, depth) { storage ->
            if (mc.options.cameraType.isFirstPerson && !sleeping && mc.gameMode?.playerMode != GameType.SPECTATOR) {
                val player = state.levelRenderState.playerRenderState
                val hands = player.firstPersonHandsAndItems
                if (hands != null) gr.firstPersonHandsAndItemsRenderer.submitHandsWithItems(World3D.partialTick, stack, storage, player, hands)
            }
        }
        modelView.popMatrix()
        stack.popPose()
    }
}
