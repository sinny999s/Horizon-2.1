package dev.horizon.client.module.esp

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.combat.PlayerShapes
import dev.horizon.client.combat.Projection
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Icon
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.entity.player.Player
import org.joml.Matrix4f
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * When a player in view leaves the server, their model stays where they were (a primary-coloured ghost, or the
 * player-esp style shape in [colorSetting]) with a tag: name, health and time since, typed out over half a second.
 * Logging back in removes it; [timeOut] removes it after [timeOutValue] seconds.
 */
class LogoutSpots : Module("logout-spots", Categories.ESP) {
    enum class RenderMode { DEFAULT, GHOST }

    val renderMode = enum("render-mode", RenderMode.GHOST)
    val colorSetting = color("color", Color(0xFFFFFFFF.toInt(), true))
    val friends = bool("friends", false)
    val maxHorizontalDistance = int("max-horizontal-distance", 64, 1..256)
    val maxVerticalDistance = int("max-vertical-distance", 32, 1..256)
    val timeOut = bool("time-out", false)
    val timeOutValue = float("time-out-value", 1.0f, 0.0f..3600.0f)

    private data class Spot(val id: UUID, val player: Player, val time: Long)

    /** The players seen in the level (by profile id), until the next player-info update. */
    private val seen = ConcurrentHashMap<UUID, Player>()
    private val spots = CopyOnWriteArrayList<Spot>()
    private val icon = Icon("no-connection", 10f, 10f)

    /** The spots for the hunt map (name, where, when they logged out). */
    fun spotsForMap(): List<Triple<String, Vec3, Long>> = spots.map { Triple(it.player.name.string, it.player.position(), it.time) }

    init {
        on<ReceivePacketEvent> { e ->
            when (val packet = e.packet) {
                is ClientboundPlayerInfoUpdatePacket -> {
                    if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                        for (entry in packet.newEntries()) spots.removeIf { s ->
                            if (s.id != entry.profile()?.id()) return@removeIf false
                            message("%s was logged back on X:%d Y:y%d Z:%d", s.player)
                            true
                        }
                    }
                    seen.clear()
                }
                is ClientboundPlayerInfoRemovePacket -> for (id in packet.profileIds()) for (key in seen.keys) {
                    if (key != id) continue
                    val p = seen[key] ?: continue
                    if (!friends.value && Friends.isFriend(p.gameProfile.name())) continue
                    message("%s was logged out on X:%d Y:%d Z:%d", p)
                    spots += Spot(key, p, System.currentTimeMillis())
                }
                else -> {}
            }
        }
        on<PlayerUpdateEvent> {
            for (p in mc.level!!.players()) {
                if (p == mc.player) continue
                if (botName(p)) continue
                seen[p.gameProfile.id()] = p
            }
            if (timeOut.value) {
                for (s in ArrayList(spots)) {
                    if ((System.currentTimeMillis() - s.time).toFloat() > timeOutValue.value * 1000) spots.remove(s)
                }
            }
        }
        on<Render3DEvent> {
            when (renderMode.value) {
                RenderMode.GHOST -> for (s in ArrayList(spots)) {
                    val age = (System.currentTimeMillis() - s.time).toFloat()
                    ghost(s.player, if (timeOut.value) 1.0f - age / (timeOutValue.value * 1000.0f) else maxOf(0.4f, 1.0f - age / 800.0f))
                }
                RenderMode.DEFAULT -> PlayerShapes.drawList(dev.horizon.client.module.RenderMode.BOTH, colorSetting.value.rgb,
                    Colors.withAlpha(colorSetting.value.rgb, 1.0f), spots.map { it.player }, maxVerticalDistance.value,
                    maxHorizontalDistance.value)
            }
        }
        on<Render2DEvent> {
            for (s in ArrayList(spots)) tag(s)
        }
    }

    override fun onEnable() {
        seen.clear()
        spots.clear()
    }

    /** Names with "-" or "_" are never tracked. */
    private fun botName(p: Player): Boolean {
        val name = p.gameProfile.name()
        return name.contains("-") || name.contains("_")
    }

    private fun message(format: String, p: Player) =
        moduleMessage(name.trim(), format.format(p.name.string, p.x.toInt(), p.y.toInt(), p.z.toInt()))

    /** One spot's tag: a glass pill above the head with the icon and the typed-out text. */
    private fun tag(s: Spot) {
        val now = System.currentTimeMillis()
        val alpha = if (timeOut.value) 1.0f - (now - s.time).toFloat() / (timeOutValue.value * 1000.0f) else 1.0f
        val box = Projection.box(s.player.position().add(0.0, 2.0, 0.0))
        val typed = (now - s.time).toFloat() / 500.0f
        val seconds = ((now - s.time) / 1000.0).toInt()
        var text = s.player.name.string + " " + (s.player.health + s.player.absorptionAmount).toInt() + " " + seconds / 60 + ":" + seconds % 60
        text = text.dropLast(maxOf(0, (text.length * (1.0f - typed)).toInt()))
        if (box == null) return
        val half = (box.z - box.x).toFloat() / 2.0f
        val w = Fonts.bold8.width(text) + 18.0f
        val x = (box.x + half - w / 2.0f).toFloat()
        val a = alpha * Colors.alphaF(Theme.widgetBlur)
        Canvas.glass(x - 2, box.y.toFloat() - 13.0f, w, 12.0f, 2.0f, 1.5f, 0.0f, 1.0f, Colors.withAlpha(Theme.widgetBlur, a),
            Colors.withAlpha(Theme.outline, a))
        icon.draw(x, (box.y.toFloat() - 13.5).toFloat(), primary = Colors.withAlpha(Theme.text, a))
        Fonts.bold8.draw(text, x + 12.0f, box.y.toFloat() - 13.0f, Colors.withAlpha(Theme.text, a))
    }

    /** The model's positions and UVs, one colour for every vertex. */
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

    /**
     * The skinned model, unlit, in the theme's primary colour with [alpha], through walls with back faces culled,
     * posed as at the moment the player left.
     */
    private fun ghost(player: Player, alpha: Float) {
        val p = player as? AbstractClientPlayer ?: return
        @Suppress("UNCHECKED_CAST")
        val renderer = mc.entityRenderDispatcher.getRenderer(p) as? AvatarRenderer<AbstractClientPlayer> ?: return
        val model: PlayerModel = renderer.model
        val rs = renderer.createRenderState(p, 1.0f) as AvatarRenderState
        val color = Colors.withAlpha(Theme.primary, alpha)
        val cam = mc.entityRenderDispatcher.camera!!.position()
        val pos = p.position()
        val stack = PoseStack()
        stack.last().pose().set(World3D.view)
        stack.pushPose()
        stack.translate((pos.x - cam.x).toFloat(), (pos.y - cam.y).toFloat(), (pos.z - cam.z).toFloat())
        stack.scale(rs.scale, rs.scale, rs.scale)
        PlayerShapes.setupRotations(rs, stack, rs.bodyRot)
        stack.scale(-1.0f, -1.0f, 1.0f)
        stack.scale(0.9375f, 0.9375f, 0.9375f)
        stack.translate(0.0f, -1.501f, 0.0f)
        model.setupAnim(rs)
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.GHOST, main.colorTextureView!!, null, Matrix4f(), Skins.skin(p)) { vc ->
            model.renderToBuffer(stack, Collector(vc, color), 15728880, 0, Theme.primary)
        }
        stack.popPose()
    }
}
