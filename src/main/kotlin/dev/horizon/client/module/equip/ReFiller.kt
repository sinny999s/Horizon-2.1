package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.item.Items

/**
 * Keeps a totem in the hotbar slot one was just used from, and every 10 ticks tops up the
 * first half-empty hotbar stack from a matching main-inventory stack. Works with no screen or one of the client's own.
 */
class ReFiller : Module("re-filler", Categories.EQUIP) {
    /**
     * Ticks left to put a totem back into hotbar [slot]. Set to 3 every tick a totem is held
     * in the main hand, and by Offhand after its hot-bar swap; counted down while the slot holds no totem.
     */
    @Volatile
    var window = 0
    @Volatile
    var slot = -1

    private val timer = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val screen = mc.gui.screen()
            if (screen != null && screen !== dev.horizon.client.gui.modern.ModernGui && screen !== dev.horizon.client.gui.modern.ModernWindows && screen !== dev.horizon.client.gui.modern.ModernHudEditor) {
                window = 0
                return@on
            }
            if (p.mainHandItem.`is`(Items.TOTEM_OF_UNDYING)) {
                window = 3
                slot = p.inventory.selectedSlot
            } else if (window > 0) {
                if (!p.inventory.getItem(slot).`is`(Items.TOTEM_OF_UNDYING)) {
                    val found = Inventory.find(Items.TOTEM_OF_UNDYING)
                    if (found != null) {
                        Inventory.swap(found.slot, slot, true)
                        window = 0
                    }
                }
                window -= 1
            }
            if (!timer.passed(10)) return@on
            for (i in 0 until 9) if (refill(i)) return@on
        }
    }

    /** Fill hotbar [i] from the first main-inventory stack with the same item and name. */
    private fun refill(i: Int): Boolean {
        val inv = mc.player?.inventory ?: return false
        val s = inv.getItem(i)
        if (s.isEmpty || !s.isStackable) return false
        if (s.maxStackSize == 16 && s.count > 8) return false
        if (s.maxStackSize == 64 && s.count > 32) return false
        for (k in 9 until 36) {
            val t = inv.getItem(k)
            if (t.isEmpty || t.item != s.item || t.hoverName != s.hoverName) continue
            val overflow = t.count + s.count > t.maxStackSize
            if (t.maxStackSize == t.count) {
                Inventory.swap(k, i, true)
            } else {
                EquipUtil.click(k)
                EquipUtil.click(if (i < 9) i + 36 else i, close = !overflow)
                if (overflow) EquipUtil.click(k, close = true)
            }
            timer.reset()
            return true
        }
        return false
    }
}
