package dev.horizon.client.module.rage

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.switchAnd
import dev.horizon.client.combat.switchFind
import dev.horizon.client.event.CollisionEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Interact
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * VANILLA: collisions see air everywhere but the block under the feet (and that too while
 * sneaking). PEARL: walking into a wall, throws a pearl at the block ahead, nearly straight down (after filling it and
 * the one below with obsidian, and breaking an item frame or crystal in the way). STEP: on enable, clips through the
 * wall ahead with a few position packets (several in one tick, which is the point of it).
 */
class Phase : Module("phase", Categories.RAGE) {
    enum class Mode { VANILLA, PEARL, STEP }

    val mode = enum("mode", Mode.VANILLA)
    val onlyOnGround = bool("only-on-ground", false) { mode.value == Mode.PEARL }
    val autoDisable = bool("auto-disable", false) { mode.value == Mode.PEARL }
    val antiSwim = bool("anti-swim", true) { mode.value == Mode.PEARL }
    val fill = bool("fill", true) { mode.value == Mode.PEARL }
    val grim = bool("grim", true) { mode.value == Mode.PEARL && fill.value }
    val switch = enum("switch", Switch.SILENT_FULL) { mode.value == Mode.PEARL }

    /** Since the last pearl. */
    private val pearlTimer = MsTimer()

    init {
        on<CollisionEvent> { e ->
            val p = mc.player ?: return@on
            val feet = BlockPos.containing(p.position())
            if (mode.value == Mode.VANILLA && (e.pos != feet.below() || mc.options.keyShift.isDown)) e.state = Blocks.AIR.defaultBlockState()
        }
        on<PlayerUpdateEvent>(-10) { pearl() }
    }

    override fun onEnable() {
        val p = mc.player ?: return
        if (!p.onGround() || mode.value != Mode.STEP) return
        val into = Movement.forward(0.44)
        val yaw = p.yRot % 90
        val diagonal = 35.0f <= yaw && yaw <= 55.0f
        Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_SPRINTING))
        if (diagonal) {
            val through = Movement.forward(0.51)
            stepUp(through)
            stepDown(into, through)
        } else {
            val through = Movement.forward(0.57)
            stepUp(through)
            p.setPos(p.x + through[0], p.y, p.z + through[1])
            Packets.send(ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, p.horizontalCollision))
            stepDown(into, through)
        }
    }

    /**
     * Up 2 and over by [through]. A raycast 0.44 ahead was meant to climb 1 when clear, but it compared the hit result
     * itself with `Type.MISS`, so it always climbs 2 (on purpose).
     */
    private fun stepUp(through: DoubleArray) {
        val p = mc.player ?: return
        p.setPos(p.x + through[0], p.y + 2, p.z + through[1])
        Packets.send(ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, p.horizontalCollision))
    }

    /** Down 2 if the block 2 under the first offset is air, else 1. */
    private fun stepDown(into: DoubleArray, through: DoubleArray) {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val down = if (level.isEmptyBlock(BlockPos.containing(p.position().add(into[0], -2.0, into[1])))) 2 else 1
        p.setPos(p.x + through[0], p.y - down, p.z + through[1])
        Packets.send(ServerboundMovePlayerPacket.Pos(p.x, p.y, p.z, true, p.horizontalCollision))
    }

    /** Runs at PlayerUpdate −10. */
    private fun pearl() {
        if (mode.value != Mode.PEARL) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        val swimming = antiSwim.value && p.pose == Pose.SWIMMING
        val ready = pearlTimer.passed(500) && !p.cooldowns.isOnCooldown(Items.ENDER_PEARL.defaultInstance) && p.tickCount > 60
        if (p.onGround() && swimming) p.jumpFromGround()
        if (!(p.horizontalCollision && !insideBlock() && ready && (PlayerState.fallDistance > 0.0 || !swimming) &&
                (p.onGround() || !onlyOnGround.value || swimming))) return
        val feet = BlockPos.containing(p.position())
        if (level.getBlockState(feet).block is ScaffoldingBlock) mc.gameMode?.startDestroyBlock(feet, Direction.UP)
        val m = PlayerState.movementOffset(0.5)
        val target = BlockPos.containing(p.x + m[0], p.y, p.z + m[1])
        if (mc.options.keyShift.isDown) return
        val rot = Rotation(Rotation.lookAt(Vec3.atCenterOf(target)).yaw, pitch())
        val found = switchFind(switch.value) { it.`is`(Items.ENDER_PEARL) } ?: return
        RotationManager.queue(RotationRequest(rot, 201) { throwPearl(target, rot, found.slot) })
    }

    /** Fill, clear the way, throw, then (auto-disable) turn off. */
    private fun throwPearl(target: BlockPos, rot: Rotation, slot: Int) {
        val p = mc.player ?: return
        if (fill.value) {
            fillBlock(target.below())
            fillBlock(target)
        }
        val e = entityAhead(rot.yaw, rot.pitch, 3.0f)
        if (e != null) {
            // No swing packet after each attack; 26.3 has none (servers swing on their own).
            if (e is ItemFrame && !e.item.isEmpty) mc.gameMode?.attack(p, e)
            if (e is ItemFrame || e is EndCrystal) mc.gameMode?.attack(p, e)
        }
        // No swing packet after the throw either (none in 26.3).
        switchAnd(slot, switch.value) { mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND) }
        if (autoDisable.value) disable()
        pearlTimer.reset()
    }

    /** Obsidian into [pos] if it's replaceable and there's a face to click. */
    private fun fillBlock(pos: BlockPos) {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (!level.getBlockState(pos).canBeReplaced()) return
        val obsidian = switchFind(switch.value) { it.`is`(Items.OBSIDIAN) } ?: return
        val range = p.blockInteractionRange().toFloat()
        val hit = Placer.findHit(pos, -1, if (grim.value) Interact.GRIM else Interact.Strict, IgnoreMode.ALL, range, range, emptyList()) ?: return
        switchAnd(obsidian.slot, switch.value) {
            mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, hit)
            p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
        }
    }

    /** The pitch: 81 off a block's top, else 85 (the ladder check compares a state with a block, so never 80). */
    private fun pitch(): Float {
        val p = mc.player ?: return 85.0f
        return if (abs(p.y) - floor(p.y) > 0.1) 81.0f else 85.0f
    }

    /** The state at the feet (y rounded) collides there but not one block up, and isn't a ladder. */
    private fun insideBlock(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        val pos = BlockPos.containing(p.x, p.y.roundToInt().toDouble(), p.z)
        val state = level.getBlockState(pos)
        val here = state.getCollisionShape(level, pos) != Shapes.empty()
        val above = state.getCollisionShape(level, pos.above()) != Shapes.empty()
        return here && !above && state.block != Blocks.LADDER
    }

    /** The pickable entity [range] along [yaw]/[pitch] from the eyes, unless a block is nearer. */
    private fun entityAhead(yaw: Float, pitch: Float, range: Float): Entity? {
        val p = mc.player ?: return null
        val level = mc.level ?: return null
        val dir = look(pitch, yaw)
        val from = p.getEyePosition(1.0f)
        val block = level.clip(ClipContext(from, from.add(dir.x * range, dir.y * range, dir.z * range), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, p))
        val eye = p.position().add(0.0, p.getEyeHeight(p.pose).toDouble(), 0.0)
        val maxSq = block.location.distanceToSqr(eye)
        val end = eye.add(dir.x * range, dir.y * range, dir.z * range)
        val box = p.boundingBox.expandTowards(dir.scale(range.toDouble())).inflate(1.0, 1.0, 1.0)
        val hit = ProjectileUtil.getEntityHitResult(p, eye, end, box, { !it.isSpectator && it.isPickable }, maxSq) ?: return null
        return if (!(eye.distanceToSqr(hit.location) < maxSq)) null else hit.entity
    }

    /** The look vector (float maths). */
    private fun look(pitch: Float, yaw: Float): Vec3 {
        val r = (Math.PI / 180.0).toFloat()
        return Vec3(
            (Math.sin((-yaw * r).toDouble()).toFloat() * Math.cos((pitch * r).toDouble()).toFloat()).toDouble(),
            (-Math.sin((pitch * r).toDouble()).toFloat()).toDouble(),
            (Math.cos((-yaw * r).toDouble()).toFloat() * Math.cos((pitch * r).toDouble()).toFloat()).toDouble(),
        )
    }
}
