package dev.horizon.client.module.player

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.event.AttackEvent
import dev.horizon.client.event.CrosshairEvent
import dev.horizon.client.event.InputEvent
import dev.horizon.client.mixin.LocalPlayerAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.attack.viewVec
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.profiling.Profiler
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes

/**
 * [ignoreEntities] picks the crosshair target from blocks only; [attackEntities]
 * attacks the entity along the look when a left click lands on a block; [openContainers] right-clicks the first
 * block entity along the server-side look within reach, through whatever is in front of it.
 */
class TraceThrough : Module("trace-through", Categories.PLAYER) {
    val attackEntities = bool("attack-entities", false)
    val ignoreEntities = bool("ignore-entities", true)
    val openContainers = bool("open-containers", false)

    init {
        on<CrosshairEvent> { e ->
            if (!ignoreEntities.value) return@on
            Profiler.get().pop()
            e.cancel()
            val camera = mc.cameraEntity ?: return@on
            val p = mc.player ?: return@on
            mc.hitResult = blockPick(camera, p.blockInteractionRange(), mc.deltaTracker.getGameTimeDeltaPartialTick(true))
            mc.crosshairPickEntity = (mc.hitResult as? EntityHitResult)?.entity
        }
        on<AttackEvent> { e ->
            if (!attackEntities.value || mc.hitResult !is BlockHitResult) return@on
            val p = mc.player ?: return@on
            val target = entityAlong(p, p.yRot, p.xRot, p.entityInteractionRange().toFloat()) ?: return@on
            mc.gameMode?.attack(p, target)
            // 26.3 has no swing packet to send with this swing.
            p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
            e.cancel()
        }
        on<InputEvent> { e -> openContainer(e) }
    }

    private fun openContainer(e: InputEvent) {
        if (!openContainers.value) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (e.device != InputEvent.Device.Mouse || e.action != InputEvent.Action.Press || e.key != InputConstants.MOUSE_BUTTON_RIGHT) return
        if (p.isShiftKeyDown || mc.gui.screen() != null) return
        val hit = mc.hitResult
        if (hit is BlockHitResult && level.getBlockState(hit.blockPos).hasBlockEntity()) return
        val last = p as LocalPlayerAccessor
        val dir = viewVec(last.horizon_xRotLast(), last.horizon_yRotLast())
        for (i in 1..p.blockInteractionRange().toInt()) {
            val pos = BlockPos.containing(p.eyePosition.add(dir.scale(i.toDouble())))
            val state = level.getBlockState(pos)
            if (!state.hasBlockEntity()) continue
            val click = clipOnly(ClipContext(p.eyePosition, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p), pos, state)
            mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, click)
            p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.interactAnimation, false)
            return
        }
    }

    /** Vanilla's block pick from the camera, a miss past [range]; entities are never picked. */
    private fun blockPick(camera: Entity, range: Double, partialTick: Float): HitResult {
        val hit = camera.pick(range, partialTick, false)
        val eye = camera.getEyePosition(partialTick)
        val loc = hit.location
        if (loc.closerThan(eye, range)) return hit
        return BlockHitResult.miss(loc, Direction.getApproximateNearest(loc.x - eye.x, loc.y - eye.y, loc.z - eye.z), BlockPos.containing(loc))
    }

    /** The first pickable, non-spectator entity along [yaw]/[pitch] within [range], with no wall check. */
    private fun entityAlong(p: LocalPlayer, yaw: Float, pitch: Float, range: Float): Entity? {
        val eye = p.position().add(0.0, p.getEyeHeight(p.pose).toDouble(), 0.0)
        val dir = viewVec(pitch, yaw)
        val end = eye.add(dir.x * range, dir.y * range, dir.z * range)
        val box = p.boundingBox.expandTowards(dir.scale(range.toDouble())).inflate(1.0, 1.0, 1.0)
        val maxSq = Math.pow(range.toDouble(), 2.0).toFloat().toDouble()
        return ProjectileUtil.getEntityHitResult(p, eye, end, box, { !it.isSpectator && it.isPickable }, maxSq)?.entity
    }

    /** The clip along [ctx] with every block but [pos] (as [state]) taken as air, and no fluids. */
    private fun clipOnly(ctx: ClipContext, pos: BlockPos, state: BlockState): BlockHitResult {
        val level = mc.level!!
        return BlockGetter.traverseBlocks(ctx.from, ctx.to, ctx, { c, at ->
            val s = if (at != pos) Blocks.AIR.defaultBlockState() else state
            val block = level.clipWithInteractionOverride(c.from, c.to, at, c.getBlockShape(s, level, at), s)
            val fluid = Shapes.empty().clip(c.from, c.to, at)
            val db = if (block == null) Double.MAX_VALUE else c.from.distanceToSqr(block.location)
            val df = if (fluid == null) Double.MAX_VALUE else c.from.distanceToSqr(fluid.location)
            if (db <= df) block else fluid
        }, { c ->
            val d = c.from.subtract(c.to)
            BlockHitResult.miss(c.to, Direction.getApproximateNearest(d.x, d.y, d.z), BlockPos.containing(c.to))
        })
    }
}
