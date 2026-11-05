package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.bindHeld
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.Bind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Not in the original: boxes you in for Bedwars / SkyWars the way a player does it by hand. While the trigger bind is
 * held (or always, with no bind), it picks one missing block of the box at a time (the floor, the four sides at the
 * feet, the four at the head, the roof), turns the camera onto a face it can be placed against in whole mouse counts,
 * and places through vanilla's own right click once the crosshair is really on that face, so every placement is one a
 * player could make. The roof can't be reached standing in a 1x1 box: like a player, it jumps, puts a block on top of
 * one of the head-level walls, and roofs against that. With [reblock], it keeps watching while you stay in that spot
 * and puts back any block of the box someone breaks, trigger held or not (only blocks that were there: letting go
 * before the box is done doesn't finish it).
 */
class BlockIn : Module("block-in", Categories.LEGIT, listOf("box-in", "self-box", "auto-block-in")) {
    enum class FilterMode { NONE, BLACKLIST, WHITELIST }

    /** Fastest the camera turns, in degrees a second. */
    val speed = float("speed", 500.0f, 60.0f..1500.0f)
    /** Milliseconds before turning to a new block. */
    val reaction = int("reaction", 50, 0..300)
    val triggerBind = bind("trigger-bind", Bind())
    val onlyWhileHeld = bool("only-while-held", true)
    val placeDelay = int("place-delay", 1, 0..5)
    val autoDisable = bool("auto-disable", true)
    val coverHead = bool("cover-head", true)
    /** Put back any block of the box that gets broken while you stay in that spot, trigger held or not. */
    val reblock = bool("reblock", true)
    val blockSelection = enum("block-selection", FilterMode.BLACKLIST)
    val blockList = blocks("blocks", blockList("block.minecraft.tnt", "block.minecraft.sand", "block.minecraft.gravel")) { blockSelection.value != FilterMode.NONE }

    /** The block being placed and the point on the face the camera turns to. */
    private var target: BlockPos? = null
    private var aim: Vec3? = null
    private var aimedSince = 0L
    private var spotU = 0.5
    private var spotV = 0.5
    /** Where the feet stood when the box was made, and its blocks seen in place; while the feet stay, [reblock] keeps those. */
    private var anchor: BlockPos? = null
    private val kept = HashSet<BlockPos>()
    private var lastPlaceTick = -100
    private var jump = false

    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    override fun onEnable() = reset()
    override fun onDisable() = reset()

    private fun reset() {
        target = null
        aim = null
        anchor = null
        kept.clear()
        jump = false
        stopTurning()
    }

    private fun stopTurning() {
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
            jump = false
            if (mc.gui.screen() != null || p.isSpectator || p.abilities.flying || p.isPassenger) {
                idle()
                return@on
            }
            val feet = p.blockPosition()
            // Leaving the spot ends the box.
            if (anchor != null && anchor != feet && p.onGround()) {
                anchor = null
                kept.clear()
            }
            val triggered = triggerBind.value.key == -1 || bindHeld(triggerBind.value)
            if (triggered && anchor == null && p.onGround()) anchor = feet
            // The box is always around the spot the feet stood on, also mid-jump (around the feet then, the floor would
            // be the block just left, and it would pillar up). In the air with no spot yet, or knocked off it, nothing.
            val base = anchor?.takeIf { it.x == feet.x && it.z == feet.z }
            val guarding = reblock.value && base != null && kept.isNotEmpty()
            if (base == null || (!triggered && !guarding)) {
                idle()
                return@on
            }
            if (triggered) for (c in cells(base)) if (!level.getBlockState(c).canBeReplaced()) kept += c

            val missing = missing(p, level, base).let { all -> if (triggered) all else all.filter { it in kept } }
            if (missing.isEmpty()) {
                idle()
                if (autoDisable.value && !onlyWhileHeld.value && !reblock.value) disable()
                return@on
            }
            if (!hasBlocks(p)) {
                idle()
                return@on
            }

            // The crosshair as vanilla's own right click would see it this tick: place if it's on a face that fills
            // one of the box's holes.
            (mc as MinecraftAccessor).horizon_pick(1f)
            val hit = mc.hitResult as? BlockHitResult
            if (hit != null && hit.type == HitResult.Type.BLOCK && p.tickCount - lastPlaceTick > placeDelay.value && !p.isUsingItem) {
                val into = hit.blockPos.relative(hit.direction)
                if (into in missing && placeable(level, hit.blockPos) && clearOfPlayers(level, into)) {
                    if (!isValidBlock(p.mainHandItem)) Inventory.hotbarFind { isValidBlock(it) }?.let { Inventory.selectHotbar(it.slot) }
                    if (isValidBlock(p.mainHandItem)) {
                        (mc as MinecraftAccessor).horizon_startUseItem()
                        lastPlaceTick = p.tickCount
                    }
                }
            }

            // Keep the block being turned to while it's still a hole with a face to place against; else the next.
            val current = target
            val keep = current != null && current in missing && aimPoint(p, level, current) != null
            if (!keep) {
                val next = pick(p, level, missing, base)
                if (next != target) {
                    target = next
                    aimedSince = System.nanoTime()
                    spotU = Rng.double(0.3, 0.7)
                    spotV = Rng.double(0.3, 0.7)
                }
            }
            aim = target?.let { aimPoint(p, level, it) }
            // Only the roof (and the block it goes against) is left, out of reach: jump, so the top of a head-level
            // wall comes into view for the block the roof goes against.
            if (aim == null && p.onGround() && missing.all { it.y == base.y + 2 }) jump = true
        }

        on<KeyboardInputEvent>(-10) {
            val p = mc.player ?: return@on
            if (!jump || !p.onGround()) return@on
            val k = p.input.keyPresses
            if (!k.jump()) p.input.keyPresses = Input(k.forward(), k.backward(), k.left(), k.right(), true, k.shift(), k.sprint())
            jump = false
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            val to = aim ?: return@on
            if ((System.nanoTime() - aimedSince) / 1e6 < reaction.value) return@on
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now
            val eye = p.getEyePosition(mc.deltaTracker.getGameTimeDeltaPartialTick(true))
            val dx = to.x - eye.x
            val dz = to.z - eye.z
            var errYaw = Mth.wrapDegrees(Math.toDegrees(atan2(dz, dx)) - 90.0 - p.yRot)
            var errPitch = -Math.toDegrees(atan2(to.y - eye.y, hypot(dx, dz))) - p.xRot
            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            val perCount = f * f * f * 8.0
            val step = perCount * 0.15
            // Within a mouse count is as close as a mouse gets: hold still there.
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
            val maxSpeed = speed.value.toDouble()
            val stiffness = 500.0
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

    private fun idle() {
        target = null
        aim = null
        stopTurning()
    }

    /** The box around [feet]: the floor, the sides at the feet and at the head, the roof. */
    private fun cells(feet: BlockPos): List<BlockPos> {
        val head = feet.above()
        return listOf(feet.below(), head.above()) + HORIZONTALS.map { feet.relative(it) } + HORIZONTALS.map { head.relative(it) }
    }

    /**
     * The box's holes, in the order they're filled: the floor, the sides at the feet, the sides at the head, then the
     * roof, which needs a block on top of a head-level wall to go against first (added just before it).
     */
    private fun missing(p: Player, level: Level, feet: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val head = feet.above()
        fun hole(pos: BlockPos) = level.getBlockState(pos).canBeReplaced()
        if (hole(feet.below())) out += feet.below()
        for (d in HORIZONTALS) if (hole(feet.relative(d))) out += feet.relative(d)
        for (d in HORIZONTALS) if (hole(head.relative(d))) out += head.relative(d)
        val roof = head.above()
        if (coverHead.value && hole(roof)) {
            val supported = HORIZONTALS.any { !hole(roof.relative(it)) }
            if (!supported) {
                // The block beside the roof, on top of a head-level wall that's already there.
                HORIZONTALS.firstOrNull { !hole(head.relative(it)) }?.let { out += roof.relative(it) }
            }
            out += roof
        }
        return out.filter { clearOfPlayers(level, it) || it == feet.below() }
    }

    /** The hole to work on next: the first stage that has one with a face to place against, the least turn among those. */
    private fun pick(p: Player, level: Level, missing: List<BlockPos>, feet: BlockPos): BlockPos? {
        val look = p.lookAngle
        val eye = p.eyePosition
        for (stage in 0..3) {
            var best: BlockPos? = null
            var bestAngle = Double.MAX_VALUE
            for (pos in missing) {
                if (stage(pos, feet) != stage) continue
                val pt = aimPoint(p, level, pos) ?: continue
                val a = Math.toDegrees(kotlin.math.acos(pt.subtract(eye).normalize().dot(look).coerceIn(-1.0, 1.0)))
                if (a < bestAngle) {
                    bestAngle = a
                    best = pos
                }
            }
            if (best != null) return best
        }
        return null
    }

    private fun stage(pos: BlockPos, feet: BlockPos) = when (pos.y - feet.y) {
        -1 -> 0
        0 -> 1
        1 -> 2
        else -> 3
    }

    /**
     * A point to aim at on a face that [pos] can be placed against: a solid, non-interactive neighbour's face toward
     * it, with the eyes on its open side, in reach, and the first thing the view meets on the way there.
     */
    private fun aimPoint(p: Player, level: Level, pos: BlockPos): Vec3? {
        val eye = p.eyePosition
        val reach = p.blockInteractionRange() - 0.1
        var best: Vec3? = null
        var bestDist = Double.MAX_VALUE
        for (d in Direction.entries) {
            val n = pos.relative(d)
            if (!placeable(level, n)) continue
            val face = d.opposite
            val pt = facePoint(n, face)
            val to = pt.subtract(eye)
            if (to.x * face.stepX + to.y * face.stepY + to.z * face.stepZ >= 0) continue
            if (to.length() > reach) continue
            val clip = level.clip(ClipContext(eye, pt.add(to.normalize().scale(0.05)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p))
            if (clip.type != HitResult.Type.BLOCK || clip.blockPos != n || clip.direction != face) continue
            if (to.length() < bestDist) {
                bestDist = to.length()
                best = pt
            }
        }
        return best
    }

    /** This block's spot on [face] of the block at [pos], kept off the edges. */
    private fun facePoint(pos: BlockPos, face: Direction): Vec3 {
        val c = Vec3.atCenterOf(pos)
        val u = (spotU - 0.5) * 0.8
        val v = (spotV - 0.5) * 0.8
        return when (face.axis) {
            Direction.Axis.X -> Vec3(c.x + face.stepX * 0.5, c.y + v, c.z + u)
            Direction.Axis.Z -> Vec3(c.x + u, c.y + v, c.z + face.stepZ * 0.5)
            Direction.Axis.Y -> Vec3(c.x + u, c.y + face.stepY * 0.5, c.z + v)
        }
    }

    /** A block to place against: solid, and not a chest, door, crafting table or the like (a click would open it). */
    private fun placeable(level: Level, pos: BlockPos): Boolean {
        val s = level.getBlockState(pos)
        return !s.canBeReplaced() && !s.getCollisionShape(level, pos).isEmpty && s.getMenuProvider(level, pos) == null
    }

    /** Nobody stands in [pos] (vanilla refuses a block there). */
    private fun clearOfPlayers(level: Level, pos: BlockPos) = level.players().none { it.boundingBox.intersects(AABB(pos)) }

    private fun hasBlocks(p: Player) = isValidBlock(p.mainHandItem) || Inventory.hotbarFind { isValidBlock(it) } != null

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
        val HORIZONTALS = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
    }
}
