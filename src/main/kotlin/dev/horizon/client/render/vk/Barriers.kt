package dev.horizon.client.render.vk

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.util.TextureViewAndSampler
import dev.horizon.client.config.Storage
import dev.horizon.client.render.vk.Hazards.Companion.ACCESS_READ
import dev.horizon.client.render.vk.Hazards.Companion.ACCESS_WRITE
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_ALL_COMMANDS
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_COLOR_OUTPUT
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_DEPTH
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_DRAW_INDIRECT
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_SHADERS
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_TRANSFER
import dev.horizon.client.render.vk.Hazards.Companion.STAGE_VERTEX_INPUT
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRDynamicRendering
import org.lwjgl.vulkan.KHRSynchronization2
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import java.util.Optional
import java.util.OptionalDouble

/**
 * Replaces the full GPU barrier Minecraft's Vulkan backend records after every render pass, clear, upload and copy
 * with the barriers the commands actually need ([Hazards]), so independent GPU work overlaps instead of the GPU
 * draining and restarting dozens of times a frame. Switched by GPU Sync in Options > Horizon > Performance
 * ([Storage.Shared.gpuSync]); fed by the Vulkan mixins.
 *
 * A render pass's reads are only known once it draws, and Vulkan allows no memory barrier inside a pass, so the pass
 * starts (vkCmdBeginRendering) just before its first draw, after a barrier for everything it reads and writes. A
 * later draw that binds something an earlier command wrote ends the pass, places the barrier and resumes it (its
 * attachments loaded). Work this can't see (other mods' own command buffers) gets a full barrier before and after it,
 * and so does every end of a command buffer, so ordering across submits, the swapchain blit and other mods' command
 * buffers stays as Minecraft had it. Turning it on or off takes effect at the next frame.
 */
object Barriers {
    /** Test hook override of the GPU Sync option (null: follow the option). */
    @Volatile private var forced: Boolean? = null

    /** True while this frame is recorded with tracked barriers (Minecraft's own full barriers are then skipped). */
    @JvmField var active = false

    private val hazards = Hazards()
    private var opId = 0

    // The render pass being recorded: its op id, its attachments and whether vkCmdBeginRendering was recorded yet.
    private var passOp = 0
    private var passOpen = false
    private var deferred = false
    private var begun = false
    private var attachments = 0
    private val attachmentImages = LongArray(10)
    private val attachmentStages = LongArray(10)
    private val attachmentLoads = BooleanArray(10)

    // A copy of the pass's VkRenderingInfo (Minecraft's lives on a stack frame that is gone by the first draw).
    private val info = VkRenderingInfo.calloc()
    private val colors = VkRenderingAttachmentInfo.calloc(MAX_COLORS)
    private val depth = VkRenderingAttachmentInfo.calloc()
    private var colorCount = 0
    private var hasDepth = false

    // Bindings of the pass, and what was last checked for each (a pass draws many times with the same bindings).
    private val vertex = arrayOfNulls<GpuBufferSlice>(MAX_VERTEX)
    private val vertexChecked = arrayOfNulls<GpuBufferSlice>(MAX_VERTEX)
    private var index: GpuBuffer? = null
    private var indexChecked: GpuBuffer? = null
    private var indirect: GpuBufferSlice? = null
    private var indirectChecked: GpuBufferSlice? = null
    private val uniformChecked = arrayOfNulls<Any>(MAX_UNIFORMS)
    private var bindingsDirty = false
    private var unknownBinding = false

    // Test hook [dump]: frames left to log, and the command being recorded.
    private var dumpLeft = 0
    private var dumpOp = ""

    // Counters for the test hook [report].
    private var frames = 0
    private var tracked = 0
    private var barriers = 0
    private var fullBarriers = 0
    private var splits = 0

    // --- frame and command buffer boundaries ---

    /** After a submit: the next frame uses the wanted mode. */
    @JvmStatic
    fun frameEnd() {
        if (active) frames++
        if (dumpLeft > 0 && --dumpLeft >= 0) println("GPUSYNC frame end")
        active = forced ?: Storage.shared.gpuSync
        passOpen = false
        if (!active) hazards.clear()
    }

    /** Before Minecraft ends its command buffer (for a submit, a semaphore or another command buffer): sync everything. */
    @JvmStatic
    fun endCommandBuffer(cb: VkCommandBuffer?) {
        if (!active || cb == null || !hazards.dirty) return
        if (dumpLeft > 0) dumpOp = "end of command buffer"
        fullBarrier(cb)
        hazards.clear()
    }

    /** Another command buffer was added to the submit (not Minecraft's upload buffer): the next command waits for it. */
    @JvmStatic
    fun external() {
        if (active) hazards.markUnknown()
        if (active && dumpLeft > 0) println("GPUSYNC external command buffer")
    }

    // --- uploads, copies and clears (outside render passes) ---

    @JvmStatic
    fun writeBuffer(cb: VkCommandBuffer, dst: GpuBufferSlice) {
        if (!active) return
        if (dumpLeft > 0) dumpOp = "writeBuffer " + describe(dst)
        hazards.begin(++opId)
        buffer(dst, STAGE_TRANSFER, true)
        flush(cb)
    }

    @JvmStatic
    fun copyBuffer(cb: VkCommandBuffer, src: GpuBufferSlice, dst: GpuBufferSlice) {
        if (!active) return
        if (dumpLeft > 0) dumpOp = "copyBuffer " + describe(src) + " -> " + describe(dst)
        hazards.begin(++opId)
        buffer(src, STAGE_TRANSFER, false)
        buffer(dst, STAGE_TRANSFER, true)
        flush(cb)
    }

    @JvmStatic
    fun writeImage(cb: VkCommandBuffer, image: GpuTexture?, other: GpuTexture?) {
        if (!active) return
        if (dumpLeft > 0) dumpOp = "writeImage " + image?.label + " " + other?.label
        hazards.begin(++opId)
        image(image, STAGE_TRANSFER, true)
        image(other, STAGE_TRANSFER, true)
        flush(cb)
    }

    @JvmStatic
    fun copyBufferToImage(cb: VkCommandBuffer, src: GpuBufferSlice, dst: GpuTexture) {
        if (!active) return
        if (dumpLeft > 0) dumpOp = "copyBufferToImage -> " + dst.label
        hazards.begin(++opId)
        buffer(src, STAGE_TRANSFER, false)
        image(dst, STAGE_TRANSFER, true)
        flush(cb)
    }

    @JvmStatic
    fun copyImageToBuffer(cb: VkCommandBuffer, src: GpuTexture, dst: GpuBuffer, offset: Long) {
        if (!active) return
        if (dumpLeft > 0) dumpOp = "copyImageToBuffer " + src.label
        hazards.begin(++opId)
        image(src, STAGE_TRANSFER, false)
        val b = dst as? VulkanGpuBuffer
        if (b == null) hazards.forceFull() else hazards.buffer(b.vkBuffer(), offset, Long.MAX_VALUE, STAGE_TRANSFER, true)
        flush(cb)
    }

    @JvmStatic
    fun copyImage(cb: VkCommandBuffer, src: GpuTexture, dst: GpuTexture) {
        if (!active) return
        if (dumpLeft > 0) dumpOp = "copyImage " + src.label + " -> " + dst.label
        hazards.begin(++opId)
        image(src, STAGE_TRANSFER, false)
        image(dst, STAGE_TRANSFER, true)
        flush(cb)
    }

    // --- render passes ---

    /** Minecraft starts a render pass: remember what it draws into. */
    @JvmStatic
    fun passCreate(descriptor: RenderPassDescriptor) {
        if (!active) return
        passOp = ++opId
        passOpen = true
        if (dumpLeft > 0) dumpOp = "pass " + descriptor.label().get()
        deferred = false
        begun = false
        attachments = 0
        for (a in descriptor.colorAttachments()) {
            if (a == null || attachments == attachmentImages.size) continue
            attachment(a.textureView(), STAGE_COLOR_OUTPUT, !(a.clearValue() as Optional<*>).isPresent)
        }
        val d = descriptor.depthAttachment()
        if (d != null) attachment(d.textureView(), STAGE_DEPTH, !(d.clearValue() as OptionalDouble).isPresent)
        index = null
        indirect = null
        vertex.fill(null)
        vertexChecked.fill(null)
        indexChecked = null
        indirectChecked = null
        uniformChecked.fill(null)
        bindingsDirty = true
        unknownBinding = false
    }

    private fun attachment(view: GpuTextureView, stage: Long, load: Boolean) {
        attachmentImages[attachments] = vkImage(view.texture())
        attachmentStages[attachments] = stage
        attachmentLoads[attachments] = load
        attachments++
    }

    /** Instead of Minecraft's vkCmdBeginRendering: keep a copy and begin at the first draw. */
    @JvmStatic
    fun beginRendering(cb: VkCommandBuffer, src: VkRenderingInfo) {
        if (!active || !passOpen || !copy(src)) {
            if (active && passOpen) {
                // Not a pass this can defer (an unusual attachment setup): sync everything and begin now.
                if (hazards.dirty) fullBarrier(cb)
                hazards.clear()
                hazards.begin(passOp)
                attachmentAccesses()
                hazards.commit()
                begun = true
            }
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, src)
            return
        }
        deferred = true
    }

    private fun copy(src: VkRenderingInfo): Boolean {
        val count = src.colorAttachmentCount()
        if (count > MAX_COLORS || src.pNext() != 0L || src.pStencilAttachment() != null) return false
        MemoryUtil.memCopy(src.address(), info.address(), VkRenderingInfo.SIZEOF.toLong())
        if (count > 0) {
            MemoryUtil.memCopy(src.pColorAttachments()!!.address(), colors.address(), count.toLong() * VkRenderingAttachmentInfo.SIZEOF)
        }
        MemoryUtil.memPutAddress(info.address() + VkRenderingInfo.PCOLORATTACHMENTS, if (count > 0) colors.address() else 0L)
        val d = src.pDepthAttachment()
        if (d != null) MemoryUtil.memCopy(d.address(), depth.address(), VkRenderingAttachmentInfo.SIZEOF.toLong())
        MemoryUtil.memPutAddress(info.address() + VkRenderingInfo.PDEPTHATTACHMENT, if (d != null) depth.address() else 0L)
        colorCount = count
        hasDepth = d != null
        return true
    }

    @JvmStatic
    fun vertexBuffer(slot: Int, slice: GpuBufferSlice?) {
        if (!active || !passOpen) return
        if (slot in 0 until MAX_VERTEX) vertex[slot] = slice else unknownBinding = true
        bindingsDirty = true
    }

    @JvmStatic
    fun indexBuffer(buffer: GpuBuffer?) {
        if (!active || !passOpen) return
        index = buffer
        bindingsDirty = true
    }

    @JvmStatic
    fun indirect(slice: GpuBufferSlice?) {
        if (!active || !passOpen) return
        indirect = slice
        bindingsDirty = true
    }

    @JvmStatic
    fun uniformsChanged() {
        if (active && passOpen) bindingsDirty = true
    }

    /** Before each draw of the pass: begin it, or split it if a new binding needs a barrier. */
    @JvmStatic
    fun beforeDraw(cb: VkCommandBuffer, uniforms: List<Any?>) {
        if (!active || !passOpen) return
        if (!deferred || begun) {
            if (!bindingsDirty) return
            hazards.begin(passOp)
            bindings(uniforms)
            bindingsDirty = false
            if (hazards.needsBarrier) {
                // A full barrier forgets everything pending, the pass's own attachments too: list them again.
                if (hazards.full) attachmentAccesses()
                if (deferred) {
                    // Something this draw reads was written before the pass: end it, sync, resume.
                    KHRDynamicRendering.vkCmdEndRenderingKHR(cb)
                    if (dumpLeft > 0) println("GPUSYNC split:")
                    emit(cb)
                    hazards.commit()
                    resume(cb)
                    splits++
                } else {
                    // A pass that began without deferring got a full barrier when it began, so nothing recorded
                    // since can conflict with its bindings; only an unidentified binding gets here.
                    hazards.commit()
                }
            } else {
                hazards.commit()
            }
            return
        }
        start(cb, uniforms)
    }

    /** A clear inside the pass (vkCmdClearAttachments) needs the pass begun. */
    @JvmStatic
    fun ensureBegun(cb: VkCommandBuffer) {
        if (active && passOpen && deferred && !begun) start(cb, null)
    }

    /** Before Minecraft ends the pass: a pass that never drew still has to begin (its clears) and end. */
    @JvmStatic
    fun passEnd(cb: VkCommandBuffer?) {
        if (!active || !passOpen) return
        if (cb != null && deferred && !begun) start(cb, null)
        passOpen = false
        deferred = false
        begun = false
    }

    private fun start(cb: VkCommandBuffer, uniforms: List<Any?>?) {
        hazards.begin(passOp)
        attachmentAccesses()
        if (uniforms != null) bindings(uniforms) else bindings(emptyList())
        bindingsDirty = false
        if (hazards.needsBarrier) emit(cb)
        hazards.commit()
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, info)
        begun = true
    }

    private fun resume(cb: VkCommandBuffer) {
        for (i in 0 until colorCount) {
            MemoryUtil.memPutInt(colors.address() + i.toLong() * VkRenderingAttachmentInfo.SIZEOF + VkRenderingAttachmentInfo.LOADOP, LOAD_OP_LOAD)
        }
        if (hasDepth) MemoryUtil.memPutInt(depth.address() + VkRenderingAttachmentInfo.LOADOP, LOAD_OP_LOAD)
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, info)
    }

    private fun attachmentAccesses() {
        for (i in 0 until attachments) {
            // Loading reads the attachment, and every pass writes it (blending, depth tests, stores).
            hazards.image(attachmentImages[i], attachmentStages[i], true)
        }
    }

    private fun bindings(uniforms: List<Any?>) {
        if (unknownBinding) {
            hazards.forceFull()
            unknownBinding = false
        }
        for (s in 0 until MAX_VERTEX) {
            val v = vertex[s]
            if (v != null && v != vertexChecked[s]) {
                buffer(v, STAGE_VERTEX_INPUT, false)
                vertexChecked[s] = v
            }
        }
        val i = index
        if (i != null && i !== indexChecked) {
            val b = i as? VulkanGpuBuffer
            if (b == null) hazards.forceFull() else hazards.buffer(b.vkBuffer(), 0L, Long.MAX_VALUE, STAGE_VERTEX_INPUT, false)
            indexChecked = i
        }
        val ind = indirect
        if (ind != null && ind != indirectChecked) {
            buffer(ind, STAGE_DRAW_INDIRECT, false)
            indirectChecked = ind
        }
        val n = uniforms.size
        if (n > MAX_UNIFORMS) {
            hazards.forceFull()
            return
        }
        for (u in 0 until n) {
            val value = uniforms[u] ?: continue
            if (value == uniformChecked[u]) continue
            when (value) {
                is GpuBufferSlice -> buffer(value, STAGE_SHADERS, false)
                is TextureViewAndSampler -> image(value.view().texture(), STAGE_SHADERS, false)
                is GpuTextureView -> image(value.texture(), STAGE_SHADERS, false)
                else -> hazards.forceFull()
            }
            uniformChecked[u] = value
        }
    }

    // --- helpers ---

    private fun buffer(slice: GpuBufferSlice, stage: Long, write: Boolean) {
        val b = slice.buffer() as? VulkanGpuBuffer
        if (b == null) {
            hazards.forceFull()
            return
        }
        hazards.buffer(b.vkBuffer(), slice.offset(), slice.offset() + slice.length(), stage, write)
    }

    private fun image(texture: GpuTexture?, stage: Long, write: Boolean) {
        if (texture == null) return
        val image = vkImage(texture)
        if (image == 0L) hazards.forceFull() else hazards.image(image, stage, write)
    }

    private fun vkImage(texture: GpuTexture?): Long = (texture as? VulkanGpuTexture)?.vkImage() ?: 0L

    private fun flush(cb: VkCommandBuffer) {
        tracked++
        if (hazards.needsBarrier) emit(cb)
        hazards.commit()
    }

    private fun emit(cb: VkCommandBuffer) {
        if (hazards.full) {
            fullBarrier(cb)
            return
        }
        barriers++
        if (dumpLeft > 0) {
            val parts = (0 until hazards.barrierSize).joinToString("; ") {
                "${stages(hazards.barrierSrc(it))} -> ${stages(hazards.barrierDst(it))} (${java.lang.Long.toHexString(hazards.barrierCause(it))})"
            }
            println("GPUSYNC barrier before $dumpOp: $parts")
        }
        MemoryStack.stackPush().use { stack ->
            val n = hazards.barrierSize
            val entries = VkMemoryBarrier2.calloc(n, stack)
            for (e in 0 until n) {
                entries.get(e).`sType$Default`()
                    .srcStageMask(hazards.barrierSrc(e))
                    .srcAccessMask(hazards.barrierSrcAccess(e))
                    .dstStageMask(hazards.barrierDst(e))
                    .dstAccessMask(ACCESS_READ or ACCESS_WRITE)
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).`sType$Default`().pMemoryBarriers(entries))
        }
    }

    private fun fullBarrier(cb: VkCommandBuffer) {
        fullBarriers++
        if (dumpLeft > 0) println("GPUSYNC full barrier before $dumpOp")
        MemoryStack.stackPush().use { stack ->
            val entry = VkMemoryBarrier2.calloc(1, stack)
            entry.get(0).`sType$Default`()
                .srcStageMask(STAGE_ALL_COMMANDS).srcAccessMask(ACCESS_READ or ACCESS_WRITE)
                .dstStageMask(STAGE_ALL_COMMANDS).dstAccessMask(ACCESS_READ or ACCESS_WRITE)
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).`sType$Default`().pMemoryBarriers(entry))
        }
    }

    /** Test hook: per-frame averages since the last call (ShotBot `call dev.horizon.client.render.vk.Barriers report`). */
    @JvmStatic
    fun report(): String {
        val f = frames.coerceAtLeast(1).toFloat()
        val s = "frames=$frames tracked/frame=${"%.1f".format(tracked / f)} barriers/frame=${"%.1f".format(barriers / f)} " +
            "full/frame=${"%.1f".format(fullBarriers / f)} splits/frame=${"%.2f".format(splits / f)} active=$active"
        frames = 0
        tracked = 0
        barriers = 0
        fullBarriers = 0
        splits = 0
        return s
    }

    /** Test hook: logs every barrier of the next [count] frames with the command it precedes and what caused it. */
    @JvmStatic
    fun dump(count: String) {
        dumpLeft = count.trim().toInt()
    }

    private fun describe(slice: GpuBufferSlice) =
        java.lang.Long.toHexString((slice.buffer() as? VulkanGpuBuffer)?.vkBuffer() ?: 0L) + "@" + slice.offset() + "+" + slice.length()

    private fun stages(mask: Long): String {
        val names = listOf(STAGE_DRAW_INDIRECT to "indirect", STAGE_VERTEX_INPUT to "vertex-input", Hazards.STAGE_VERTEX_SHADER to "vs",
            Hazards.STAGE_FRAGMENT_SHADER to "fs", Hazards.STAGE_EARLY_FRAGMENT_TESTS to "early-z", Hazards.STAGE_LATE_FRAGMENT_TESTS to "late-z",
            STAGE_COLOR_OUTPUT to "color", STAGE_TRANSFER to "transfer", STAGE_ALL_COMMANDS to "all")
        return names.filter { mask and it.first != 0L }.joinToString("|") { it.second }
    }

    /** Fence polls answered from one read of the GPU's progress (VkCommandEncoderMixin.pollOnce); off only for tests. */
    @JvmField var cachedPolls = true

    /** Test hook: `call dev.horizon.client.render.vk.Barriers polls true|false`. */
    @JvmStatic
    fun polls(on: String) {
        cachedPolls = on.trim().toBoolean()
    }

    /** Test hook: on, off or follow the option (`call dev.horizon.client.render.vk.Barriers set true|false|option`). */
    @JvmStatic
    fun set(on: String) {
        forced = on.trim().toBooleanStrictOrNull()
    }

    private const val MAX_COLORS = 8
    private const val MAX_VERTEX = 8
    private const val MAX_UNIFORMS = 32
    private const val LOAD_OP_LOAD = 0
}
