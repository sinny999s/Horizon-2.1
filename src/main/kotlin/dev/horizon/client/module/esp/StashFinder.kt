package dev.horizon.client.module.esp

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Storage
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FindEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.World3D
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.vehicle.minecart.MinecartChest
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.DispenserBlockEntity
import net.minecraft.world.level.block.entity.DropperBlockEntity
import net.minecraft.world.level.block.entity.EnderChestBlockEntity
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Trouser-Streak inspired dedicated high-density storage and stash scanner.
 * Scans loaded chunks for dense clusters of shulker boxes, chests, barrels, and hoppers,
 * alerting with coordinates, audio cue, automatic waypoint, file logging, and 3D visual boxes with tracers.
 * Each stash chunk is announced once per server and dimension: chunks already in the log file (and, without it, those
 * announced earlier this session) aren't announced again when you come back, so no repeat sounds, lines or waypoints.
 */
class StashFinder : Module("stash-finder", Categories.ESP) {
    val shulkersThreshold = int("shulkers", 4, 1..50)
    val chestsThreshold = int("chests", 15, 2..100)
    val hoppersThreshold = int("hoppers", 6, 1..50)
    val totalThreshold = int("total", 20, 2..200)
    val notify = bool("notify", true)
    val sound = bool("sound", true)
    val waypoints = bool("waypoints", false)
    val logToFile = bool("log-to-file", true)
    val tracers = bool("tracers", true)
    val labels = bool("labels", true)
    val range = int("range", 500, 16..2000)
    val color = color("color", Color(0x64FF2878, true))
    val tracerColor = color("tracer-color", Color(0xFFFF2878.toInt(), true)) { tracers.value }
    val clear = action("clear") { reset() }

    class StashChunk(
        val cx: Int,
        val cz: Int,
        var shulkers: Int,
        var chests: Int,
        var hoppers: Int,
        var total: Int,
        var minPos: BlockPos,
        var maxPos: BlockPos,
    ) {
        val center: Vec3 get() = Vec3(
            (minPos.x + maxPos.x) / 2.0 + 0.5,
            (minPos.y + maxPos.y) / 2.0 + 0.5,
            (minPos.z + maxPos.z) / 2.0 + 0.5,
        )
        val box: AABB get() = AABB(
            minPos.x.toDouble(), minPos.y.toDouble(), minPos.z.toDouble(),
            maxPos.x.toDouble() + 1.0, maxPos.y.toDouble() + 1.0, maxPos.z.toDouble() + 1.0
        )
    }

    private val stashes = HashMap<Long, StashChunk>()
    /** Chunks already announced, by "server|dimension"; [alerted] is this world's. */
    private val seen = HashMap<String, HashSet<Long>>()
    private var alerted = HashSet<Long>()
    private var level: ClientLevel? = null
    private var ticks = 0

    init {
        on<TickEvent> { tick() }
        on<Render3DEvent> { draw() }
        on<Render2DEvent> { if (labels.value) labels() }
    }

    override fun onEnable() = reset()

    override fun onDisable() = reset()

    private fun reset() {
        stashes.clear()
        level = null
        ticks = 0
    }

    private fun tick() {
        val lvl = mc.level ?: return
        if (lvl !== level) {
            reset()
            level = lvl
            val server = mc.currentServer?.ip ?: "localhost"
            val dim = lvl.dimension().identifier().path
            alerted = seen.getOrPut("$server|$dim") { logged(server, dim) }
        }
        if (++ticks % 10 != 0) return

        val p = mc.player ?: return
        val entities = lvl.entitiesForRendering()

        class Accumulator(
            var shulkers: Int = 0,
            var chests: Int = 0,
            var hoppers: Int = 0,
            var other: Int = 0,
            var minX: Int = Int.MAX_VALUE,
            var minY: Int = Int.MAX_VALUE,
            var minZ: Int = Int.MAX_VALUE,
            var maxX: Int = Int.MIN_VALUE,
            var maxY: Int = Int.MIN_VALUE,
            var maxZ: Int = Int.MIN_VALUE,
        ) {
            fun include(pos: BlockPos) {
                minX = min(minX, pos.x); minY = min(minY, pos.y); minZ = min(minZ, pos.z)
                maxX = max(maxX, pos.x); maxY = max(maxY, pos.y); maxZ = max(maxZ, pos.z)
            }
        }

        val byChunk = HashMap<Long, Accumulator>()

        val pcx = p.blockX shr 4
        val pcz = p.blockZ shr 4
        val rd = mc.options.renderDistance().get()
        for (dx in -rd..rd) {
            for (dz in -rd..rd) {
                val chunk = lvl.chunkSource.getChunkNow(pcx + dx, pcz + dz) ?: continue
                for (be in chunk.blockEntities.values) {
                    val pos = be.blockPos
                    val key = ChunkPos.pack(pos.x shr 4, pos.z shr 4)
                    when (be) {
                        is ShulkerBoxBlockEntity -> {
                            val acc = byChunk.getOrPut(key) { Accumulator() }
                            acc.shulkers++
                            acc.include(pos)
                        }
                        is ChestBlockEntity, is TrappedChestBlockEntity, is BarrelBlockEntity -> {
                            val acc = byChunk.getOrPut(key) { Accumulator() }
                            acc.chests++
                            acc.include(pos)
                        }
                        is HopperBlockEntity -> {
                            val acc = byChunk.getOrPut(key) { Accumulator() }
                            acc.hoppers++
                            acc.include(pos)
                        }
                        is DispenserBlockEntity, is DropperBlockEntity, is EnderChestBlockEntity -> {
                            val acc = byChunk.getOrPut(key) { Accumulator() }
                            acc.other++
                            acc.include(pos)
                        }
                    }
                }
            }
        }

        for (e in entities) {
            val pos = e.blockPosition()
            val key = ChunkPos.pack(pos.x shr 4, pos.z shr 4)
            when (e) {
                is MinecartChest -> {
                    val acc = byChunk.getOrPut(key) { Accumulator() }
                    acc.chests++
                    acc.include(pos)
                }
                is MinecartHopper -> {
                    val acc = byChunk.getOrPut(key) { Accumulator() }
                    acc.hoppers++
                    acc.include(pos)
                }
            }
        }

        for ((key, acc) in byChunk) {
            val total = acc.shulkers + acc.chests + acc.hoppers + acc.other
            val qualifies = acc.shulkers >= shulkersThreshold.value ||
                acc.chests >= chestsThreshold.value ||
                acc.hoppers >= hoppersThreshold.value ||
                total >= totalThreshold.value

            if (!qualifies) continue

            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            val minPos = BlockPos(acc.minX, acc.minY, acc.minZ)
            val maxPos = BlockPos(acc.maxX, acc.maxY, acc.maxZ)
            val stash = stashes.getOrPut(key) { StashChunk(cx, cz, acc.shulkers, acc.chests, acc.hoppers, total, minPos, maxPos) }
            stash.shulkers = acc.shulkers
            stash.chests = acc.chests
            stash.hoppers = acc.hoppers
            stash.total = total
            stash.minPos = minPos
            stash.maxPos = maxPos

            if (alerted.add(key)) {
                announce(lvl, stash)
            }
        }
    }

    private fun announce(lvl: ClientLevel, stash: StashChunk) {
        val p = mc.player ?: return
        val cx = stash.cx
        val cz = stash.cz
        val midX = cx * 16 + 8
        val midZ = cz * 16 + 8
        val y = stash.minPos.y
        val dist = p.position().distanceTo(stash.center).toInt()
        EventBus.post(FindEvent("stash", stash.center))

        if (sound.value) {
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f))
        }

        if (notify.value) {
            moduleMessage(
                name.trim(),
                "§d[Stash] Found at §f$midX, $y, $midZ§d (${dist}m away): §f${stash.shulkers}§d shulkers, §f${stash.chests}§d chests/barrels, §f${stash.hoppers}§d hoppers (total: §f${stash.total}§d)!"
            )
        }

        val server = mc.currentServer?.ip ?: "localhost"
        val dim = lvl.dimension().identifier().path

        if (logToFile.value) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val line = "$time | $server | $dim | X $midX Y $y Z $midZ | ${stash.shulkers} shulkers, ${stash.chests} chests, ${stash.hoppers} hoppers | total ${stash.total}\n"
            try {
                val dir = Storage.dir("stashfinder")
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("stashes.txt"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Couldn't write stash log", e)
            }
        }

        if (waypoints.value) {
            val n = (Waypoints.list.filter { it.server == server && it.id.startsWith("stash-") }
                .maxOfOrNull { it.id.substringAfter('-').toIntOrNull() ?: 0 } ?: 0) + 1
            Waypoints.list += WayPoint("stash-$n", midX, y, midZ, server, Waypoints.dimension(dim), true)
            Waypoints.save()
        }
    }

    /** The chunks of the stashes in the log file for [server] and [dim] ("time | server | dim | X x Y y Z z | ..."). */
    private fun logged(server: String, dim: String): HashSet<Long> {
        val out = HashSet<Long>()
        val f = Storage.dir("stashfinder").resolve("stashes.txt")
        if (!Files.exists(f)) return out
        try {
            for (line in Files.readAllLines(f)) {
                val parts = line.split(" | ")
                if (parts.size < 4 || parts[1] != server || parts[2] != dim) continue
                val xyz = parts[3].split(' ')
                val x = xyz.getOrNull(1)?.toIntOrNull() ?: continue
                val z = xyz.getOrNull(5)?.toIntOrNull() ?: continue
                out += ChunkPos.pack(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't read stash log", e)
        }
        return out
    }

    private fun draw() {
        if (stashes.isEmpty()) return
        val p = mc.player ?: return
        val maxDistSqr = range.value.toDouble() * range.value.toDouble()
        val c = color.value.rgb
        val outline = PlaceBoxes.withAlpha(c, 1f)
        val tColor = tracerColor.value.rgb

        for (stash in stashes.values) {
            if (p.position().distanceToSqr(stash.center) > maxDistSqr) continue
            World3D.outline(stash.box, outline)
            World3D.fill(stash.box, c)
            if (tracers.value) {
                World3D.line(Esp2.tracerStart(), stash.center, tColor, noCull = true)
            }
        }
    }

    private fun labels() {
        if (stashes.isEmpty()) return
        val p = mc.player ?: return
        val maxDistSqr = range.value.toDouble() * range.value.toDouble()

        for (stash in stashes.values) {
            val distSqr = p.position().distanceToSqr(stash.center)
            if (distSqr > maxDistSqr) continue
            val top = Vec3(stash.center.x, stash.box.maxY + 0.6, stash.center.z)
            val s = World3D.project(top) ?: continue
            val dist = kotlin.math.sqrt(distSqr).toInt()
            Fonts.bold8.drawCentered("Stash (${dist}m)", s[0], s[1] - 4f, 0xFFFF2878.toInt())
            Fonts.medium65.drawCentered("${stash.shulkers} Shulkers, ${stash.chests} Chests (${stash.total} total)", s[0], s[1] + 6f, -1)
        }
    }
}
