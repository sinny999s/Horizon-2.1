package dev.horizon.client.module.protect

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.StopUsingItemEvent
import dev.horizon.client.event.UsingItemEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items

/**
 * At low health, eats the golden apple already in a hand (off-hand first) and keeps
 * eating after right-click is let go — the player counts as "using" and vanilla's release is cancelled. Never switches.
 */
class AutoGapple : Module("auto-gapple", Categories.PROTECT) {
    val health = float("health", 15.0f, 1.0f..36.0f)

    /** The eating flag; kept across a toggle (on purpose). */
    private var eating = false

    init {
        on<PlayerUpdateEvent>(-10) { tick() }
        on<UsingItemEvent> { if (eating) it.cancel() }
        on<StopUsingItemEvent> { if (eating) it.cancel() }
    }

    private fun tick() {
        val p = mc.player ?: return
        eating = false
        val offGap = p.offhandItem.item == Items.GOLDEN_APPLE || p.offhandItem.item == Items.ENCHANTED_GOLDEN_APPLE
        val mainGap = p.mainHandItem.item == Items.GOLDEN_APPLE || p.mainHandItem.item == Items.ENCHANTED_GOLDEN_APPLE
        if (!offGap && !mainGap) return
        if (p.health + p.absorptionAmount - health.value > 0.0f) return
        // Also set while something else is in use (a bow, a shield): its release is blocked too (on purpose).
        if (!p.isUsingItem) mc.gameMode?.useItem(p, if (offGap) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND)
        eating = true
    }
}
