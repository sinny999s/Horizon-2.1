package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.HoleAnchor
import dev.horizon.client.module.esp.HoleEsp
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Hole cache: every tick an async scan around the feet for 1x1, 2x1 and 2x2 holes walled by blast-proof
 * blocks; readers get the last finished scan. Nobody reading for a second stops the scanning (and empties the list).
 */
object HoleCache {
    enum class Safety { SAFE, UNSAFE }
    enum class Shape { SINGLE, DOUBLE_X, DOUBLE_Z, QUAD }

    /** [pos] is the hole's min corner. */
    class Hole(val pos: BlockPos, val safety: Safety, val shape: Shape)

    private val mc get() = Minecraft.getInstance()

    private val UNBREAK: Set<Block> = setOf(Blocks.BEDROCK, Blocks.BARRIER)
    private val RESIST: Set<Block> = setOf(Blocks.OBSIDIAN, Blocks.ENDER_CHEST, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK)

    // Walls besides (-1,0,0) and (0,0,-1), relative to the min corner.
    private val QUAD_OFFS = listOf(intArrayOf(-1, 1), intArrayOf(0, 2), intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, 0), intArrayOf(1, -1))
    private val DZ_OFFS = listOf(intArrayOf(-1, 1), intArrayOf(0, 2), intArrayOf(1, 1), intArrayOf(1, 0))
    private val DX_OFFS = listOf(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(2, 0), intArrayOf(1, -1))

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val idle = MsTimer()

    @Volatile
    private var scan: CompletableFuture<List<Hole>>? = null

    @Volatile
    private var holes: List<Hole> = emptyList()

    private var plugged = false

    /** Plugs the per-tick scan (once). Called at module construction, before any module is plugged. */
    fun init() {
        if (plugged) return
        plugged = true
        EventBus.on<PlayerUpdateEvent>(this) {
            if (idle.passed(1000)) {
                if (holes.isNotEmpty()) holes = emptyList()
                return@on
            }
            // A finished scan nobody has read yet is kept when the next one starts, not dropped.
            scan?.let { if (it.isDone) holes = it.get() }
            scan = CompletableFuture.supplyAsync({ scan() }, executor).exceptionally { emptyList() }
        }
    }

    /** Keeps the scanning alive, and picks up the latest scan if it has finished. */
    fun holes(): List<Hole> {
        idle.reset()
        val s = scan
        if (s != null && s.isDone) holes = s.get()
        return holes
    }

    /** [pos] is a cell of a cached hole. */
    fun contains(pos: BlockPos): Boolean = holes().any { h ->
        val o = h.pos
        pos == o || when (h.shape) {
            Shape.DOUBLE_X -> pos == o.offset(1, 0, 0)
            Shape.DOUBLE_Z -> pos == o.offset(0, 0, 1)
            Shape.QUAD -> pos == o.offset(0, 0, 1) || pos == o.offset(1, 0, 1) || pos == o.offset(1, 0, 0)
            Shape.SINGLE -> false
        }
    }

    /** A block read that works off-thread; air for anything unloaded or failing. */
    private fun block(x: Int, y: Int, z: Int): BlockState = try {
        val level = mc.level!!
        val chunk = level.getChunk(x shr 4, z shr 4)
        val i = level.getSectionIndex(y)
        val sections = chunk.sections
        if (i < 0 || i >= sections.size || sections[i].hasOnlyAir()) Blocks.AIR.defaultBlockState()
        else sections[i].getBlockState(x and 15, y and 15, z and 15)
    } catch (_: Throwable) {
        Blocks.AIR.defaultBlockState()
    }

    /** The scan, on a virtual thread. */
    private fun scan(): List<Hole> {
        val out = ArrayList<Hole>()
        val p = BlockPos.containing(mc.player!!.position())
        val s = minOf(32, maxOf(Modules.get<HoleAnchor>().horizontalRange.value, Modules.get<HoleEsp>().rangeXY.value))
        val tv = minOf(32, maxOf(Modules.get<HoleAnchor>().verticalRange.value, Modules.get<HoleEsp>().rangeY.value))
        for (x in p.x - s..p.x + s) for (y in p.y - tv..p.y + tv) for (z in p.z - s..p.z + s) {
            if (!block(x, y, z).canBeReplaced() || !block(x, y + 1, z).canBeReplaced() || !block(x, y + 2, z).canBeReplaced()) continue
            val floor = block(x, y - 1, z).block
            if (floor !in RESIST && floor !in UNBREAK) continue
            classify(x, y, z)?.let { out += it }
        }
        return out
    }

    /** The hole whose min corner is (x, y, z), walls counted at hole level only. */
    private fun classify(x: Int, y: Int, z: Int): Hole? {
        var r = 0
        var u = 0
        fun wall(wx: Int, wz: Int) {
            val b = block(wx, y, wz).block
            if (b in RESIST) r++ else if (b in UNBREAK) u++
        }
        fun walls(offs: List<IntArray>) {
            for (o in offs) wall(x + o[0], z + o[1])
        }
        fun hole(total: Int, shape: Shape) = if (r + u == total) Hole(BlockPos(x, y, z), if (u == total) Safety.SAFE else Safety.UNSAFE, shape) else null
        wall(x - 1, z)
        wall(x, z - 1)
        if (r + u < 2) return null
        val airZ = block(x, y, z + 1).isAir
        val airX = block(x + 1, y, z).isAir
        if (airZ && airX) {
            if (!block(x + 1, y, z + 1).isAir) return null
            walls(QUAD_OFFS)
            return hole(8, Shape.QUAD)
        }
        if (airZ && block(x, y, z + 1).canBeReplaced()) {
            walls(DZ_OFFS)
            return hole(6, Shape.DOUBLE_Z)
        }
        if (airX && block(x + 1, y, z).canBeReplaced()) {
            walls(DX_OFFS)
            return hole(6, Shape.DOUBLE_X)
        }
        wall(x, z + 1)
        wall(x + 1, z)
        return hole(4, Shape.SINGLE)
    }
}
