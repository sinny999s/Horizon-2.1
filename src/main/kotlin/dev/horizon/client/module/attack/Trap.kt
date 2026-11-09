package dev.horizon.client.module.attack

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.PlacerModule
import dev.horizon.client.module.SubCategory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The trap base, shared by trap and self-trap: encloses a player's feet and head ring, the block over the
 * head and the open block under the feet, growing around any cell another living entity stands in, with one helper
 * block where a cell has nothing to be placed against.
 */
abstract class TrapBase(id: String, subCategory: SubCategory) : PlacerModule(id, subCategory, 131) {
    val blocksSetting = blocks("blocks", blockList("block.minecraft.obsidian"))
    val onlyStanding = bool("only-standing", true)

    override fun blocks(): List<Block> = blocksSetting.value

    /** A crystal in a cell is broken only if its explosion leaves us above 4 HP. */
    override fun mayBreak(crystal: Entity): Boolean {
        val p = mc.player ?: return false
        val dmg = Explosions.damage(crystal.position(), p, 0, false, emptyList(), emptyList())
        return dmg + 4.0f - (p.health + p.absorptionAmount) < 0
    }

    protected fun trapPlayer(target: Player?) {
        val self = mc.player ?: return
        if (target === self && self.isVisuallySwimming && onlyStanding.value) return
        if (target == null) return
        val off = PlayerState.movementOffset(0.5)
        val bb = target.boundingBox
        // Airborne: the hitbox top comes down by half a block, so the cage is built at standing height.
        if (!target.onGround()) target.setBoundingBox(AABB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY - 0.5f, bb.maxZ))
        val base = BlockPos.containing(target.position())
        var list: List<BlockPos> = search(base, LinkedHashSet()).toList()
        list = if (target === self) {
            list.sortedWith(compareBy { Vec3.atCenterOf(it).distanceToSqr(self.x + off[0], self.y, self.z + off[1]) })
        } else {
            // Farthest from the (live) eyes first.
            list.sortedWith { a, b -> compareValues(self.eyePosition.distanceTo(Vec3.atCenterOf(b)), self.eyePosition.distanceTo(Vec3.atCenterOf(a))) }
        }
        placeList(list, false)
        if (target.onGround()) return
        target.setBoundingBox(AABB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ))
    }

    /**
     * Depth-first over the six neighbours; an occupied cell is searched around, a clickable one
     * added, otherwise the first helper (not UP) that can be placed goes first, then the cell.
     */
    private fun search(pos: BlockPos, visited: LinkedHashSet<BlockPos>): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val result = ArrayList<BlockPos>()
        for (d in Direction.entries) {
            val n = pos.relative(d)
            val st = level.getBlockState(n)
            if (n in visited) continue
            if (!st.canBeReplaced() && !st.getCollisionShape(level, n).isEmpty) continue
            visited += n
            if (Placer.occupied(n)) {
                result += search(n, visited)
                continue
            }
            if (findHit(n, result)) {
                result += n
                continue
            }
            for (d2 in HELPER) {
                val h = n.relative(d2)
                if (h in visited) continue
                if (!level.getBlockState(h).canBeReplaced()) continue
                visited += h
                if (findHit(h, result)) {
                    result += h
                    result += n
                    break
                }
            }
        }
        return result
    }

    /** `findHit` with this module's settings, cells planned so far in this call counting as clickable. */
    private fun findHit(pos: BlockPos, planned: List<BlockPos>) =
        Placer.findHit(pos, -1, interact.value, IgnoreMode.ONLY_CRYSTALS, range.value, wallRange.value, planned) != null

    private companion object {
        val HELPER = listOf(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
    }
}

/** Traps the target finder's pick (FOV sort). */
class Trap : TrapBase("trap", Categories.ATTACK) {
    init {
        on<PlayerUpdateEvent> { trapPlayer(TargetFinder.find(range.value.toDouble(), TargetFinder.Sort.FOV, false, false)) }
    }
}
