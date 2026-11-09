package dev.horizon.client.huntmap

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import net.minecraft.world.level.material.MapColor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * The hunt map's recorded regions for the world you're in, kept per world like Breadcrumbs (server or
 * singleplayer world, then dimension) under `horizon/huntmap/<key>/`. A region loads from disk the first time it's
 * needed and is saved and dropped from memory once many are loaded; a small summary of each stays for zoomed-out
 * views. All changes happen on the main thread; files are written on a background thread from copies taken here.
 */
object MapStore {
    /** A region at a glance: a 64x64 terrain picture and its chunk states and ground checks. */
    class Summary(val terrain: IntArray, val states: ByteArray, val diff: ByteArray, val version: Int)

    var key: String? = null
        private set
    private var dir: Path? = null
    private val regions = HashMap<Long, MapRegion>()

    /** Every region with a file or recorded this session: how far the map reaches. */
    val known = HashSet<Long>()
    private val summaries = HashMap<Long, Summary>()
    private val loading = HashSet<Long>()
    private val saving = ConcurrentHashMap.newKeySet<Long>()
    private val arrived = ConcurrentLinkedQueue<Pair<String?, MapRegion>>()
    private val failed = ConcurrentLinkedQueue<Pair<String?, Long>>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "HorizonClient hunt map files").apply { isDaemon = true } }

    /** Packed map colour to ARGB (0 for no colour). */
    val palette: IntArray by lazy { IntArray(256) { if (it < 4) 0 else MapColor.getColorFromPackedId(it) } }

    /** Switches to another world's map (null for none), saving the one you leave. */
    fun use(newKey: String?) {
        if (newKey == key) return
        saveAll()
        regions.clear()
        known.clear()
        summaries.clear()
        built.clear()
        loading.clear()
        key = newKey
        dir = newKey?.let { Storage.dir("huntmap").resolve(it).also { d -> Files.createDirectories(d) } }
        val d = dir ?: return
        try {
            Files.list(d).use { s ->
                s.forEach { p -> MapRegion.parseName(p.fileName.toString())?.let { (x, z) -> known += MapRegion.key(x, z) } }
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't list {}", d, e)
        }
    }

    /** This world's folder, for the map's other files. */
    fun folder(): Path? = dir

    /** The region, loaded from disk now if it isn't in memory; a new one when [create]. */
    fun region(rx: Int, rz: Int, create: Boolean): MapRegion? {
        val k = MapRegion.key(rx, rz)
        regions[k]?.let {
            it.lastUsed = System.currentTimeMillis()
            return it
        }
        val d = dir ?: return null
        var r: MapRegion? = null
        if (k in known) {
            val f = d.resolve(MapRegion.fileName(rx, rz))
            if (Files.exists(f)) {
                r = try {
                    MapRegion.load(f, rx, rz)
                } catch (e: Exception) {
                    HorizonClient.LOG.warn("Couldn't read {}", f, e)
                    null
                }
            }
        }
        if (r == null) {
            if (!create) return null
            r = MapRegion(rx, rz)
        }
        r.lastUsed = System.currentTimeMillis()
        regions[k] = r
        known += k
        loading -= k
        return r
    }

    /** The region if it's in memory; otherwise starts loading it in the background and returns null. */
    fun peek(rx: Int, rz: Int): MapRegion? {
        val k = MapRegion.key(rx, rz)
        regions[k]?.let {
            it.lastUsed = System.currentTimeMillis()
            return it
        }
        if (k !in known || k in loading) return null
        val d = dir ?: return null
        val forKey = key
        loading += k
        io.execute {
            try {
                arrived += forKey to MapRegion.load(d.resolve(MapRegion.fileName(rx, rz)), rx, rz)
            } catch (e: Exception) {
                failed += forKey to k
            }
        }
        return null
    }

    /** Takes in the regions loaded in the background. */
    fun drain() {
        while (true) {
            val (k, r) = arrived.poll() ?: break
            if (k != key) continue
            val rk = MapRegion.key(r.rx, r.rz)
            loading -= rk
            if (rk !in regions) {
                r.lastUsed = System.currentTimeMillis()
                regions[rk] = r
            }
        }
        while (true) {
            val (k, s) = summed.poll() ?: break
            if (k != key) continue
            loading -= s.first
            if (s.first !in regions) summaries[s.first] = s.second
        }
        while (true) {
            val (k, rk) = failed.poll() ?: break
            if (k != key) continue
            loading -= rk
            if (rk !in regions) known -= rk
        }
    }

    fun changed(r: MapRegion) {
        r.version++
        r.dirty = true
        known += MapRegion.key(r.rx, r.rz)
    }

    /** Summary rebuilds the map screen may still do this frame (each takes about a millisecond). */
    var rebuildBudget = 0

    /** The region's summary; rebuilt from its data first when it changed (and [rebuild]). */
    fun summary(rx: Int, rz: Int, rebuild: Boolean): Summary? {
        val k = MapRegion.key(rx, rz)
        val s = summaries[k]
        val r = regions[k]
        if (rebuild && r != null && s?.version != r.version) {
            return Summary(MapRegion.overview(r.colors, palette), r.states.copyOf(), r.diff.copyOf(), r.version).also { summaries[k] = it }
        }
        return s
    }

    /**
     * The summary for drawing: rebuilt (within [rebuildBudget], at most twice a second) when the region in memory
     * changed, or read from its file in the background when the region isn't in memory.
     */
    fun summaryFor(rx: Int, rz: Int): Summary? {
        val k = MapRegion.key(rx, rz)
        val s = summaries[k]
        val r = regions[k]
        if (r != null) {
            val now = System.currentTimeMillis()
            if (s?.version != r.version && rebuildBudget > 0 && (s == null || now - (built[k] ?: 0L) > 500)) {
                rebuildBudget--
                built[k] = now
                return summary(rx, rz, true)
            }
            return s
        }
        if (s != null || k !in known || k in loading) return s
        val d = dir ?: return null
        val forKey = key
        loading += k
        io.execute {
            try {
                val loaded = MapRegion.load(d.resolve(MapRegion.fileName(rx, rz)), rx, rz)
                summed += forKey to (k to Summary(MapRegion.overview(loaded.colors, palette), loaded.states, loaded.diff, -1))
            } catch (e: Exception) {
                failed += forKey to k
            }
        }
        return null
    }

    private val built = HashMap<Long, Long>()
    private val summed = ConcurrentLinkedQueue<Pair<String?, Pair<Long, Summary>>>()

    fun saveAll() {
        for (r in regions.values) if (r.dirty) save(r)
    }

    private fun save(r: MapRegion) {
        val d = dir ?: return
        val k = MapRegion.key(r.rx, r.rz)
        val f = d.resolve(MapRegion.fileName(r.rx, r.rz))
        val s = r.states.copyOf()
        val df = r.diff.copyOf()
        val c = r.colors.copyOf()
        r.dirty = false
        saving += k
        io.execute {
            try {
                MapRegion.save(f, s, df, c)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Couldn't save {}", f, e)
            } finally {
                saving -= k
            }
        }
    }

    /** Saves, then drops from memory, the least recently used regions beyond [MAX_LOADED] (keeping their summaries). */
    fun trim() {
        if (regions.size <= MAX_LOADED) return
        for (r in regions.values.sortedBy { it.lastUsed }.take(regions.size - MAX_LOADED)) {
            val k = MapRegion.key(r.rx, r.rz)
            if (r.dirty) {
                save(r)
                continue
            }
            if (k in saving) continue
            summary(r.rx, r.rz, true)
            regions.remove(k)
        }
    }

    private const val MAX_LOADED = 160
}
