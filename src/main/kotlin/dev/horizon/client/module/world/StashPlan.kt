package dev.horizon.client.module.world

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import java.nio.file.Files

/**
 * What the Dupe Stash schematic needs crafted and smelted (Auto Craft's and Auto Smelt's Stash mode)
 * and how much of it they've made so far, kept in `horizon/stash-progress.json` across restarts.
 */
object StashPlan {
    /**
     * Blocks to craft, by item id, in the order they're worked on (the top of each chain first, so raw materials go to
     * the rarer blocks): what's placed, plus the 62 deepslate bricks and 12 stone bricks Auto Smelt cracks.
     */
    val craft = linkedMapOf(
        "chest" to 120, "lantern" to 16, "chiseled_deepslate" to 56, "deepslate_tiles" to 48, "deepslate_brick_slab" to 2,
        "deepslate_bricks" to 588, "polished_deepslate" to 830, "stone_brick_slab" to 80, "stone_bricks" to 584,
    )

    /** Smelting jobs: input id, output id, how many outputs (stone for all the stone bricks and slabs). */
    val smelt = listOf(
        Triple("cobblestone", "stone", 628),
        Triple("deepslate_bricks", "cracked_deepslate_bricks", 62),
        Triple("stone_bricks", "cracked_stone_bricks", 12),
    )

    private val file get() = Storage.root.resolve("stash-progress.json")
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val made: HashMap<String, Int> by lazy {
        try {
            if (Files.exists(file)) HashMap(Storage.json.decodeFromString(serializer, Files.readString(file))) else HashMap()
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't read {}", file, e)
            HashMap()
        }
    }

    fun item(id: String): Item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id))

    fun id(item: Item): String = BuiltInRegistries.ITEM.getKey(item).path

    /** How many more of a crafted block ([craft]) or smelted output ([smelt], by output id) are needed. */
    fun remaining(id: String): Int {
        craft[id]?.let { return (it - (made[id] ?: 0)).coerceAtLeast(0) }
        val total = smelt.firstOrNull { it.second == id }?.third ?: return 0
        return (total - (made["smelted:$id"] ?: 0)).coerceAtLeast(0)
    }

    /** Counts [n] more of a crafted block [id] made (negative: used up crafting something else). */
    fun addCrafted(id: String, n: Int) {
        if (n != 0 && id in craft) add(id, n)
    }

    /** Counts [n] more of a smelted output [id] taken out of a furnace. */
    fun addSmelted(id: String, n: Int) {
        if (n != 0 && smelt.any { it.second == id }) add("smelted:$id", n)
    }

    private fun add(key: String, n: Int) {
        made[key] = (made[key] ?: 0) + n
        save()
    }

    fun reset() {
        made.clear()
        save()
    }

    /** What's still to do, for chat: "chest 40, polished_deepslate 120, ...". */
    fun summary(): String {
        val left = (craft.keys + smelt.map { it.second }).map { it to remaining(it) }.filter { it.second > 0 }
        return if (left.isEmpty()) "everything is made" else left.joinToString(", ") { "${it.first} ${it.second}" }
    }

    private fun save() {
        try {
            Files.writeString(file, Storage.json.encodeToString(serializer, made))
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't save {}", file, e)
        }
    }
}
