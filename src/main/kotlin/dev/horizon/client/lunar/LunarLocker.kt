package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import java.nio.file.Files

/**
 * Not in the original: the cosmetics you wear yourself, chosen in the locker (`gui/modern/LunarLockerScreen`), one per
 * kind as Lunar's locker allows. They're drawn on your own player by this client. Ones your Lunar account owns (from
 * Lunar's Login answer, [owned]) also go on your Lunar outfit ([LunarAccount]), so other Lunar players see them; the
 * rest only you see. Kept in Storage's `lunar-cosmetics/locker.json`.
 */
object LunarLocker {
    /** A locker tab: its label, icon and the index types it lists. */
    class Tab(val label: String, val icon: String, val types: Set<String>)

    val tabs = listOf(
        Tab("Cloaks", "cosmetic", setOf("cloak")),
        Tab("Hats", "player", setOf("hat")),
        Tab("Face", "eye", setOf("glasses", "mask", "bandanna")),
        Tab("Wings", "wings", setOf("dragon_wings")),
        Tab("Back", "box", setOf("backpack")),
        Tab("Body", "users", setOf("bodywear", "neckwear", "belts", "suits", "shoes", "wristwear", "shields")),
        Tab("Pets", "fav", setOf("pet", "companion")),
        Tab("Auras", "theme", setOf("auras")),
    )

    /** What a cosmetic is for you: owned by your Lunar account (others on Lunar can see it once equipped in Lunar), or client-side only. */
    enum class Kind { OWNED, CLIENT_SIDE, UNKNOWN }

    /** Equipped cosmetic ids by kind (the index type). */
    private val equipped = LinkedHashMap<String, Int>()
    private var loaded = false

    @Volatile
    private var ownedIds: Set<Int> = emptySet()

    @Volatile
    private var ownsAll = false

    @Volatile
    private var ownershipKnown = false

    /** The cosmetic being tried on (the locker's hovered tile), shown instead of what's equipped of its kind. */
    @Volatile
    var preview: Int? = null

    private val file get() = Storage.dir("lunar-cosmetics").resolve("locker.json")

    /** From Lunar's Login answer (any thread). */
    fun owned(ids: Set<Int>, all: Boolean) {
        ownedIds = ids
        ownsAll = all
        ownershipKnown = true
        HorizonClient.LOG.info("Lunar cosmetics: your Lunar account owns {} cosmetics{}", ids.size, if (all) " (all of them)" else "")
    }

    val signedIn get() = ownershipKnown
    val ownedCount get() = if (ownsAll) -1 else ownedIds.size

    fun kind(id: Int): Kind = when {
        !ownershipKnown -> Kind.UNKNOWN
        ownsAll || id in ownedIds -> Kind.OWNED
        else -> Kind.CLIENT_SIDE
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!Files.exists(file)) return
            val index = LunarAssets.index
            for (el in JsonParser.parseString(Files.readString(file)).asJsonArray) {
                val id = el.asInt
                // The kind is known once the index is in; until then each id keeps its own slot.
                equipped[index?.get(id)?.type ?: "id:$id"] = id
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Lunar locker: couldn't read {}: {}", file, e.toString())
        }
    }

    private fun save() {
        try {
            val a = JsonArray()
            for (id in equipped.values) a.add(id)
            Files.writeString(file, a.toString())
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Lunar locker: couldn't save: {}", e.toString())
        }
    }

    /** The equipped ids, in the order they were put on. */
    fun equippedIds(): List<Int> {
        load()
        rekey()
        return equipped.values.toList()
    }

    /** Ids loaded before the index was in get their real kind once it is. */
    private fun rekey() {
        val index = LunarAssets.index ?: return
        if (equipped.keys.none { it.startsWith("id:") }) return
        val old = equipped.values.toList()
        equipped.clear()
        for (id in old) equipped[index[id]?.type ?: "id:$id"] = id
    }

    fun isEquipped(id: Int) = equippedIds().contains(id)

    /** Puts [id] on (replacing what you wore of its kind), or takes it off if it's on. */
    fun toggle(id: Int) {
        load()
        rekey()
        val type = LunarAssets.index?.get(id)?.type ?: return
        val on = equipped[type] != id
        if (on) equipped[type] = id else equipped.remove(type)
        save()
        // An owned one goes on (or off) your Lunar outfit too.
        LunarAccount.sync(id, on)
    }

    /** Takes everything off (owned ones off your Lunar outfit too). */
    fun clear() {
        load()
        val was = equipped.values.toList()
        equipped.clear()
        save()
        for (id in was) LunarAccount.sync(id, false)
    }

    /** What you wear (the equipped set with the tried-on cosmetic), or null with nothing chosen. */
    fun wearing(): List<LunarWire.Equipped>? {
        val ids = LinkedHashMap<String, Int>()
        load()
        rekey()
        ids.putAll(equipped)
        preview?.let { p -> LunarAssets.index?.get(p)?.let { ids[it.type] = p } }
        if (ids.isEmpty()) return null
        return ids.values.map { LunarWire.Equipped(it) }
    }

    /** The cosmetics a tab lists, filtered by [search] (name, case-insensitive), newest first as in Lunar's index. */
    fun list(tab: Tab, search: String): List<LunarCosmeticIndex.Entry> {
        val index = LunarAssets.index ?: return emptyList()
        val q = search.trim().lowercase()
        // Not ones whose files aren't on Lunar's CDN (some index lines name models that were never released).
        return index.values.filter { it.type in tab.types && (q.isEmpty() || q in it.name.lowercase()) && (!it.gek || LunarAssets.has(it.assetPath)) }
            .sortedByDescending { it.id }
    }
}
