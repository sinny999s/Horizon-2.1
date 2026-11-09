package dev.horizon.client.module.misc

import dev.horizon.client.combat.ItemSwitch
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items

/** Every player tick at or below [minHealth], switches to mushroom stew and uses it. */
class AutoSoup : Module("auto-soup", Categories.MISC) {
    val minHealth = float("min-health", 5.0f, 1.0f..20.0f)
    val switch = enum("switch", Switch.SILENT_FULL)

    init {
        on<PlayerUpdateEvent>(-10) {
            val player = mc.player ?: return@on
            if (player.health > minHealth.value) return@on
            ItemSwitch.use(Items.MUSHROOM_STEW, switch.value) {
                mc.player?.let { mc.gameMode?.useItem(it, InteractionHand.MAIN_HAND) }
            }
        }
    }
}
