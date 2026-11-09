package dev.horizon.client.render

/**
 * An animated value: [update] moves towards a target over [duration] ms along [easing], restarting from
 * the current value whenever the target changes. Time is wall-clock ms.
 */
class Animation(private val easing: Easing, private val duration: Long) {
    /** The current value. */
    var value = 0f

    private var start = System.currentTimeMillis()
    private var from = 0f
    private var target = 0f

    /** True once the run reached its target (not updated on the frame the target changes, on purpose). */
    var finished = false

    /** Call every frame with where the value should go. */
    fun update(target: Float) {
        if (this.target != target) {
            this.target = target
            reset()
        } else {
            finished = System.currentTimeMillis() - duration - start > 0
            if (finished) {
                value = target
                return
            }
        }
        val t = easing.apply((System.currentTimeMillis() - start).toDouble() / duration)
        value = (from + (this.target - from) * t).toFloat()
    }

    /** Restart from [v]. */
    fun reset(v: Float) {
        start = System.currentTimeMillis()
        from = v
        finished = false
    }

    /** Restart from the current value. */
    fun reset() = reset(value)
}
