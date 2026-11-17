package dev.horizon.client.render.vk

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

/**
 * Which GPU memory recent commands read and wrote, and the smallest pipeline barrier a new command needs before it.
 *
 * Minecraft's Vulkan backend stops the whole GPU (a full ALL_COMMANDS barrier) after every render pass, clear, upload
 * and copy, so nothing ever overlaps. This keeps the accesses since they were last synchronized instead (buffer byte
 * ranges and whole images, with the pipeline stages that touched them) and, for each new command, asks only for the
 * dependencies it really has: a write after any access to the same memory, a read after a write. Each dependency waits
 * only for the stages that made the earlier access and blocks only the stages of the new one, so a draw into the same
 * color target can start shading while the previous pass is still writing, and uploads to unrelated buffers need no
 * barrier at all.
 *
 * One command is described with [begin], then [buffer] / [image] for each thing it touches (checked against earlier commands
 * only, never against itself), then the caller emits [barrier] if [needsBarrier] and calls [commit]. Game-free; the
 * Vulkan side is [Barriers].
 */
class Hazards {
    // Accesses since the last full synchronization, struct-of-arrays, chained per resource.
    private var size = 0
    private var starts = LongArray(512)
    private var ends = LongArray(512)
    private var stages = LongArray(512)
    private var writes = BooleanArray(512)
    private var done = LongArray(512)
    private var ops = IntArray(512)
    private var next = IntArray(512)
    private val bufferHeads = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private val imageHeads = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }

    // The command being described.
    private var op = 0
    private var stagedSize = 0
    private var stagedImages = BooleanArray(32)
    private var stagedHandles = LongArray(32)
    private var stagedStarts = LongArray(32)
    private var stagedEnds = LongArray(32)
    private var stagedStages = LongArray(32)
    private var stagedWrites = BooleanArray(32)

    /** Set when unknown GPU work was recorded (another command buffer): the next command waits for everything. */
    private var unknown = false

    /** The barrier the current command needs: entries of (source stages, source access, destination stages). */
    var barrierSize = 0
        private set
    private val barrierSrc = LongArray(MAX_ENTRIES)
    private val barrierSrcAccess = LongArray(MAX_ENTRIES)
    private val barrierDst = LongArray(MAX_ENTRIES)
    private val barrierCause = LongArray(MAX_ENTRIES)

    /** True when the current command needs a full barrier (everything before it, all stages). */
    var full = false
        private set

    val pending: Int get() = size

    fun barrierSrc(i: Int) = barrierSrc[i]
    fun barrierSrcAccess(i: Int) = barrierSrcAccess[i]
    fun barrierDst(i: Int) = barrierDst[i]

    /** The handle of the first earlier access that made entry [i] necessary (for the debug dump). */
    fun barrierCause(i: Int) = barrierCause[i]

    val needsBarrier: Boolean get() = full || barrierSize > 0

    /** Starts describing a command. Accesses from earlier commands with the same [opId] are not hazards (a pass's own attachments). */
    fun begin(opId: Int) {
        op = opId
        stagedSize = 0
        barrierSize = 0
        full = unknown
    }

    /** The current command touches bytes [start, end) of the VkBuffer [handle] in [stage]s; [write] if it changes them. */
    fun buffer(handle: Long, start: Long, end: Long, stage: Long, write: Boolean) = access(false, handle, start, end, stage, write)

    /** The current command touches the VkImage [handle] (all of it) in [stage]s; [write] if it changes it. */
    fun image(handle: Long, stage: Long, write: Boolean) = access(true, handle, 0L, 1L, stage, write)

    private fun access(image: Boolean, handle: Long, start: Long, end: Long, stage: Long, write: Boolean) {
        if (!full) {
            var i = (if (image) imageHeads else bufferHeads).get(handle)
            while (i >= 0) {
                if (ops[i] != op && (write || writes[i]) && starts[i] < end && start < ends[i]) {
                    val missing = stage and done[i].inv()
                    if (missing != 0L) need(stages[i], if (writes[i]) ACCESS_WRITE else 0L, stage, handle)
                }
                i = next[i]
            }
        }
        stage(image, handle, start, end, stage, write)
    }

    private fun need(src: Long, srcAccess: Long, dst: Long, cause: Long) {
        // Entries are kept per source: merging different sources into one entry would make every destination wait
        // for every source (a color write would then hold back vertex shading).
        for (e in 0 until barrierSize) {
            if (barrierSrc[e] == src) {
                barrierDst[e] = barrierDst[e] or dst
                barrierSrcAccess[e] = barrierSrcAccess[e] or srcAccess
                return
            }
        }
        if (barrierSize == MAX_ENTRIES) {
            full = true
            return
        }
        barrierSrc[barrierSize] = src
        barrierSrcAccess[barrierSize] = srcAccess
        barrierDst[barrierSize] = dst
        barrierCause[barrierSize] = cause
        barrierSize++
    }

    private fun stage(image: Boolean, handle: Long, start: Long, end: Long, stage: Long, write: Boolean) {
        if (stagedSize == stagedHandles.size) {
            val n = stagedSize * 2
            stagedImages = stagedImages.copyOf(n)
            stagedHandles = stagedHandles.copyOf(n)
            stagedStarts = stagedStarts.copyOf(n)
            stagedEnds = stagedEnds.copyOf(n)
            stagedStages = stagedStages.copyOf(n)
            stagedWrites = stagedWrites.copyOf(n)
        }
        stagedImages[stagedSize] = image
        stagedHandles[stagedSize] = handle
        stagedStarts[stagedSize] = start
        stagedEnds[stagedSize] = end
        stagedStages[stagedSize] = stage
        stagedWrites[stagedSize] = write
        stagedSize++
    }

    /** Records that the barrier asked for (if any) was emitted, then adds the current command's accesses. */
    fun commit() {
        if (full) {
            clear()
        } else if (barrierSize > 0) {
            for (i in 0 until size) {
                for (e in 0 until barrierSize) {
                    if (stages[i] and barrierSrc[e].inv() == 0L) done[i] = done[i] or barrierDst[e]
                }
            }
        }
        unknown = false
        for (s in 0 until stagedSize) add(stagedImages[s], stagedHandles[s], stagedStarts[s], stagedEnds[s], stagedStages[s], stagedWrites[s])
        stagedSize = 0
        barrierSize = 0
        full = false
        if (size > LIMIT) {
            // A frame that never ends its command buffer: start over after a full barrier at the next command.
            clear()
            unknown = true
        }
    }

    private fun add(image: Boolean, handle: Long, start: Long, end: Long, stage: Long, write: Boolean) {
        val heads = if (image) imageHeads else bufferHeads
        val head = heads.get(handle)
        if (!write) {
            // The same read again (a uniform buffer bound for every draw): nothing new to remember.
            var i = head
            while (i >= 0) {
                if (!writes[i] && ops[i] == op && stages[i] == stage && starts[i] <= start && end <= ends[i] && done[i] == 0L) return
                i = next[i]
            }
        }
        if (size == starts.size) grow()
        starts[size] = start
        ends[size] = end
        stages[size] = stage
        writes[size] = write
        done[size] = 0L
        ops[size] = op
        next[size] = head
        heads.put(handle, size)
        size++
    }

    private fun grow() {
        val n = starts.size * 2
        starts = starts.copyOf(n)
        ends = ends.copyOf(n)
        stages = stages.copyOf(n)
        writes = writes.copyOf(n)
        done = done.copyOf(n)
        ops = ops.copyOf(n)
        next = next.copyOf(n)
    }

    /** The current command touches something this can't identify: it waits for everything before it. */
    fun forceFull() {
        full = true
    }

    /** Unknown GPU work was recorded: the next command waits for everything before it. */
    fun markUnknown() {
        unknown = true
    }

    /** True when anything was accessed since the last full synchronization (or unknown work is outstanding). */
    val dirty: Boolean get() = size > 0

    /** A full barrier was emitted: nothing earlier can conflict any more. */
    fun clear() {
        size = 0
        bufferHeads.clear()
        imageHeads.clear()
    }

    companion object {
        // VkPipelineStageFlagBits2 / VkAccessFlagBits2.
        const val STAGE_DRAW_INDIRECT = 0x2L
        const val STAGE_VERTEX_INPUT = 0x4L
        const val STAGE_VERTEX_SHADER = 0x8L
        const val STAGE_FRAGMENT_SHADER = 0x80L
        const val STAGE_EARLY_FRAGMENT_TESTS = 0x100L
        const val STAGE_LATE_FRAGMENT_TESTS = 0x200L
        const val STAGE_COLOR_OUTPUT = 0x400L
        const val STAGE_TRANSFER = 0x1000L
        const val STAGE_ALL_COMMANDS = 0x10000L
        const val STAGE_SHADERS = STAGE_VERTEX_SHADER or STAGE_FRAGMENT_SHADER
        const val STAGE_DEPTH = STAGE_EARLY_FRAGMENT_TESTS or STAGE_LATE_FRAGMENT_TESTS
        const val ACCESS_READ = 0x8000L
        const val ACCESS_WRITE = 0x10000L

        const val MAX_ENTRIES = 8
        const val LIMIT = 1 shl 14
    }
}
