package dev.horizon.client.module.legit

import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * Takes away the client's miss delay, 1.8's "hit delay". A swing that hits nothing makes vanilla
 * ignore the attack button for the next 10 ticks (half a second, `Minecraft.missTime`), so a click that whiffs just
 * before someone steps into your crosshair loses the hit after it. This clears that counter at the start of every tick,
 * before vanilla reads the button, so every click attacks. Nothing extra is sent: only clicks that already happen
 * count. Auto Clicker and Trigger Bot clear it for their own clicks; this does it for yours.
 */
class NoHitDelay : Module("no-hit-delay", Categories.LEGIT, listOf("hit-delay", "no-miss-delay", "nohitdelay")) {
    init {
        on<TickEvent> {
            if (mc.player != null) (mc as MinecraftAccessor).horizon_setMissTime(0)
        }
    }
}
