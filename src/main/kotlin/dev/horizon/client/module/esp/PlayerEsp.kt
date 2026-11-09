package dev.horizon.client.module.esp

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.combat.Frames
import dev.horizon.client.combat.PlayerShapes
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.config.Friends
import dev.horizon.client.event.EntityAlphaEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.RenderArmorEvent
import dev.horizon.client.event.RenderNameTagEvent
import dev.horizon.client.mixin.LocalPlayerAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode2
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.RenderMode
import dev.horizon.client.module.ShaderMode
import dev.horizon.client.module.legit.Teams
import dev.horizon.client.module.world.FreeCam
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.CameraType
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.awt.Color

/**
 * Types: default = the player models as flat shapes, box = interpolated boxes with a bottom-to-top colour gradient,
 * frame = 2D screen rectangles, skeleton = bone lines, shader = the players through the entity shaders; in MIRROR their
 * real models and all armour are hidden, leaving the glass.
 */
class PlayerEsp : Module("player-esp", Categories.ESP) {
    val types = group("types", GroupMode.BOOLS)
    val renderMode = enum("render-mode", RenderMode.BOTH) { default.value || box.value }
    val shaderMode = enum("shader-mode", ShaderMode.DEFAULT) { shader.value }
    val colorMode = enum("color-mode", ColorMode2.RGB) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val default = bool("default", false, parent = types.value)
    val box = bool("box", false, parent = types.value)
    val frame = bool("frame", false, parent = types.value)
    val shader = bool("shader", false, parent = types.value)
    val skeleton = bool("skeleton", false, parent = types.value)
    val extraPart = bool("extra-part", false) { skeleton.value }
    val shaderColor = color("shader-color", Color(0xFFFFFFFF.toInt(), true)) { shader.value }
    val skeletonColor = color("skeleton-color", Color(0xFFFFFFFF.toInt(), true)) { skeleton.value }
    val skeletonFriendColor = color("skeleton-friend-color", Color(0xFFFFFFFF.toInt(), true)) { skeleton.value }
    val fill = color("fill", Color(0x78FFFFFF.toInt(), true))
    val line = color("line", Color(0xFFFFFFFF.toInt(), true))
    val friendFill = color("friend-fill", Color(0x7800FF00.toInt(), true))
    val friendLine = color("friend-line", Color(0xFF00FF00.toInt(), true))
    val sFill = color("s-fill", Color(0x78FFFFFF.toInt(), true)) { box.value }
    val sLine = color("s-line", Color(0xFFFFFFFF.toInt(), true)) { box.value }
    val sFriendFill = color("s-friend-fill", Color(0x7800FF00.toInt(), true)) { box.value }
    val sFriendLine = color("s-friend-line", Color(0xFF00FF00.toInt(), true)) { box.value }
    val glass = bool("glass", false) { shaderMode.value == ShaderMode.MIRROR && shader.value }
    val border = bool("border", false) { shaderMode.value == ShaderMode.MIRROR && shader.value }
    val radius = float("radius", 6.0f, 0.1f..15.0f) { shaderMode.value == ShaderMode.BLOOM && shader.value }
    val distance = float("distance", 1.3f, 0.1f..15.0f) { shaderMode.value == ShaderMode.BLOOM && shader.value }
    val glowAlpha = float("glow-alpha", 1.7f, 1.0f..5.0f) { shaderMode.value == ShaderMode.BLOOM && shader.value }
    val lineWidth = float("line-width", 4.0f, 1.0f..4.0f) { (shaderMode.value == ShaderMode.DEFAULT || shaderMode.value == ShaderMode.BLOOM) }
    val alpha = float("alpha", 0.2f, 0.0f..2.0f) { shaderMode.value == ShaderMode.MIRROR }
    val secondColor = color("second-color", Color(0xFFFFFFFF.toInt(), true)) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val glowQuality = int("glow-quality", 2, 1..4) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val glowRadius = float("glow-radius", 4.0f, 1.0f..16.0f) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val glowAlphaMultiplier = float("glow-alpha-multiplier", 2.0f, 0.0f..4.0f) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val fillAlphaMultiplier = float("fill-alpha-multiplier", 0.6f, 0.0f..1.0f) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val saturation = float("saturation", 0.7f, 0.0f..5.0f) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val brightness = float("brightness", 2.0f, 0.0f..45.0f) { shaderMode.value == ShaderMode.DOUBLE && shader.value }
    val maxHorizontalDistance = int("max-horizontal-distance", 64, 1..256)
    val maxVerticalDistance = int("max-vertical-distance", 32, 1..256)

    /** The players' chams mask. */
    private val mask = Chams.EntityMask()

    init {
        on<Render3DEvent> { e ->
            if (shader.value) mask.render(e.view, false, maxVerticalDistance.value, maxHorizontalDistance.value)
            if (box.value) boxes()
            if (default.value) PlayerShapes.drawLevel(renderMode.value, fill.value.rgb, line.value.rgb, friendFill.value.rgb,
                friendLine.value.rgb, maxVerticalDistance.value, maxHorizontalDistance.value)
            if (skeleton.value) skeletons()
        }
        on<Render2DEvent> {
            if (frame.value) frames()
            if (shader.value) {
                mask.composite(shaderMode.value, shaderColor.value.rgb, glass.value, border.value, radius.value, distance.value, glowAlpha.value,
                    lineWidth.value, colorMode.value.ordinal, shaderColor.value.rgb, secondColor.value.rgb, glowQuality.value, glowRadius.value,
                    glowAlphaMultiplier.value, fillAlphaMultiplier.value, saturation.value, brightness.value, alpha.value)
            }
        }
        // No player nametags in the mask.
        on<RenderNameTagEvent> { e -> if (e.state is AvatarRenderState && shader.value && mask.active) e.cancel() }
        // MIRROR hides what's drawn outside the mask (every humanoid's armour, other players' models).
        on<RenderArmorEvent> { e -> if (mirrorHides()) e.cancel() }
        on<EntityAlphaEvent> { e ->
            if (!mirrorHides()) return@on
            e.alpha = 0.0f
            e.cancel()
        }
    }

    private fun mirrorHides() = shader.value && shaderMode.value == ShaderMode.MIRROR && !mask.active

    private fun friend(p: Player) = Friends.isFriend(p.gameProfile.name()) || Teams.isEspAlly(p)

    /** The players [boxes] draws: within the distances of the camera entity, yourself only in third person. */
    private fun inRange(): List<Player> {
        val self = mc.player!!
        val cam = (mc.cameraEntity ?: self).position()
        return mc.level!!.entitiesForRendering().filterIsInstance<Player>().filter { p ->
            if (p == self && mc.options.cameraType == CameraType.FIRST_PERSON) return@filter false
            Math.hypot(cam.x - p.x, cam.z - p.z) < maxHorizontalDistance.value && Math.abs(cam.y - p.y) < maxVerticalDistance.value
        }
    }

    /** The player's hitbox at its interpolated position, [fill]/[line] at the bottom and the s-colours at the top. */
    private fun boxes() {
        val pt = World3D.partialTick.toDouble()
        for (p in inRange()) {
            val bb = p.boundingBox
            val pos = Vec3(Mth.lerp(pt, p.xo, p.x), Mth.lerp(pt, p.yo, p.y), Mth.lerp(pt, p.zo, p.z))
            val box = AABB(pos.add(-bb.xsize / 2.0, 0.0, -bb.zsize / 2.0), pos.add(bb.xsize / 2.0, bb.ysize, bb.zsize / 2.0))
            val f = friend(p)
            if (renderMode.value == RenderMode.BOTH || renderMode.value == RenderMode.FILL) {
                World3D.fill(box, (if (f) friendFill else fill).value.rgb, (if (f) sFriendFill else sFill).value.rgb)
            }
            if (renderMode.value == RenderMode.BOTH || renderMode.value == RenderMode.OUTLINE) {
                World3D.outline(box, (if (f) friendLine else line).value.rgb, (if (f) sFriendLine else sLine).value.rgb)
            }
        }
    }

    /**
     * Around every player (yourself only in third person) the screen rectangle of its render box's on-screen
     * corners, a half-unit line in [line] (friends [friendLine]) inside a black border.
     */
    private fun frames() {
        val self = mc.player!!
        for (p in mc.level!!.entitiesForRendering()) {
            if (p !is Player) continue
            if (p == self && mc.options.cameraType == CameraType.FIRST_PERSON) continue
            val r = Frames.screenBox(p) ?: continue
            Frames.draw(r[0], r[1], r[2], r[3], (if (friend(p)) friendLine else line).value.rgb)
        }
    }

    /**
     * Every player's skeleton in smooth lines (yourself not in first person, unless free-cam is on): spine,
     * shoulders, hips, and head, legs and arms along their model parts' rotations; [extraPart] adds a line from the face.
     * Your own body yaw follows the server rotation and your pitch is the last one sent.
     */
    private fun skeletons() {
        val players = mc.level!!.entitiesForRendering().filterIsInstance<Player>()
        if (players.isEmpty()) return
        val pt = World3D.partialTick
        val cam = mc.gameRenderer.mainCamera()
        val segments = ArrayList<Pair<Vector3f, Int>>()
        for (p in players) {
            if (mc.options.cameraType == CameraType.FIRST_PERSON && !Modules.get<FreeCam>().enabled && mc.player == p) continue
            val ap = p as? AbstractClientPlayer ?: continue
            @Suppress("UNCHECKED_CAST")
            val renderer = mc.entityRenderDispatcher.getRenderer(ap) as? AvatarRenderer<AbstractClientPlayer> ?: continue
            // The view rotation and its undoing cancel out: the line vertices are camera-relative, unrotated.
            val stack = PoseStack()
            stack.last().pose().set(World3D.view)
            stack.rotateDegrees(Axis.YP, -cam.yRot() + 180.0f)
            stack.rotateDegrees(Axis.XP, -cam.xRot())
            val color = (if (friend(p)) skeletonFriendColor else skeletonColor).value.rgb
            val c = mc.entityRenderDispatcher.camera!!.position()
            val pos = Vec3(Mth.lerp(pt.toDouble(), p.xo, p.x) - c.x, Mth.lerp(pt.toDouble(), p.yo, p.y) - c.y,
                Mth.lerp(pt.toDouble(), p.zo, p.z) - c.z)
            val model: PlayerModel = renderer.model
            val rs = renderer.createRenderState(ap, pt) as AvatarRenderState
            val previous = RotationManager.previous
            val current = RotationManager.current
            val yaw = if (p == mc.player && previous != null && current != null) Mth.wrapDegrees(Mth.lerp(pt, previous.yaw, current.yaw))
            else Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot)
            val pitch = if (p == mc.player) (mc.player as LocalPlayerAccessor).horizon_xRotLast() else p.getViewXRot(pt)
            model.setupAnim(rs)
            val swim = p.isVisuallySwimming
            val sneak = p.isShiftKeyDown
            val fly = p.isFallFlying
            stack.translate(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
            if (swim) stack.translate(0.0f, 0.35f, 0.0f)
            stack.rotateDegrees(Axis.YN, yaw + 180)
            if (swim || fly) stack.rotateDegrees(Axis.XN, 80 + pitch)
            if (swim) stack.translate(0.0f, -0.95f, 0.0f)
            fun point(x: Float, y: Float, z: Float) {
                segments += stack.last().pose().transformPosition(x, y, z, Vector3f()) to color
            }
            val hip = if (sneak) 0.6f else 0.7f
            val back = if (sneak) 0.23f else 0.0f
            val neck = if (sneak) 1.05f else 1.4f
            val shoulder = if (sneak) 1.05f else 1.35f
            point(0.0f, hip, back); point(0.0f, neck, 0.0f)
            point(-0.37f, shoulder, 0.0f); point(0.37f, shoulder, 0.0f)
            point(-0.15f, hip, back); point(0.15f, hip, back)
            // A bone along a model part's rotation, then back to the body.
            fun bone(part: ModelPart, length: Float) {
                if (part.zRot != 0.0f) stack.rotate(Axis.ZP, part.zRot)
                if (part.yRot != 0.0f) stack.rotate(Axis.YN, part.yRot)
                if (part.xRot != 0.0f) stack.rotate(Axis.XN, part.xRot)
                point(0.0f, 0.0f, 0.0f); point(0.0f, length, 0.0f)
                stack.popPose()
            }
            stack.pushPose()
            stack.translate(0.0f, neck, 0.0f)
            bone(model.head, 0.3f)
            if (extraPart.value) {
                stack.pushPose()
                stack.translate(0.0f, if (sneak) 0.5f else 0.7f, 0.0f)
                stack.rotate(Axis.ZP, model.head.zRot)
                stack.rotate(Axis.YN, model.head.yRot)
                stack.rotate(Axis.XN, 1.1f)
                point(0.0f, 0.0f, 0.0f); point(0.0f, 0.3f, 0.0f)
                stack.popPose()
            }
            stack.pushPose()
            stack.translate(0.15f, hip, back)
            bone(model.rightLeg, -0.6f)
            stack.pushPose()
            stack.translate(-0.15f, hip, back)
            bone(model.leftLeg, -0.6f)
            stack.pushPose()
            stack.translate(0.37f, shoulder, 0.0f)
            bone(model.rightArm, -0.55f)
            stack.pushPose()
            stack.translate(-0.37f, shoulder, 0.0f)
            bone(model.leftArm, -0.55f)
        }
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.LINES, main.colorTextureView!!, null, World3D.view, smooth = true) { vc ->
            for ((v, col) in segments) vc.addVertex(v.x, v.y, v.z).setColor(col)
        }
    }
}
