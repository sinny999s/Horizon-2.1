package dev.horizon.client.module.player

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.world.level.block.Blocks

/**
 * Teleport confirmations aren't sent while a nether portal was within 2 blocks in
 * the last 5 seconds.
 */
class PortalGodMode : Module("portal-god-mode", Categories.PLAYER) {
    /** When a portal was last near; it starts 99999 ms back, long expired. */
    private var lastPortal = System.currentTimeMillis() - 99999

    init {
        on<SendPacketEvent> { e ->
            if (e.packet is ServerboundAcceptTeleportationPacket && System.currentTimeMillis() - lastPortal < 5000) e.cancel()
        }
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            // Truncated towards zero (on purpose).
            for (x in (p.x - 2).toInt() until (p.x + 2).toInt()) {
                for (z in (p.z - 2).toInt() until (p.z + 2).toInt()) {
                    for (y in (p.y - 2).toInt() until (p.y + 2).toInt()) {
                        if (level.getBlockState(BlockPos(x, y, z)).block == Blocks.NETHER_PORTAL) lastPortal = System.currentTimeMillis()
                    }
                }
            }
        }
    }
}
