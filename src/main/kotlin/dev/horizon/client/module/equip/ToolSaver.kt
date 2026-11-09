package dev.horizon.client.module.equip

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.tags.ItemTags

/**
 * With a pickaxe, sword, axe, hoe or shovel in hand at [savePercent] or less of its
 * durability, moves the selection one slot right (`(slot + 1) % 8`, so 7 and 8 both go to 0) and says so, every tick.
 */
class ToolSaver : Module("tool-saver", Categories.EQUIP) {
    val savePercent = int("save-percent", 10, 1..50)

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val s = p.mainHandItem
            // 26.3 has no AxeItem/HoeItem/ShovelItem, only the tags.
            if (!(s.`is`(ItemTags.PICKAXES) || s.`is`(ItemTags.SWORDS) || s.`is`(ItemTags.AXES) || s.`is`(ItemTags.HOES) ||
                    s.`is`(ItemTags.SHOVELS))) return@on
            val left = (s.maxDamage - s.damageValue).toFloat()
            if ((left / s.maxDamage * 100f).toInt() > savePercent.value) return@on
            p.inventory.selectedSlot = (p.inventory.selectedSlot + 1) % 8
            moduleMessage(name.trim(), "Your tool is almost broken!")
        }
    }
}
