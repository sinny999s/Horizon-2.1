package dev.horizon.client.gui

import com.mojang.blaze3d.platform.cursor.CursorType

/**
 * Cursor shapes asked for by widgets this frame. 26.3 picks one cursor a frame
 * (GuiGraphicsExtractor.applyCursor: the arrow unless a vanilla widget asks for another), so setting ours after it
 * switched the cursor twice a frame and it flickered between the arrow and the hand. Instead the shape asked for in
 * the last frame goes into vanilla's own update (GuiGraphicsExtractorMixin).
 */
object Cursor {
    private var requested: CursorType? = null

    /** The last finished frame's shape, handed to vanilla's next cursor update. */
    var last: CursorType? = null
        private set

    fun request(type: CursorType) {
        requested = type
    }

    /** At the end of the frame: this frame's shape becomes the one vanilla applies next. */
    fun endFrame() {
        last = requested
        requested = null
    }
}

/** FPS counter: frames drawn in the last second, at least 4. */
object Fps {
    private val frames = ArrayDeque<Long>()

    fun frame() {
        val now = System.currentTimeMillis()
        frames.addLast(now)
        while (frames.isNotEmpty() && now - frames.first() > 1000) frames.removeFirst()
    }

    val value: Int get() = maxOf(frames.size, 4)

    /** Frame-rate based smoothing: `t = clamp((fps > 5 ? 1/fps : 0.016) * speed)`. */
    fun smooth(cur: Float, target: Float, speed: Float): Float {
        val t = ((if (value > 5) 1f / value else 0.016f) * speed).coerceIn(0f, 1f)
        return (1f - t) * cur + t * target
    }
}
