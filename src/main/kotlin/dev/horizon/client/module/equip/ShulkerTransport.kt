package dev.horizon.client.module.equip

import dev.horizon.client.combat.Slots
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.phys.Vec3

/**
 * Moves shulker boxes between bases. Opening a chest (or barrel, ender chest) at the first base fills
 * your inventory with its shulkers; opening one at the other base empties them into it. Auto picks which by distance:
 * carrying shulkers more than [distance] blocks from where you last took some (or, before taking any, with nowhere
 * left to put more) means dump, anything else take; Take and Dump force it. Shulkers in the hotbar are filled and emptied too unless
 * [hotbar] is off. One shulker every [delay] ticks; the chest closes when done with [close].
 */
class ShulkerTransport : Module("shulker-transport", Categories.EQUIP) {
    enum class Mode { AUTO, TAKE, DUMP }
    private enum class Action { TAKE, DUMP }

    val mode = enum("mode", Mode.AUTO)
    val distance = int("distance", 50, 5..500) { mode.value == Mode.AUTO }
    val hotbar = bool("hotbar", true)
    val delay = int("delay", 2, 1..10)
    val close = bool("close", true)

    private var action: Action? = null
    private var wait = 0
    private var moved = 0
    private var done = false

    /** Where shulkers were last taken from (Auto dumps once you're [distance] blocks from it). */
    private var lastTake: Vec3? = null

    init {
        on<TickEvent> { tick() }
    }

    override fun onEnable() {
        lastTake = null
    }

    private fun tick() {
        val p = mc.player ?: return
        val menu = p.containerMenu as? ChestMenu
        if (mc.gui.screen() !is AbstractContainerScreen<*> || menu == null) {
            action = null
            done = false
            moved = 0
            return
        }
        if (done || !menu.carried.isEmpty) return
        if (wait > 0) {
            wait--
            return
        }
        val chest = 0 until menu.rowCount * 9
        val inventory = menu.slots.indices.filter { i ->
            val s = menu.slots[i]
            s.container is Inventory && (hotbar.value || s.containerSlot >= 9)
        }
        val act = action ?: decide(p.position(), menu, inventory).also { action = it }
        when (act) {
            Action.TAKE -> {
                val from = chest.firstOrNull { shulker(menu.getSlot(it).item) }
                val to = inventory.firstOrNull { !menu.getSlot(it).hasItem() }
                if (from == null || to == null) return finish("Took $moved shulkers" + if (to == null) " (inventory full)" else "")
                if (hotbar.value) {
                    Slots.click(from, 0, ContainerInput.QUICK_MOVE)
                } else {
                    // Shift-clicking would fill the hotbar first: pick it up and put it in a free slot above.
                    Slots.click(from)
                    Slots.click(to)
                }
                moved++
                lastTake = p.position()
            }
            Action.DUMP -> {
                val from = inventory.firstOrNull { shulker(menu.getSlot(it).item) }
                val room = chest.any { !menu.getSlot(it).hasItem() }
                if (from == null || !room) return finish("Dumped $moved shulkers" + if (from != null) " (chest full)" else "")
                Slots.click(from, 0, ContainerInput.QUICK_MOVE)
                moved++
            }
        }
        wait = delay.value - 1
    }

    private fun decide(pos: Vec3, menu: ChestMenu, inventory: List<Int>): Action = when (mode.value) {
        Mode.TAKE -> Action.TAKE
        Mode.DUMP -> Action.DUMP
        Mode.AUTO -> {
            val carrying = inventory.any { shulker(menu.getSlot(it).item) }
            val full = inventory.none { !menu.getSlot(it).hasItem() }
            // Without a place they were taken from (just switched on), a full inventory means they're to go.
            val far = lastTake?.let { it.distanceTo(pos) > distance.value } ?: full
            if (carrying && far) Action.DUMP else Action.TAKE
        }
    }

    private fun finish(text: String) {
        done = true
        if (moved > 0) moduleMessage(name.trim(), text)
        if (close.value && moved > 0) mc.player?.closeContainer()
    }

    private fun shulker(stack: ItemStack): Boolean = (stack.item as? BlockItem)?.block is ShulkerBoxBlock
}
