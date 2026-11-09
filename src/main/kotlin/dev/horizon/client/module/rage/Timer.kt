package dev.horizon.client.module.rage

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * Sets the tick-rate factor to [value] every player tick; with [timeout], turns itself off
 * once [timeoutValue] ms have passed since it was enabled (the factor written that tick still applies until the next).
 * It writes at PlayerUpdate priority 0, so any module writing the factor later in the tick wins.
 */
class Timer : Module("timer", Categories.RAGE) {
    val value = float("value", 1.0f, 0.1f..10.0f)
    val timeout = bool("timeout", false)
    val timeoutValue = int("timeout-value", 1, 1..10000) { timeout.value }

    private val sinceEnable = MsTimer()

    override fun onEnable() {
        sinceEnable.reset()
    }

    init {
        on<PlayerUpdateEvent> {
            TimerManager.factor = value.value
            if (timeout.value && sinceEnable.passed(timeoutValue.value.toLong())) disable()
        }
    }
}
