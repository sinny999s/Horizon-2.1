package dev.horizon.client.module.misc

import dev.horizon.client.event.PlayerListSizeEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/** The tab list shows up to 1000 players instead of 80. */
class ExtraTab : Module("extra-tab", Categories.MISC) {
    init {
        on<PlayerListSizeEvent> { it.cancel() }
    }
}
