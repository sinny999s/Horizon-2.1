package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Items

/**
 * When the worn elytra has 2 or fewer uses left, the first glider with more than 2
 * (inventory index 36 down to 0) is swapped onto the chest with three pickup clicks and a close, every tick it holds.
 */
class ElytraReplace : Module("elytra-replace", Categories.EQUIP) {
    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val chest = p.getItemBySlot(EquipmentSlot.CHEST)
            if (!chest.`is`(Items.ELYTRA) || chest.maxDamage - chest.damageValue > 2) return@on
            val found = Inventory.find { it.item.components().has(DataComponents.GLIDER) && it.maxDamage - it.damageValue > 2 } ?: return@on
            EquipUtil.click(found.slot)
            EquipUtil.click(6)
            EquipUtil.click(found.slot, close = true)
            Notifications.post(name, REPLACED, 2f, NotificationType.INFO)
            moduleMessage(name.trim(), REPLACED)
        }
    }

    private companion object {
        const val REPLACED = "Replaced elytra"
    }
}
