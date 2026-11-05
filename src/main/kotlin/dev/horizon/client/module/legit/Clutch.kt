package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Not in the original: saves a fall into the void the way a player clutches. Knocked off (or jumping off) with nothing
 * to land on within [minDrop] blocks, it turns the camera in whole mouse counts onto the side of a block nearby and
 * right-clicks through vanilla once the crosshair is on a face whose new block would catch you, all before your feet
 * sink past it. Blocks come from the hotbar if you aren't holding any. Walking straight off an edge leaves no time for
 * this (for a player either); it's the time a knock-up or a jump gives above the edge that makes the save.
 */
class Clutch : Module("clutch", Categories.LEGIT) {
    enum class FilterMode { NONE, BLACKLIST, WHITELIST }

    /** Fastest the camera turns, in degrees a second; a flick in a clutch is quick. */
    val turnSpeed = float("turn-speed", 900.0f, 200.0f..2000.0f)
    /** Milliseconds between seeing the fall and starting to turn. */
    val reaction = int("reaction", 40, 0..200)
    /** A fall to ground this many blocks down (or none at all) is one to save. */
    val minDrop = int("min-drop", 4, 2..30)
    val switchBlocks = bool("switch-blocks", true)
    val blockSelection = enum("block-selection", FilterMode.BLACKLIST)
    val blockList = blocks("blocks", blockList("block.minecraft.tnt", "block.minecraft.sand", "block.minecraft.gravel")) { blockSelection.value != FilterMode.NONE }

    /** When the fall was noticed (`System.nanoTime`), 0 when not clutching. */
    private var since = 0L
    /** Where on the block face the camera is turning to. */
    private var aim: Vec3? = null
    /** Where on a face to aim, as a fraction across it: one spot per clutch, not always the dead centre. */
    private var spotU = 0.5
    private var spotV = 0.5
    /** Cells whose new block would catch the player this tick, by [BridgePlanner.key]. */
    private val catches = HashSet<Long>()
    private val probe = BlockPos.MutableBlockPos()
    /** Feet height when last on the ground: the drop is measured from where the fall started, not mid-jump. */
    private var groundY = 0.0

    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    /** Mid-clutch with somewhere to aim: [FireballDeflect] leaves the camera to it. */
    val busy get() = enabled && since != 0L && aim != null

    override fun onEnable() = reset()
    override fun onDisable() = reset()

    private fun reset() {
        since = 0L
        aim = null
        catches.clear()
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (p.onGround()) groundY = p.y
            if (mc.gui.screen() != null || p.onGround() || p.abilities.flying || p.isInLiquid || p.isPassenger ||
                p.isFallFlying || p.isSpectator || godBridging(p)) {
                reset()
                return@on
            }
            val solid = { x: Int, y: Int, z: Int ->
                probe.set(x, y, z)
                !level.getBlockState(probe).getCollisionShape(level, probe).isEmpty
            }
            if (!predict(p, solid)) {
                // A safe landing ahead, for now: turning while a key is held swings the path about, so the clutch
                // (and its reaction and aim spot) is only over once the feet are down.
                aim = null
                return@on
            }
            if (since == 0L) {
                since = System.nanoTime()
                spotU = Rng.double(0.3, 0.7)
                spotV = Rng.double(0.3, 0.7)
            }
            // Nothing to build with: the camera is left alone (a pearl, if Pearl Save has one, is the way out).
            if (!isValidBlock(p.mainHandItem) && (!switchBlocks.value || Inventory.hotbarFind { isValidBlock(it) } == null)) {
                aim = null
                return@on
            }
            aim = plan(p, level)
            // The crosshair as vanilla's own right click would see it this tick.
            (mc as MinecraftAccessor).horizon_pick(1f)
            val hit = mc.hitResult as? BlockHitResult
            val target = hit?.takeIf { it.type == HitResult.Type.BLOCK }?.let { h ->
                h.blockPos.relative(h.direction).takeIf {
                    BridgePlanner.key(it.x, it.y, it.z) in catches && !level.getBlockState(h.blockPos).canBeReplaced() &&
                        level.getBlockState(h.blockPos).getMenuProvider(level, h.blockPos) == null
                }
            }
            // Nothing to build against and nothing under the crosshair: leave the hotbar alone (Pearl Save may be
            // getting a pearl out).
            if (aim == null && target == null) return@on
            if (!isValidBlock(p.mainHandItem)) {
                if (!switchBlocks.value) return@on
                val found = Inventory.hotbarFind { isValidBlock(it) } ?: return@on
                Inventory.selectHotbar(found.slot)
            }
            if (target != null && !p.isUsingItem) {
                (mc as MinecraftAccessor).horizon_startUseItem()
                if (!level.getBlockState(target).canBeReplaced()) {
                    // Placed: nothing more to aim for this fall.
                    aim = null
                    catches.clear()
                }
            }
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            val to = aim ?: return@on
            if (since == 0L || (System.nanoTime() - since) / 1e6 < reaction.value) return@on
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now
            val eye = p.getEyePosition(mc.deltaTracker.getGameTimeDeltaPartialTick(true))
            val dx = to.x - eye.x
            val dz = to.z - eye.z
            val wantYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
            val wantPitch = -Math.toDegrees(atan2(to.y - eye.y, hypot(dx, dz)))
            var errYaw = Mth.wrapDegrees(wantYaw - p.yRot)
            var errPitch = wantPitch - p.xRot

            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            val perCount = f * f * f * 8.0
            val step = perCount * 0.15
            if (abs(errYaw) < step) {
                errYaw = 0.0
                vYaw = 0.0
                carryX = 0.0
            }
            if (abs(errPitch) < step) {
                errPitch = 0.0
                vPitch = 0.0
                carryY = 0.0
            }
            // A stiff, nearly critically damped spring capped at the turn speed: a flick that settles, not a snap.
            val maxSpeed = turnSpeed.value.toDouble()
            val stiffness = 900.0
            val c = 2.0 * sqrt(stiffness) * 0.9
            vYaw = (vYaw + (stiffness * errYaw - c * vYaw) * dt).coerceIn(-maxSpeed, maxSpeed)
            vPitch = (vPitch + (stiffness * errPitch - c * vPitch) * dt).coerceIn(-maxSpeed, maxSpeed)
            carryX += vYaw * dt / step
            carryY += vPitch * dt / step
            val countsX = carryX.toInt()
            val countsY = carryY.toInt()
            carryX -= countsX
            carryY -= countsY
            if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
        }
    }

    /** GodBridge jumps over the void on purpose; its own planner looks after those. */
    private fun godBridging(p: LocalPlayer): Boolean {
        val gb = Modules.get<GodBridge>()
        if (!gb.enabled || gb.mode.value != GodBridge.Mode.GOD_BRIDGE) return false
        return Movement.moving() && (p.input.keyPresses.backward() || p.input.moveVector.y < 0f)
    }

    /**
     * Plays the fall out with vanilla's physics (keys held as they are). Returns whether it needs saving: no landing
     * within [minDrop] blocks of the feet. Fills [catches] with the cells a new block would catch the player on: the
     * ones under the box at the tick the feet pass their top, still clear of the player now.
     */
    private fun predict(p: Player, solid: (Int, Int, Int) -> Boolean): Boolean {
        catches.clear()
        val v = p.deltaMovement
        val s = BridgePlanner.State(p.x, p.y, p.z, v.x, v.y, v.z, false)
        val ground = BridgePlanner.push(p.xxa.toDouble(), p.zza.toDouble(), p.yRot.toDouble(), p.speed.toDouble())
        val air = BridgePlanner.push(p.xxa.toDouble(), p.zza.toDouble(), p.yRot.toDouble(), if (p.isSprinting) 0.026 else 0.02)
        val params = BridgePlanner.Params(0.0, -1.0, 0.0, ground[0], ground[1], air[0], air[1],
            halfWidth = p.bbWidth / 2.0, height = p.bbHeight.toDouble(), gravity = p.gravity,
            jumpPower = p.getAttributeValue(Attributes.JUMP_STRENGTH))
        val hw = p.bbWidth / 2.0
        for (t in 0 until LOOK_AHEAD) {
            val x0 = s.x
            val y0 = s.y
            val z0 = s.z
            BridgePlanner.step(s, params, solid, false)
            if (s.onGround) return s.y < min(p.y, groundY) - minDrop.value
            // Every block top the feet sink past this tick, under where the box was (vanilla moves along Y first).
            var top = floor(y0).toInt()
            while (top > s.y) {
                if (top <= p.y + 1e-6) {
                    for (cx in floor(x0 - hw + 1e-7).toInt()..floor(x0 + hw - 1e-7).toInt())
                        for (cz in floor(z0 - hw + 1e-7).toInt()..floor(z0 + hw - 1e-7).toInt())
                            catches += BridgePlanner.key(cx, top - 1, cz)
                }
                top--
            }
            if (s.y < min(p.y, groundY) - minDrop.value - 2) break
        }
        return true
    }

    /**
     * The point to turn to: on the side of a solid block next to one of the catching cells (the earliest catch first,
     * then the smallest turn), facing the eyes, in reach, and the first thing the view would hit going there.
     */
    private fun plan(p: Player, level: Level): Vec3? {
        val eye = p.eyePosition
        val look = p.lookAngle
        val reach = p.blockInteractionRange()
        var best: Vec3? = null
        var bestScore = Double.MAX_VALUE
        for (k in catches) {
            val cell = unkey(k)
            if (!level.getBlockState(cell).canBeReplaced()) continue
            for (d in Direction.entries) {
                val n = cell.relative(d)
                val state = level.getBlockState(n)
                if (state.canBeReplaced() || state.getMenuProvider(level, n) != null) continue
                // The face of n that touches the cell, and a point on it.
                val face = d.opposite
                val point = facePoint(n, face)
                val toPoint = point.subtract(eye)
                if (toPoint.length() > reach - 0.1) continue
                // Seen from the open side with nothing in the way, a click there works now. A face still edge-on (the
                // player hasn't yet crossed its plane, which the cell under their path means they're about to) is
                // turned to all the same: a player starts the flick before the face comes into view.
                val open = toPoint.x * face.stepX + toPoint.y * face.stepY + toPoint.z * face.stepZ < 0
                val clip = if (open) level.clip(ClipContext(eye, point.add(toPoint.normalize().scale(0.05)), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, p)) else null
                val visible = clip != null && clip.type == HitResult.Type.BLOCK && clip.blockPos == n && clip.direction == face
                if (open && !visible) continue
                // Higher catches come first in the fall; then one in view; then the least turning.
                val angle = Math.toDegrees(kotlin.math.acos(toPoint.normalize().dot(look).coerceIn(-1.0, 1.0)))
                val score = -cell.y * 1000.0 + (if (visible) 0.0 else 300.0) + angle
                if (score < bestScore) {
                    bestScore = score
                    best = point
                }
            }
        }
        return best
    }

    /** A point on [face] of the block at [pos], at this clutch's spot across it (kept off the edges). */
    private fun facePoint(pos: BlockPos, face: Direction): Vec3 {
        val c = Vec3.atCenterOf(pos)
        val u = spotU - 0.5
        val v = spotV - 0.5
        return when (face.axis) {
            Direction.Axis.X -> Vec3(c.x + face.stepX * 0.5, c.y + v * 0.8, c.z + u * 0.8)
            Direction.Axis.Z -> Vec3(c.x + u * 0.8, c.y + v * 0.8, c.z + face.stepZ * 0.5)
            Direction.Axis.Y -> Vec3(c.x + u * 0.8, c.y + face.stepY * 0.5, c.z + v * 0.8)
        }
    }

    private fun unkey(k: Long) = BlockPos((k shr 38).toInt(), (k shl 52 shr 52).toInt(), (k shl 26 shr 38).toInt())

    private fun isValidBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.item !is BlockItem) return false
        val block = (stack.item as BlockItem).block
        if (!block.defaultBlockState().isCollisionShapeFullBlock(mc.level ?: return false, BlockPos.ZERO)) return false
        return when (blockSelection.value) {
            FilterMode.NONE -> true
            FilterMode.BLACKLIST -> block !in blockList.value
            FilterMode.WHITELIST -> block in blockList.value
        }
    }

    private companion object {
        /** Ticks the fall is played out for: about two seconds, far enough to tell a drop from a step down. */
        const val LOOK_AHEAD = 40
    }
}
