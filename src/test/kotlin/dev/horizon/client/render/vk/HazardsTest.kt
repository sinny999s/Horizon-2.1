package dev.horizon.client.render.vk

import dev.horizon.client.render.vk.Hazards.Companion.ACCESS_WRITE
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_COLOR_OUTPUT
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_SHADERS
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_TRANSFER
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_VERTEX_INPUT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The barriers GPU Sync asks for between Minecraft's GPU commands (uploads, passes), as in recorded frames. */
class HazardsTest {
    private val h = Hazards()
    private var op = 0

    private val UBO = 0x10L
    private val VERTS = 0x20L
    private val MAIN = 0x30L
    private val DEPTH = 0x31L
    private val BLOOM = 0x40L

    /** One command; returns the barrier entries it needed as (src, srcAccess, dst) triples, or "full". */
    private fun cmd(block: Hazards.() -> Unit): List<Any> {
        h.begin(++op)
        h.block()
        val out: List<Any> = if (h.full) listOf("full") else (0 until h.barrierSize).map { Triple(h.barrierSrc(it), h.barrierSrcAccess(it), h.barrierDst(it)) }
        h.commit()
        return out
    }

    private fun upload(buffer: Long, start: Long = 0, end: Long = 64) = cmd { buffer(buffer, start, end, STAGE_TRANSFER, true) }

    private fun pass(color: Long, vararg uniforms: Long) = cmd {
        image(color, STAGE_COLOR_OUTPUT, true)
        for (u in uniforms) buffer(u, 0, 64, STAGE_SHADERS, false)
    }

    @Test
    fun uploadsToDifferentBuffersNeedNothing() {
        assertEquals(emptyList(), upload(UBO))
        assertEquals(emptyList(), upload(VERTS))
        assertEquals(emptyList(), upload(UBO, 64, 128))
    }

    @Test
    fun aPassReadingAnUploadWaitsOnlyForTheCopy() {
        upload(UBO)
        assertEquals(listOf(Triple(STAGE_TRANSFER, ACCESS_WRITE, STAGE_SHADERS)), pass(MAIN, UBO))
    }

    @Test
    fun rewritingAUniformAPassReadWaitsForItsShaders() {
        pass(MAIN, UBO)
        // Write after read: only an execution dependency (nothing of the read to make visible).
        assertEquals(listOf(Triple(STAGE_SHADERS, 0L, STAGE_TRANSFER)), upload(UBO))
    }

    @Test
    fun passesIntoTheSameTargetOnlyOrderTheirColorWrites() {
        pass(MAIN)
        assertEquals(listOf(Triple(STAGE_COLOR_OUTPUT, ACCESS_WRITE, STAGE_COLOR_OUTPUT)), pass(MAIN))
    }

    @Test
    fun samplingARenderedImageWaitsForItsColorWrites() {
        pass(BLOOM)
        val needed = cmd {
            image(MAIN, STAGE_COLOR_OUTPUT, true)
            image(BLOOM, STAGE_SHADERS, false)
        }
        assertEquals(listOf(Triple(STAGE_COLOR_OUTPUT, ACCESS_WRITE, STAGE_SHADERS)), needed)
    }

    @Test
    fun differentSourcesStaySeparateEntries() {
        pass(MAIN)
        upload(UBO)
        val needed = pass(MAIN, UBO)
        // Merged, the color writes would hold back vertex and fragment shading of the next pass.
        assertEquals(2, needed.size)
        assertTrue(Triple(STAGE_COLOR_OUTPUT, ACCESS_WRITE, STAGE_COLOR_OUTPUT) in needed)
        assertTrue(Triple(STAGE_TRANSFER, ACCESS_WRITE, STAGE_SHADERS) in needed)
    }

    @Test
    fun aSyncedWriteIsOnlyVisibleToTheStagesItWasSyncedFor() {
        upload(VERTS)
        assertEquals(1, cmd { buffer(VERTS, 0, 64, STAGE_SHADERS, false) }.size)
        // The same buffer read by later shaders: already visible.
        assertEquals(emptyList(), cmd { buffer(VERTS, 0, 64, STAGE_SHADERS, false) })
        // Read as vertices: a stage the barrier didn't cover.
        assertEquals(listOf(Triple(STAGE_TRANSFER, ACCESS_WRITE, STAGE_VERTEX_INPUT)), cmd { buffer(VERTS, 0, 64, STAGE_VERTEX_INPUT, false) })
    }

    @Test
    fun readsNeverWaitForReads() {
        pass(MAIN, UBO)
        assertEquals(emptyList(), cmd { buffer(UBO, 0, 64, STAGE_SHADERS, false) })
    }

    @Test
    fun aCommandNeverWaitsForItself() {
        // A pass loads, blends into and stores its own attachments.
        assertEquals(emptyList(), cmd {
            image(MAIN, STAGE_COLOR_OUTPUT, true)
            image(DEPTH, Hazards.STAGE_DEPTH, true)
            image(MAIN, STAGE_COLOR_OUTPUT, true)
        })
    }

    @Test
    fun unknownWorkMakesTheNextCommandWaitForEverything() {
        pass(MAIN)
        h.markUnknown()
        assertEquals(listOf<Any>("full"), upload(VERTS))
        // The full barrier synced the pass: a new pass into MAIN needs nothing.
        assertEquals(emptyList(), pass(MAIN))
        assertFalse(h.full)
    }

    @Test
    fun anUnidentifiedAccessIsFull() {
        assertEquals(listOf<Any>("full"), cmd { forceFull() })
    }

    @Test
    fun endOfCommandBufferClearsEverything() {
        pass(MAIN, UBO)
        assertTrue(h.dirty)
        h.clear()
        assertFalse(h.dirty)
        assertEquals(emptyList(), upload(UBO))
    }
}
