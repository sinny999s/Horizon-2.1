package dev.horizon.client.module.player

import dev.horizon.client.event.FlagEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.mixin.player2.PlayerPositionPacketAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.PositionMoveRotation

/**
 * A server teleport doesn't turn the camera. [legit]: the teleport is applied as
 * sent, then the rotation from the start of the tick (+1e-6) is put back; otherwise the packet's rotation is replaced
 * with the current one before it is handled.
 */
class NoServerRotate : Module("no-server-rotate", Categories.PLAYER) {
    val legit = bool("legit", true)

    /** The rotation at the start of this player tick. */
    private var yaw = 0f
    private var pitch = 0f

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            yaw = p.yRot
            pitch = p.xRot
        }
        on<FlagEvent> {
            if (!legit.value) return@on
            val p = mc.player ?: return@on
            p.yRot = yaw + 1.0E-6f
            p.xRot = pitch + 1.0E-6f
        }
        on<ReceivePacketEvent> { e ->
            val packet = e.packet as? ClientboundPlayerPositionPacket ?: return@on
            if (legit.value) return@on
            val p = mc.player ?: return@on
            val c = packet.change()
            // The relative flags are left alone (on purpose).
            (packet as PlayerPositionPacketAccessor).horizonP2_setChange(PositionMoveRotation(c.position(), c.deltaMovement(), p.yRot, p.xRot))
        }
    }
}
