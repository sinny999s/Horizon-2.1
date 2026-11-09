package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Interact
import dev.horizon.client.module.Modules
import dev.horizon.client.module.PlaceMode
import dev.horizon.client.module.Switch2
import dev.horizon.client.module.player.BoxShrinker
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import java.util.concurrent.ConcurrentHashMap

/** Which entities in the target block don't block a placement. */
enum class IgnoreMode { ALL, ONLY_CRYSTALS, NONE }

/** Positions AutoCrystal is about to put a crystal on; the placer won't fill them. */
object PlacementLock {
    private val locks = ConcurrentHashMap<BlockPos, Long>()

    fun lock(pos: BlockPos) {
        locks[pos] = System.currentTimeMillis()
    }

    fun unlock(pos: BlockPos) {
        locks.remove(pos)
    }

    fun isLocked(pos: BlockPos) = locks.containsKey(pos)

    fun expire(ping: Int) {
        val now = System.currentTimeMillis()
        locks.entries.removeIf { now - it.value > ping * 3 }
    }
}

/** A neighbour to click: [pos] is the solid (or pending) block, [face] the face of it that touches the target. */
private class Candidate(val pos: BlockPos, val face: Direction)

/**
 * The block placer: finds the face and point to click so a block lands in a position, builds the rotation
 * request for it, and does the click with the chosen switching.
 */
object Placer {
    private val mc get() = Minecraft.getInstance()

    /** Positions placed but not confirmed yet (placing against them works meanwhile). */
    val pending = ConcurrentHashMap<BlockPos, Long>()

    /** The face clicked, and where the clicked block is relative to the target, in this order. */
    private val NEIGHBOURS = listOf(
        Direction.UP to BlockPos(0, -1, 0),
        Direction.DOWN to BlockPos(0, 1, 0),
        Direction.EAST to BlockPos(-1, 0, 0),
        Direction.WEST to BlockPos(1, 0, 0),
        Direction.NORTH to BlockPos(0, 0, 1),
        Direction.SOUTH to BlockPos(0, 0, -1),
    )

    val DEFAULT_BLOCKS: List<Block> = listOf(Blocks.OBSIDIAN, Blocks.ENDER_CHEST, Blocks.NETHERITE_BLOCK, Blocks.RESPAWN_ANCHOR)

    fun init() {
        EventBus.on<PlayerUpdateEvent>(this, 10) {
            val now = System.currentTimeMillis()
            val ping = PlayerState.ping
            pending.entries.removeIf { (now - it.value).toFloat() > ping * 2.0f }
            PlacementLock.expire(ping)
        }
    }

    /** The block's box, a hair narrower in X and Z with Box Shrinker on. */
    fun box(pos: BlockPos): AABB {
        val b = AABB(pos)
        return if (Modules.get<BoxShrinker>().enabled) b.inflate(-1e-12, 0.0, -1e-12) else b
    }

    /** A living entity stands in the block. */
    fun occupied(pos: BlockPos): Boolean =
        mc.level?.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity::class.java, box(pos))?.isNotEmpty() ?: false

    /**
     * A request that rotates to the click point and, when run, places in [pos] from hotbar [slot]
     * (−1 = whatever is held). Null when there's nothing to click.
     */
    fun place(pos: BlockPos, slot: Int, interact: Interact, mode: PlaceMode, ignore: IgnoreMode, range: Float, wallRange: Float,
              priority: Int, switch: Switch2, calcGhost: Collection<BlockPos> = emptyList(), grimSilent: Boolean = false): RotationRequest? {
        val hit = findHit(pos, slot, interact, ignore, range, wallRange, calcGhost) ?: return null
        val rot = Rotation.lookAt(hit.location, grimSilent)
        val action = { click(slot, switch, hit, mode, pos) }
        return if (interact == Interact.GRIM) GrimRequest(rot, priority, action) else RotationRequest(rot, priority, action)
    }

    /** Not placed already (pending), and there is something to click for it. */
    fun canPlace(pos: BlockPos, interact: Interact, ignore: IgnoreMode, range: Float, wallRange: Float): Boolean =
        !pending.containsKey(pos) && findHit(pos, -1, interact, ignore, range, wallRange, emptyList()) != null

    fun findHit(pos: BlockPos, slot: Int, interact: Interact, ignore: IgnoreMode, range: Float, wallRange: Float,
                calcGhost: Collection<BlockPos>): BlockHitResult? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        if (PlacementLock.isLocked(pos)) return null
        val ents = level.getEntitiesOfClass(Entity::class.java, box(pos))
        val blocking = when (ignore) {
            IgnoreMode.ALL -> ents.filter { it !is ItemEntity && it !is ExperienceOrb }
            IgnoreMode.ONLY_CRYSTALS -> ents.filter { it !is EndCrystal && it !is ItemEntity && it !is ExperienceOrb }
            IgnoreMode.NONE -> emptyList()
        }
        if (blocking.isNotEmpty()) return null
        val state = level.getBlockState(pos)
        if (!state.canBeReplaced()) return null
        if (interact == Interact.AirPlace) {
            // Only the target counts, as a full block; every other block is air.
            val from = PlayerState.eye
            val center = Vec3.atCenterOf(pos)
            return Shapes.block().clip(from, center, pos)
                ?: BlockHitResult.miss(center, Direction.getApproximateNearest(from.x - center.x, from.y - center.y, from.z - center.z),
                    BlockPos.containing(center))
        }
        val cands = neighbours(pos, calcGhost)
        val item = if (slot in 0..8) player.inventory.getItem(slot).item else Items.OBSIDIAN
        if (item == Items.ITEM_FRAME) cands.removeIf { it.face == Direction.UP || it.face == Direction.DOWN }
        if (item == Items.VINE && state.block == Blocks.VINE) return null
        if (item == Items.LADDER) cands.removeIf { c ->
            val at = Vec3.atCenterOf(c.pos).relative(c.face, 0.5)
            ents.any { it.position().distanceToSqr(at) < 0.5 }
        }
        for (c in cands) {
            if (interact == Interact.Strict || interact == Interact.GRIM) {
                val vis = visibleFaces(c.pos)
                if (vis.isEmpty()) return null
                if (c.face !in vis) continue
            }
            val v = faceHitVec(c.face, c.pos, range, wallRange, interact == Interact.GRIM) ?: continue
            return BlockHitResult(v, c.face, c.pos, false)
        }
        return null
    }

    /** Blocks next to [pos] that can be clicked (solid, pending, or in [calcGhost]). */
    private fun neighbours(pos: BlockPos, calcGhost: Collection<BlockPos>): MutableList<Candidate> {
        val level = mc.level ?: return ArrayList()
        val out = ArrayList<Candidate>()
        for ((face, off) in NEIGHBOURS) {
            val n = pos.offset(off)
            if (!level.getBlockState(n).canBeReplaced() || pending.containsKey(n) || n in calcGhost) out += Candidate(n, face)
        }
        return out
    }

    /** "Strict directions": faces of [pos] the eyes are beyond and that aren't covered. */
    fun visibleFaces(pos: BlockPos): List<Direction> {
        val level = mc.level ?: return emptyList()
        val c = Vec3.atCenterOf(pos)
        val e = PlayerState.eye
        val out = ArrayList<Direction>(6)
        fun add(d: Direction, beyond: Boolean) {
            if (beyond && level.getBlockState(pos.relative(d)).canBeReplaced()) out += d
        }
        add(Direction.EAST, e.x > c.x + 0.5)
        add(Direction.WEST, e.x < c.x - 0.5)
        add(Direction.SOUTH, e.z > c.z + 0.5)
        add(Direction.NORTH, e.z < c.z - 0.5)
        add(Direction.UP, e.y > c.y + 0.5)
        add(Direction.DOWN, e.y < c.y - 0.5)
        return out
    }

    /** The closest (or, for GRIM, a random) point of 8x8 on the face that's in range. */
    fun faceHitVec(face: Direction, n: BlockPos, range: Float, wallRange: Float, random: Boolean): Vec3? {
        val points = ArrayList<Vec3>(64)
        fun steps(): DoubleArray {
            val out = DoubleArray(8)
            var i = 0
            var a = 0.15
            while (a < 0.85) {
                out[i++] = a
                a += 0.1
            }
            return out.copyOf(i)
        }
        val s = steps()
        for (a in s) for (b in s) {
            val p = when (face) {
                Direction.DOWN -> Vec3(n.x + a, n.y + 0.0, n.z + b)
                Direction.UP -> Vec3(n.x + a, n.y + 1.0, n.z + b)
                Direction.NORTH -> Vec3(n.x + a, n.y + b, n.z + 0.0)
                Direction.SOUTH -> Vec3(n.x + a, n.y + b, n.z + 1.0)
                Direction.WEST -> Vec3(n.x + 0.0, n.y + a, n.z + b)
                Direction.EAST -> Vec3(n.x + 1.0, n.y + a, n.z + b)
            }
            if (!invalid(p, n, range, wallRange)) points += p
        }
        if (points.isEmpty()) return null
        if (random) return points.random()
        val eye = mc.player?.eyePosition ?: return null
        var best = points[0]
        var bd = eye.distanceToSqr(best)
        for (p in points) {
            val d = eye.distanceToSqr(p)
            if (d < bd) {
                best = p
                bd = d
            }
        }
        return best
    }

    private fun invalid(p: Vec3, n: BlockPos, range: Float, wallRange: Float): Boolean {
        val level = mc.level ?: return true
        val player = mc.player ?: return true
        val hit = level.clip(ClipContext(PlayerState.eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val d = p.distanceToSqr(player.eyePosition).toFloat()
        if (hit.type == HitResult.Type.BLOCK && hit.blockPos != n && d > wallRange * wallRange) return true
        return d > range * range
    }

    /** Clicking it would open or flip it, so the placer sneaks. */
    fun isInteractable(b: Block) =
        b is BaseEntityBlock || b == Blocks.CRAFTING_TABLE || b is TrapDoorBlock || b is LeverBlock || b is ButtonBlock || b is DoorBlock

    /** Switch, (sneak), click, record as pending, switch back. */
    fun click(slot: Int, switch: Switch2, hit: BlockHitResult, mode: PlaceMode, target: BlockPos) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val prev = player.inventory.selectedSlot
        if (slot != -1) when (switch) {
            Switch2.Normal, Switch2.Silent -> Inventory.selectHotbar(slot)
            Switch2.Alternative -> Inventory.swapWithSelected(slot)
        }
        val sneak = isInteractable(level.getBlockState(hit.blockPos).block) && !player.isShiftKeyDown
        if (sneak) PlayerState.setSneak(true)
        if (mode == PlaceMode.Normal) mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        else Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
        // No swing packet here: 26.3 has none (the server swings for a successful use by itself).
        pending[target] = System.currentTimeMillis()
        if (sneak) PlayerState.setSneak(false)
        if (slot == -1) return
        when (switch) {
            Switch2.Silent -> Inventory.selectHotbar(prev)
            Switch2.Alternative -> Inventory.swapWithSelected(slot)
            Switch2.Normal -> {}
        }
    }
}
