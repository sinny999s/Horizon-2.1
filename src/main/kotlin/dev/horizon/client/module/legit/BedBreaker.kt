package dev.horizon.client.module.legit

import dev.horizon.client.combat.BedTeams
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Digs into an enemy bed the way a player does. Near another team's bed (in reach), it looks at
 * every straight way from your eyes to the bed and what you'd have to break along it, and takes the quickest to dig
 * with the tools on your hotbar (one wool block beats two of end stone; obsidian is gone round, not through). It turns
 * the camera in whole mouse counts onto that way, takes out the best tool for the block in front, and mines through
 * vanilla's own attack, block by block, then the bed. Your own team's bed is never touched ([BedTeams] says whose is
 * whose; hitting your own bed on a server that refuses it teaches it that one too).
 *
 * With `activation` on ATTACK_HELD it only steers and switches tools while you hold attack; ALWAYS also holds attack
 * for you whenever the crosshair is on the block to break (bind it to a key with hold on).
 */
class BedBreaker : Module("bed-breaker", Categories.LEGIT, listOf("legit-bed-breaker", "bed-miner")) {
    enum class Activation { ATTACK_HELD, ALWAYS }

    val activation = enum("activation", Activation.ATTACK_HELD)
    /** Fastest the camera turns, in degrees a second. */
    val turnSpeed = float("turn-speed", 450.0f, 100.0f..1500.0f)
    /** Milliseconds between a bed coming into reach and starting to turn to it. */
    val reaction = int("reaction", 80, 0..300)
    val switchTool = bool("switch-tool", true)
    /** Back to the slot held before once there's nothing left to break. */
    val swapBack = bool("swap-back", true) { switchTool.value }
    /** Outlines the block being broken and the rest of the way to the bed. */
    val render = bool("render", true)

    /** The way chosen: where on the bed to look, the blocks in the way (nearest first), the bed. */
    private class Way(val aim: Vec3, val blocks: List<BlockPos>, val bed: BedTeams.Bed, val half: BlockPos, val cost: Double) {
        val next: BlockPos get() = blocks.firstOrNull() ?: half
    }

    private var way: Way? = null
    /** When the current bed came into reach (`System.nanoTime`), 0 with none. */
    private var since = 0L
    /** The block being broken, and the ticks spent on it: a way through it is kept while it's being broken. */
    private var mining: BlockPos? = null
    private var miningTicks = 0
    /** Whether attack is held down by this module (ALWAYS). */
    private var pressing = false
    private var prevSlot = -1
    private var idle = 0
    /** Where on each face to look, as fractions across it: one spot per bed, not always the dead centre. */
    private var spotU = 0.5
    private var spotV = 0.5

    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    override fun onEnable() = reset()

    override fun onDisable() {
        release()
        if (prevSlot >= 0) Inventory.selectHotbar(prevSlot)
        prevSlot = -1
        reset()
    }

    private fun reset() {
        way = null
        since = 0L
        mining = null
        miningTicks = 0
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
    }

    private fun release() {
        if (pressing) mc.options.keyAttack.setDown(false)
        pressing = false
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            // Now and then a wide look at which bed colours are around: if they're all one, colour isn't the team's.
            if (p.tickCount % 40 == 0) BedTeams.scan(p, level, 64)
            val active = mc.gui.screen() == null && !p.isSpectator && !Modules.get<Clutch>().busy &&
                (activation.value == Activation.ALWAYS || mc.options.keyAttack.isDown)
            val w = if (active) plan(p, level) else null
            if (w == null) {
                release()
                reset()
                if (++idle >= SWAP_BACK_TICKS && prevSlot >= 0) {
                    if (swapBack.value) Inventory.selectHotbar(prevSlot)
                    prevSlot = -1
                }
                return@on
            }
            idle = 0
            if (way?.bed?.head != w.bed.head) {
                since = System.nanoTime()
                spotU = Rng.double(0.3, 0.7)
                spotV = Rng.double(0.3, 0.7)
            }
            way = w
            val next = w.next
            if (next != mining) {
                mining = next
                miningTicks = 0
            }
            if (switchTool.value) bestSlot(p, level, next)?.let { slot ->
                if (slot != p.inventory.selectedSlot) {
                    if (prevSlot < 0) prevSlot = p.inventory.selectedSlot
                    Inventory.selectHotbar(slot)
                }
            }
            // The crosshair as vanilla's own attack will see it this tick.
            (mc as MinecraftAccessor).horizon_pick(1f)
            val hit = (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
            val onTarget = hit != null && (hit == next || (next == w.half && w.bed.contains(hit)))
            if (onTarget) miningTicks++
            if (activation.value == Activation.ALWAYS) {
                if (onTarget && !pressing) {
                    mc.options.keyAttack.setDown(true)
                    pressing = true
                } else if (!onTarget) release()
            }
        }

        on<AfterReceivePacketEvent> { e ->
            val pk = e.packet as? ClientboundSystemChatPacket ?: return@on
            if (pk.overlay()) return@on
            // "You can't break your own bed!": the bed being dug at is this player's own.
            if (!pk.content().string.lowercase().contains("own bed")) return@on
            val w = way ?: return@on
            BedTeams.learnOwn(w.bed.head)
            moduleMessage("That's your own bed: leaving it alone.")
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            val to = way?.aim ?: return@on
            if (since == 0L || (System.nanoTime() - since) / 1e6 < reaction.value) return@on
            turn(p, to)
        }

        on<Render3DEvent> {
            if (!render.value) return@on
            val w = way ?: return@on
            val c = Theme.primary
            World3D.outline(AABB(w.next), PlaceBoxes.withAlpha(c, 1f))
            for (b in w.blocks.drop(1)) World3D.outline(AABB(b), PlaceBoxes.withAlpha(c, 0.35f))
            if (w.blocks.isNotEmpty()) World3D.outline(AABB(w.half).setMaxY(w.half.y + BED_TOP), PlaceBoxes.withAlpha(c, 0.35f))
        }
    }

    private fun moduleMessage(text: String) = dev.horizon.client.combat.moduleMessage(name.trim(), text)

    /**
     * The quickest way into a bed in reach that isn't yours: over spots on the top and open sides of each half, the
     * straight line from the eyes and what it passes through, costed in ticks to break with the best hotbar tool. The
     * block already being broken keeps its way (the ticks spent on it count), so the aim doesn't hop between two.
     */
    private fun plan(p: LocalPlayer, level: Level): Way? {
        val eye = p.eyePosition
        val reach = p.blockInteractionRange()
        val r = ceil(reach).toInt() + 1
        val e = BlockPos.containing(eye)
        val beds = LinkedHashMap<BlockPos, BedTeams.Bed>()
        for (x in -r..r) for (y in -r..r) for (z in -r..r) {
            val pos = e.offset(x, y, z)
            if (level.getBlockState(pos).block !is BedBlock) continue
            val bed = BedTeams.bedAt(level, pos) ?: continue
            if (bed.head in beds || BedTeams.owner(bed) == BedTeams.Owner.OWN) continue
            beds[bed.head] = bed
        }
        if (beds.isEmpty()) return null
        val look = p.lookAngle
        val cache = HashMap<BlockPos, Double>()
        var best: Way? = null
        for (bed in beds.values) for (half in listOf(bed.head, bed.foot)) {
            for (point in spots(bed, half)) {
                val to = point.subtract(eye)
                if (to.length() > reach - 0.05) continue
                val blocks = obstacles(level, eye, point, bed) ?: continue
                var cost = 0.0
                for (b in blocks) cost += cache.getOrPut(b) { breakTicks(p, level, b) }
                if (cost.isInfinite()) continue
                // A little for the turn (a degree is next to nothing), and a lot for the block already being broken.
                cost += Math.toDegrees(kotlin.math.acos(to.normalize().dot(look).coerceIn(-1.0, 1.0))) * 0.02
                val first = blocks.firstOrNull() ?: half
                if (first == mining || (blocks.isEmpty() && mining != null && bed.contains(mining!!))) cost -= miningTicks + 2.0
                if (best == null || cost < best.cost) best = Way(point, blocks, bed, half, cost)
            }
        }
        val w = best ?: return null
        if (w.blocks.isEmpty()) return w
        return Way(blockAim(p, level, eye, w.aim, w.blocks[0]), w.blocks, w.bed, w.half, w.cost)
    }

    /**
     * Where to look to break [block], the first in the way to [bedPoint]: the middle part of the face the way enters it
     * by (at this bed's spot), as a player looks at the block they're breaking, not along a line that may only graze
     * its edge. Where that part is hidden, the way's own entry point, pulled a little towards the face's middle.
     */
    private fun blockAim(p: LocalPlayer, level: Level, eye: Vec3, bedPoint: Vec3, block: BlockPos): Vec3 {
        val shape = level.getBlockState(block).getShape(level, block)
        val entry = shape.clip(eye, bedPoint, block) ?: return bedPoint
        val box = shape.bounds().move(block)
        val face = entry.direction
        val c = box.center
        val su = spotU - 0.5
        val sv = spotV - 0.5
        val spot = when (face.axis) {
            Direction.Axis.X -> Vec3(if (face.stepX > 0) box.maxX else box.minX, c.y + sv * box.ysize * 0.4, c.z + su * box.zsize * 0.4)
            Direction.Axis.Z -> Vec3(c.x + su * box.xsize * 0.4, c.y + sv * box.ysize * 0.4, if (face.stepZ > 0) box.maxZ else box.minZ)
            Direction.Axis.Y -> Vec3(c.x + su * box.xsize * 0.4, if (face.stepY > 0) box.maxY else box.minY, c.z + sv * box.zsize * 0.4)
        }
        if (spot.distanceTo(eye) <= p.blockInteractionRange() - 0.05) {
            val hit = level.clip(ClipContext(eye, spot.add(spot.subtract(eye).normalize().scale(0.05)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, p))
            if (hit.type == HitResult.Type.BLOCK && hit.blockPos == block) return spot
        }
        val loc = entry.location
        return loc.add(Vec3(c.x - loc.x, c.y - loc.y, c.z - loc.z).scale(0.35)).let {
            // Back onto the face's plane: pulling towards the centre moved it into the block.
            when (face.axis) {
                Direction.Axis.X -> Vec3(loc.x, it.y, it.z)
                Direction.Axis.Y -> Vec3(it.x, loc.y, it.z)
                Direction.Axis.Z -> Vec3(it.x, it.y, loc.z)
            }
        }
    }

    /** Points on [half]'s top and its sides that aren't against the other half, at this bed's spot across them. */
    private fun spots(bed: BedTeams.Bed, half: BlockPos): List<Vec3> {
        val out = ArrayList<Vec3>()
        val other = if (half == bed.head) bed.foot else bed.head
        for (u in GRID) for (v in GRID) {
            out += Vec3(half.x + shift(u, spotU), half.y + BED_TOP, half.z + shift(v, spotV))
        }
        for (d in Direction.Plane.HORIZONTAL) {
            if (half.relative(d) == other) continue
            for (u in GRID) for (h in SIDE_HEIGHTS) {
                val a = shift(u, spotU)
                val y = half.y + h
                out += when (d) {
                    Direction.NORTH -> Vec3(half.x + a, y, half.z.toDouble())
                    Direction.SOUTH -> Vec3(half.x + a, y, half.z + 1.0)
                    Direction.WEST -> Vec3(half.x.toDouble(), y, half.z + a)
                    else -> Vec3(half.x + 1.0, y, half.z + a)
                }
            }
        }
        return out
    }

    /** [u] moved by this bed's spot, staying well inside the face (0.17 to 0.83 across). */
    private fun shift(u: Double, spot: Double) = u + (spot - 0.5) * 0.4

    /**
     * The blocks the line from [from] to [to] (a point on [bed]) passes through on its way, nearest first: every cell
     * it crosses whose outline it actually goes through. Null if it goes through the bed first (the spot faces away).
     */
    private fun obstacles(level: Level, from: Vec3, to: Vec3, bed: BedTeams.Bed): List<BlockPos>? {
        val out = ArrayList<BlockPos>()
        val dir = to.subtract(from)
        val len = dir.length()
        val d = dir.scale(1.0 / len)
        var x = floor(from.x).toInt()
        var y = floor(from.y).toInt()
        var z = floor(from.z).toInt()
        val sx = if (d.x > 0) 1 else -1
        val sy = if (d.y > 0) 1 else -1
        val sz = if (d.z > 0) 1 else -1
        fun first(o: Double, dd: Double, s: Int, c: Int) = if (dd == 0.0) Double.MAX_VALUE else ((if (s > 0) c + 1 else c) - o) / dd
        var tx = first(from.x, d.x, sx, x)
        var ty = first(from.y, d.y, sy, y)
        var tz = first(from.z, d.z, sz, z)
        val dx = if (d.x == 0.0) Double.MAX_VALUE else abs(1 / d.x)
        val dy = if (d.y == 0.0) Double.MAX_VALUE else abs(1 / d.y)
        val dz = if (d.z == 0.0) Double.MAX_VALUE else abs(1 / d.z)
        // Stop just short of the spot itself, which is on the bed's outline.
        val end = len - 1e-3
        val pos = BlockPos.MutableBlockPos()
        var t = 0.0
        while (t <= end) {
            pos.set(x, y, z)
            val state = level.getBlockState(pos)
            if (!state.isAir) {
                val shape = state.getShape(level, pos)
                if (!shape.isEmpty && shape.clip(from, from.add(d.scale(end)), pos) != null) {
                    if (bed.contains(pos)) return null
                    if (state.getDestroySpeed(level, pos) < 0f) return null
                    out += pos.immutable()
                }
            }
            if (tx < ty && tx < tz) {
                t = tx; tx += dx; x += sx
            } else if (ty < tz) {
                t = ty; ty += dy; y += sy
            } else {
                t = tz; tz += dz; z += sz
            }
        }
        return out
    }

    /** Ticks to break the block at [pos] with the quickest tool on the hotbar (or what's held, if not switching). */
    private fun breakTicks(p: LocalPlayer, level: Level, pos: BlockPos): Double {
        val state = level.getBlockState(pos)
        if (switchTool.value) return (0..8).minOf { ticksWith(p.inventory.getItem(it), state, level, pos) }
        return ticksWith(p.mainHandItem, state, level, pos)
    }

    /** The hotbar slot that breaks [pos] quickest, if it's quicker than what's held. */
    private fun bestSlot(p: LocalPlayer, level: Level, pos: BlockPos): Int? {
        val state = level.getBlockState(pos)
        val held = ticksWith(p.mainHandItem, state, level, pos)
        val best = (0..8).minBy { ticksWith(p.inventory.getItem(it), state, level, pos) }
        return best.takeIf { ticksWith(p.inventory.getItem(it), state, level, pos) < held }
    }

    /** Vanilla's break time in ticks with [stack] in hand (efficiency included, on the ground, no effects). */
    private fun ticksWith(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos): Double {
        val hardness = state.getDestroySpeed(level, pos)
        if (hardness < 0f) return Double.POSITIVE_INFINITY
        if (hardness == 0f) return 1.0
        var speed = stack.getDestroySpeed(state).toDouble()
        if (speed > 1.0) {
            val eff = efficiency(stack)
            if (eff > 0) speed += eff * eff + 1.0
        }
        val correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)
        val perTick = speed / hardness / (if (correct) 30.0 else 100.0)
        return ceil(1.0 / perTick)
    }

    private fun efficiency(stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        val holder = mc.level?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.getOrThrow(Enchantments.EFFICIENCY) ?: return 0
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack)
    }

    /** Turns toward [to] in whole mouse counts: a spring capped at the turn speed, like Clutch's flick but calmer. */
    private fun turn(p: LocalPlayer, to: Vec3) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
        lastNs = now
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
        val maxSpeed = turnSpeed.value.toDouble()
        val stiffness = 400.0
        val c = 2.0 * sqrt(stiffness) * 0.95
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

    private companion object {
        /** A bed's height: 9 pixels. */
        const val BED_TOP = 0.5625
        /** Fractions across a face, before the spot's shift. */
        val GRID = listOf(0.25, 0.5, 0.75)
        val SIDE_HEIGHTS = listOf(0.15, 0.4)
        const val SWAP_BACK_TICKS = 6
    }
}
