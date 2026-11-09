package dev.horizon.client.module.equip

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.GuiMove
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket

/**
 * Every container close packet is dropped (any container id), so the server keeps the
 * 2x2 crafting grid filled. With gui-move on in a bypass mode it turns itself off at the first close instead.
 */
class XCarry : Module("x-carry", Categories.EQUIP) {
    init {
        on<SendPacketEvent> { e ->
            if (e.packet !is ServerboundContainerClosePacket) return@on
            val guiMove = Modules.get<GuiMove>()
            if (guiMove.enabled && guiMove.bypass.value != GuiMove.Bypass.NONE) {
                // Off, then the raw message (it doesn't translate it).
                disable()
                moduleMessage(name.trim(), "not-compatible")
                return@on
            }
            e.cancel()
        }
    }
}
