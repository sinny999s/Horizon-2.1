package dev.horizon.client.render

import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.mixin.GuiGraphicsExtractorAccessor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f

/**
 * The animated backdrop every screen out of a world shows instead of the panorama (MenuPanoramaMixin).
 * One full-screen quad drawn with `shaders/core/menu_background`: black through white only, a horizon with a soft white
 * light over it, fog drifting along it and dust in the air; time goes to the shader in the quad's UVs, the theme colour
 * (unused by the shader now) in its vertex colour. It
 * is a vanilla GUI element, so vanilla's widgets and the client's 2D layer draw over it. Going between the title screen
 * and the others, it eases from one version to the other (about a quarter of a second) instead of switching, and its
 * time is kept here, slowing while calm, so the smoke never jumps.
 */
object Backdrop {
    private var last = 0L
    private var phase = 0f
    private var mode = -1f

    /** Fills [w] x [h] GUI pixels; [calm] is the slower, darker version for the screens under the title screen. */
    fun draw(graphics: GuiGraphicsExtractor, w: Int, h: Int, calm: Boolean) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        val target = if (calm) 1f else 0f
        if (mode < 0f) mode = target
        mode += (target - mode) * minOf(1f, dt * 8f)
        phase = (phase + dt * (1f - 0.3f * mode)) % 3600f
        val seconds = phase
        val calmness = mode
        val accent = Theme.primary or (0xFF shl 24)
        val pose = Matrix3x2f()
        val bounds = ScreenRectangle(0, 0, w, h)
        graphics.nextStratum()
        (graphics as GuiGraphicsExtractorAccessor).horizon_guiRenderState().addGuiElement(object : GuiElementRenderState {
            override fun buildVertices(vc: VertexConsumer) {
                vc.addVertexWith2DPose(pose, 0f, 0f).setUv(seconds, calmness).setColor(accent)
                vc.addVertexWith2DPose(pose, 0f, h.toFloat()).setUv(seconds, calmness).setColor(accent)
                vc.addVertexWith2DPose(pose, w.toFloat(), h.toFloat()).setUv(seconds, calmness).setColor(accent)
                vc.addVertexWith2DPose(pose, w.toFloat(), 0f).setUv(seconds, calmness).setColor(accent)
            }

            override fun pipeline() = Pipelines.MENU_BACKGROUND

            override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

            override fun scissorArea(): ScreenRectangle? = null

            override fun bounds(): ScreenRectangle = bounds
        })
    }
}
