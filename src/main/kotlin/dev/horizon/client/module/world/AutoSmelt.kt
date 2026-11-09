package dev.horizon.client.module.world

import dev.horizon.client.combat.Slots
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractFurnaceMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * When you open a furnace (or smoker, blast furnace), takes out what's done, tops the input up from
 * your inventory and the fuel from [fuel], then closes it with [close]. Stash: smelts what the Dupe Stash still needs
 * ([StashPlan]: cobblestone into stone, then the deepslate and stone bricks to crack), counting what it takes out;
 * Litematica: what furnaces must make for your Litematica placement's missing blocks ([CraftPlanner.shortfall]: the
 * cracked bricks, the stone for stone bricks), less what's already smelting, recounted each time a furnace opens;
 * Items: anything in [items]. Inputs go in a stack at a time, so a job can end up to a stack over. One click every
 * [delay] ticks.
 */
class AutoSmelt : Module("auto-smelt", Categories.WORLD) {
    enum class Mode { STASH, LITEMATICA, ITEMS }

    val mode = enum("mode", Mode.STASH)
    val items = items("items", arrayListOf(Items.COBBLESTONE)) { mode.value == Mode.ITEMS }
    val fuel = items("fuel", arrayListOf(Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK, Items.LAVA_BUCKET, Items.DRIED_KELP_BLOCK, Items.BLAZE_ROD))
    val delay = int("delay", 2, 1..10)
    val close = bool("close", true)

    private var wait = 0
    private var done = false
    private var started = false

    /** Litematica: outputs put in furnaces and not taken out yet (this session), so two furnaces don't double up. */
    private val queued = HashMap<Item, Int>()

    /** Litematica: input → the output it's needed for, worked out once per click. */
    private var inputs: Map<Item, Item> = emptyMap()

    /** Inputs this furnace didn't take (not smeltable in it), and the last one tried with the input count then. */
    private val rejected = HashSet<Item>()
    private var tried: Item? = null
    private var triedCount = 0

    init {
        on<TickEvent> { tick() }
    }

    private fun tick() {
        val p = mc.player ?: return
        val menu = p.containerMenu as? AbstractFurnaceMenu
        if (mc.gui.screen() !is AbstractFurnaceScreen<*> || menu == null) {
            done = false
            started = false
            rejected.clear()
            tried = null
            return
        }
        if (done || !menu.carried.isEmpty) return
        if (wait > 0) {
            wait--
            return
        }
        if (!started) {
            started = true
            // Litematica recounts the build over the next ticks.
            if (mode.value == Mode.LITEMATICA) {
                LitematicaMaterials.refresh()
                wait = 10
                return
            }
        }
        wait = delay.value - 1
        if (mode.value == Mode.LITEMATICA) {
            val found = litematicaInputs()
            if (found == null) {
                done = true
                moduleMessage(name.trim(), "Litematica mode needs Litematica with a placement selected")
                return
            }
            inputs = found
        }
        val input = menu.getSlot(0).item
        tried?.let { if (input.count <= triedCount) rejected += it }
        tried = null
        val out = menu.getSlot(2).item
        if (!out.isEmpty) {
            if (mode.value == Mode.STASH) StashPlan.addSmelted(StashPlan.id(out.item), out.count)
            queued[out.item] = ((queued[out.item] ?: 0) - out.count).coerceAtLeast(0)
            Slots.click(2, 0, ContainerInput.QUICK_MOVE)
            return
        }
        if (input.isEmpty || input.count < input.maxStackSize) {
            val slot = inventorySlot(menu) { wanted(it.item) && (input.isEmpty || ItemStack.isSameItemSameComponents(it, input)) }
            if (slot != null) {
                val stack = menu.getSlot(slot).item
                tried = stack.item
                triedCount = input.count
                inputs[stack.item]?.let { queued.merge(it, minOf(stack.count, stack.maxStackSize - input.count), Int::plus) }
                Slots.click(slot, 0, ContainerInput.QUICK_MOVE)
                return
            }
        }
        val f = menu.getSlot(1).item
        if (!menu.getSlot(0).item.isEmpty && (f.isEmpty || f.count < f.maxStackSize)) {
            val slot = inventorySlot(menu) { it.item in fuel.value && (f.isEmpty || ItemStack.isSameItemSameComponents(it, f)) }
            if (slot != null) {
                Slots.click(slot, 0, ContainerInput.QUICK_MOVE)
                return
            }
        }
        done = true
        if (close.value) p.closeContainer()
    }

    private fun wanted(item: Item): Boolean {
        if (item in rejected) return false
        return when (mode.value) {
            Mode.ITEMS -> item in items.value
            Mode.LITEMATICA -> item in inputs
            Mode.STASH -> StashPlan.smelt.any { (input, output, _) -> StashPlan.item(input) == item && StashPlan.remaining(output) > 0 }
        }
    }

    /**
     * Litematica: each input that smelts into something the build still lacks after crafting (less what's already
     * smelting), with that output; null without Litematica or a placement.
     */
    private fun litematicaInputs(): Map<Item, Item>? {
        val missing = LitematicaMaterials.missing() ?: return null
        val inv = CraftPlanner.counts()
        val book = CraftPlanner.recipes()
        val smelting = CraftPlanner.smelting()
        val targets = missing.map { (item, n) -> item to n - (inv[item] ?: 0) }.filter { it.second > 0 }
        val out = HashMap<Item, Item>()
        for ((output, n) in CraftPlanner.shortfall(targets, inv, book)) {
            if (n - (queued[output] ?: 0) <= 0) continue
            for (input in smelting[output] ?: continue) out.putIfAbsent(input, output)
        }
        return out
    }

    /** The menu slot of the first stack of your inventory (hotbar included) that [test] accepts. */
    private fun inventorySlot(menu: AbstractFurnaceMenu, test: (ItemStack) -> Boolean): Int? =
        menu.slots.indices.firstOrNull { i ->
            val s = menu.slots[i]
            s.container is Inventory && s.hasItem() && test(s.item)
        }
}
