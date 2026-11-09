package dev.horizon.client.module.world

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Animation
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Interact
import dev.horizon.client.module.Module
import dev.horizon.client.module.PlaceMode
import dev.horizon.client.module.Render
import dev.horizon.client.module.Switch2
import dev.horizon.client.module.Timing
import dev.horizon.client.render.Theme
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Placing soul sand with the use key (the stem) plans the rest of a wither (three more
 * soul sand, three wither skeleton skulls) around it, then places it with the block placer, one block per tick
 * (Vanilla) or up to blocks-per-action per tick with delay-per-action ticks in between (Instant).
 */
class AutoWither : Module("auto-wither", Categories.WORLD) {
    enum class Pose { STANDING, LYING }

    val timing = enum("timing", Timing.Vanilla)
    val blocksPerAction = int("blocks-per-action", 6, 2..6) { timing.value == Timing.Instant }
    val delayPerAction = int("delay-per-action", 0, 0..10) { timing.value == Timing.Instant }
    val rotate = bool("rotate", true)
    val placeMode = enum("place-mode", PlaceMode.Normal)
    val interact = enum("interact", Interact.Strict)
    val switch = enum("switch", Switch2.Silent)
    val range = float("range", 4.0f, 2.0f..10.0f)
    val wallRange = float("wall-range", 4.0f, 2.0f..10.0f)
    val render = bool("render", true)
    val pose = enum("pose", Pose.LYING)

    /** Blocks still to place: position and whether it is soul sand (else a skull), in placing order. */
    private val plan = ArrayList<Pair<BlockPos, Boolean>>()
    private var wait = 0
    private val useTimer = MsTimer()

    init {
        on<PlayerUpdateEvent> { update() }
    }

    private fun update() {
        val player = mc.player ?: return
        if (mc.options.keyUse.isDown && useTimer.passedAndReset(250) && player.mainHandItem.item == Items.SOUL_SAND) {
            plan.clear()
            val hit = mc.hitResult ?: return
            if (hit.type != HitResult.Type.BLOCK) return
            if (hit !is BlockHitResult) return
            build(hit.blockPos.relative(hit.direction))
        }
        if (wait > 0) {
            wait--
            return
        }
        if (plan.isEmpty()) return
        var left = blocksPerAction.value
        val it = plan.iterator()
        while (it.hasNext()) {
            val (pos, sand) = it.next()
            if (left <= 0) break
            val slot = Inventory.hotbarFind(if (sand) Items.SOUL_SAND else Items.WITHER_SKELETON_SKULL)?.slot ?: -1
            if (slot == -1) continue
            val req = Placer.place(pos, slot, interact.value, placeMode.value, IgnoreMode.ALL, range.value, wallRange.value, 150,
                switch.value) ?: continue
            place(pos, req)
            it.remove()
            if (timing.value != Timing.Instant) break
            left--
            wait = delayPerAction.value
        }
    }

    /** The wither around [stem], by the horizontal direction the player looks (none when looking up/down). */
    private fun build(stem: BlockPos) {
        val standing = pose.value == Pose.STANDING
        fun add(p: BlockPos, sand: Boolean) {
            plan += p to sand
        }
        when (mc.player?.nearestViewDirection) {
            Direction.EAST -> if (standing) {
                add(stem.above(), true); add(stem.above().south(), true); add(stem.above().north(), true)
                add(stem.above().above(), false); add(stem.above().above().south(), false); add(stem.above().above().north(), false)
            } else {
                add(stem.west(), true); add(stem.west().south(), true); add(stem.west().north(), true)
                add(stem.west().west().south(), false); add(stem.west().west().north(), false); add(stem.west().west(), false)
            }
            Direction.NORTH -> if (standing) {
                add(stem.above(), true); add(stem.above().east(), true); add(stem.above().west(), true)
                add(stem.above().above(), false); add(stem.above().above().east(), false); add(stem.above().above().west(), false)
            } else {
                add(stem.south(), true); add(stem.south().east(), true); add(stem.south().west(), true)
                add(stem.south().south().east(), false); add(stem.south().south().west(), false); add(stem.south().south(), false)
            }
            Direction.SOUTH -> if (standing) {
                add(stem.above(), true); add(stem.above().west(), true); add(stem.above().east(), true)
                add(stem.above().above(), false); add(stem.above().above().west(), false); add(stem.above().above().east(), false)
            } else {
                add(stem.north(), true); add(stem.north().east(), true); add(stem.north().west(), true)
                add(stem.north().north().east(), false); add(stem.north().north().west(), false); add(stem.north().north(), false)
            }
            Direction.WEST -> if (standing) {
                add(stem.above(), true); add(stem.above().north(), true); add(stem.above().south(), true)
                add(stem.above().above(), false); add(stem.above().above().north(), false); add(stem.above().above().south(), false)
            } else {
                add(stem.east(), true); add(stem.east().south(), true); add(stem.east().north(), true)
                add(stem.east().east().south(), false); add(stem.east().east().north(), false); add(stem.east().east(), false)
            }
            else -> {}
        }
    }

    /** Hand the request to the rotation manager, or place now; then the 300 ms box. */
    private fun place(pos: BlockPos, req: RotationRequest) {
        if (rotate.value) {
            if (timing.value == Timing.Instant) RotationManager.queueInstant(req) else RotationManager.queue(req)
        } else {
            req.action()
            req.action = {}
        }
        if (render.value) {
            val c = PlaceBoxes.withAlpha(Theme.primary, 1.0f)
            PlaceBoxes.add(pos, c, c, Animation.Fade, Render.BOTH)
        }
    }
}
