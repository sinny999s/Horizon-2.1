package dev.horizon.client.lunar

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Not in the original: the files that describe Lunar's cosmetics, read the way Lunar Client reads them (all on Lunar's
 * public texture CDN, no sign-in needed). Game-free.
 * - The asset index (the launcher API's `textures.jitIndexUrl`): one `path sha1 size mtime` line per file; a file is
 *   fetched as `baseUrl + sha1`.
 * - `assets/lunar/cosmetics/index`: one CSV line per cosmetic, `id,…,name,…,type,legacy model,path,is gek,categories,
 *   colours`. A cloak's path is its picture; a GeckoLib cosmetic's path is its `.gek.json`; the rest are Lunar's older
 *   models (an `.obj` per legacy model name, placed by `indexes/hats.json`/`bodywear.json`), which aren't drawn here.
 * - A `.gek.json`: the cosmetic's type, the body part it rides on, its Bedrock geometry, texture and animations.
 * - A texture's `.mcmeta`: `{"animation": {"frametime", "width", "height"}}` for an animated one (frames in a grid).
 */
object LunarCosmeticIndex {
    /** One line of the cosmetics index. */
    data class Entry(val id: Int, val name: String, val type: String, val legacyModel: String, val path: String, val gek: Boolean,
                     /** The particle effect it emits while worn (second column), or "". */
                     val particle: String = "",
                     /** Lunar's style tags ("FIRE", "SPACE"...) and colour tags ("RED", "CHROMA"...), as the locker filters by. */
                     val categories: Set<String> = emptySet(), val colors: Set<String> = emptySet()) {
        /** The path in the asset index ("assets/lunar/cosmetics/..."). */
        val assetPath get() = asset(path)
        val isCloak get() = type == "cloak" && !gek && path.endsWith(".webp")
    }

    /** A `.gek.json`. [model], [texture] and [animation] are asset index paths. */
    data class Gek(
        val type: String, val attachedBone: String, val model: String?, val texture: String?, val animation: String?,
        val translucent: Boolean, val emissive: String?,
        /** The model for slim-armed skins, when the cosmetic has one (its `model` is then `{path: molang condition}`). */
        val slimModel: String? = null,
    )

    /** An animated texture: each frame [width]×[height] (texture pixels), shown [frametime] ticks. */
    data class Animation(val frametime: Int, val width: Int, val height: Int)

    /** The cosmetics index; lines that don't parse are skipped. */
    /**
     * `assets/lunar/cosmetics.json`: every cosmetic Lunar sells, as Lunar Client's locker lists them. It's kept up to
     * date where the CSV index stopped (January 2026: ~800 newer cosmetics, "Lunar Purple" among them, are only here).
     * Its fields map onto the index's columns: category (type), indexType (legacy model), resource (path), geckolibCosmetic,
     * morph (the particle), tags and colors. Companions (category "companion") were never in the index.
     */
    fun parseCosmetics(json: String): Map<Int, Entry> {
        val out = HashMap<Int, Entry>()
        val arr = com.google.gson.JsonParser.parseString(json) as? com.google.gson.JsonArray ?: return out
        fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        fun tags(o: JsonObject, k: String) = (o.get(k) as? com.google.gson.JsonArray)?.mapNotNull { e ->
            e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        }?.toSet() ?: emptySet()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            val res = str(o, "resource").takeIf { it.isNotEmpty() } ?: continue
            val category = str(o, "category")
            val name = str(o, "name").ifEmpty { "#$id" }
            out[id] = if (category == "companion") Entry(id, name, "companion", "NONE", res.removePrefix("lunar:"), true)
            else Entry(id, name, category, str(o, "indexType").ifEmpty { "NONE" }, res.removePrefix("lunar:"),
                o.get("geckolibCosmetic")?.takeIf { it.isJsonPrimitive }?.asBoolean == true, str(o, "morph"), tags(o, "tags"), tags(o, "colors"))
        }
        return out
    }

    /** cosmetics.json's companions alone (see [parseCosmetics]). */
    fun parseCompanions(json: String): Map<Int, Entry> = parseCosmetics(json).filterValues { it.type == "companion" }

    fun parseIndex(text: String): Map<Int, Entry> {
        val out = HashMap<Int, Entry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val p = line.split(',')
            // Read the fixed columns from both ends, so a comma in a name can't shift them.
            if (p.size < 12) continue
            val id = p[0].toIntOrNull() ?: continue
            val n = p.size
            val name = p.subList(3, n - 8).joinToString(",")
            fun tags(s: String) = s.split('|').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            out[id] = Entry(id, name, p[n - 6], p[n - 5], p[n - 4], p[n - 3].equals("true", true), p[1].trim(), tags(p[n - 2]), tags(p[n - 1]))
        }
        return out
    }

    /** The asset index: path to sha1. */
    fun parseAssets(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val p = line.trim().split(' ')
            if (p.size >= 2 && p[1].length == 40) out[p[0]] = p[1]
        }
        return out
    }

    /** An asset index path for a cosmetics index path ("cosmetics/...") or a resource ("lunar:cosmetics/..."). */
    fun asset(ref: String): String = when {
        ref.startsWith("assets/") -> ref
        ref.startsWith("lunar:") -> "assets/lunar/" + ref.removePrefix("lunar:")
        else -> "assets/lunar/$ref"
    }

    fun parseGek(json: String): Gek {
        val o = JsonParser.parseString(json).asJsonObject
        fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        // A model per arm width: {"<wide>.geo.json": "!q.is_slim_model", "<slim>.geo.json": "q.is_slim_model"}.
        var model = str("model")?.let(::asset)
        var slim: String? = null
        (o.get("model") as? JsonObject)?.let { m ->
            for ((path, cond) in m.entrySet()) {
                val c = if (cond.isJsonPrimitive) cond.asString.replace(" ", "") else ""
                val forSlim = c.contains("is_slim_model") && !c.startsWith("!")
                if (forSlim) slim = asset(path) else if (model == null) model = asset(path)
            }
            if (model == null) model = slim
        }
        return Gek(
            str("type") ?: "", str("attached_bone") ?: "NONE", model, str("texture")?.let(::asset),
            str("animation")?.let(::asset), o.get("is_translucent")?.asBoolean == true, str("emissive_texture")?.let(::asset), slim,
        )
    }

    /** An `.mcmeta`'s animation, or null when it has none. */
    fun parseMcmeta(json: String): Animation? {
        val a = (JsonParser.parseString(json) as? JsonObject)?.getAsJsonObject("animation") ?: return null
        val w = a.get("width")?.asInt ?: return null
        val h = a.get("height")?.asInt ?: return null
        if (w <= 0 || h <= 0) return null
        return Animation(maxOf(1, a.get("frametime")?.asInt ?: 1), w, h)
    }
}
