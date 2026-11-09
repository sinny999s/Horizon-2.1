package dev.horizon.client.module.rage

import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket

/**
 * While gliding with Aura's target within Aura's range, the player doesn't move;
 * with [cancelPacket] its move and input packets are dropped too.
 */
class ElytraFreeze : Module("elytra-freeze", Categories.RAGE) {
    val cancelPacket = bool("cancel-packet", false)

    private fun frozen(): Boolean {
        val p = mc.player ?: return false
        if (!p.isFallFlying) return false
        val aura = Modules.get<Aura>()
        val t = aura.target ?: return false
        return t.distanceTo(p) - aura.reach.value < 0.0f
    }

    private fun inGame() = mc.player != null && mc.level != null

    init {
        on<MoveEvent> { e ->
            if (!inGame()) {
                disable()
                return@on
            }
            if (frozen()) {
                e.x = 0.0
                e.y = 0.0
                e.z = 0.0
                e.cancel()
            }
        }
        on<SendPacketEvent> { e ->
            if (!cancelPacket.value || !inGame() || !frozen()) return@on
            if (e.packet is ServerboundMovePlayerPacket || e.packet is ServerboundPlayerInputPacket) e.cancel()
        }
    }
}
