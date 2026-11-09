package dev.horizon.client.module.world

import dev.horizon.client.combat.Slots
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.world.CraftPlanner.Step
import net.minecraft.client.gui.screens.inventory.CraftingScreen
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.item.Item

/**
 * At an open crafting table, crafts everything it can from your inventory, working through whole
 * chains (logs → planks → chests; cobbled deepslate → polished → bricks → tiles; [CraftPlanner]). Stash: the Dupe
 * Stash's blocks, up to what it still needs ([StashPlan], progress kept across trips); Litematica: what your Litematica
 * placement's material list says is missing, less what you carry, plus what furnaces need to make the missing smelted
 * blocks (the bricks for cracked bricks), recounted each time the table opens; Items: as many of [items] as possible.
 * It crafts the way a player does with the recipe book: places a recipe (one set per click, or a full grid when that
 * isn't too many) and shift-clicks the result, one action every [delay] ticks, so recipe-spam limits (Paper's) aren't
 * hit. Only recipes your recipe book has unlocked can be used.
 */
class AutoCraft : Module("auto-craft", Categories.WORLD) {
    enum class Mode { STASH, LITEMATICA, ITEMS }

    val mode = enum("mode", Mode.STASH)
    val items = items("items") { mode.value == Mode.ITEMS }
    val delay = int("delay", 2, 1..10)
    val close = bool("close", false)
    val resetProgress = action("reset-progress", visible = { mode.value == Mode.STASH }) {
        StashPlan.reset()
        moduleMessage(name.trim(), "Stash progress reset")
    }

    private enum class Phase { START, PLAN, PLACE, TAKE, SETTLE, DONE }

    private var phase = Phase.START
    private var wait = 0
    private var step: Step? = null
    private var placesLeft = 0
    private var timeout = 0
    private var before: Map<Item, Int> = emptyMap()
    private var crafted = 0

    init {
        on<TickEvent> { tick() }
    }

    override fun onDisable() {
        phase = Phase.START
    }

    private fun tick() {
        val p = mc.player ?: return
        val menu = p.containerMenu as? CraftingMenu
        if (mc.gui.screen() !is CraftingScreen || menu == null) {
            phase = Phase.START
            crafted = 0
            return
        }
        if (phase == Phase.DONE) return
        if (wait > 0) {
            wait--
            return
        }
        when (phase) {
            Phase.START -> {
                // Litematica recounts the build over the next ticks.
                if (mode.value == Mode.LITEMATICA) {
                    LitematicaMaterials.refresh()
                    wait = 10
                }
                phase = Phase.PLAN
            }
            Phase.PLAN -> {
                val targets = targets() ?: return finish("Litematica mode needs Litematica with a placement selected")
                val book = CraftPlanner.recipes()
                val inv = CraftPlanner.counts()
                val next = targets.firstNotNullOfOrNull { (item, want) ->
                    if (want > 0) CraftPlanner.maxPlan(item, want, inv, book).firstOrNull() else null
                }
                if (next == null) {
                    finish(if (crafted > 0) "Crafted $crafted items" else "Nothing to craft from your inventory", targets)
                    return
                }
                step = next
                before = inv
                // A full grid when that's no more than what's needed (Items: always), else one set per click.
                placesLeft = if (mode.value == Mode.ITEMS || next.sets >= CraftPlanner.fullGrid(next.recipe, inv)) -1 else next.sets
                phase = Phase.PLACE
            }
            Phase.PLACE -> {
                val s = step ?: return
                mc.connection?.send(ServerboundPlaceRecipePacket(menu.containerId, s.recipe.entry.id(), placesLeft == -1))
                if (placesLeft > 0) placesLeft--
                if (placesLeft <= 0) {
                    phase = Phase.TAKE
                    timeout = 20
                }
                wait = delay.value - 1
            }
            Phase.TAKE -> {
                if (!menu.getSlot(0).hasItem()) {
                    if (--timeout <= 0) finish("Couldn't fill the grid for ${StashPlan.id(step!!.recipe.result)}")
                    return
                }
                Slots.click(0, 0, ContainerInput.QUICK_MOVE)
                phase = Phase.SETTLE
                wait = delay.value
            }
            Phase.SETTLE -> {
                if (menu.getSlot(0).hasItem()) {
                    finish("Your inventory is full")
                    return
                }
                val after = CraftPlanner.counts()
                if (mode.value == Mode.STASH) {
                    for (item in before.keys + after.keys) StashPlan.addCrafted(StashPlan.id(item), (after[item] ?: 0) - (before[item] ?: 0))
                }
                val made = step!!.recipe.result
                crafted += ((after[made] ?: 0) - (before[made] ?: 0)).coerceAtLeast(0)
                phase = Phase.PLAN
            }
            Phase.DONE -> {}
        }
    }

    /** What to make: each item and how many more (Litematica: missing less carried; null without Litematica). */
    private fun targets(): List<Pair<Item, Int>>? = when (mode.value) {
        Mode.STASH -> StashPlan.craft.keys.map { StashPlan.item(it) to StashPlan.remaining(it) }
        Mode.ITEMS -> items.value.map { it to 36 * 64 }
        Mode.LITEMATICA -> {
            val missing = LitematicaMaterials.missing()
            if (missing == null) {
                null
            } else {
                val inv = CraftPlanner.counts()
                val book = CraftPlanner.recipes()
                val targets = LinkedHashMap<Item, Int>()
                for ((item, n) in missing) targets[item] = n - (inv[item] ?: 0)
                // Smelted blocks need their furnace input: craft that too (the deepslate bricks for cracked ones).
                val smelting = CraftPlanner.smelting()
                val short = CraftPlanner.shortfall(targets.filter { it.value > 0 }.toList(), inv, book)
                for ((out, n) in short) {
                    val input = smelting[out]?.firstOrNull { book.containsKey(it) } ?: continue
                    targets[input] = (targets[input] ?: -(inv[input] ?: 0)) + n
                }
                targets.toList()
            }
        }
    }

    private fun finish(text: String, targets: List<Pair<Item, Int>>? = null) {
        phase = Phase.DONE
        val left = when (mode.value) {
            Mode.STASH -> ". Stash still needs: " + StashPlan.summary()
            Mode.LITEMATICA -> CraftPlanner.recipes().let { book -> targets?.filter { it.second > 0 && book.containsKey(it.first) } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { t -> ". Still to make: " + t.joinToString(", ") { "${StashPlan.id(it.first)} ${it.second}" } } ?: ""
            Mode.ITEMS -> ""
        }
        moduleMessage(name.trim(), text + left)
        if (close.value) mc.player?.closeContainer()
    }
}
