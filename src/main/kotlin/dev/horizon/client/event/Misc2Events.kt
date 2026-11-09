package dev.horizon.client.event

import net.minecraft.world.entity.player.Player

/**
 * Another player in render distance was first seen dead, with the totems they popped since their last death
 * (fired by the pop tracker).
 */
class PlayerDeathEvent(val player: Player, val totemsPopped: Int) : Event()
