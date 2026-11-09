package dev.horizon.client.module.esp

import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.BlockStateEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.gui.Fps
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Finds the chosen blocks chunk by chunk on a worker thread (re-scanning chunks that changed)
 * and boxes them, nearest first; boxes the chosen entity types too.
 */
class Search : Module("search", Categories.ESP) {
    val blocksSetting = blocks("blocks")
    val entitiesSetting = entities("entities")
    val limit = bool("limit", true)
    val limitCount = int("limit-count", 50, 1..2048) { limit.value }
    val mapColor = bool("map-color", false)
    val colorSetting = color("color", Color(0x78AA0000.toInt(), true)) { !mapColor.value }
    val tracers = bool("tracers", false)
    val fill = bool("fill", true)
    val outline = bool("outline", true)

    private class Found(val pos: BlockPos, val color: Color)

    /** A data class, removed by equality. */
    private data class Chunk(val x: Int, val z: Int, val list: List<Found>, var recalc: Boolean)

    private val chunks = ArrayList<Chunk>()
    // A daemon thread, so it can't keep the game from exiting.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "HorizonClient search").apply { isDaemon = true } }
    private val timer = MsTimer()

    /** A scan pass is allowed to be queued. */
    @Volatile
    private var ready = false

    /** Shared with the worker, which reads the position when it runs (on purpose). */
    @Volatile
    private var lastChanged: BlockPos = BlockPos.ZERO

    init {
        on<PlayerUpdateEvent> {
            if (ready) worker.submit {
                ready = false
                worker.submit { scanAll() }
            }
        }
        on<BlockStateEvent> { e ->
            lastChanged = e.pos
            worker.submit { markChanged(lastChanged.x shr 4, lastChanged.z shr 4) }
        }
        on<AfterReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundLevelChunkWithLightPacket ?: return@on
            // Compares the chunk's x with the packet's z as well (on purpose).
            worker.submit { markChanged(p.z, p.z) }
        }
        on<Render3DEvent> { render() }
    }

    override fun onEnable() {
        chunks.clear()
        ready = true
    }

    private fun markChanged(cx: Int, cz: Int) = synchronized(chunks) {
        chunks.firstOrNull { it.z == cz && it.x == cx }?.recalc = true
    }

    /** (Re)scan every chunk around the player that is new, changed, or empty and due (every 1.5 s). */
    private fun scanAll() = synchronized(chunks) {
        val due = timer.passedAndReset(1500)
        for (cp in chunksAround()) {
            val old = chunks.firstOrNull { it.z == cp.z() && it.x == cp.x() }
            if (old != null && !(due && old.list.isEmpty() && chunks.remove(old)) && !old.recalc) continue
            val found = scan(cp)
            if (old != null && old.recalc) chunks.remove(old)
            chunks += Chunk(cp.x(), cp.z(), found, false)
            val p = mc.player ?: break
            val px = p.x.toInt() shr 4
            val pz = p.z.toInt() shr 4
            // Chunk coordinates against a distance in blocks (on purpose): nothing realistic is ever dropped.
            chunks.removeIf { hypot(px.toDouble() - it.x, pz.toDouble() - it.z) - (mc.options.renderDistance().get() + 4) * 16 > 0 }
        }
        ready = true
    }

    /** The chunks within render distance + 2 of the player's chunk. */
    private fun chunksAround(): List<ChunkPos> {
        val p = mc.player ?: return emptyList()
        val r = mc.options.renderDistance().get() + 2
        val cx = p.x.toInt() shr 4
        val cz = p.z.toInt() shr 4
        val list = ArrayList<ChunkPos>()
        for (x in -r..r) for (z in -r..r) list += ChunkPos(cx + x, cz + z)
        return list
    }

    /** The chosen blocks in one chunk, y from -64 to 319. */
    private fun scan(cp: ChunkPos): List<Found> {
        val level = mc.level ?: return emptyList()
        val list = ArrayList<Found>()
        val pos = BlockPos.MutableBlockPos()
        val x0 = cp.x() * 16
        val z0 = cp.z() * 16
        for (y in -64 until 320) for (x in 0 until 16) for (z in 0 until 16) {
            pos.set(x0 + x, y, z0 + z)
            val block = level.getBlockState(pos).block
            if (block in blocksSetting.value) {
                list += Found(pos.immutable(), if (mapColor.value) Color(block.defaultMapColor().col) else colorSetting.value)
            }
        }
        return list
    }

    private fun render() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (chunks.isEmpty() && entitiesSetting.value.isEmpty()) return
        var count = 0
        if (Fps.value < 8 && p.tickCount > 100) {
            disable()
            return
        }
        if (!fill.value && !outline.value) return
        val view = (mc.options.renderDistance().get() + 4) * 16
        val sorted = ArrayList(chunks).filterNotNull().sortedBy { p.distanceToSqr(it.x * 16.0, p.y, it.z * 16.0) }
        for (c in sorted) {
            for (f in c.list.sortedBy { p.distanceToSqr(Vec3.atCenterOf(it.pos)) }) {
                if (count > limitCount.value && limit.value) continue
                if (Vec3.atCenterOf(f.pos).distanceTo(p.position()) - view > 0) continue
                val box = AABB(f.pos)
                val color = if (mapColor.value) PlaceBoxes.withAlpha(f.color.rgb, 0.5f) else colorSetting.value.rgb
                if (fill.value) World3D.fill(box, color)
                if (outline.value) World3D.outline(box, PlaceBoxes.withAlpha(color, (color ushr 24) * 2f))
                if (tracers.value) World3D.line(Esp2.tracerStart(), Vec3.atCenterOf(f.pos), color, noCull = true)
                count++
            }
        }
        val c = colorSetting.value.rgb
        for (e in level.entitiesForRendering().filter { it.type in entitiesSetting.value }) {
            if (e == mc.player) continue
            val box = Esp2.renderBox(e)
            if (fill.value) World3D.fill(box, c)
            if (outline.value) World3D.outline(box, PlaceBoxes.withAlpha(c, (c ushr 24) * 2f))
            if (tracers.value) World3D.line(Esp2.tracerStart(), box.center, c, noCull = true)
        }
    }
}
