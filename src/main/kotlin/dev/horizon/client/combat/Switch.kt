package dev.horizon.client.combat

import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.basic.GuiMove
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * The shared switch helper behind every `switch` ([Switch]) setting:
 * finds a stack where the mode looks, and runs an action with it in the main hand.
 */
object Switching {
    /** Gui-move's flag: set while [run] switches (gui-move's handlers do nothing while it is). */
    var busy: Boolean
        get() = Modules.get<GuiMove>().internalClick
        set(value) {
            Modules.get<GuiMove>().internalClick = value
        }

    /**
     * NORMAL/SILENT look in the hotbar (a hotbar index), INVENTORY_* in the whole
     * inventory (a container slot id), the FULL modes in the hotbar first, then the whole inventory.
     */
    fun search(mode: Switch, pred: (ItemStack) -> Boolean): Found? = when (mode) {
        Switch.NORMAL, Switch.SILENT -> Inventory.hotbarFind(pred)
        Switch.INVENTORY_NORMAL, Switch.INVENTORY_SILENT -> Inventory.find(pred)
        Switch.NORMAL_FULL, Switch.SILENT_FULL -> Inventory.hotbarFind(pred) ?: Inventory.find(pred)
    }

    /** [search], then [run] with what was found; false if nothing was. */
    fun run(mode: Switch, pred: (ItemStack) -> Boolean, action: () -> Unit): Boolean {
        val f = search(mode, pred) ?: return false
        run(f.slot, mode, action)
        return true
    }

    fun run(mode: Switch, item: Item, action: () -> Unit): Boolean = run(mode, { it.`is`(item) }, action)

    /**
     * NORMAL selects [slot] and stays; SILENT selects it and switches back to the
     * slot selected before; INVENTORY_NORMAL number-key swaps container slot [slot] with the selected hotbar slot
     * (INVENTORY_SILENT swaps back afterwards; no close packet); the FULL modes take the hotbar way for 0..8, else the
     * inventory way.
     */
    fun run(slot: Int, mode: Switch, action: () -> Unit) {
        busy = true
        when (mode) {
            Switch.NORMAL -> {
                if (Inventory.selected != slot) Inventory.selectHotbar(slot)
                action()
            }
            Switch.SILENT -> {
                val prev = Inventory.selected
                if (Inventory.serverSlot != slot) Inventory.selectHotbar(slot)
                action()
                if (Inventory.serverSlot != prev) Inventory.selectHotbar(prev)
            }
            Switch.INVENTORY_NORMAL -> {
                swap(slot)
                action()
            }
            Switch.INVENTORY_SILENT -> {
                swap(slot)
                action()
                swap(slot)
            }
            Switch.NORMAL_FULL -> run(slot, if (slot >= 9) Switch.INVENTORY_NORMAL else Switch.NORMAL, action)
            Switch.SILENT_FULL -> run(slot, if (slot >= 9) Switch.INVENTORY_SILENT else Switch.SILENT, action)
        }
        busy = false
    }

    /** A SWAP click of container slot [slot] with the selected hotbar slot, in whatever menu is open. */
    private fun swap(slot: Int) = Inventory.click(slot, Inventory.selected, ContainerInput.SWAP, false)
}
