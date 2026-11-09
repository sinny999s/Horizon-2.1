package dev.horizon.client.huntmap

/**
 * The hunt map's markers, read from the logs other modules already write (Base Finder's bases and
 * clues, Stash Finder's stashes) and from the map's own files (portals, old-chunk islands, worker border crossings).
 * Kept free of the game so it can be tested on its own.
 */
object MapData {
    /** Something to mark: [kind] picks the colour ("base", "stash", "portal", "clue", "island", "waypoint", "logout"). */
    class Find(val kind: String, val x: Int, val y: Int, val z: Int, val label: String, val detail: String)

    /** Where you were moved from one 6b6t worker to another. */
    class Crossing(val from: String, val to: String, val x: Int, val z: Int)

    /** "X 12 Y 64 Z -30" to its numbers. */
    fun xyz(part: String): IntArray? {
        val p = part.trim().split(' ')
        if (p.size < 6 || p[0] != "X" || p[2] != "Y" || p[4] != "Z") return null
        val x = p[1].toIntOrNull() ?: return null
        val y = p[3].toIntOrNull() ?: return null
        val z = p[5].toIntOrNull() ?: return null
        return intArrayOf(x, y, z)
    }

    /** Base Finder's bases: "time | [BASE or STASH | ]X x Y y Z z | score n | what | chunks ...". */
    fun parseBases(lines: List<String>): List<Find> = lines.mapNotNull { line ->
        val parts = line.split(" | ")
        val at = parts.indexOfFirst { it.startsWith("X ") }
        if (at < 1) return@mapNotNull null
        val c = xyz(parts[at]) ?: return@mapNotNull null
        val stash = at >= 2 && parts[1] == "STASH"
        val what = parts.getOrNull(at + 2) ?: ""
        val score = parts.getOrNull(at + 1) ?: ""
        Find(if (stash) "stash" else "base", c[0], c[1], c[2], if (stash) "Stash" else "Base", listOf(what, score).filter { it.isNotEmpty() }.joinToString(", "))
    }

    /** Lines of "time | kind | X x Y y Z z | detail": Base Finder's clues and the map's own finds. */
    fun parseKinds(lines: List<String>, kindOf: (String) -> String): List<Find> = lines.mapNotNull { line ->
        val parts = line.split(" | ")
        if (parts.size < 3) return@mapNotNull null
        val c = xyz(parts[2]) ?: return@mapNotNull null
        Find(kindOf(parts[1]), c[0], c[1], c[2], parts[1], parts.drop(3).joinToString(" | "))
    }

    /** Stash Finder's stashes on one server and dimension: "time | server | dim | X x Y y Z z | contents | total n". */
    fun parseStashes(lines: List<String>, server: String, dim: String): List<Find> = lines.mapNotNull { line ->
        val parts = line.split(" | ")
        if (parts.size < 5 || parts[1] != server || parts[2] != dim) return@mapNotNull null
        val c = xyz(parts[3]) ?: return@mapNotNull null
        Find("stash", c[0], c[1], c[2], "Stash", parts[4])
    }

    /** The map's crossings file: "time | from | to | x z". */
    fun parseCrossings(lines: List<String>): List<Crossing> = lines.mapNotNull { line ->
        val parts = line.split(" | ")
        if (parts.size < 4) return@mapNotNull null
        val xz = parts[3].trim().split(' ')
        val x = xz.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val z = xz.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        Crossing(parts[1], parts[2], x, z)
    }

    /**
     * [crossings] without the ones on a marked portal. Before the map knew that a portal moves you to another 6b6t
     * worker, it saved those trips as crossings too, and drew borders between portals.
     */
    fun withoutPortalTrips(crossings: List<Crossing>, finds: List<Find>): List<Crossing> {
        val portals = finds.filter { it.kind == "portal" }
        return crossings.filter { c -> portals.none { kotlin.math.abs(it.x - c.x) <= 8 && kotlin.math.abs(it.z - c.z) <= 8 } }
    }

    private val WORKER = Regex("""now playing on (\S+?),? hosted on (\S+?),? in ([\w-]+)""", RegexOption.IGNORE_CASE)

    /** 6b6t's "You're now playing on worker-1, hosted on node-13 in us-east." to worker, node and region. */
    fun parseWorker(message: String): List<String>? {
        val m = WORKER.find(message) ?: return null
        return m.groupValues.drop(1)
    }

    /** The seeds setting: "host=seed" or "host=seed/netherSeed", separated by spaces or commas. */
    fun parseSeeds(s: String): Map<String, Pair<Long, Long?>> {
        val out = LinkedHashMap<String, Pair<Long, Long?>>()
        for (entry in s.split(Regex("[\\s,;]+"))) {
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val host = entry.substring(0, eq).lowercase()
            val seeds = entry.substring(eq + 1).split('/')
            val seed = seeds[0].toLongOrNull() ?: continue
            out[host] = seed to seeds.getOrNull(1)?.toLongOrNull()
        }
        return out
    }

    /** The seed for a server address ("play.6b6t.org:25565" matches "6b6t.org"), its nether seed in the nether. */
    fun seedFor(seeds: Map<String, Pair<Long, Long?>>, address: String, nether: Boolean): Long? {
        val host = address.substringBefore(':').lowercase().trimEnd('.')
        val e = seeds.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value ?: return null
        return if (nether) e.second ?: e.first else e.first
    }
}
