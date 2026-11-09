package dev.horizon.client.module.misc

import dev.horizon.client.combat.Misc2Util
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ClientboundPingPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Pings and keep-alives are held back and handed to vanilla (which answers them) on the
 * first frame [delay] ms after they came. Nothing is flushed on disable or disconnect: what is held then is answered,
 * possibly on a later connection, when the module is next on (on purpose).
 */
class PingSpoof : Module("ping-spoof", Categories.MISC) {
    val delay = int("delay", 100, 1..29500)

    init {
        on<ReceivePacketEvent> { e ->
            Misc2Util.received(e) { p ->
                if (p is ClientboundPingPacket || p is ClientboundKeepAlivePacket) {
                    held[p] = System.currentTimeMillis()
                    true
                } else {
                    false
                }
            }
        }
        on<Render3DEvent> {
            for ((p, t) in held.entries.map { it.key to it.value }) {
                if (System.currentTimeMillis() - t < delay.value) continue
                try {
                    when (p) {
                        is ClientboundPingPacket -> p.handle(mc.connection!!)
                        is ClientboundKeepAlivePacket -> p.handle(mc.connection!!)
                    }
                    held.remove(p)
                } catch (e: Exception) {
                    Misc2Util.chat(this, "Error processing packet: " + e.message)
                }
            }
        }
    }

    private companion object {
        /** Held packets and when they came. */
        val held = ConcurrentHashMap<Packet<*>, Long>()
    }
}
