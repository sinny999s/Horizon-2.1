package dev.horizon.client.module.cosmetic

import dev.horizon.client.event.BasicProjectionMatrixEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/** The level is projected with [ratio] as its aspect ratio. */
class Aspect : Module("aspect", Categories.COSMETIC) {
    val ratio = float("ratio", 1.0f, 0.1f..10.0f)

    init {
        on<BasicProjectionMatrixEvent> { e ->
            BasicProjectionMatrixEvent.ratio = ratio.value
            e.cancel()
        }
    }
}
