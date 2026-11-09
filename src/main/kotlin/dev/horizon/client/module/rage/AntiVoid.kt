package dev.horizon.client.module.rage

import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket

/**
 * Falling with only air down to y −64, NCP stops the vertical move (and, with rubber-band, reports an on-ground
 * position); TIMER slows the client to 0.2. MoveEvent priority −20.
 */
class AntiVoid : Module("anti-void", Categories.RAGE) {
    enum class Mode { NCP, TIMER }

    val mode = enum("mode", Mode.TIMER)
    val rubberBand = bool("rubber-band", true) { mode.value == Mode.NCP }

    init {
        on<MoveEvent>(-20) { e ->
            val p = mc.player ?: return@on
            if (!overVoid()) return@on
            if (mode.value == Mode.NCP) {
                e.cancel()
                e.y = 0.0
                if (rubberBand.value) Packets.send(ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, p.horizontalCollision))
            } else {
                TimerManager.factor = 0.2f
            }
        }
    }

    /** Not gliding, nothing but air from the feet down to y −64, and falling. */
    private fun overVoid(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (p.isFallFlying) return false
        var y = p.y.toInt()
        while (-65 < y) {
            if (!level.isEmptyBlock(BlockPos.containing(p.x, y.toDouble(), p.z))) return false
            y--
        }
        return PlayerState.fallDistance > 0.0
    }
}
