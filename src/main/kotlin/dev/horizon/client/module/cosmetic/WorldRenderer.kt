package dev.horizon.client.module.cosmetic

import dev.horizon.client.event.ApplyFogEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import org.joml.Vector4f
import java.awt.Color
import java.time.ZonedDateTime

/**
 * Fog with its own colour and distances, and a fixed (or real-world) time of day.
 * While the time changer is on, the server's time packets are dropped (their game time kept for when it is turned off).
 */
class WorldRenderer : Module("world-renderer", Categories.COSMETIC) {
    val fogModify = bool("fog-modify", true)
    val fogStart = int("fog-start", 0, 0..256) { fogModify.value }
    val fogEnd = int("fog-end", 64, 10..256) { fogModify.value }
    val fogColor = color("fog-color", Color(0xFFA900FF.toInt(), true)) { fogModify.value }
    val timeChanger = bool("time-changer", false)
    val realTime = bool("real-time", false) { timeChanger.value }
    val time = int("time", 21000, 0..23000) { timeChanger.value }

    /** The server's game time, put back on disable. */
    private var serverTime = 0L

    init {
        on<ReceivePacketEvent> { e ->
            val p = e.packet
            if (p is ClientboundSetTimePacket && timeChanger.value) {
                serverTime = p.gameTime()
                e.cancel()
            }
        }
        on<PlayerUpdateEvent> {
            if (!timeChanger.value) return@on
            if (realTime.value) time.value = realTimeTicks()
            mc.level?.setTimeFromServer(time.value.toLong())
        }
        on<ApplyFogEvent> { e ->
            if (!fogModify.value || mc.player == null || mc.level == null) return@on
            val c = fogColor.value
            e.color = Vector4f(c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f)
            e.envStart = fogStart.value.toFloat()
            e.envEnd = fogEnd.value.toFloat()
            e.renderStart = fogStart.value.toFloat()
            e.renderEnd = fogEnd.value.toFloat()
            e.cancel()
        }
    }

    override fun onEnable() {
        mc.level?.let { serverTime = it.gameTime }
    }

    override fun onDisable() {
        mc.level?.setTimeFromServer(serverTime)
    }

    /** The local time of day in ticks (6 am = 0). */
    fun realTimeTicks(): Int {
        val ticks = ZonedDateTime.now().toLocalTime().toSecondOfDay().toDouble() / 86400.0 * 24000.0
        return ((ticks - 6000 + 24000) % 24000).toInt()
    }
}
