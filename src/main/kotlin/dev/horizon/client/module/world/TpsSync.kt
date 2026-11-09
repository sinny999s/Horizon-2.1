package dev.horizon.client.module.world

import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSetTimePacket

/**
 * Below 18 average TPS the game timer runs at TPS / 20; with kick-protection, once the
 * server has sent other packets but no time packet for over a second, the player tick is skipped (no movement, no
 * movement packet) while on the ground or gliding. PlayerUpdate isn't cancellable, so [skipTick] is read by
 * `TpsSyncLocalPlayerMixin` right after `LocalPlayer.tick`'s HEAD (before anything else in the tick runs).
 */
class TpsSync : Module("tps-sync", Categories.WORLD) {
    val kickProtection = bool("kick-protection", true)

    init {
        PacketClock
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val tps = ServerInfo.averageTps()
            if (tps < 18.0f && p.tickCount > 100 && !ServerInfo.warmingUp()) TimerManager.factor = tps / 20.0f
            if (!kickProtection.value) return@on
            if (p.tickCount <= 1200) return@on
            if (PacketClock.sinceTimePacket() - 1000L <= 0L) return@on
            if (mc.isLocalServer) return@on
            if (!p.onGround() && !p.isFallFlying) return@on
            skipTick = true
        }
    }

    companion object {
        /** Set by this tick's PlayerUpdate: skip the rest of `LocalPlayer.tick`. */
        @JvmStatic
        var skipTick = false

        /** For the mixin: whether to skip this player tick (clears the request). */
        @JvmStatic
        fun takeSkip(): Boolean {
            val s = skipTick
            skipTick = false
            return s
        }
    }
}

/**
 * A packet clock, always on: when any packet and when the last time packet arrived, on the
 * network thread. A time packet in the first 20 player ticks (after the first one) updates neither.
 */
object PacketClock {
    private val mc get() = Minecraft.getInstance()

    @Volatile
    private var lastPacket = 0L

    @Volatile
    private var lastTime = 0L

    init {
        EventBus.on<ReceivePacketEvent>(this) { e ->
            val now = System.currentTimeMillis()
            if (e.packet is ClientboundSetTimePacket) {
                val p = mc.player
                if (lastTime != 0L && p != null && p.tickCount < 20) return@on
                lastTime = now
            }
            lastPacket = now
        }
    }

    /** Milliseconds between the last time packet and the last packet. */
    fun sinceTimePacket(): Long = lastPacket - lastTime
}
