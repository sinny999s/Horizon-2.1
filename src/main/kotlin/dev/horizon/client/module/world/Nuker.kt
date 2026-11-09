package dev.horizon.client.module.world

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.AttackBlockEvent
import dev.horizon.client.event.BlockStateEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.player.SpeedMine
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Breaks the chosen blocks around the player. Survival: one target at a time, turned towards
 * by at most `degrees-per-tick`, then handed to Speed Mine (or mined the vanilla way). Creative: START+STOP for every
 * matching block in range in one go. Nothing is reset on toggle.
 */
class Nuker : Module("nuker", Categories.WORLD) {
    @Suppress("EnumEntryName")
    enum class Filter { Select, All, BlackList, WhiteList }
    enum class Sort { FOV, DISTANCE }

    val filter = enum("filter", Filter.Select)
    val sort = enum("sort", Sort.FOV)
    val blocksSetting = blocks("blocks")
    val delay = int("delay", 25, 0..1000)
    val range = float("range", 4.2f, 3.0f..7.0f)
    val degreesPerTick = int("degrees-per-tick", 35, 5..180)
    val ignoreWalls = bool("ignore-walls", false)
    val flatten = bool("flatten", false)
    val creative = bool("creative", false)
    val avoidLava = bool("avoid-lava", false)

    /** The target block, the point to look at and the face to mine. Frozen at acquisition. */
    private class Target(val pos: BlockPos, val aim: Vec3, val face: Direction)

    /** The block type picked by attacking a block in Select mode; never cleared. */
    private var selected: Block? = null

    /** The survival target. */
    private var target: Target? = null

    /** The yaw is within one step of the target (mining waits for it; pitch doesn't count). */
    private var yawReady = false

    /** Gates both the creative pass and [mine]. */
    private val timer = MsTimer()

    init {
        on<AttackBlockEvent> { e ->
            val level = mc.level ?: return@on
            if (level.isEmptyBlock(e.pos)) return@on
            val b = level.getBlockState(e.pos).block
            if (filter.value == Filter.Select && b != selected) selected = b
        }
        on<BlockStateEvent> { e ->
            // Checks the old state (previous) on purpose: this fires when the target spot was air and got a block back (e.g.
            // a break the server undid), not on a normal break.
            if (target?.pos != e.pos) return@on
            val s = e.previous ?: return@on
            if (!s.isAir) return@on
            if (creative.value) return@on
            target = null
            acquire()
        }
        on<PlayerUpdateEvent> { tick() }
        on<Render3DEvent> {
            if (creative.value) return@on
            val t = target ?: return@on
            World3D.outline(AABB(t.pos), PlaceBoxes.withAlpha(Theme.primary, 1.0f))
            World3D.fill(AABB(t.pos), PlaceBoxes.withAlpha(Theme.primary, 0.5f))
        }
    }

    private fun tick() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        acquire()
        target?.let { t ->
            val b = level.getBlockState(t.pos).block
            if (b != selected && filter.value == Filter.Select || eyeDistSq(Vec3.atCenterOf(t.pos)) > range.value * range.value ||
                b == Blocks.AIR) {
                target = null
                acquire()
            }
        }
        if (mc.options.keyAttack.isDown) return
        val t = target ?: return
        if (!creative.value) rotationStep(p, t.aim)
        if (creative.value) {
            if (timer.passedAndReset(delay.value.toLong())) creativePass(p, level)
            return
        }
        if (!yawReady) return
        if (timer.passedAndReset(delay.value.toLong())) mine()
    }

    private fun acquire() {
        if ((selected != null || filter.value != Filter.Select) && !mc.options.keyAttack.isDown && target == null) target = findTarget()
    }

    /** One step of at most `degrees-per-tick` from the last sent rotation, queued at priority 0 every tick. */
    private fun rotationStep(p: LocalPlayer, aim: Vec3) {
        val lastYaw = Rotation.lastYaw
        val lastPitch = Rotation.lastPitch
        val dYaw = Mth.wrapDegrees(Math.toDegrees(atan2(aim.z - p.z, aim.x - p.x)) - 90.0f - lastYaw)
        val dPitch = (-Math.toDegrees(atan2(aim.y - p.eyePosition.y, sqrt(Math.pow(aim.x - p.x, 2.0) + Math.pow(aim.z - p.z, 2.0))))).toFloat() -
            lastPitch
        val s = degreesPerTick.value.toFloat()
        val yawStep = Mth.clamp(abs(dYaw.toFloat()), -s, s)
        val pitchStep = Mth.clamp(dPitch, -s, s)
        yawReady = abs(dYaw.toFloat()) <= s
        val yaw = lastYaw + if (dYaw > 0.0) yawStep else -yawStep
        val pitch = Mth.clamp(lastPitch + pitchStep, -90.0f, 90.0f)
        RotationManager.queue(RotationRequest(Rotation(yaw, pitch), 0) {})
    }

    /** Hand the target to Speed Mine when it has room, else mine it the vanilla way and swing. */
    @Synchronized
    private fun mine() {
        val t = target ?: return
        if (mc.options.keyAttack.isDown) return
        val gm = mc.gameMode ?: return
        val sm = Modules.get<SpeedMine>()
        // Not gated on Speed Mine being on (on purpose): it hands off with Speed Mine off too.
        if (sm.mode.value != SpeedMine.Mode.DAMAGE && !sm.busy()) {
            sm.startMining(t.pos, t.face)
            return
        }
        if (gm.playerMode == GameType.CREATIVE) gm.startDestroyBlock(t.pos, t.face) else gm.continueDestroyBlock(t.pos, t.face)
        swing()
    }

    /** The creative pass: every matching block in range, START then STOP (two predictions), removed client-side. */
    private fun creativePass(p: LocalPlayer, level: ClientLevel) {
        val r2 = range.value * range.value
        for (pos in candidates(p)) {
            if (flatten.value && pos.y - p.y < 0) continue
            if (avoidLava.value && lavaAdjacent(level, pos)) continue
            if (eyeDistSq(Vec3.atCenterOf(pos)) > r2) continue
            if (!accepts(level.getBlockState(pos).block)) continue
            Packets.sendSequenced { ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, it) }
            Packets.sendSequenced { ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP, it) }
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
            swing()
        }
    }

    /** The first sorted block that passes the checks and can be seen (or any, with ignore-walls). */
    private fun findTarget(): Target? {
        val p = mc.player ?: return null
        val level = mc.level ?: return null
        val sm = Modules.get<SpeedMine>()
        val r2 = range.value * range.value
        for (pos in candidates(p)) {
            if (sm.enabled && sm.inQueue(pos)) continue
            if (flatten.value && pos.y - p.y < 0) continue
            if (eyeDistSq(Vec3.atCenterOf(pos)) > r2) continue
            if (avoidLava.value && lavaAdjacent(level, pos)) continue
            if (!accepts(level.getBlockState(pos).block)) continue
            if (ignoreWalls.value) {
                val ctx = ClipContext(p.eyePosition, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)
                val hit = clipOnly(level, ctx, pos, Blocks.OBSIDIAN.defaultBlockState())
                return Target(pos, hit.location, hit.direction)
            }
            // 5x5x5 sample points (float maths), the first one whose ray hits this block is the aim
            for (i in 0..4) for (j in 0..4) for (k in 0..4) {
                val step = 0.25f
                val v = Vec3((pos.x + i * step).toDouble(), (pos.y + j * step).toDouble(), (pos.z + k * step).toDouble())
                val h = level.clip(ClipContext(p.eyePosition, v, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p))
                if (h.type == HitResult.Type.BLOCK && h.blockPos == pos) return Target(pos, v, h.direction)
            }
        }
        return null
    }

    /** The cube of `floor(range) + 1` around the feet block (x outer, z inner), stably sorted by [sortKey]. */
    private fun candidates(p: LocalPlayer): List<BlockPos> {
        val r = (floor(range.value.toDouble()).toFloat() + 1).toInt()
        val origin = BlockPos.containing(p.position())
        val list = ArrayList<BlockPos>()
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) list += origin.offset(dx, dy, dz)
        return list.sortedBy { sortKey(p, it) }
    }

    /** FOV: yaw difference to the block centre, not wrapped again after the subtraction (0..360); DISTANCE: [eyeDistSq]. */
    private fun sortKey(p: LocalPlayer, pos: BlockPos): Float {
        val c = Vec3.atCenterOf(pos)
        if (sort.value == Sort.DISTANCE) return eyeDistSq(c)
        return abs(Mth.wrapDegrees(Math.toDegrees(atan2(c.z - p.z, c.x - p.x)) - 90.0) - Mth.wrapDegrees(p.yRot)).toFloat()
    }

    private fun accepts(b: Block): Boolean = when (filter.value) {
        Filter.All -> b != Blocks.BEDROCK && b != Blocks.AIR && b != Blocks.CAVE_AIR && b !is LiquidBlock
        Filter.Select -> b == selected
        Filter.WhiteList -> blocksSetting.value.contains(b)
        Filter.BlackList -> !blocksSetting.value.contains(b) && b != Blocks.BEDROCK && b != Blocks.AIR && b != Blocks.CAVE_AIR &&
            b !is LiquidBlock
    }

    /** Lava (source or flowing) on any of the six sides. */
    private fun lavaAdjacent(level: ClientLevel, pos: BlockPos) = Direction.entries.any { level.getBlockState(pos.relative(it)).block == Blocks.LAVA }

    /** Squared distance from the eye cached at the start of the tick, as a float. */
    private fun eyeDistSq(v: Vec3): Float {
        val e = PlayerState.eye
        val dx = v.x - e.x
        val dy = v.y - e.y
        val dz = v.z - e.z
        return (dx * dx + dy * dy + dz * dz).toFloat()
    }

    /** A ray through a world where only [block] exists, as [state] (its shape for the context's block mode). */
    private fun clipOnly(level: ClientLevel, ctx: ClipContext, block: BlockPos, state: BlockState): BlockHitResult =
        BlockGetter.traverseBlocks<BlockHitResult, ClipContext>(ctx.from, ctx.to, ctx, { c, pos ->
            val s = if (pos != block) Blocks.AIR.defaultBlockState() else state
            level.clipWithInteractionOverride(c.from, c.to, pos, c.getBlockShape(s, level, pos), s)
        }, { c ->
            val d = c.from.subtract(c.to)
            BlockHitResult.miss(c.to, Direction.getApproximateNearest(d.x, d.y, d.z), BlockPos.containing(c.to))
        })

    /** Swings the main hand; 26.3 has no swing packet (the server swings on its own). */
    private fun swing() {
        val p = mc.player ?: return
        p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
    }
}
