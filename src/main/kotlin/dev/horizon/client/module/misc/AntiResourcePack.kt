package dev.horizon.client.module.misc

import dev.horizon.client.combat.Packets
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket

/**
 * Drops the server's resource-pack push, then answers ACCEPTED after 15–30
 * player ticks and SUCCESSFULLY_LOADED after 40–60 (each bound re-rolled every tick). The pack id it sends is the
 * player's UUID (on purpose). Turns itself off on crystalpvp.cc.
 */
class AntiResourcePack : Module("anti-resource-pack", Categories.MISC) {
    private var pending = false
    private var accepted = false
    private var ticks = 0

    init {
        on<ReceivePacketEvent> { e ->
            if (e.packet !is ClientboundResourcePackPushPacket) return@on
            pending = true
            accepted = false
            ticks = 0
            e.cancel()
        }
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            if (mc.connection?.serverData?.ip?.contains("crystalpvp.cc") == true) disable()
            if (!pending) return@on
            ticks++
            if (ticks > 15 + Math.random() * 15 && !accepted) {
                Packets.send(ServerboundResourcePackPacket(player.uuid, ServerboundResourcePackPacket.Action.ACCEPTED))
                accepted = true
            }
            if (ticks > 40 + Math.random() * 20 && accepted) {
                Packets.send(ServerboundResourcePackPacket(player.uuid, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED))
                pending = false
            }
        }
    }
}
