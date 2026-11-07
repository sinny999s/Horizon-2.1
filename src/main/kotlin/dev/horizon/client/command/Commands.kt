package dev.horizon.client.command

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.math.Axis
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.InputSuggestorEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.SendMessageEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.module.esp.Tracers
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The original's command manager (`vc`): chat lines starting with [prefix] run a client command instead of being sent, the
 * chat box completes them, and [gps] (set by `gps`, or by Team Communication) draws a compass arrow at the top.
 */
object Commands {
    private val mc get() = Minecraft.getInstance()

    /** `vc.N`. */
    var prefix = "^"

    /** `vc.L`: where the GPS arrow points (y unused). */
    var gps: BlockPos? = null

    /** `vc.g`. */
    val list = ArrayList<ClientCommand>()

    /** `vc.C`. */
    val dispatcher = CommandDispatcher<ClientSuggestionProvider>()

    fun init() {
        for (c in listOf(FixAllCommand, BindCommand, ConfigCommand, NeofetchCommand, AngleCommand, GpsCommand, FriendCommand, WaypointCommand, WpCommand, CoordsCommand, UpdateCommand, HypixelCommand)) add(c)
        EventBus.on<SendMessageEvent>(this) { e ->
            val p = if (e.message.startsWith(prefix)) prefix else if (e.message.startsWith(".")) "." else null
            if (p == null) return@on
            try {
                dispatcher.execute(e.message.substring(p.length), mc.connection!!.suggestionsProvider)
            } catch (_: CommandSyntaxException) {
            }
            e.cancel()
        }
        EventBus.on<InputSuggestorEvent>(this) { e ->
            e.source = mc.connection?.suggestionsProvider
            e.prefix = prefix
            e.dispatcher = dispatcher
            e.cancel()
        }
        EventBus.on<Render2DEvent>(this) { gpsArrow() }
    }

    /** `vc.c`: registered once per name. */
    fun add(c: ClientCommand) {
        if (list.any { it.name == c.name }) return
        c.register(dispatcher)
        list += c
    }

    /** `vc.y` (Render2D): the arrow towards [gps], tilted with the pitch like Tracers', and the distance under it. */
    private fun gpsArrow() {
        val target = gps ?: return
        val p = mc.player ?: return
        val cx = mc.window.guiScaledWidth / 2.0f
        val cy = 40.0f
        val ps = PoseStack()
        ps.pushPose()
        ps.translate(cx, cy, 0.0f)
        ps.rotateDegrees(Axis.XP, 90.0f / abs(90.0f / Math.clamp(55.0f, p.xRot, 90.0f)) - 102)
        ps.translate(-cx, -cy, 0.0f)
        ps.pushPose()
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble()
        val x = Mth.lerp(pt, p.xo, p.x)
        val z = Mth.lerp(pt, p.zo, p.z)
        val angle = Mth.wrapDegrees(Math.toDegrees(atan2(target.z - z, target.x - x)) - 90.0).toFloat() - p.yRot
        ps.translate(cx, cy, 0.0f)
        ps.rotateDegrees(Axis.ZP, angle)
        ps.translate(-cx, -cy, 0.0f)
        Modules.get<Tracers>().arrow(ps, cx, cy, 32.0f, Theme.gradient(0))
        ps.translate(cx, cy, 0.0f)
        ps.rotateDegrees(Axis.ZP, -angle)
        ps.translate(-cx, -cy, 0.0f)
        ps.popPose()
        ps.popPose()
        val center = Vec3.atCenterOf(target)
        val dx = p.x - center.x
        val dz = p.z - center.z
        val text = sqrt((dx * dx + dz * dz).toFloat()).toInt().toString() + "m"
        val w = Fonts.medium75.width(text)
        Canvas.glass(cx - w / 2.0f - 2.0f, 56.0f, w + 4.0f, 11.0f, 3.0f, 1.5f, 0.0f, 1.0f, Theme.widgetBlur, Theme.outline)
        Fonts.medium75.drawCentered(text, cx, 60.0f, -1)
    }
}
