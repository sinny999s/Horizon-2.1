package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.CobWebEvent
import dev.horizon.client.event.CollisionEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.WebBlock

/**
 * In a web: TIMER runs the client at `timer` while airborne, FLY moves at `speed` (jump/sneak
 * vertical). SOLID makes webs collide like dirt; IGNORE skips the web's slowdown (grim: plus a stop-destroy packet).
 */
class AntiWeb : Module("anti-web", Categories.RAGE) {
    enum class Mode { TIMER, SOLID, IGNORE, FLY }

    val mode = enum("mode", Mode.SOLID)
    val timer = float("timer", 20.0f, 1.0f..50.0f) { mode.value == Mode.TIMER }
    val speed = float("speed", 0.3f, 0.0f..10.0f) { mode.value == Mode.FLY }
    val grim = bool("grim", false) { mode.value == Mode.IGNORE }

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (!Movement.inCobweb(p)) return@on
            if (mode.value == Mode.TIMER && !p.onGround()) TimerManager.factor = timer.value
            if (mode.value == Mode.FLY) {
                val m = Movement.strafe(speed.value)
                p.setDeltaMovement(m[0], 0.0, m[1])
                if (mc.options.keyJump.isDown) p.deltaMovement = p.deltaMovement.add(0.0, speed.value.toDouble(), 0.0)
                if (mc.options.keyShift.isDown) p.deltaMovement = p.deltaMovement.add(0.0, -speed.value.toDouble(), 0.0)
            }
        }
        on<CollisionEvent> { e ->
            if (e.state.block is WebBlock && mode.value == Mode.SOLID) e.state = Blocks.DIRT.defaultBlockState()
        }
        on<CobWebEvent> { e ->
            if (mode.value != Mode.IGNORE) return@on
            e.cancel()
            // The packet always names 0, 0, 0, not the web's position (on purpose).
            if (grim.value) Packets.sendSequenced { ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP, it) }
        }
    }
}
