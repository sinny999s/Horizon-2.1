package dev.horizon.client.lunar

/**
 * The locker's filters (`gui/modern/LunarLockerScreen`), apart from the screen so they can be
 * tested. What's listed: everything, what your Lunar account owns, or what you've picked; one of Lunar's colour tags and
 * one of its style tags (the index's last two columns); newest first (Lunar's ids count up) or by name. The search box
 * matches names and style tags ("fire" finds the Fire cloaks). Game-free.
 */
object LockerFilter {
    enum class Show(val label: String) { ALL("All"), OWNED("Owned"), PICKED("Picked") }
    enum class Sort(val label: String) { NEWEST("Newest"), NAME("A-Z") }

    /** The colour tags in the order the panel shows them, with a swatch colour each (CHROMA is drawn as a gradient). */
    val COLORS = linkedMapOf(
        "WHITE" to 0xFFF2F2F2.toInt(), "SILVER" to 0xFFC0C4CC.toInt(), "GREY" to 0xFF8B8D98.toInt(), "BLACK" to 0xFF1C1C1F.toInt(),
        "RED" to 0xFFE5484D.toInt(), "ORANGE" to 0xFFF76808.toInt(), "GOLD" to 0xFFD4A72C.toInt(), "YELLOW" to 0xFFF5D90A.toInt(),
        "GREEN" to 0xFF46A758.toInt(), "BLUE" to 0xFF3B82F6.toInt(), "PURPLE" to 0xFF8E4EC6.toInt(), "PINK" to 0xFFE93D82.toInt(),
        "BROWN" to 0xFF8D6E63.toInt(), "CHROMA" to 0,
    )

    data class Filter(val show: Show = Show.ALL, val sort: Sort = Sort.NEWEST, val color: String? = null, val style: String? = null) {
        /** How many of the panel's filters (colour, style) are set. */
        val panelCount get() = (if (color != null) 1 else 0) + (if (style != null) 1 else 0)
    }

    /** [entries] (one tab's) as the locker lists them; [owned] and [picked] say what each id is for you. */
    fun apply(entries: Collection<LunarCosmeticIndex.Entry>, query: String, f: Filter, owned: (Int) -> Boolean, picked: (Int) -> Boolean):
        List<LunarCosmeticIndex.Entry> {
        val q = query.trim().lowercase()
        val out = entries.filter { e ->
            (q.isEmpty() || q in e.name.lowercase() || e.categories.any { q in label(it).lowercase() }) &&
                (f.color == null || f.color in e.colors) &&
                (f.style == null || f.style in e.categories) &&
                when (f.show) {
                    Show.ALL -> true
                    Show.OWNED -> owned(e.id)
                    Show.PICKED -> picked(e.id)
                }
        }
        return when (f.sort) {
            Sort.NEWEST -> out.sortedByDescending { it.id }
            Sort.NAME -> out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    /** The style tags [entries] use, most used first (what the panel offers for a tab). */
    fun styles(entries: Collection<LunarCosmeticIndex.Entry>): List<String> =
        entries.flatMap { it.categories }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /** A tag as shown: "8_BIT" -> "8-bit", "SCARY" -> "Scary" (and Lunar's "GRAFFETI" spelt right). */
    fun label(tag: String): String = when (tag) {
        "GRAFFETI" -> "Graffiti"
        "8_BIT" -> "8-bit"
        else -> tag.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
