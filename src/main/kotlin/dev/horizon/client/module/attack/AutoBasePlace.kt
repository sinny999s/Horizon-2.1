package dev.horizon.client.module.attack

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Modules
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SpongeBlock
import net.minecraft.world.phys.BlockHitResult
import java.util.concurrent.Executors

/**
 * While Auto Crystal has a target, a background search for crystal bases that aren't
 * obsidian yet; one that would do clearly more damage than any real base gets its obsidian placed (and, with sponges,
 * a sponge next to it to soak up water above).
 */
class AutoBasePlace : PlacerModule("auto-base-place", Categories.ATTACK, 150) {
    val sponges = bool("sponges", false)
    val spongeDelay = int("sponge-delay", 10, 0..60) { sponges.value }
    val onlyNoPositions = bool("only-no-positions", false)

    /** The latest finished search's base (written by the worker threads, unsynchronised on purpose). */
    @Volatile
    private var found: BlockHitResult? = null

    /** The base last tried (placed or not); the sponge goes next to it. */
    private var last: BlockPos? = null
    private val baseTimer = TickTimer()
    private val spongeTimer = TickTimer()

    override fun blocks(): List<Block> = listOf(Blocks.OBSIDIAN)

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    private fun tick() {
        val ac = Modules.get<AutoCrystal>()
        if (!ac.enabled) return
        if (ac.target == null) return
        val spongeOk = sponges.value && Inventory.hotbarFind(Items.SPONGE) != null
        // One worker per tick, never joined; this tick's placing uses an earlier finished search.
        executor.execute {
            Thread.sleep(0)
            found = ac.supportSearch(onlyNoPositions.value, spongeOk)
        }
        if (baseTimer.passed(10)) {
            val hit = found
            if (hit != null) {
                placeList(listOf(hit.blockPos), false)
                last = hit.blockPos
                baseTimer.reset()
            }
        }
        val d = last
        if (spongeOk && d != null && spongeTimer.passed(spongeDelay.value)) spongeStep(d)
    }

    /** A sponge beside [base] when something replaceable (water) is on top of it. */
    private fun spongeStep(base: BlockPos) {
        val level = mc.level ?: return
        val above = level.getBlockState(base.above())
        if (above.isAir) return
        if (!above.canBeReplaced()) return
        for (d in SIDES) if (level.getBlockState(base.relative(d)).block is SpongeBlock) return
        for (d in SIDES) {
            val p = base.relative(d)
            if (canPlace(p)) {
                placeList(listOf(p), false, false, listOf(Blocks.SPONGE))
                spongeTimer.reset()
                return
            }
        }
    }

    /** Auto Crystal's support search asks this (off-thread) whether a base can be placed. */
    fun canPlace(pos: BlockPos) = Placer.canPlace(pos, interact.value, ignoreMode, range.value, wallRange.value)

    private companion object {
        val executor = Executors.newVirtualThreadPerTaskExecutor()!!

        val SIDES = listOf(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
    }
}
