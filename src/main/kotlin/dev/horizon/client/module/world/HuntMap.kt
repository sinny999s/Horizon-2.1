package dev.horizon.client.module.world

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.compat.Baritone
import dev.horizon.client.config.Storage
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.FindEvent
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.huntmap.ChunkColors
import dev.horizon.client.huntmap.MapData
import dev.horizon.client.huntmap.MapRegion
import dev.horizon.client.huntmap.MapScreen
import dev.horizon.client.huntmap.MapStore
import dev.horizon.client.huntmap.SeedTerrain
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.esp.Breadcrumbs
import dev.horizon.client.module.esp.LogoutSpots
import dev.horizon.client.module.rage.ElytraAutopilot
import dev.horizon.client.setting.Bind
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A full-screen map for base hunting ([openKey], M by default). While on, it records every chunk you
 * load (top-down colours, and New Chunks' new/old verdict, so New Chunks remembers across relogs and 6b6t worker moves)
 * per server and dimension. Where the world's seed is known ([seeds]: 6b6t and 2b2t are filled in; singleplayer uses
 * its own), the map shows the untouched land as parchment everywhere, and flags recorded overworld chunks whose ground
 * doesn't match it ([changes]). It marks Base Finder's bases and clues, Stash Finder's stashes, Portal ESP's portals, New Chunks'
 * islands, waypoints, logout spots, your Breadcrumbs trail and the 6b6t worker borders you crossed. Right-click a spot
 * to fly there (Baritone's elytra in the nether, Elytra Autopilot elsewhere), walk there, or mark it.
 */
class HuntMap : Module("hunt-map", Categories.WORLD) {
    val openKey = bind("open-key", Bind(16))
    val record = bool("record", true)
    val predict = bool("predict", true)
    val seeds = string("seeds", DEFAULT_SEEDS) { predict.value }
    val changes = bool("changes", true) { predict.value }
    val threads = int("threads", 2, 1..4) { predict.value }

    // The map's layer switches, flipped on the map itself.
    val showTerrain = bool("show-terrain", true) { false }
    val showSeed = bool("show-seed", true) { false }
    val showNew = bool("show-new", true) { false }
    val showOld = bool("show-old", true) { false }
    val showChanged = bool("show-changed", true) { false }
    val showTrail = bool("show-trail", true) { false }
    val showFinds = bool("show-finds", true) { false }
    val showWaypoints = bool("show-waypoints", true) { false }
    val showLogouts = bool("show-logouts", true) { false }
    val showWorkers = bool("show-workers", true) { false }

    /** Where the map looks, kept between openings. */
    class View(var x: Double = 0.0, var z: Double = 0.0, var zoom: Double = 1.0, var follow: Boolean = true)

    val view = View()

    /** Everything to mark, gathered when the map opens and every few seconds while it's open. */
    class Marks(
        val finds: List<MapData.Find>,
        val trail: List<Vec3?>,
        val crossings: List<MapData.Crossing>,
    )

    private val pending = LinkedHashSet<Long>()
    private val touched = HashMap<Long, Long>()

    private class Checked(val key: String?, val gen: String, val nether: Boolean, val cx: Int, val cz: Int, val result: IntArray)

    private val checked = ConcurrentLinkedQueue<Checked>()
    private var statsGen: String? = null
    var columnsChecked = 0
        private set
    var columnsMatched = 0
        private set

    /** The 6b6t worker you're on ("worker-1, node-13, us-east"), from the server's message. */
    var worker: String? = null
        private set
    private var workerName: String? = null

    /** The dimension you were in when the last worker message came. */
    private var workerDim: ResourceKey<Level>? = null
    private var lastPos: Vec3? = null
    private var crossings: MutableList<MapData.Crossing>? = null
    private var crossingsKey: String? = null
    private val ownFinds = HashSet<String>()
    private var ticks = 0
    private var seedText: String? = null
    private var seedTable: Map<String, Pair<Long, Long?>> = emptyMap()

    init {
        on<TickEvent> { tick() }
        on<AfterReceivePacketEvent> { e ->
            when (val p = e.packet) {
                is ClientboundLevelChunkWithLightPacket -> if (record.value) pending += ChunkPos.pack(p.x(), p.z())
                is ClientboundBlockUpdatePacket -> touch(p.pos)
                is ClientboundSectionBlocksUpdatePacket -> p.runUpdates { pos, _ -> touch(pos) }
                is ClientboundSystemChatPacket -> workerMessage(p.content().string)
                else -> {}
            }
        }
        on<InputEvent> { e ->
            if (e.action != InputEvent.Action.Press || mc.gui.screen() != null) return@on
            val b = openKey.value
            if (b.key == -1 || b.key != e.key || b.mouse != (e.device == InputEvent.Device.Mouse)) return@on
            // Swallow the press, or it carries on into the map just opened, which closes on the same key.
            e.cancel()
            open()
        }
        on<FindEvent> { e -> if (e.kind == "portal" || e.kind == "island") ownFind(e.kind, e.pos) }
    }

    override fun onDisable() {
        MapStore.use(null)
        pending.clear()
        touched.clear()
    }

    fun open() {
        if (mc.level == null) return
        ensure()
        mc.gui.setScreen(MapScreen(this))
    }

    // ---- recording ----

    /** The map's name for the world you're in, the way Breadcrumbs names its trails. */
    fun worldKey(): String? {
        val level = mc.level ?: return null
        val server = mc.singleplayerServer?.worldData?.levelName?.let { "singleplayer-$it" } ?: mc.currentServer?.ip ?: "unknown"
        return (server + "_" + level.dimension().identifier().path).replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun ensure() {
        val k = worldKey()
        if (k != MapStore.key) {
            MapStore.use(k)
            pending.clear()
            touched.clear()
            view.follow = true
        }
    }

    private fun tick() {
        val lvl = mc.level
        if (lvl == null) {
            MapStore.use(null)
            return
        }
        ensure()
        MapStore.drain()
        applyChecks()
        SeedTerrain.threads(threads.value)
        mc.player?.let { lastPos = it.position() }
        var n = 0
        val it = pending.iterator()
        while (it.hasNext() && n < CHUNKS_PER_TICK) {
            val k = it.next()
            it.remove()
            recordChunk(lvl, ChunkPos.getX(k), ChunkPos.getZ(k), true)
            n++
        }
        ticks++
        if (ticks % 20 == 0 && touched.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val again = touched.filterValues { now - it > 2000 }.keys
            for (k in again) {
                touched.remove(k)
                recordChunk(lvl, ChunkPos.getX(k), ChunkPos.getZ(k), false)
            }
        }
        if (ticks % 600 == 0) MapStore.saveAll()
        if (ticks % 100 == 0) MapStore.trim()
    }

    private fun touch(pos: BlockPos) {
        if (!record.value) return
        val k = ChunkPos.pack(pos)
        if (k !in pending) touched[k] = System.currentTimeMillis()
    }

    private fun recordChunk(lvl: ClientLevel, cx: Int, cz: Int, check: Boolean) {
        val chunk = lvl.chunkSource.getChunkNow(cx, cz) ?: return
        val region = MapStore.region(cx shr 5, cz shr 5, true) ?: return
        val ground = ChunkColors.record(lvl, chunk, region, lvl.dimensionType().hasCeiling())
        region.setState(cx and 31, cz and 31, MapRegion.SEEN)
        MapStore.changed(region)
        if (!check || !changes.value || region.diff[(cz and 31) * 32 + (cx and 31)].toInt() != 0) return
        val g = gen() ?: return
        val key = MapStore.key
        SeedTerrain.submit {
            try {
                checked += Checked(key, g.id, g.nether, cx, cz, SeedTerrain.check(g, cx, cz, ground))
            } catch (e: Throwable) {
                HorizonClient.LOG.warn("Hunt map: couldn't check chunk {} {}", cx, cz, e)
            }
        }
    }

    private fun applyChecks() {
        while (true) {
            val c = checked.poll() ?: break
            if (c.key != MapStore.key) continue
            val (n, d) = c.result[0] to c.result[1]
            if (c.gen != statsGen) {
                statsGen = c.gen
                columnsChecked = 0
                columnsMatched = 0
            }
            columnsChecked += n
            columnsMatched += n - d
            // In the nether, extra caves and older terrain move the floor too often to call a chunk changed
            // (a fifth of untouched chunks would be); there the check only tells whether the seed is right.
            if (n < 8 || c.nether) continue
            val region = MapStore.region(c.cx shr 5, c.cz shr 5, true) ?: continue
            region.diff[(c.cz and 31) * 32 + (c.cx and 31)] =
                (if (d * 16 >= SeedTerrain.CHANGED_COLUMNS * n) MapRegion.CHANGED else MapRegion.MATCH).toByte()
            MapStore.changed(region)
        }
    }

    // ---- New Chunks' memory ----

    /** What the map recorded New Chunks making of this chunk before (0 when nothing, or when the map is off). */
    fun savedState(cx: Int, cz: Int): Int {
        if (!enabled || mc.level == null) return MapRegion.UNKNOWN
        ensure()
        return MapStore.region(cx shr 5, cz shr 5, false)?.state(cx and 31, cz and 31) ?: MapRegion.UNKNOWN
    }

    fun recordState(chunk: Long, state: Int) {
        if (!enabled || mc.level == null) return
        ensure()
        val cx = ChunkPos.getX(chunk)
        val cz = ChunkPos.getZ(chunk)
        val r = MapStore.region(cx shr 5, cz shr 5, true) ?: return
        if (r.setState(cx and 31, cz and 31, state)) MapStore.changed(r)
    }

    // ---- seeds ----

    /** The world's seed for the parchment layer: singleplayer's own, else the [seeds] entry for the server. */
    fun seed(): Long? {
        val lvl = mc.level ?: return null
        val nether = lvl.dimension() == Level.NETHER
        if (!nether && lvl.dimension() != Level.OVERWORLD) return null
        val sp = mc.singleplayerServer
        if (sp != null) {
            val sl = sp.getLevel(lvl.dimension()) ?: return null
            val generator = sl.chunkSource.generator as? NoiseBasedChunkGenerator ?: return null
            if (!generator.generatorSettings().`is`(if (nether) NoiseGeneratorSettings.NETHER else NoiseGeneratorSettings.OVERWORLD)) return null
            return sl.seed
        }
        val ip = mc.currentServer?.ip ?: return null
        if (seeds.value != seedText) {
            seedText = seeds.value
            seedTable = MapData.parseSeeds(seeds.value)
        }
        return MapData.seedFor(seedTable, ip, nether)
    }

    /** The seed's generator once it's set up (null while it's being set up, or with no seed). */
    fun gen(): SeedTerrain.Gen? {
        if (!predict.value) return null
        val s = seed() ?: return null
        return SeedTerrain.ready(s, mc.level?.dimension() == Level.NETHER)
    }

    // ---- 6b6t workers ----

    private fun workerMessage(text: String) {
        val w = MapData.parseWorker(text) ?: return
        val prev = workerName
        val prevDim = workerDim
        workerName = w[0]
        workerDim = mc.level?.dimension()
        worker = w.joinToString(", ")
        val pos = lastPos ?: return
        // 6b6t runs each dimension on its own workers, so a portal moves you to another: that's no border.
        if (prev == null || prev == w[0] || workerDim != prevDim || !enabled) return
        ensure()
        val c = MapData.Crossing(prev, w[0], pos.x.toInt(), pos.z.toInt())
        crossingsNow().add(c)
        val dir = MapStore.folder() ?: return
        append(dir.resolve("workers.txt"), "${now()} | ${c.from} | ${c.to} | ${c.x} ${c.z}\n")
    }

    private fun crossingsNow(): MutableList<MapData.Crossing> {
        val k = MapStore.key
        val have = crossings
        if (have != null && crossingsKey == k) return have
        val f = MapStore.folder()?.resolve("workers.txt")
        val list = if (f != null && Files.exists(f)) MapData.parseCrossings(read(f)).toMutableList() else ArrayList()
        crossings = list
        crossingsKey = k
        return list
    }

    // ---- marks ----

    private fun ownFind(kind: String, pos: Vec3) {
        if (mc.level == null) return
        ensure()
        val b = BlockPos.containing(pos)
        val id = "${MapStore.key} $kind ${b.x shr 4} ${b.z shr 4}"
        if (!ownFinds.add(id)) return
        val f = MapStore.folder()?.resolve("finds.txt") ?: return
        val existing = if (Files.exists(f)) MapData.parseKinds(read(f)) { it } else emptyList()
        if (existing.any { it.label == kind && it.x shr 4 == b.x shr 4 && it.z shr 4 == b.z shr 4 }) return
        append(f, "${now()} | $kind | X ${b.x} Y ${b.y} Z ${b.z} | \n")
    }

    /** Everything the map marks in the world you're in. */
    fun marks(): Marks {
        val lvl = mc.level ?: return Marks(emptyList(), emptyList(), emptyList())
        ensure()
        val dimPath = lvl.dimension().identifier().path
        val key = MapStore.key
        val finds = ArrayList<MapData.Find>()
        val bf = Storage.dir("basefinder")
        if (key != null) {
            finds += MapData.parseBases(read(bf.resolve("$key.txt")))
            finds += MapData.parseKinds(read(bf.resolve("${key}_clues.txt"))) { if (it == "Pearl stasis") "stasis" else "clue" }
            MapStore.folder()?.let { finds += MapData.parseKinds(read(it.resolve("finds.txt"))) { k -> k } }
        }
        val server = mc.currentServer?.ip ?: "localhost"
        finds += MapData.parseStashes(read(Storage.dir("stashfinder").resolve("stashes.txt")), server, dimPath)
        val dim = Waypoints.dimension(dimPath)
        for (w in Waypoints.list) {
            if (w.server == server && w.dimension == dim && w.visible) finds += MapData.Find("waypoint", w.x, w.y, w.z, w.id, "Waypoint")
        }
        for ((name, pos, time) in Modules.get<LogoutSpots>().spotsForMap()) {
            val mins = (System.currentTimeMillis() - time) / 60_000
            finds += MapData.Find("logout", pos.x.toInt(), pos.y.toInt(), pos.z.toInt(), name, "Logged out ${mins}m ago")
        }
        return Marks(finds, Modules.get<Breadcrumbs>().trailForMap(), MapData.withoutPortalTrips(crossingsNow(), finds))
    }

    // ---- actions ----

    fun flyTo(x: Int, z: Int) {
        val lvl = mc.level ?: return
        if (lvl.dimension() == Level.NETHER) {
            if (Baritone.command("goal $x $z") && Baritone.command("elytra")) say("Baritone is flying to $x $z.")
            else say("Under the nether roof this needs Baritone, which isn't installed.")
            return
        }
        val ap = Modules.get<ElytraAutopilot>()
        ap.disable()
        ap.target.value = ElytraAutopilot.Target.COORDS
        ap.coords.value = "$x $z"
        ap.enable()
    }

    fun walkTo(x: Int, z: Int) {
        if (Baritone.command("goto $x $z")) say("Baritone is going to $x $z.") else say("Walking there needs Baritone, which isn't installed.")
    }

    fun addWaypoint(x: Int, z: Int) {
        val lvl = mc.level ?: return
        val y = if (lvl.chunkSource.getChunkNow(x shr 4, z shr 4) != null) lvl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        else mc.player?.blockY ?: 64
        val server = mc.currentServer?.ip ?: "localhost"
        val dim = Waypoints.dimension(lvl.dimension().identifier().path)
        var n = 1
        while (Waypoints.list.any { it.id == "map-$n" && it.server == server }) n++
        Waypoints.list += WayPoint("map-$n", x, y, z, server, dim, true)
        Waypoints.save()
        say("Added waypoint map-$n at $x $y $z.")
    }

    fun copy(x: Int, z: Int) {
        mc.keyboardHandler.setClipboard("$x $z")
        say("Copied $x $z.")
    }

    private fun say(text: String) = moduleMessage(name.trim(), text)

    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    private fun read(f: java.nio.file.Path): List<String> = try {
        if (Files.exists(f)) Files.readAllLines(f) else emptyList()
    } catch (e: Exception) {
        HorizonClient.LOG.warn("Couldn't read {}", f, e)
        emptyList()
    }

    private fun append(f: java.nio.file.Path, line: String) {
        try {
            Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't write {}", f, e)
        }
    }

    private companion object {
        /** 6b6t uses 2b2t's seed for both dimensions (checked against cached 6b6t terrain); 2b2t's nether has its own. */
        const val DEFAULT_SEEDS = "6b6t.org=-4172144997902289642 2b2t.org=-4172144997902289642/146008555100680"

        /** Chunks recorded per tick at most, so joining a world doesn't stall a frame. */
        const val CHUNKS_PER_TICK = 48
    }
}
