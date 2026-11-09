package dev.horizon.client.module.legit

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items

/**
 * Once anything has been in use for `delay` ticks with a bow in either hand, releases it, draws the main hand again
 * server-side and stops using client-side. The item check is loose (on purpose), and the re-draw always uses the main
 * hand.
 */
class BowSpam : Module("bow-spam", Categories.LEGIT) {
    val delay = int("delay", 3, 0..20)

    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            if (!p.offhandItem.`is`(Items.BOW) && !p.mainHandItem.`is`(Items.BOW)) return@on
            if (!p.isUsingItem) return@on
            if (p.ticksUsingItem < delay.value) return@on
            val gm = mc.gameMode ?: return@on
            gm.releaseUsingItem(p)
            gm.useItem(p, InteractionHand.MAIN_HAND)
            p.releaseUsingItem()
        }
    }
}
