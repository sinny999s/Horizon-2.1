package dev.horizon.client.module.protect

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.attack.TrapBase

/** The trap base around ourselves (sorted towards where the movement keys go). */
class SelfTrap : TrapBase("self-trap", Categories.PROTECT) {
    init {
        on<PlayerUpdateEvent> { trapPlayer(mc.player) }
    }
}
