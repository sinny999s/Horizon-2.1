package dev.horizon.client.module.player

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.mixin.player2.JumpDelayAccessor
import dev.horizon.client.mixin.player2.UseDelayAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/**
 * Each player tick, lowers the right-click repeat delay to [delay] for the main-hand item
 * (when it passes the filter) and, with [jump], clears the hold-to-rejump cooldown. Air Place reads [blocksSetting]
 * and [all].
 */
class NoDelay : Module("no-delay", Categories.PLAYER) {
    val delay = int("delay", 1, 0..4)
    val jump = bool("jump", false)
    val blocksSetting = bool("blocks", false)
    val crystals = bool("crystals", false)
    val xP = bool("x-p", false)
    val all = bool("all", true)

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val j = p as JumpDelayAccessor
            if (j.horizonP2_noJumpDelay() > 0 && jump.value) j.horizonP2_setNoJumpDelay(0)
            if (appliesTo(p.mainHandItem.item)) {
                val m = mc as UseDelayAccessor
                if (m.horizonP2_rightClickDelay() > delay.value) m.horizonP2_setRightClickDelay(delay.value)
            }
        }
    }

    private fun appliesTo(item: Item): Boolean {
        if (item is BlockItem && blocksSetting.value) return true
        if (item == Items.END_CRYSTAL && crystals.value) return true
        if (item == Items.EXPERIENCE_BOTTLE && xP.value) return true
        return all.value
    }
}
