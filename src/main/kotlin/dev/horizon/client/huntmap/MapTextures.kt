package dev.horizon.client.huntmap

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.HorizonClient
import net.minecraft.client.renderer.texture.DynamicTexture
import java.util.concurrent.ConcurrentLinkedQueue

/** ARGB pixel arrays as GPU textures for the hunt map (main thread only). */
internal object MapTex {
    fun create(px: IntArray, size: Int): DynamicTexture {
        val img = NativeImage(size, size, false)
        fill(img, px, size)
        return DynamicTexture({ "horizon-huntmap" }, img)
    }

    fun update(t: DynamicTexture, px: IntArray, size: Int) {
        val img = t.pixels ?: return
        fill(img, px, size)
        t.upload()
    }

    private fun fill(img: NativeImage, px: IntArray, size: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val c = px[y * size + x]
            img.setPixelABGR(x, y, (c and 0xFF00FF00.toInt()) or (c shr 16 and 255) or ((c and 255) shl 16))
        }
    }
}

/**
 * The parchment tiles on the GPU, made on [SeedTerrain]'s threads and uploaded a few a frame. A tile that
 * isn't ready yet is drawn from a coarser one that is.
 */
internal object SeedTiles {
    private class Tile(val key: String) {
        var texture: DynamicTexture? = null

        @Volatile
        var requested = false

        @Volatile
        var wanted = 0L
        var used = 0L
    }

    private val tiles = HashMap<String, Tile>()
    private val done = ConcurrentLinkedQueue<Pair<Tile, IntArray>>()

    private fun get(g: SeedTerrain.Gen, level: Int, tx: Int, tz: Int, request: Boolean): GpuTextureView? {
        val key = "${g.id}/$level/$tx/$tz"
        val t = tiles[key] ?: if (request) Tile(key).also { tiles[key] = it } else return null
        val now = System.currentTimeMillis()
        t.used = now
        t.texture?.let { return it.textureView }
        if (!request) return null
        t.wanted = now
        if (!t.requested) {
            t.requested = true
            SeedTerrain.submit {
                // Scrolled or zoomed away before its turn came: drop it.
                if (System.currentTimeMillis() - t.wanted > 2500) {
                    t.requested = false
                    return@submit
                }
                try {
                    done += t to SeedTerrain.renderTile(g, level, tx, tz)
                } catch (e: Throwable) {
                    HorizonClient.LOG.warn("Hunt map: couldn't draw tile {}", key, e)
                }
            }
        }
        return null
    }

    /** Draws one tile at [level] over the GUI square [x], [y], [size]: [draw] gets the texture and its uv rectangle. */
    fun draw(g: SeedTerrain.Gen, level: Int, tx: Int, tz: Int, draw: (GpuTextureView, Float, Float, Float, Float) -> Unit) {
        get(g, level, tx, tz, true)?.let { return draw(it, 0f, 0f, 1f, 1f) }
        for (up in 1..4) {
            val l = level + up
            if (l > MAX_LEVEL) break
            val k = 1 shl up
            val ptx = Math.floorDiv(tx, k)
            val ptz = Math.floorDiv(tz, k)
            val t = get(g, l, ptx, ptz, false) ?: continue
            val u = (tx - ptx * k).toFloat() / k
            val v = (tz - ptz * k).toFloat() / k
            return draw(t, u, v, u + 1f / k, v + 1f / k)
        }
    }

    /** Uploads finished tiles, at most [max]. */
    fun upload(max: Int) {
        repeat(max) {
            val (t, px) = done.poll() ?: return
            if (tiles[t.key] !== t) return@repeat
            t.texture?.close()
            t.texture = MapTex.create(px, SeedTerrain.TILE)
        }
    }

    /** Frees the least recently used tiles beyond [MAX_TILES]. */
    fun trim() {
        if (tiles.size <= MAX_TILES) return
        for (t in tiles.values.sortedBy { it.used }.take(tiles.size - MAX_TILES)) {
            t.texture?.close()
            tiles.remove(t.key)
        }
    }

    fun clear() {
        for (t in tiles.values) t.texture?.close()
        tiles.clear()
        done.clear()
    }

    const val MIN_LEVEL = -2
    const val MAX_LEVEL = 7
    private const val MAX_TILES = 360
}

/**
 * The recorded regions on the GPU. Each region has a full-detail texture (512 square, only when zoomed
 * in), a 64x64 overview from its summary, and a 32x32 overlay of chunk states, each rebuilt when the data changes.
 */
internal object RegionTextures {
    private class Entry {
        var full: DynamicTexture? = null
        var fullVersion = Int.MIN_VALUE
        var fullBuilt = 0L
        var fullUsed = 0L
        var overview: DynamicTexture? = null
        var overviewOf: MapStore.Summary? = null
        var overlay: DynamicTexture? = null
        var overlayOf: MapStore.Summary? = null
        var overlayStyle = 0
        var used = 0L
    }

    private val entries = HashMap<Long, Entry>()
    private var uploads = 0

    fun frame() {
        uploads = 6
    }

    private fun entry(k: Long) = entries.getOrPut(k) { Entry() }.also { it.used = System.currentTimeMillis() }

    /** The full-detail texture; rebuilt at most once a second while the region keeps changing. */
    fun full(r: MapRegion): GpuTextureView? {
        val e = entry(MapRegion.key(r.rx, r.rz))
        val now = System.currentTimeMillis()
        e.fullUsed = now
        if (e.fullVersion != r.version && uploads > 0 && (e.full == null || now - e.fullBuilt > 1000)) {
            uploads--
            val px = IntArray(MapRegion.BLOCKS)
            val pal = MapStore.palette
            for (i in px.indices) px[i] = pal[r.colors[i].toInt() and 255]
            val t = e.full
            if (t == null) e.full = MapTex.create(px, 512) else MapTex.update(t, px, 512)
            e.fullVersion = r.version
            e.fullBuilt = now
        }
        return e.full?.textureView
    }

    fun overview(k: Long, s: MapStore.Summary): GpuTextureView {
        val e = entry(k)
        val t = e.overview
        if (t == null) {
            e.overview = MapTex.create(s.terrain, 64)
        } else if (e.overviewOf !== s) {
            MapTex.update(t, s.terrain, 64)
        }
        e.overviewOf = s
        return e.overview!!.textureView
    }

    /** The chunk-state overlay; [style] says which states show and in what colours, [pixel] colours one chunk. */
    fun overlay(k: Long, s: MapStore.Summary, style: Int, pixel: (Int, Int) -> Int): GpuTextureView {
        val e = entry(k)
        val t = e.overlay
        if (t == null || e.overlayOf !== s || e.overlayStyle != style) {
            val px = IntArray(MapRegion.CHUNKS) { pixel(s.states[it].toInt(), s.diff[it].toInt()) }
            if (t == null) e.overlay = MapTex.create(px, 32) else MapTex.update(t, px, 32)
            e.overlayOf = s
            e.overlayStyle = style
        }
        return e.overlay!!.textureView
    }

    /** Frees full textures beyond a few dozen, and everything not drawn for a while. */
    fun trim() {
        val now = System.currentTimeMillis()
        val fulls = entries.values.filter { it.full != null }
        if (fulls.size > MAX_FULL) {
            for (e in fulls.sortedBy { it.fullUsed }.take(fulls.size - MAX_FULL)) {
                e.full?.close()
                e.full = null
                e.fullVersion = Int.MIN_VALUE
            }
        }
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.used < 30_000) continue
            e.full?.close()
            e.overview?.close()
            e.overlay?.close()
            it.remove()
        }
    }

    fun clear() {
        for (e in entries.values) {
            e.full?.close()
            e.overview?.close()
            e.overlay?.close()
        }
        entries.clear()
    }

    private const val MAX_FULL = 40
}
