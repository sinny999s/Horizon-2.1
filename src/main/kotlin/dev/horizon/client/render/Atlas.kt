package dev.horizon.client.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 2D layer's small pictures, [WebText]'s strings and [VectorIcon]'s and [Icon]'s icons, each rasterised exactly as
 * before, on shared texture pages instead of a texture apiece, so [Canvas] can batch the text and icons of a whole HUD
 * or screen into a draw per page. A picture keeps its pixels: drawn at the same place it samples the same texels, and a
 * [GAP] of transparent pixels round every slot stands in for the clamp a texture of its own had at its edges.
 *
 * Slots are packed in shelves. A full page starts the next one; past [MAX_PAGES] the page used longest ago is wiped and
 * its strings go back to being rasterised on demand ([Slot.valid]), never one drawn this frame, whose earlier draws
 * still read it. Everything here runs on the render thread.
 */
internal object Atlas {
    const val SIZE = 2048
    private const val GAP = 2
    private const val MAX_PAGES = 3

    class Page(val texture: GpuTexture, val view: GpuTextureView) {
        var generation = 0
        var x = 0
        var y = 0
        var shelf = 0
        var usedFrame = -1L
    }

    /** A picture's place: [x], [y] (its top left, inside the gap) on [page], [w] by [h]. */
    class Slot(val page: Page, private val generation: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
        /** Still on the page (it wasn't wiped since). */
        fun valid() = page.generation == generation

        val u0 get() = x / SIZE.toFloat()
        val v0 get() = y / SIZE.toFloat()
        val u1 get() = (x + w) / SIZE.toFloat()
        val v1 get() = (y + h) / SIZE.toFloat()

        /** Drawn this frame: its page mustn't be wiped before the frame is out. */
        fun use() {
            page.usedFrame = frame
        }
    }

    private val pages = ArrayList<Page>()
    private var current = -1
    private var upload: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder())

    /** Counts frames ([Canvas.endFrame]), for not wiping a page drawn from this frame. */
    var frame = 0L
        private set

    fun endFrame() {
        frame++
    }

    /** Off, every picture gets a texture of its own again, as before the atlas (for comparing the two: [testAtlas]). */
    private var enabled = true

    /** For ShotBot: the atlas "on" or "off" (off also sends every picture back to a texture of its own). */
    @JvmStatic
    fun testAtlas(on: String) {
        enabled = on != "off"
        if (!enabled) for (p in pages) p.generation++
    }

    /**
     * [w] by [h] straight-alpha pixels ([argb], row by row from the top) on a page, or null when they don't fit (too
     * big, or every page full and drawn from this frame): the caller then gives the picture a texture of its own.
     */
    fun put(argb: IntArray, w: Int, h: Int): Slot? {
        val sw = w + 2 * GAP
        val sh = h + 2 * GAP
        if (!enabled || sw > SIZE || sh > SIZE) return null
        val page = room(sw, sh) ?: return null
        if (page.x + sw > SIZE) {
            page.x = 0
            page.y += page.shelf
            page.shelf = 0
        }
        val sx = page.x
        val sy = page.y
        page.x += sw
        page.shelf = maxOf(page.shelf, sh)
        write(page, argb, w, h, sx, sy, sw, sh)
        return Slot(page, page.generation, sx + GAP, sy + GAP, w, h)
    }

    /** A page with room for an [sw] by [sh] slot: the current one, a new one, or one wiped for it. */
    private fun room(sw: Int, sh: Int): Page? {
        fun fits(p: Page) = (p.x + sw <= SIZE && p.y + maxOf(p.shelf, sh) <= SIZE) || p.y + p.shelf + sh <= SIZE
        if (current >= 0 && fits(pages[current])) return pages[current]
        if (pages.size < MAX_PAGES) {
            val device = RenderSystem.getDevice()
            val texture = device.createTexture({ "horizon atlas ${pages.size}" }, 5, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1)
            pages += Page(texture, device.createTextureView(texture))
            current = pages.size - 1
            return pages[current]
        }
        // The page drawn from longest ago, if not this frame: wiped, its strings rasterised again when next drawn.
        val oldest = pages.withIndex().filter { it.value.usedFrame < frame }.minByOrNull { it.value.usedFrame } ?: return null
        val p = oldest.value
        p.generation++
        p.x = 0
        p.y = 0
        p.shelf = 0
        current = oldest.index
        return p
    }

    /** The slot ([sw] by [sh] at [sx], [sy]): the gap transparent, the string's pixels inside it. */
    private fun write(page: Page, argb: IntArray, w: Int, h: Int, sx: Int, sy: Int, sw: Int, sh: Int) {
        val bytes = sw * sh * 4
        if (upload.capacity() < bytes) upload = ByteBuffer.allocateDirect(maxOf(bytes, upload.capacity() * 2)).order(ByteOrder.nativeOrder())
        val b = upload
        b.clear()
        for (yy in 0 until sh) {
            val row = yy - GAP
            for (xx in 0 until sw) {
                val col = xx - GAP
                val c = if (row in 0 until h && col in 0 until w) argb[row * w + col] else 0
                val a = c ushr 24
                if (a == 0) {
                    b.putInt(0)
                } else {
                    // RGBA8 in memory: red, green, blue, alpha (as Textures.upload lays out an AWT pixel).
                    b.put((c shr 16 and 255).toByte()).put((c shr 8 and 255).toByte()).put((c and 255).toByte()).put(a.toByte())
                }
            }
        }
        b.flip()
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(page.texture, b, 0, 0, sx, sy, sw, sh)
    }
}
