package dev.horizon.client.module.cosmetic

import dev.horizon.client.event.LightMapUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/** The lightmap stays full white (its update is cancelled). */
class FullBright : Module("full-bright", Categories.COSMETIC) {
    init {
        on<LightMapUpdateEvent> { it.cancel() }
    }
}
