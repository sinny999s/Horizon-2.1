package dev.horizon.client.module.esp

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.config.Friends
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.gui.Fps
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.legit.Teams
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import java.awt.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * An arrow per player around the crosshair pointing at them (tilted like a compass), or in
 * LINES mode a line from in front of the camera to each player.
 */
class Tracers : Module("tracers", Categories.ESP) {
    enum class Mode(val sprite: FloatArray) {
        ARROWS_1(Esp2.TRIANGLE1), ARROWS_2(Esp2.TRIANGLE2), ARROWS_3(Esp2.TRIANGLE3), ARROWS_4(Esp2.TRIANGLE4),
        ARROWS_5(Esp2.TRIANGLE5), LINES(Esp2.TRIANGLE1)
    }

    val mode = enum("mode", Mode.ARROWS_1)
    val flat = bool("flat", false)
    val onlyOffScreen = bool("only-off-screen", false)
    val friend = color("friend", Color(0xFF00FF00.toInt(), true))
    val teamColors = bool("team-colors", false)

    /** Arrow tilt floor and ring radius. */
    private val tilt = Animation(Easing.OUT_QUINT, 300)
    private val radius = Animation(Easing.OUT_QUINT, 300)

    /** The smoothed yaw the arrows turn with. */
    private var yaw = 0f

    init {
        on<Render2DEvent> { arrows() }
        on<Render3DEvent> { lines() }
    }

    private fun colorOf(p: Player, gradientOffset: Int): Int {
        var c = if (Friends.isFriend(p.gameProfile.name) || Teams.isEspAlly(p)) friend.value.rgb else Theme.gradient(gradientOffset)
        if (teamColors.value) Teams.resolveColor(p)?.let { c = Color(it).rgb }
        return c
    }

    private fun arrows() {
        if (mode.value == Mode.LINES) return
        val me = mc.player ?: return
        val screen = mc.gui.screen()
        val inScreen = screen != null && screen !is ChatScreen
        tilt.update(if (flat.value || inScreen) 90f else 42f)
        radius.update(if (inScreen) 150f else 40f)
        val cx = mc.window.guiScaledWidth / 2f
        val cy = mc.window.guiScaledHeight / 2f
        yaw = Fps.smooth(yaw, me.yRot, 13f)
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble()
        for ((index, p) in PlayerState.players.withIndex()) {
            if (p == mc.player) continue
            if (onlyOffScreen.value && World3D.isVisible(p.boundingBox)) continue
            val ps = PoseStack()
            ps.pushPose()
            ps.translate(cx, cy, 0f)
            val pitch = min(90f, max(me.xRot, tilt.value))
            ps.rotateDegrees(Axis.XP, abs(pitch) - 102)
            ps.translate(-cx, -cy, 0f)
            val x = Mth.lerp(pt, p.xo, p.x)
            val z = Mth.lerp(pt, p.zo, p.z)
            val mx = Mth.lerp(pt, me.xo, me.x)
            val mz = Mth.lerp(pt, me.zo, me.z)
            val angle = Mth.wrapDegrees(Math.toDegrees(atan2(z - mz, x - mx)) - 90.0).toFloat() - yaw
            ps.translate(cx, cy, 0f)
            ps.rotateDegrees(Axis.ZP, angle)
            ps.translate(-cx, -cy, 0f)
            arrow(ps, cx, cy - radius.value, 18f, colorOf(p, index * 30))
            ps.popPose()
        }
    }

    /** The mode's atlas triangle, [size] square, centred on ([x], [y]), additive; the GPS uses it too. */
    fun arrow(ps: PoseStack, x: Float, y: Float, size: Float, color: Int) {
        val uv = mode.value.sprite
        val hw = size / 2f
        val hh = size / 2f
        Esp2.drawGui(Pipelines.COLORED_TEXTURE_ADDITIVE, Skins.atlas(), ps.last().pose()) { vc ->
            // The first corner is offset by the half height, not the half width (the same for a square).
            vc.addVertex(x - hh, y + hh, 0f).setUv(uv[0], uv[3]).setColor(color)
            vc.addVertex(x + hw, y + hh, 0f).setUv(uv[2], uv[3]).setColor(color)
            vc.addVertex(x + hw, y - hh, 0f).setUv(uv[2], uv[1]).setColor(color)
            vc.addVertex(x - hw, y - hh, 0f).setUv(uv[0], uv[1]).setColor(color)
        }
    }

    /** LINES mode. */
    private fun lines() {
        if (mode.value != Mode.LINES) return
        val level = mc.level ?: return
        for (p in level.players()) {
            if (p == mc.player) continue
            val box = Esp2.renderBox(p)
            World3D.line(Esp2.tracerStart(), box.center, colorOf(p, 30), noCull = true)
        }
    }
}
