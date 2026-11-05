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
 * sink past it. Knocked further out than that from the edge, it builds out to you the way a player clutches: a short
 * line of blocks from the edge to under your path, one click at a time (no faster than [MIN_GAP] ticks apart), each
 * against the last, preferring a line that can be done before your feet pass its level. Blocks come from the hotbar if
 * you aren't holding any. Walking straight off an edge leaves no time for this (for a player either); it's the time a
 * knock-up or a jump gives above the edge that makes the save. A jump that falls short of an island counts too: the
 * fall is played out against walls, so one that hits the island's side and slides down it is seen as a fall (and the
 * island's side is a block to build against).
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
    /** Cells whose new block would catch the player, by [BridgePlanner.key], with the tick the feet pass their top. */
    private val catches = HashMap<Long, Int>()
    /** The line being built: the cells from the edge out to a catch, the next one to place first. */
    private var line: List<BlockPos> = emptyList()
    private var lastPlaceTick = -100
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
        line = emptyList()
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
                    (BridgePlanner.key(it.x, it.y, it.z) in catches || it in line) && !level.getBlockState(h.blockPos).canBeReplaced() &&
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
            if (target != null && !p.isUsingItem && p.tickCount - lastPlaceTick >= MIN_GAP) {
                (mc as MinecraftAccessor).horizon_startUseItem()
                if (!level.getBlockState(target).canBeReplaced()) {
                    lastPlaceTick = p.tickCount
                    // Placed: the next tick plans again from it (the line's next block, or nothing left to do).
                    aim = null
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
            // From where the click will be made: the next tick starts at the player's current (not the drawn,
            // in-between) position. Falling half a block a tick, aiming from the drawn one misses a steep face.
            val eye = p.eyePosition
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
            BridgePlanner.step(s, params, solid, false, walls = true)
            if (s.onGround) return s.y < min(p.y, groundY) - minDrop.value
            // Every block top the feet sink past this tick, under where the box was (vanilla moves along Y first).
            var top = floor(y0).toInt()
            while (top > s.y) {
                if (top <= p.y + 1e-6) {
                    for (cx in floor(x0 - hw + 1e-7).toInt()..floor(x0 + hw - 1e-7).toInt())
                        for (cz in floor(z0 - hw + 1e-7).toInt()..floor(z0 + hw - 1e-7).toInt())
                            catches.putIfAbsent(BridgePlanner.key(cx, top - 1, cz), t)
                }
                top--
            }
            if (s.y < min(p.y, groundY) - minDrop.value - 2) break
        }
        return true
    }

    /**
     * The point to turn to, and [line]: the shortest line of new blocks, at a catch's level, from a block there is to a
     * catching cell, every cell in reach. The first block of it goes against the side of a solid block (facing the eyes,
     * in reach, the first thing the view would hit going there); each next one against the last. A line that can be
     * built (a click every [MIN_GAP] ticks) before the feet pass its catch wins; then the shortest, the earliest catch,
     * the least turning. With none in time, the best of the rest is tried all the same.
     */
    private fun plan(p: Player, level: Level): Vec3? {
        val was = line.firstOrNull()
        line = emptyList()
        if (catches.isEmpty()) return null
        val eye = p.eyePosition
        val look = p.lookAngle
        val reach = p.blockInteractionRange()
        // The face to click for the first block of a line starting at a cell, with its turn.
        fun anchor(cell: BlockPos): Pair<Vec3, Double>? {
            var best: Pair<Vec3, Double>? = null
            for (d in Direction.entries) {
                val n = cell.relative(d)
                val state = level.getBlockState(n)
                if (state.canBeReplaced() || state.getMenuProvider(level, n) != null) continue
                val face = d.opposite
                val point = facePoint(n, face)
                val toPoint = point.subtract(eye)
                if (toPoint.length() > reach - 0.1) continue
                // Seen from the open side with nothing in the way, a click there works now. A face still edge-on (the
                // player hasn't yet crossed its plane) is turned to all the same: a player starts the flick before the
                // face comes into view.
                val open = toPoint.x * face.stepX + toPoint.y * face.stepY + toPoint.z * face.stepZ < 0
                val clip = if (open) level.clip(ClipContext(eye, point.add(toPoint.normalize().scale(0.05)), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, p)) else null
                val visible = clip != null && clip.type == HitResult.Type.BLOCK && clip.blockPos == n && clip.direction == face
                if (open && !visible) continue
                // A face seen nearly edge-on is a sliver on screen: a fraction of a degree off misses it, and the fall
                // keeps changing the angle. Those come last among the faces in view.
                val facing = -(toPoint.x * face.stepX + toPoint.y * face.stepY + toPoint.z * face.stepZ) / toPoint.length()
                val angle = Math.toDegrees(kotlin.math.acos(toPoint.normalize().dot(look).coerceIn(-1.0, 1.0))) +
                    (if (visible) 0.0 else 300.0) + (if (facing < GRAZING) (GRAZING - facing) * 1000.0 else 0.0)
                if (best == null || angle < best.second) best = point to angle
            }
            return best
        }
        fun inReach(c: BlockPos) = Vec3.atCenterOf(c).distanceTo(eye) <= reach + 0.3
        val wait = (MIN_GAP - (p.tickCount - lastPlaceTick)).coerceAtLeast(0)
        var bestLine: List<BlockPos>? = null
        var bestAim: Vec3? = null
        var bestScore = Double.MAX_VALUE
        // One search per catch level: outwards from every cell there with a block to go against, across empty cells
        // in reach, to the catches.
        for (y in catches.keys.map { unkey(it).y }.distinct()) {
            val anchors = HashMap<BlockPos, Pair<Vec3, Double>>()
            val prev = HashMap<BlockPos, BlockPos>()
            val dist = HashMap<BlockPos, Int>()
            val queue = ArrayDeque<BlockPos>()
            val ex = floor(eye.x).toInt()
            val ez = floor(eye.z).toInt()
            for (x in ex - SEARCH..ex + SEARCH) for (z in ez - SEARCH..ez + SEARCH) {
                val c = BlockPos(x, y, z)
                if (!level.getBlockState(c).canBeReplaced() || !inReach(c)) continue
                anchors[c] = anchor(c) ?: continue
                dist[c] = 1
                queue += c
            }
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val d = dist[c]!!
                if (d >= MAX_LINE) continue
                for (dir in Direction.Plane.HORIZONTAL) {
                    val n = c.relative(dir)
                    if (n in dist || !level.getBlockState(n).canBeReplaced() || !inReach(n)) continue
                    dist[n] = d + 1
                    prev[n] = c
                    queue += n
                }
            }
            for ((k, tPass) in catches) {
                val cell = unkey(k)
                if (cell.y != y) continue
                val d = dist[cell] ?: continue
                val path = ArrayList<BlockPos>()
                var c: BlockPos? = cell
                while (c != null) {
                    path += c
                    c = prev[c]
                }
                path.reverse()
                val first = anchors[path[0]] ?: continue
                // The first click after the turn to it (at the turn speed, and a tick to settle), then one every MIN_GAP.
                val turn = kotlin.math.ceil(min(first.second, 180.0) / (turnSpeed.value / 20.0)).toInt() + 1
                val ticks = maxOf(wait, turn) + (d - 1) * MIN_GAP
                // Staying with the block already being turned to: switching back and forth never lets the flick land.
                val score = (if (ticks <= tPass) 0.0 else 100000.0) + d * 1000.0 + tPass * 10.0 + first.second +
                    (if (path[0] == was) -400.0 else 0.0)
                if (score < bestScore) {
                    bestScore = score
                    bestLine = path
                    bestAim = first.first
                }
            }
        }
        line = bestLine ?: emptyList()
        return bestAim
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
        /** Fewest ticks between two placements: ten a second, a quick player's right-click. */
        const val MIN_GAP = 2
        /** Longest line built out to a catch. */
        const val MAX_LINE = 6
        /** How squarely (the cosine to the face's normal) a face is seen for it to be an easy click. */
        const val GRAZING = 0.35
        /** How far around the eyes (in blocks, each way) a line can start. */
        const val SEARCH = 6
    }
}
