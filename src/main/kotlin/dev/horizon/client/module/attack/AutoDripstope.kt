package dev.horizon.client.module.attack

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.Slots
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Interact
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Finds a bottom-half trapdoor above a living entity within block reach, places
 * pointed dripstone underneath it, and flips the trapdoor to drop the dripstone.
 */
class AutoDripstope : Module("auto-dripstope", Categories.ATTACK) {
    val delay = int("delay", 0, 0..15)
    val switch = enum("switch", Switch.SILENT_FULL)
    val fastOpen = bool("fast-open", true)

    private val delayTimer = TickTimer()
    private val openTimer = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            val pl = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (!delayTimer.passed(delay.value)) return@on
            val center = BlockPos.containing(pl.position())
            val r = ceil(pl.blockInteractionRange()).toInt()
            val candidates = LinkedHashMap<BlockPos, BlockState>()
            for (x in -r..r) for (y in -r..r) for (z in -r..r) {
                val pos = center.offset(x, y, z)
                val state = level.getBlockState(pos)
                val belowState = level.getBlockState(pos.below())
                if (state.block !is TrapDoorBlock || state.getValue(TrapDoorBlock.HALF) != Half.BOTTOM) continue
                if (!belowState.isAir && belowState.block != Blocks.POINTED_DRIPSTONE) continue
                val posCenter = Vec3.atCenterOf(pos)
                val hasTarget = level.entitiesForRendering().any { e ->
                    e is LivingEntity && !e.isDeadOrDying && hypot(posCenter.x - e.x, posCenter.z - e.z) < 0.8f && e !== pl && e.y < pos.y
                }
                if (hasTarget) candidates[pos] = state
            }
            val picked = candidates.entries.minByOrNull { fovTo(it.key) } ?: return@on
            if (!picked.value.getValue(TrapDoorBlock.OPEN) && level.getBlockState(picked.key.below()).isAir) {
                if (placeDripstone(picked.key)) delayTimer.reset()
            } else {
                val rand = Random.Default.nextDouble() / 10.0 + 0.5
                val targetVec = Vec3(picked.key.x + rand, picked.key.y.toDouble(), picked.key.z + rand)
                if (openTimer.passed(5)) {
                    RotationManager.queue(RotationRequest(Rotation.lookAt(targetVec), 100) { toggleTrapdoor(picked.key) })
                }
                delayTimer.reset()
            }
        }
    }

    private fun placeDripstone(pos: BlockPos): Boolean {
        val pl = mc.player ?: return true
        val found = Slots.find(switch.value) { it.`is`(Items.POINTED_DRIPSTONE) }
        if (found != null) {
            val reach = pl.blockInteractionRange().toFloat()
            val hit = Placer.findHit(pos.below(), -1, Interact.Strict, IgnoreMode.ALL, reach, reach, emptyList())
            if (hit != null) {
                RotationManager.queue(RotationRequest(Rotation.lookAt(hit.location), 100) {
                    val p = mc.player ?: return@RotationRequest
                    Slots.withSwitch(found.slot, switch.value) {
                        PlayerState.setSneak(true)
                        Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
                        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                        PlayerState.setSneak(false)
                        toggleTrapdoor(pos)
                        if (fastOpen.value) toggleTrapdoor(pos)
                        openTimer.reset()
                    }
                })
            }
        }
        return true
    }

    private fun toggleTrapdoor(pos: BlockPos) {
        val p = mc.player ?: return
        val rand = Random.Default.nextDouble() / 10.0 + 0.5
        val hit = BlockHitResult(Vec3(pos.x + rand, pos.y.toDouble(), pos.z + rand), Direction.DOWN, pos, false)
        mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, hit)
        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
    }

    private fun fovTo(pos: BlockPos): Float {
        val p = mc.player ?: return 0f
        val c = Vec3.atCenterOf(pos)
        return abs(Mth.wrapDegrees(Math.toDegrees(atan2(c.z - p.z, c.x - p.x)) - 90.0) - Mth.wrapDegrees(p.yRot)).toFloat()
    }
}
