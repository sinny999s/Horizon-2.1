package dev.horizon.client.huntmap

import dev.horizon.client.HorizonClient
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext
import net.minecraft.world.level.material.Fluids
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * What the world looks like before anyone touched it, worked out from its seed with the game's own
 * generator (land shape, water and biomes; no trees, ores or structures). It draws the hunt map's parchment layer, and
 * checks recorded ground against it: where they differ, someone dug, built or flattened (or a structure stands there, or
 * the land is from an older version). Everything runs on low-priority background threads, newest request first.
 */
object SeedTerrain {
    /** One seed's generator for the overworld or the nether. */
    class Gen(val seed: Long, val nether: Boolean) {
        val id = "$seed:" + if (nether) "nether" else "overworld"
        val generator: NoiseBasedChunkGenerator
        val random: RandomState
        val biomes: MultiNoiseBiomeSource
        val sampler: Climate.Sampler
        val heights: LevelHeightAccessor
        val minY: Int
        val maxY: Int
        internal val tints = ConcurrentHashMap<Holder<Biome>, Int>()

        init {
            val settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(if (nether) NoiseGeneratorSettings.NETHER else NoiseGeneratorSettings.OVERWORLD)
            biomes = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(if (nether) MultiNoiseBiomeSourceParameterLists.NETHER else MultiNoiseBiomeSourceParameterLists.OVERWORLD))
            generator = NoiseBasedChunkGenerator(biomes, settings)
            random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings.value())
            sampler = random.createClimateSampler(SamplerContext.EMPTY_UNCACHED)
            val ns = settings.value().noiseSettings()
            minY = ns.minY()
            maxY = ns.minY() + ns.height()
            heights = LevelHeightAccessor.create(minY, ns.height())
        }
    }

    /** The vanilla worldgen data, built once (a second or two) on a background thread. */
    private val lookup: HolderLookup.Provider by lazy { VanillaRegistries.createWorldLookup() }

    private val gens = ConcurrentHashMap<String, Gen>()
    private val starting = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var failure: String? = null
        private set

    /** The generator for a seed once it's set up; until then null, and it's being set up. */
    fun ready(seed: Long, nether: Boolean): Gen? {
        val id = "$seed:" + if (nether) "nether" else "overworld"
        gens[id]?.let { return it }
        if (starting.add(id)) {
            pool.execute {
                try {
                    gens[id] = Gen(seed, nether)
                } catch (e: Throwable) {
                    failure = e.toString()
                    HorizonClient.LOG.warn("Hunt map: couldn't set up the generator for seed {}", seed, e)
                }
            }
        }
        return null
    }

    // ---- threads ----

    /** Newest first: what's on screen now matters more than what was a second ago. */
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, Lifo()) { r ->
        Thread(r, "HorizonClient hunt map terrain").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.apply { allowCoreThreadTimeOut(true) }

    fun threads(n: Int) {
        if (pool.corePoolSize == n) return
        if (n > pool.maximumPoolSize) {
            pool.maximumPoolSize = n
            pool.corePoolSize = n
        } else {
            pool.corePoolSize = n
            pool.maximumPoolSize = n
        }
    }

    fun submit(task: Runnable) = pool.execute(task)

    // ---- columns ----

    /** A column as generated: its ground (or nether floor), the water above it, and whether that's lava. */
    class Column(val ground: Int, val water: Int, val lava: Boolean)

    fun column(g: Gen, x: Int, z: Int): Column {
        val col = g.generator.getBaseColumn(x, z, g.heights, g.random)
        if (g.nether) {
            // The same walk as the recorded map: down through the roof to open space, then to its floor.
            var y = min(ChunkColors.ROOF_START, g.maxY - 1)
            var s = col.getBlock(y)
            while (y > g.minY && !s.isAir) s = col.getBlock(--y)
            while (y > g.minY && s.isAir) s = col.getBlock(--y)
            return Column(y, 0, s.fluidState.`is`(Fluids.LAVA))
        }
        var y = g.maxY - 1
        var s = col.getBlock(y)
        while (y > g.minY && s.isAir) s = col.getBlock(--y)
        val top = y
        val lava = s.fluidState.`is`(Fluids.LAVA)
        while (y > g.minY && !s.fluidState.isEmpty) s = col.getBlock(--y)
        return Column(y, top - y, lava && top != y)
    }

    /**
     * How many of a chunk's 16 sample columns have recorded ground ([real], as [ChunkColors.record] returns it), and how
     * many of those differ from the seed's by more than 3 blocks.
     */
    fun check(g: Gen, cx: Int, cz: Int, real: IntArray): IntArray {
        var n = 0
        var d = 0
        for (sz in 0..3) for (sx in 0..3) {
            val lx = 2 + sx * 4
            val lz = 2 + sz * 4
            val r = real[lx + lz * 16]
            if (r == Int.MIN_VALUE) continue
            n++
            if (abs(column(g, cx * 16 + lx, cz * 16 + lz).ground - r) > 3) d++
        }
        return intArrayOf(n, d)
    }

    /** An overworld chunk with this many of 16 columns off counts as changed (in a test world no untouched chunk did). */
    const val CHANGED_COLUMNS = 9

    // ---- parchment tiles ----

    /** A tile's size in pixels. */
    const val TILE = 128

    /** Blocks per tile pixel at a detail level (level 0 is one block a pixel, each level up halves the detail). */
    fun scale(level: Int): Double = if (level >= 0) (1 shl level).toDouble() else 1.0 / (1 shl -level)

    /**
     * One parchment tile (ARGB, [TILE] square) covering [TILE] * [scale] blocks from tile corner [tx], [tz]. Heights
     * come from a 19x19 grid of columns (one tile edge's worth plus a border), biomes from a 33x33 grid; both are
     * interpolated per pixel, then hill-shaded with contour lines and an inked coastline.
     */
    fun renderTile(g: Gen, level: Int, tx: Int, tz: Int): IntArray {
        val bpp = scale(level)
        val span = TILE * bpp
        val x0 = tx * span
        val z0 = tz * span
        val step = 8 * bpp
        val n = 19
        val gh = FloatArray(n * n)
        val wd = FloatArray(n * n)
        val lv = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val c = column(g, floor(x0 + (i - 1) * step).toInt(), floor(z0 + (j - 1) * step).toInt())
            gh[j * n + i] = c.ground.toFloat()
            wd[j * n + i] = c.water.toFloat()
            lv[j * n + i] = if (c.lava) 1f else 0f
        }
        val m = 33
        val tr = FloatArray(m * m)
        val tg = FloatArray(m * m)
        val tb = FloatArray(m * m)
        for (j in 0 until m) for (i in 0 until m) {
            val near = (j / 2 + 1) * n + (i / 2 + 1)
            val y = if (g.nether) gh[near].toInt() + 1 else maxOf(gh[near].toInt() + wd[near].toInt(), 63)
            val t = tint(g, floor(x0 + i * step / 2).toInt(), y, floor(z0 + j * step / 2).toInt())
            tr[j * m + i] = (t shr 16 and 255).toFloat()
            tg[j * m + i] = (t shr 8 and 255).toFloat()
            tb[j * m + i] = (t and 255).toFloat()
        }
        val f = TILE + 2
        val hf = FloatArray(f * f)
        val wf = FloatArray(f * f)
        val lf = FloatArray(f * f)
        for (pz in -1..TILE) for (px in -1..TILE) {
            val u = (px + 0.5f) / 8f + 1f
            val v = (pz + 0.5f) / 8f + 1f
            val k = (pz + 1) * f + px + 1
            hf[k] = bilinear(gh, n, u, v)
            wf[k] = bilinear(wd, n, u, v)
            lf[k] = bilinear(lv, n, u, v)
        }
        val interval = if (g.nether) (if (bpp <= 1) 8 else if (bpp <= 4) 16 else 32) else (if (bpp <= 1) 16 else if (bpp <= 4) 32 else if (bpp <= 16) 64 else 128)
        val paper = if (g.nether) NETHER_PAPER else PAPER
        val ink = if (g.nether) NETHER_INK else INK
        val out = IntArray(TILE * TILE)
        for (pz in 0 until TILE) for (px in 0 until TILE) {
            val k = (pz + 1) * f + px + 1
            val h = hf[k]
            val water = wf[k] > 0.5f
            val lava = lf[k] > 0.5f
            val wet = water || lava
            var r: Float
            var gg: Float
            var b: Float
            if (wet) {
                val base = if (lava) (if (g.nether) NETHER_LAVA else LAVA) else WATER
                val deep = if (lava) base else WATER_DEEP
                val t = if (lava) 0f else min(1f, wf[k] / 24f)
                r = lerp(base shr 16 and 255, deep shr 16 and 255, t)
                gg = lerp(base shr 8 and 255, deep shr 8 and 255, t)
                b = lerp(base and 255, deep and 255, t)
            } else {
                val u = (px + 0.5f) / 4f
                val v = (pz + 0.5f) / 4f
                r = lerp(paper shr 16 and 255, bilinear(tr, m, u, v), 0.32f)
                gg = lerp(paper shr 8 and 255, bilinear(tg, m, u, v), 0.32f)
                b = lerp(paper and 255, bilinear(tb, m, u, v), 0.32f)
                val gx = (hf[k + 1] - hf[k - 1]) / (2f * bpp.toFloat())
                val gz = (hf[k + f] - hf[k - f]) / (2f * bpp.toFloat())
                val shade = (1f - (gx + gz) * 0.25f).coerceIn(0.72f, 1.22f)
                r *= shade
                gg *= shade
                b *= shade
                val band = floor(h / interval)
                if (band != floor(hf[k + 1] / interval) || band != floor(hf[k + f] / interval)) {
                    r = lerp(r, ink shr 16 and 255, 0.3f)
                    gg = lerp(gg, ink shr 8 and 255, 0.3f)
                    b = lerp(b, ink and 255, 0.3f)
                }
                // Coastline: land next to water.
                if (wf[k + 1] > 0.5f || wf[k - 1] > 0.5f || wf[k + f] > 0.5f || wf[k - f] > 0.5f ||
                    lf[k + 1] > 0.5f || lf[k - 1] > 0.5f || lf[k + f] > 0.5f || lf[k - f] > 0.5f) {
                    r = lerp(r, ink shr 16 and 255, 0.65f)
                    gg = lerp(gg, ink shr 8 and 255, 0.65f)
                    b = lerp(b, ink and 255, 0.65f)
                }
            }
            // Paper grain, fixed to the world so it doesn't swim between tiles.
            val wx = floor(x0 + (px + 0.5) * bpp).toInt()
            val wz = floor(z0 + (pz + 0.5) * bpp).toInt()
            val grain = ((hash(wx, wz) and 7) - 3) * 1.5f
            out[pz * TILE + px] = (255 shl 24) or (clamp(r + grain) shl 16) or (clamp(gg + grain) shl 8) or clamp(b + grain)
        }
        return out
    }

    private fun tint(g: Gen, x: Int, y: Int, z: Int): Int {
        val holder = g.biomes.getNoiseBiome(g.sampler.sample(x shr 2, y shr 2, z shr 2))
        return g.tints.computeIfAbsent(holder) { h -> tintOf(h.unwrapKey().map { it.identifier().path }.orElse("")) }
    }

    /** A biome's colour on the parchment, by its name. */
    fun tintOf(name: String): Int = when {
        name == "nether_wastes" -> rgb(196, 120, 100)
        name == "crimson_forest" -> rgb(182, 72, 70)
        name == "warped_forest" -> rgb(84, 150, 136)
        name == "soul_sand_valley" -> rgb(150, 124, 100)
        name == "basalt_deltas" -> rgb(118, 116, 122)
        "badlands" in name -> rgb(212, 150, 100)
        "desert" in name || "beach" in name -> rgb(226, 204, 142)
        "cherry" in name -> rgb(218, 168, 178)
        "mushroom" in name -> rgb(172, 140, 166)
        "stony_peaks" in name -> rgb(176, 170, 160)
        "snow" in name || "frozen" in name || "ice" in name || "grove" in name || "peaks" in name -> rgb(246, 245, 238)
        "jungle" in name -> rgb(118, 160, 80)
        "swamp" in name || "mangrove" in name -> rgb(124, 146, 100)
        "forest" in name || "taiga" in name || "birch" in name -> rgb(140, 166, 104)
        "savanna" in name -> rgb(198, 186, 116)
        "plains" in name || "meadow" in name -> rgb(184, 192, 126)
        "windswept" in name || "stony" in name || "dripstone" in name -> rgb(166, 166, 152)
        "ocean" in name || "river" in name -> rgb(170, 196, 190)
        else -> rgb(182, 176, 140)
    }

    private val PAPER = rgb(236, 221, 185)
    private val INK = rgb(112, 86, 54)
    private val WATER = rgb(192, 210, 202)
    private val WATER_DEEP = rgb(146, 174, 178)
    private val LAVA = rgb(232, 166, 104)
    private val NETHER_PAPER = rgb(226, 198, 174)
    private val NETHER_INK = rgb(104, 62, 48)
    private val NETHER_LAVA = rgb(236, 162, 100)

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private fun lerp(a: Int, b: Float, t: Float) = a + (b - a) * t
    private fun lerp(a: Float, b: Int, t: Float) = a + (b - a) * t
    private fun lerp(a: Int, b: Int, t: Float) = a + (b - a) * t

    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)

    private fun bilinear(a: FloatArray, n: Int, u: Float, v: Float): Float {
        val i = floor(u).toInt().coerceIn(0, n - 2)
        val j = floor(v).toInt().coerceIn(0, n - 2)
        val fx = (u - i).coerceIn(0f, 1f)
        val fz = (v - j).coerceIn(0f, 1f)
        val top = a[j * n + i] + (a[j * n + i + 1] - a[j * n + i]) * fx
        val bottom = a[(j + 1) * n + i] + (a[(j + 1) * n + i + 1] - a[(j + 1) * n + i]) * fx
        return top + (bottom - top) * fz
    }

    private fun hash(x: Int, z: Int): Int {
        var h = x * 73856093 xor z * 19349663
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        return h xor (h ushr 15)
    }
}
