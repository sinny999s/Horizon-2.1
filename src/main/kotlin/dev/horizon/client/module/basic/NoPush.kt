package dev.horizon.client.module.basic

import dev.horizon.client.event.EntityPushEvent
import dev.horizon.client.event.PushOutOfBlocksEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.WaterPushEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.level.block.IceBlock
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.phys.Vec3

/**
 * No pushing out of blocks, by entities, by flowing liquids (falling liquid still pulls
 * down; this applies to every flow while it's on), and no pull from a fishing hook caught in us.
 */
class NoPush : Module("no-push", Categories.BASIC) {
    val blocksSetting = bool("blocks", true)
    val entitiesSetting = bool("entities", true)
    val liquids = bool("liquids", true)
    val fishingHook = bool("fishing-hook", true)

    init {
        on<ReceivePacketEvent> { e ->
            val packet = e.packet as? ClientboundEntityEventPacket ?: return@on
            if (!fishingHook.value) return@on
            if (packet.eventId.toInt() != 31) return@on
            val hook = packet.getEntity(mc.level ?: return@on) as? FishingHook ?: return@on
            if (hook.hookedIn == mc.player) e.cancel()
        }
        on<WaterPushEvent> { e ->
            if (!liquids.value) return@on
            val level = mc.level ?: return@on
            if (mc.player == null) return@on
            val m = BlockPos.MutableBlockPos()
            var vec = Vec3.ZERO
            if (e.state.getValue(FlowingFluid.FALLING)) {
                for (dir in Direction.Plane.HORIZONTAL) {
                    m.setWithOffset(e.pos, dir)
                    if (solidFace(level, e.fluid, m, dir) || solidFace(level, e.fluid, m.above(), dir)) {
                        vec = vec.normalize().add(0.0, -6.0, 0.0)
                        break
                    }
                }
            }
            e.vec = vec.normalize()
            e.cancel()
        }
        on<PushOutOfBlocksEvent> { if (blocksSetting.value) it.cancel() }
        on<EntityPushEvent> { if (entitiesSetting.value) it.cancel() }
    }

    /** Vanilla's `FlowingFluid.isSolidFace`, read from the client level. */
    private fun solidFace(level: ClientLevel, fluid: Fluid, pos: BlockPos, dir: Direction): Boolean {
        val state = level.getBlockState(pos)
        if (level.getFluidState(pos).type.isSame(fluid)) return false
        if (dir == Direction.UP) return true
        if (state.block is IceBlock) return false
        return state.isFaceSturdy(level, pos, dir)
    }
}
