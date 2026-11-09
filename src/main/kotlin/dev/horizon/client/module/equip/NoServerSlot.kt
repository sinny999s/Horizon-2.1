package dev.horizon.client.module.equip

import dev.horizon.client.combat.Packets
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket

/**
 * The server may not change the selected hotbar slot. Its slot packet is dropped
 * (on the network thread) and the slot we have selected is sent back instead.
 */
class NoServerSlot : Module("no-server-slot", Categories.EQUIP) {
    init {
        on<ReceivePacketEvent> { e ->
            if (e.packet !is ClientboundSetHeldSlotPacket) return@on
            e.cancel()
            val p = mc.player ?: return@on
            Packets.send(ServerboundSetCarriedItemPacket(p.inventory.selectedSlot))
        }
    }
}
