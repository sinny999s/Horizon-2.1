package dev.horizon.client.module.attack

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Predictor
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Modules
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GrassBlock
import net.minecraft.world.level.block.LiquidBlock

/**
 * Auto-web with sweet berry bushes, planted under the enemy's predicted feet
 * (and around them with `directions`).
 */
class AutoSweetBerries : PlacerModule("auto-sweet-berries", Categories.ATTACK, 150) {
    enum class TargetMode { AUTO, ALL }

    val targetMode = enum("target-mode", TargetMode.AUTO)
    val directions = bool("directions", false)

    override fun blocks(): List<Block> = listOf(Blocks.SWEET_BERRY_BUSH)

    /** No entity check: the bush goes into the target's own cell. */
    override val ignoreMode: IgnoreMode get() = IgnoreMode.NONE

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    /** Auto-web's tick, with "in a bush" for "webbed". */
    private fun tick() {
        val self = mc.player ?: return
        val level = mc.level ?: return
        val range = range.value
        val fallback = TargetFinder.find(range.toDouble(), TargetFinder.Sort.FOV, false, false)
        if (targetMode.value == TargetMode.AUTO) {
            var t: Player? = fallback
            val aura = Modules.get<Aura>()
            val auraTarget = aura.target
            if (aura.enabled && auraTarget is Player) t = auraTarget
            val crystal = Modules.get<AutoCrystal>()
            if (crystal.enabled && crystal.target != null) t = crystal.target
            if (t == null || inBush(t)) return
            plant(t)
            return
        }
        for (p in level.players()) {
            if (p === self || Friends.isFriend(p.gameProfile.name)) continue
            if (p.distanceTo(self) - range > 0) continue
            if (inBush(p)) continue
            plant(p)
        }
    }

    /** A bush (any age) in the feet block. */
    private fun inBush(p: Player): Boolean = mc.level?.getBlockState(BlockPos.containing(p.position()))?.block == Blocks.SWEET_BERRY_BUSH

    /** Grass block or dirt (a soil test narrower than vanilla's). */
    private fun soil(b: BlockPos): Boolean {
        val block = mc.level?.getBlockState(b)?.block ?: return false
        return block is GrassBlock || block == Blocks.DIRT
    }

    /** The predicted feet block, then (with `directions`) its N, S, W, E neighbours; never our feet. */
    private fun plant(target: Player) {
        val self = mc.player ?: return
        val level = mc.level ?: return
        val p = BlockPos.containing(Predictor.linear(target, Predictor.linearTicks(target)))
        val own = BlockPos.containing(self.position())
        val list = ArrayList<BlockPos>(5)
        if (soil(p.below()) && level.getBlockState(p).block !is LiquidBlock && p != own) list += p
        if (directions.value) {
            for (d in HORIZONTAL) {
                val n = p.relative(d)
                if (!soil(n.below())) continue
                if (level.getBlockState(n).block is LiquidBlock) continue
                if (n == own) continue
                list += n
            }
        }
        placeList(list, false)
    }

    private companion object {
        val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
    }
}
