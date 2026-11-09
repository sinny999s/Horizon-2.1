package dev.horizon.client.module.esp

import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FindEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.huntmap.MapRegion
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.world.HuntMap
import dev.horizon.client.render.World3D
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Marks chunks the server has only just generated (new) and ones generated before (old), so a line of
 * new chunks through old ground shows where someone travelled. Uses both fluid spread and Trouser-Streak's palette
 * check ([palette]) for new chunks, and settled flowing fluid for old chunks. With Hunt Map on, each verdict is saved
 * and reused the next time the chunk loads, so your own trail stays new after a relog or a 6b6t worker move.
 */
class NewChunks : Module("new-chunks", Categories.ESP) {
    enum class Mode { FLAT, OUTLINE }

    val mode = enum("mode", Mode.FLAT)
    val palette = bool("palette", true)
    val showOld = bool("show-old", true)
    val height = int("height", 0, -64..320)
    val range = int("range", 16, 4..64)
    val newColor = color("new-color", Color(0x50FF3B3B, true))
    val oldColor = color("old-color", Color(0x283BFF6B, true)) { showOld.value }
    val islandDetector = bool("island-detector", true)
    val maxCluster = int("max-cluster", 4, 1..9) { islandDetector.value }
    val minDistance = int("min-distance", 0, 0..100000) { islandDetector.value }
    val islandColor = color("island-color", Color(0x90FFD700.toInt(), true)) { islandDetector.value }
    val islandAlert = bool("island-alert", true) { islandDetector.value }
    val islandSound = bool("island-sound", true) { islandDetector.value }
    val islandWaypoints = bool("island-waypoints", false) { islandDetector.value }

    private val new = HashSet<Long>()
    private val old = HashSet<Long>()

    /** Every chunk [loaded] looked at, so "not new" can mean old where the palette check applies. */
    private val checked = HashSet<Long>()
    val islands = HashSet<Long>()
    private val alertedIslands = HashSet<Long>()
    private var level: ClientLevel? = null
    private val huntMap by lazy { Modules.get<HuntMap>() }

    init {
        on<AfterReceivePacketEvent> { e ->
            val lvl = mc.level ?: return@on
            if (lvl !== level) {
                new.clear()
                old.clear()
                checked.clear()
                islands.clear()
                alertedIslands.clear()
                level = lvl
            }
            when (val p = e.packet) {
                is ClientboundBlockUpdatePacket -> update(lvl, p.pos, p.blockState)
                is ClientboundSectionBlocksUpdatePacket -> p.runUpdates { pos, state -> update(lvl, pos, state) }
                is ClientboundLevelChunkWithLightPacket -> loaded(lvl, p.x(), p.z())
                else -> {}
            }
        }
        on<Render3DEvent> { draw() }
    }

    override fun onDisable() {
        new.clear()
        old.clear()
        checked.clear()
        islands.clear()
        alertedIslands.clear()
        level = null
    }

    /** Liquid spreading for the first time: a flowing fluid with a still one beside or above it. */
    private fun update(lvl: ClientLevel, pos: BlockPos, state: BlockState) {
        val fluid = state.fluidState
        if (fluid.isEmpty || fluid.isSource) return
        val key = ChunkPos.pack(pos)
        if (key in old || key in new) return
        for (dir in SPREAD) {
            if (lvl.getBlockState(pos.relative(dir)).fluidState.isSource) {
                markNew(lvl, key)
                return
            }
        }
    }

    /**
     * Checks newly arriving chunks: first with the palette check for freshly generated chunks (when [palette] is on),
     * then for already-settled flowing fluid (old chunks).
     */
    private fun loaded(lvl: ClientLevel, cx: Int, cz: Int) {
        val key = ChunkPos.pack(cx, cz)
        if (key in new || key in old) return
        checked += key
        // What it was when you first loaded it, if Hunt Map recorded that: a chunk you generated stays new.
        when (huntMap.savedState(cx, cz)) {
            MapRegion.NEW -> return markNew(lvl, key)
            MapRegion.OLD -> return markOld(lvl, key)
        }
        val chunk = lvl.getChunk(cx, cz)
        val dim = lvl.dimension()
        if (palette.value) {
            var firstAppearsNew = false
            var isNewChunk = false
            var loops = 0
            var newSectionCount = 0
            for (section in chunk.sections) {
                if (dim == Level.END) {
                    var bIdx = 0
                    section.biomes.forEachInPalette { holder ->
                        if (bIdx == 0 && holder.`is`(Biomes.PLAINS)) isNewChunk = true
                        bIdx++
                    }
                }
                if (section.hasOnlyAir()) continue
                var isNewSection = 0
                var paletteLen = 0
                var entry0: Block? = null
                var entry1: Block? = null
                var entry2: Block? = null
                section.states.forEachInPalette { st ->
                    when (paletteLen) {
                        0 -> entry0 = st.block
                        1 -> entry1 = st.block
                        2 -> entry2 = st.block
                    }
                    paletteLen++
                }
                if (paletteLen > 16) {
                    var used = 0
                    section.states.count { _, _ -> used++ }
                    if (used > 1 && used < paletteLen) isNewSection = 2
                }
                if (loops == 0 && entry0 === Blocks.AIR && dim != Level.END) firstAppearsNew = true
                if (dim != Level.NETHER && dim != Level.END) {
                    if (entry0 === Blocks.AIR) isNewSection++
                    if (entry1 in OVERWORLD_SECOND) isNewSection++
                    if (entry2 in OVERWORLD_THIRD) isNewSection++
                }
                if (isNewSection >= 2) newSectionCount++
                loops++
            }
            if (loops > 0 && dim != Level.NETHER && dim != Level.END && newSectionCount * 100 >= loops * 51) isNewChunk = true
            if (firstAppearsNew) isNewChunk = true
            if (isNewChunk) {
                markNew(lvl, key)
                return
            }
        }
        for (section in chunk.sections) {
            if (section.hasOnlyAir() || !section.states.maybeHas { !it.fluidState.isEmpty && !it.fluidState.isSource }) continue
            for (x in 0..15) for (y in 0..15) for (z in 0..15) {
                val f = section.getFluidState(x, y, z)
                if (!f.isEmpty && !f.isSource) {
                    markOld(lvl, key)
                    return
                }
            }
        }
    }

    /**
     * For Elytra Autopilot's trail mode: the chunk existed before you loaded it. In the overworld with [palette] on, any
     * checked chunk the palette didn't flag as new counts (it came off the server's disk); elsewhere only settled flowing
     * fluid proves it.
     */
    fun isOld(key: Long): Boolean =
        key in old || palette.value && level?.dimension() == Level.OVERWORLD && key in checked && key !in new

    /** Whether the chunk has been looked at at all (loaded, or seen spreading fluid). */
    fun isKnown(key: Long): Boolean = key in checked || key in new || key in old

    private fun markNew(lvl: ClientLevel, key: Long) {
        new += key
        huntMap.recordState(key, MapRegion.NEW)
        if (islandDetector.value) {
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            for (dx in -1..1) for (dz in -1..1) {
                if (dx != 0 || dz != 0) {
                    val nKey = ChunkPos.pack(cx + dx, cz + dz)
                    if (nKey in old) checkIslands(lvl, cx + dx, cz + dz)
                }
            }
        }
    }

    private fun markOld(lvl: ClientLevel, key: Long) {
        old += key
        huntMap.recordState(key, MapRegion.OLD)
        if (islandDetector.value) {
            checkIslands(lvl, ChunkPos.getX(key), ChunkPos.getZ(key))
        }
    }

    /**
     * Checks if the old chunk at [cx], [cz] belongs to an isolated old chunk pocket (<= [maxCluster] connected chunks)
     * surrounded by newly generated chunks or ungenerated void.
     */
    private fun checkIslands(lvl: ClientLevel, cx: Int, cz: Int) {
        if (!islandDetector.value) return
        val bx = cx shl 4
        val bz = cz shl 4
        if (hypot(bx.toDouble(), bz.toDouble()) < minDistance.value.toDouble()) return
        val startKey = ChunkPos.pack(cx, cz)
        if (startKey !in old) return

        val cluster = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        val visited = HashSet<Long>()
        queue.add(startKey)
        visited.add(startKey)
        var tooLarge = false

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            cluster.add(curr)
            if (cluster.size > maxCluster.value) {
                tooLarge = true
                break
            }
            val curX = ChunkPos.getX(curr)
            val curZ = ChunkPos.getZ(curr)
            for (dx in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val nKey = ChunkPos.pack(curX + dx, curZ + dz)
                    if (nKey in old && visited.add(nKey)) {
                        queue.add(nKey)
                    }
                }
            }
        }

        if (tooLarge) {
            islands.removeAll(visited)
            return
        }

        // Isolated island: verify that the old cluster is enclosed by new chunks on at least 3 cardinal directions
        var hasNorthNew = false
        var hasSouthNew = false
        var hasEastNew = false
        var hasWestNew = false

        for (k in cluster) {
            val kx = ChunkPos.getX(k)
            val kz = ChunkPos.getZ(k)
            if (ChunkPos.pack(kx, kz - 1) in new) hasNorthNew = true
            if (ChunkPos.pack(kx, kz + 1) in new) hasSouthNew = true
            if (ChunkPos.pack(kx + 1, kz) in new) hasEastNew = true
            if (ChunkPos.pack(kx - 1, kz) in new) hasWestNew = true
        }

        val cardinalSides = (if (hasNorthNew) 1 else 0) + (if (hasSouthNew) 1 else 0) + (if (hasEastNew) 1 else 0) + (if (hasWestNew) 1 else 0)

        if (cardinalSides >= 3) {
            islands.addAll(cluster)
            val rep = cluster.minOrNull() ?: startKey
            if (alertedIslands.add(rep)) {
                val ix = (ChunkPos.getX(rep) shl 4) + 8
                val iz = (ChunkPos.getZ(rep) shl 4) + 8
                if (islandAlert.value) {
                    moduleMessage(name.trim(), "§eFound isolated old chunk island at X: §f$ix§e, Z: §f$iz§e (cluster: §f${cluster.size}§e)!")
                }
                EventBus.post(FindEvent("island", Vec3(ix + 0.5, height.value.toDouble(), iz + 0.5)))
                if (islandSound.value) {
                    mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.ARROW_HIT_PLAYER, 1f))
                }
                if (islandWaypoints.value) {
                    val server = mc.currentServer?.ip ?: "localhost"
                    val dim = Waypoints.dimension(lvl.dimension().identifier().path)
                    Waypoints.list += WayPoint("Island ($ix, $iz)", ix, height.value, iz, server, dim, true)
                    Waypoints.save()
                }
            }
        }
    }

    private fun draw() {
        val p = mc.player ?: return
        val pcx = p.blockX shr 4
        val pcz = p.blockZ shr 4
        val r = range.value
        val y = height.value.toDouble()
        fun square(key: Long, fill: Int, lineAlpha: Float) {
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            if (abs(cx - pcx) > r || abs(cz - pcz) > r) return
            val box = AABB((cx shl 4).toDouble(), y, (cz shl 4).toDouble(), (cx shl 4) + 16.0, y, (cz shl 4) + 16.0)
            if (mode.value == Mode.FLAT) World3D.fill(box, fill)
            World3D.outline(box, PlaceBoxes.withAlpha(fill, lineAlpha))
        }
        if (showOld.value) {
            val c = oldColor.value.rgb
            for (k in old) square(k, c, 0.5f)
        }
        val c = newColor.value.rgb
        for (k in new) square(k, c, 1f)
        if (islandDetector.value && islands.isNotEmpty()) {
            val ic = islandColor.value.rgb
            for (k in islands) {
                val cx = ChunkPos.getX(k)
                val cz = ChunkPos.getZ(k)
                if (abs(cx - pcx) > r || abs(cz - pcz) > r) continue
                val x0 = (cx shl 4).toDouble()
                val z0 = (cz shl 4).toDouble()
                val box = AABB(x0, y, z0, x0 + 16.0, y + 2.0, z0 + 16.0)
                World3D.fill(box, PlaceBoxes.withAlpha(ic, 0.45f))
                World3D.outline(box, PlaceBoxes.withAlpha(ic, 1.0f))
                World3D.line(Vec3(x0 + 8.0, y, z0 + 8.0), Vec3(x0 + 8.0, y + 48.0, z0 + 8.0), PlaceBoxes.withAlpha(ic, 1.0f))
            }
        }
    }

    private companion object {
        /** Where the still fluid feeding a flowing one can be: beside it, or above it. */
        val SPREAD = arrayOf(Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.UP)

        val OVERWORLD_SECOND = hashSetOf(Blocks.WATER, Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.SNOW_BLOCK)
        val OVERWORLD_THIRD = hashSetOf(Blocks.SNOW_BLOCK, Blocks.DIRT, Blocks.POWDER_SNOW)
    }
}
