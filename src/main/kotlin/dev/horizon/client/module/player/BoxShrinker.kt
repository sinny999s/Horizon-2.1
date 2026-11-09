package dev.horizon.client.module.player

import dev.horizon.client.event.BoxContractEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/** The local player's bounding box is 1e-12 narrower on each side in X and Z. */
class BoxShrinker : Module("box-shrinker", Categories.PLAYER) {
    /** Set while the handler reads the real box, whose own call comes back through the event. */
    private var reading = false

    init {
        on<BoxContractEvent> { e ->
            if (reading) return@on
            val p = mc.player ?: return@on
            reading = true
            e.value = p.boundingBox.inflate(-1.0E-12, 0.0, -1.0E-12)
            reading = false
            e.cancel()
        }
    }
}
