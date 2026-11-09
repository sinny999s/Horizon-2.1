package dev.horizon.client.lunar

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.render.Canvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Lunar's logo and badge by a Lunar player's name, laid out as Lunar Client lays them out.
 * - Name tags: the logo (10 px, tinted the player's logo colour, with a small plus in their Lunar+ colour on its top
 *   right) before the name and the badge (8 px) after it, each in 12 px of the tag, which widens to fit. Used for
 *   vanilla's name tags (`misc2/LunarNameTagMixin`) and Name Tags' plates.
 * - Tab list: the logo, then the badge, before the name, 9 px each (`misc2/LunarTabMixin`).
 * Which players: [LunarNetwork.peer]. Lunar Spoof's `nametag-logo`, `nametag-badge` and `tab-badge` turn each off, as
 * Lunar's own settings of the same names do.
 */
object LunarNameTag {
    private val LOGO = Identifier.fromNamespaceAndPath("horizon", "textures/lunar_logo.png")
    private val PLUS = Identifier.fromNamespaceAndPath("horizon", "textures/lunar_plus.png")
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** The tab list's icon size and how far each icon moves the name. */
    const val TAB_ICON = 8
    const val TAB_SHIFT = TAB_ICON + 1

    /** Each name tag icon's room in the tag (Lunar's 12) and size. */
    const val TAG_ROOM = 12
    const val TAG_LOGO = 10
    const val TAG_BADGE = 8


    /** What to show by [uuid]'s name tag: (logo, badge sprite or null), or null for nothing. */
    class Marks(val peer: LunarNetwork.Peer, val logo: Boolean, val badge: LunarBadges.Sprite?) {
        val lead get() = if (logo) TAG_ROOM else 0
        val tail get() = if (badge != null) TAG_ROOM else 0
    }

    fun marks(uuid: UUID): Marks? {
        val peer = LunarNetwork.peer(uuid) ?: return null
        val logo = LunarSettings.nametagLogo.value
        val badge = if (LunarSettings.nametagBadge.value) LunarBadges.sprite(peer.badge) else null
        if (!logo && badge == null) return null
        return Marks(peer, logo, badge)
    }

    // ---- GUI (tab list, Name Tags) ----

    /** Lunar's logo at [x], [y], [size] square, tinted [argb], with the Lunar+ plus ([plus], 0 for none) on its top right. */
    fun drawLogo(g: GuiGraphicsExtractor, x: Float, y: Float, size: Float, argb: Int, plus: Int) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(size / 100f, size / 100f)
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, 0, 0, 0f, 0f, 100, 100, 100, 100, 100, 100, argb)
        // Lunar's: 3 px at (7.25, 0.5) on a 10 px logo.
        if (plus != 0) g.blit(RenderPipelines.GUI_TEXTURED, PLUS, 72, 5, 0f, 0f, 30, 30, 16, 16, 16, 16, plus)
        pose.popMatrix()
    }

    /** A badge's current frame at [x], [y], [size] square. */
    fun drawBadge(g: GuiGraphicsExtractor, x: Float, y: Float, size: Float, s: LunarBadges.Sprite) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(size / 64f, size / 64f)
        // The texture's frames are stacked; 64 units across, [v0, v1) of its height.
        val frames = Math.round(1f / (s.v1 - s.v0))
        g.blit(RenderPipelines.GUI_TEXTURED, s.texture, 0, 0, 0f, Math.round(s.v0 * frames) * 64f, 64, 64, 64, 64, 64, 64 * frames, -1)
        pose.popMatrix()
    }

    /** How far right the tab list moves [uuid]'s name for Lunar's icons (0: none). */
    @JvmStatic
    fun tabShift(uuid: UUID): Int {
        val peer = LunarNetwork.peer(uuid) ?: return 0
        return TAB_SHIFT + if (tabBadge(peer) != null) TAB_SHIFT else 0
    }

    private fun tabBadge(peer: LunarNetwork.Peer): LunarBadges.Sprite? =
        if (LunarSettings.tabBadge.value) LunarBadges.sprite(peer.badge) else null

    /** Lunar's logo, then badge, at [x], [y] in the tab list; returns how far the name moves. */
    @JvmStatic
    fun drawTab(g: GuiGraphicsExtractor, x: Int, y: Int, uuid: UUID): Int {
        val peer = LunarNetwork.peer(uuid) ?: return 0
        drawLogo(g, x.toFloat(), y.toFloat(), TAB_ICON.toFloat(), peer.logo, peer.plus)
        val badge = tabBadge(peer) ?: return TAB_SHIFT
        drawBadge(g, (x + TAB_SHIFT).toFloat(), y.toFloat(), TAB_ICON.toFloat(), badge)
        return TAB_SHIFT * 2
    }

    /**
     * Name Tags' plate: the logo at the start and the badge at the end of a plate [width] wide from [left], 12 high from
     * [top] (Horizon's Canvas, so they layer like the plate's text).
     */
    fun drawPlate(marks: Marks, left: Float, top: Float, width: Float) {
        if (marks.logo) {
            canvas(LOGO, left, top + 1f, TAG_LOGO.toFloat(), TAG_LOGO.toFloat(), marks.peer.logo)
            if (marks.peer.plus != 0) canvas(PLUS, left + 7.25f, top + 1.5f, 3f, 3f, marks.peer.plus)
        }
        marks.badge?.let { canvas(it.texture, left + width - (TAG_ROOM - 2), top + 2f, TAG_BADGE.toFloat(), TAG_BADGE.toFloat(), -1, it.v0, it.v1) }
    }

    /** Lunar's logo (with the Lunar+ plus) at [x], [y], [size] square, on Horizon's Canvas (the friends list). */
    fun canvasLogo(x: Float, y: Float, size: Float, argb: Int, plus: Int) {
        canvas(LOGO, x, y, size, size, argb)
        if (plus != 0) canvas(PLUS, x + size * 0.725f, y + size * 0.05f, size * 0.3f, size * 0.3f, plus)
    }

    /** Badge [id] at [x], [y], [size] square, on Horizon's Canvas; false until its picture is in. */
    fun canvasBadge(id: Int, x: Float, y: Float, size: Float): Boolean {
        val s = LunarBadges.sprite(id) ?: return false
        canvas(s.texture, x, y, size, size, -1, s.v0, s.v1)
        return true
    }

    private fun canvas(id: Identifier, x: Float, y: Float, w: Float, h: Float, argb: Int, v0: Float = 0f, v1: Float = 1f) =
        Canvas.texture(mc.textureManager.getTexture(id).textureView, x, y, w, h, argb, argb, argb, argb, 0f, v0, 1f, v1)

    // ---- vanilla name tags ----

    /** [name] with room for the icons: spaces before and after, as wide as [Marks.lead] and [Marks.tail]. */
    @JvmStatic
    fun padded(name: Component, marks: Marks): Component {
        val space = maxOf(1, mc.font.width(" "))
        val out = Component.empty()
        if (marks.lead > 0) out.append(" ".repeat((marks.lead + space - 1) / space))
        out.append(name)
        if (marks.tail > 0) out.append(" ".repeat((marks.tail + space - 1) / space))
        return out
    }

    /**
     * Lunar's icons on a vanilla name tag: the same place and turn as [SubmitNodeCollector.submitNameTag] gives the
     * text ([padded], at [y]), drawn through walls faintly when [seeThrough] as the text is.
     */
    @JvmStatic
    fun submit(collector: SubmitNodeCollector, poseStack: PoseStack, attachment: Vec3, y: Int, padded: Component, marks: Marks,
               seeThrough: Boolean, light: Int, camera: CameraRenderState) {
        val width = mc.font.width(padded)
        val left = -width / 2f
        poseStack.pushPose()
        poseStack.translate(attachment.x, attachment.y + 0.5, attachment.z)
        poseStack.rotate(camera.orientation)
        poseStack.scale(0.025f, -0.025f, 0.025f)
        val passes = if (seeThrough) listOf(true, false) else listOf(false)
        for (through in passes) {
            val alpha = if (through) 0x40 else 0xFF
            val lit = light
            if (marks.logo) {
                val x = left
                val top = y - 1f
                collector.submitCustomGeometry(poseStack, if (through) RenderTypes.textSeeThrough(LOGO) else RenderTypes.text(LOGO)) { pose, c ->
                    quad(c, pose, x, top, TAG_LOGO.toFloat(), TAG_LOGO.toFloat(), 0f, 1f, recolor(marks.peer.logo, alpha), lit)
                }
                if (marks.peer.plus != 0) {
                    collector.submitCustomGeometry(poseStack, if (through) RenderTypes.textSeeThrough(PLUS) else RenderTypes.text(PLUS)) { pose, c ->
                        quad(c, pose, x + 7.25f, top + 1.5f, 3f, 3f, 0f, 1f, recolor(marks.peer.plus, alpha), lit, -0.01f)
                    }
                }
            }
            marks.badge?.let { s ->
                val x = left + width - (TAG_ROOM - 2)
                collector.submitCustomGeometry(poseStack, if (through) RenderTypes.textSeeThrough(s.texture) else RenderTypes.text(s.texture)) { pose, c ->
                    quad(c, pose, x, y - 0.5f, TAG_BADGE.toFloat(), TAG_BADGE.toFloat(), s.v0, s.v1, recolor(-1, alpha), lit)
                }
            }
        }
        poseStack.popPose()
    }

    private fun recolor(argb: Int, alpha: Int) = (argb and 0xFFFFFF) or (((argb ushr 24) * alpha / 255) shl 24)

    /** A quad facing the camera in name tag space (y down), slightly in front of the tag's background. */
    private fun quad(c: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, w: Float, h: Float, v0: Float, v1: Float, argb: Int, light: Int, z: Float = 0f) {
        c.addVertex(pose, x, y, z).setColor(argb).setUv(0f, v0).setLight(light)
        c.addVertex(pose, x, y + h, z).setColor(argb).setUv(0f, v1).setLight(light)
        c.addVertex(pose, x + w, y + h, z).setColor(argb).setUv(1f, v1).setLight(light)
        c.addVertex(pose, x + w, y, z).setColor(argb).setUv(1f, v0).setLight(light)
    }
}
