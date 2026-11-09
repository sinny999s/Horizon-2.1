package dev.horizon.client.module.world

import dev.horizon.client.HorizonClient
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay
import net.minecraft.world.item.crafting.display.SlotDisplayContext

/**
 * Auto Craft's and Auto Smelt's planning, from the recipes in your recipe book. A target is planned
 * down to what the inventory has: missing ingredients are crafted first (their own ingredients before them), and what
 * can't be crafted or found is a shortfall, which furnaces may make.
 */
object CraftPlanner {
    private val mc get() = Minecraft.getInstance()

    class Recipe(val entry: RecipeDisplayEntry, val result: Item, val count: Int, val inputs: List<Pair<List<Item>, Int>>)
    class Step(val recipe: Recipe, val sets: Int)

    /** The crafting recipes in your recipe book, by result. */
    fun recipes(): Map<Item, List<Recipe>> {
        val ctx = SlotDisplayContext.fromLevel(mc.level!!)
        val out = HashMap<Item, MutableList<Recipe>>()
        for (collection in mc.player!!.recipeBook.collections) for (e in collection.recipes) {
            val d = e.display()
            if (d !is ShapedCraftingRecipeDisplay && d !is ShapelessCraftingRecipeDisplay) continue
            val req = e.craftingRequirements().orElse(null) ?: continue
            val result = e.resultItems(ctx).firstOrNull { !it.isEmpty } ?: continue
            val groups = LinkedHashMap<Ingredient, Int>()
            for (i in req) groups.merge(i, 1, Int::plus)
            out.getOrPut(result.item) { ArrayList() } +=
                Recipe(e, result.item, result.count, groups.map { (i, n) -> i.items().map { it.value() }.toList() to n })
        }
        return out
    }

    /** The furnace (smoker, blast furnace) recipes in your recipe book: output → the items that smelt into it. */
    fun smelting(): Map<Item, List<Item>> {
        val ctx = SlotDisplayContext.fromLevel(mc.level!!)
        val out = HashMap<Item, MutableList<Item>>()
        for (collection in mc.player!!.recipeBook.collections) for (e in collection.recipes) {
            val d = e.display() as? FurnaceRecipeDisplay ?: continue
            val result = d.result().resolveForFirstStack(ctx)
            if (result.isEmpty) continue
            val list = out.getOrPut(result.item) { ArrayList() }
            for (s in d.ingredient().resolveForStacks(ctx)) if (!s.isEmpty && s.item !in list) list += s.item
        }
        return out
    }

    /** What the inventory holds (hotbar included), by item. */
    fun counts(): HashMap<Item, Int> {
        val inv = mc.player!!.inventory
        val out = HashMap<Item, Int>()
        for (i in 0 until 36) {
            val s = inv.getItem(i)
            if (!s.isEmpty) out.merge(s.item, s.count, Int::plus)
        }
        return out
    }

    /** The sets a full-grid placement of [r] would put in: what [inv] has of each ingredient, a stack at most. */
    fun fullGrid(r: Recipe, inv: Map<Item, Int>): Int =
        r.inputs.minOf { (accepted, per) -> accepted.sumOf { inv[it] ?: 0 } / per }.coerceAtMost(64)

    /** The steps for as many new [target] as possible, up to [want] (binary search over what the inventory allows). */
    fun maxPlan(target: Item, want: Int, inv: Map<Item, Int>, book: Map<Item, List<Recipe>>): List<Step> {
        var lo = 0
        var hi = want
        var best: List<Step> = emptyList()
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val i = HashMap(inv)
            i[target] = 0 // new ones: what's already carried isn't counted
            val s = ArrayList<Step>()
            if (produce(target, mid, i, s, book, emptySet(), null)) {
                lo = mid
                best = s
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /**
     * What making [targets] (new ones, on top of what's carried) would still lack after the inventory and all the
     * crafting: the raw materials, and what furnaces make.
     */
    fun shortfall(targets: List<Pair<Item, Int>>, inv: Map<Item, Int>, book: Map<Item, List<Recipe>>): Map<Item, Int> {
        val short = HashMap<Item, Int>()
        val i = HashMap(inv)
        for ((target, want) in targets) {
            if (want <= 0) continue
            i[target] = 0
            produce(target, want, i, ArrayList(), book, emptySet(), short)
        }
        return short
    }

    /**
     * Takes [n] of [item] from [inv], crafting what's missing (its ingredients first). Without a way to get it: false,
     * or with [short], counted there as lacking and taken as found.
     */
    private fun produce(item: Item, n: Int, inv: HashMap<Item, Int>, steps: MutableList<Step>, book: Map<Item, List<Recipe>>,
                        chain: Set<Item>, short: HashMap<Item, Int>?): Boolean {
        val have = inv[item] ?: 0
        val take = minOf(have, n)
        inv[item] = have - take
        val missing = n - take
        if (missing == 0) return true
        val recipes = book[item]
        if (recipes == null || item in chain || chain.size > 6) {
            if (short == null) return false
            short.merge(item, missing, Int::plus)
            return true
        }
        for (r in recipes) {
            val tryInv = HashMap(inv)
            val trySteps = ArrayList(steps)
            val sets = (missing + r.count - 1) / r.count
            if (r.inputs.all { (accepted, per) -> consume(accepted, sets * per, tryInv, trySteps, book, chain + item, short) }) {
                tryInv[item] = (tryInv[item] ?: 0) + sets * r.count - missing
                trySteps += Step(r, sets)
                inv.clear()
                inv.putAll(tryInv)
                steps.clear()
                steps.addAll(trySteps)
                return true
            }
        }
        return false
    }

    /** [n] of any mix of [accepted] (one ingredient) from [inv], crafting the rest from the first that can be made. */
    private fun consume(accepted: List<Item>, n: Int, inv: HashMap<Item, Int>, steps: MutableList<Step>, book: Map<Item, List<Recipe>>,
                        chain: Set<Item>, short: HashMap<Item, Int>?): Boolean {
        var need = n
        for (it in accepted.sortedByDescending { inv[it] ?: 0 }) {
            val use = minOf(inv[it] ?: 0, need)
            inv[it] = (inv[it] ?: 0) - use
            need -= use
            if (need == 0) return true
        }
        // Prefer an accepted item that has a recipe (planks from logs), so a shortfall names the raw material.
        for (it in accepted.sortedByDescending { book.containsKey(it) }) {
            val tryInv = HashMap(inv)
            val trySteps = ArrayList(steps)
            if (produce(it, need, tryInv, trySteps, book, chain, null)) {
                inv.clear()
                inv.putAll(tryInv)
                steps.clear()
                steps.addAll(trySteps)
                return true
            }
        }
        if (short == null) return false
        val first = accepted.firstOrNull { book.containsKey(it) } ?: accepted.firstOrNull() ?: return false
        return produce(first, need, inv, steps, book, chain, short)
    }
}

/**
 * Litematica's material list, read by reflection (Litematica is optional): the list Litematica has
 * open or last opened, else a new one for the selected placement.
 */
object LitematicaMaterials {
    private val loaded by lazy { FabricLoader.getInstance().isModLoaded("litematica") }

    private fun list(): Any? {
        val dm = Class.forName("fi.dy.masa.litematica.data.DataManager")
        dm.getMethod("getMaterialList").invoke(null)?.let { return it }
        val manager = dm.getMethod("getSchematicPlacementManager").invoke(null)
        val placement = manager.javaClass.getMethod("getSelectedSchematicPlacement").invoke(manager) ?: return null
        val list = Class.forName("fi.dy.masa.litematica.materials.MaterialListPlacement")
            .getConstructor(Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement"), Boolean::class.javaPrimitiveType)
            .newInstance(placement, true)
        dm.getMethod("setMaterialList", Class.forName("fi.dy.masa.litematica.materials.MaterialListBase")).invoke(null, list)
        return list
    }

    /** Starts a recount of the list's blocks in the world; Litematica fills the new counts in over the next ticks. */
    fun refresh() {
        if (!loaded) return
        try {
            val list = list() ?: return
            list.javaClass.getMethod("reCreateMaterialList").invoke(list)
        } catch (t: Throwable) {
            HorizonClient.LOG.warn("Couldn't refresh Litematica's material list", t)
        }
    }

    /** The blocks still missing from the build, by item; null without Litematica or a placement. */
    fun missing(): Map<Item, Int>? {
        if (!loaded) return null
        return try {
            val list = list() ?: return null
            val out = LinkedHashMap<Item, Int>()
            for (e in list.javaClass.getMethod("getMaterialsAll").invoke(list) as List<*>) {
                e ?: continue
                val stack = e.javaClass.getMethod("getStack").invoke(e) as ItemStack
                val n = (e.javaClass.getMethod("getCountMissing").invoke(e) as Number).toInt()
                if (n > 0 && !stack.isEmpty) out.merge(stack.item, n, Int::plus)
            }
            out
        } catch (t: Throwable) {
            HorizonClient.LOG.warn("Couldn't read Litematica's material list", t)
            null
        }
    }
}
