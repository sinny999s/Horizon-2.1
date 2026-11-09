package dev.horizon.client.huntmap

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * One 32x32-chunk region of the hunt map (512x512 blocks), the unit it is saved and drawn in. It holds
 * what was recorded there: each chunk's state from New Chunks, whether its ground matched the seed's terrain, and one
 * map colour per block, packed the way vanilla maps pack them (colour id * 4 + brightness, 0 for nothing). Saved as
 * `r.<rx>.<rz>.bin`: a small header, then the three arrays deflated.
 */
class MapRegion(val rx: Int, val rz: Int) {
    val states = ByteArray(CHUNKS)
    val diff = ByteArray(CHUNKS)
    val colors = ByteArray(BLOCKS)

    /** Bumped on every change; pictures and textures made from the region rebuild when it moves. */
    var version = 0
    var dirty = false
    var lastUsed = 0L

    fun state(lcx: Int, lcz: Int) = states[lcz * 32 + lcx].toInt()

    /** Raises a chunk's state: old or new replaces "seen", and the first old/new it got stays. */
    fun setState(lcx: Int, lcz: Int, s: Int): Boolean {
        val i = lcz * 32 + lcx
        val cur = states[i].toInt()
        if (cur == s || cur >= OLD || s < cur) return false
        states[i] = s.toByte()
        return true
    }

    companion object {
        const val CHUNKS = 32 * 32
        const val BLOCKS = 512 * 512

        /** Chunk states: never loaded, loaded but not classified, old, new. */
        const val UNKNOWN = 0
        const val SEEN = 1
        const val OLD = 2
        const val NEW = 3

        /** Ground checks against the seed: not checked, matched, changed. */
        const val MATCH = 1
        const val CHANGED = 2

        private const val MAGIC = 0x484D4150 // "HMAP"
        private const val VERSION = 1

        fun key(rx: Int, rz: Int) = (rx.toLong() shl 32) or (rz.toLong() and 0xFFFFFFFFL)
        fun rx(key: Long) = (key shr 32).toInt()
        fun rz(key: Long) = key.toInt()

        fun fileName(rx: Int, rz: Int) = "r.$rx.$rz.bin"

        /** The region of a file name, or null for anything else. */
        fun parseName(name: String): Pair<Int, Int>? {
            val parts = name.split('.')
            if (parts.size != 4 || parts[0] != "r" || parts[3] != "bin") return null
            val x = parts[1].toIntOrNull() ?: return null
            val z = parts[2].toIntOrNull() ?: return null
            return x to z
        }

        /** Writes copies of a region's arrays (taken on the main thread) through a temporary file. */
        fun save(file: Path, states: ByteArray, diff: ByteArray, colors: ByteArray) {
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            val deflater = Deflater(6)
            try {
                DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp), 1 shl 16)).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    val z = DeflaterOutputStream(out, deflater, 1 shl 16)
                    z.write(states)
                    z.write(diff)
                    z.write(colors)
                    z.finish()
                }
            } finally {
                deflater.end()
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fun load(file: Path, rx: Int, rz: Int): MapRegion {
            val r = MapRegion(rx, rz)
            val inflater = Inflater()
            try {
                DataInputStream(BufferedInputStream(Files.newInputStream(file), 1 shl 16)).use { raw ->
                    if (raw.readInt() != MAGIC) throw IOException("not a hunt map region: $file")
                    val v = raw.readInt()
                    if (v != VERSION) throw IOException("hunt map region version $v: $file")
                    val z = DataInputStream(InflaterInputStream(raw, inflater, 1 shl 16))
                    z.readFully(r.states)
                    z.readFully(r.diff)
                    z.readFully(r.colors)
                }
            } finally {
                inflater.end()
            }
            return r
        }

        /**
         * A 64x64 picture of the region (8x8 blocks a pixel, averaged over the blocks that have a colour), for zoomed-out
         * views. [palette] turns a packed colour into ARGB.
         */
        fun overview(colors: ByteArray, palette: IntArray): IntArray {
            val out = IntArray(64 * 64)
            for (oy in 0 until 64) for (ox in 0 until 64) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in 0 until 8) {
                    val row = (oy * 8 + dy) * 512 + ox * 8
                    for (dx in 0 until 8) {
                        val c = colors[row + dx].toInt() and 255
                        if (c < 4) continue
                        val argb = palette[c]
                        r += argb shr 16 and 255
                        g += argb shr 8 and 255
                        b += argb and 255
                        n++
                    }
                }
                if (n == 0) continue
                // Partly recorded cells fade in, so the edge of what you've seen stays soft.
                val a = 255 * n / 64
                out[oy * 64 + ox] = (maxOf(a, 96) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            return out
        }
    }
}
