package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items

/**
 * Automatically drops unwanted junk items (netherrack, cobblestone, dirt, diorite, seeds, etc.)
 * from the player's inventory while mining, digging highways, or looting stashes.
 */
class AutoDrop : Module("auto-drop", Categories.EQUIP) {
    enum class Mode { ALWAYS, INVENTORY_FULL, ONLY_HOTBAR }

    val mode = enum("mode", Mode.ALWAYS)
    val dropStack = bool("drop-stack", true)
    val delay = int("delay-ms", 100, 0..1000)
    val inventoryOnly = bool("inventory-only", false)
    val dropList = items(
        "drop-list",
        arrayListOf(
            Items.NETHERRACK,
            Items.COBBLESTONE,
            Items.DIRT,
            Items.DIORITE,
            Items.ANDESITE,
            Items.GRANITE,
            Items.GRAVEL,
            Items.TUFF,
            Items.DEEPSLATE,
            Items.COBBLED_DEEPSLATE,
            Items.ROTTEN_FLESH,
            Items.POISONOUS_POTATO,
            Items.WHEAT_SEEDS
        )
    )

    private val timer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (!timer.passed(delay.value.toLong())) return@on

            val inInventory = mc.gui.screen() is net.minecraft.client.gui.screens.inventory.InventoryScreen
            if (inventoryOnly.value && !inInventory) return@on

            // Don't drop while in a container menu (like chest or anvil)
            if (mc.gui.screen() != null && !inInventory) return@on

            if (mode.value == Mode.INVENTORY_FULL) {
                // Check if main inventory has any empty slots (9..35)
                val hasEmpty = (9..35).any { p.inventory.getItem(it).isEmpty }
                if (hasEmpty) return@on
            }

            val range = when (mode.value) {
                Mode.ONLY_HOTBAR -> 0..8
                else -> 9..35
            }

            for (i in range) {
                val stack = p.inventory.getItem(i)
                if (stack.isEmpty) continue
                if (dropList.value.contains(stack.item)) {
                    // Convert inventory index to container slot id (hotbar 0..8 -> 36..44, main 9..35 -> 9..35)
                    val slotId = if (i < 9) i + 36 else i
                    val button = if (dropStack.value) 1 else 0
                    EquipUtil.click(slotId, button, ContainerInput.THROW)
                    timer.reset()
                    return@on
                }
            }

            // Also check hotbar if ALWAYS mode and main inventory has no drop candidates
            if (mode.value == Mode.ALWAYS) {
                for (i in 0..8) {
                    val stack = p.inventory.getItem(i)
                    if (stack.isEmpty) continue
                    if (dropList.value.contains(stack.item)) {
                        val slotId = i + 36
                        val button = if (dropStack.value) 1 else 0
                        EquipUtil.click(slotId, button, ContainerInput.THROW)
                        timer.reset()
                        return@on
                    }
                }
            }
        }
    }
}
