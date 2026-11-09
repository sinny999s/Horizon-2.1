package dev.horizon.client.module.esp

/**
 * The game-free shape logic of [PortalPatternFinder] and [TunnelEsp], for testing without the game.
 */
object HuntShapes {
    data class Cell(val u: Int, val v: Int)

    /** Inclusive bounds on a portal's plane: u along it, v up. */
    data class Rect(val u0: Int, val v0: Int, val u1: Int, val v1: Int)

    /**
     * Whether a flat patch of air is the shape a nether portal leaves: a full rectangle at least 2 wide and 3 tall (the
     * smallest inside of a frame). From 4 by 5 (the smallest frame, when that was mined too) its corners may be missing,
     * as a frame's aren't needed.
     */
    fun portalRect(cells: Set<Cell>, maxWidth: Int, maxHeight: Int): Rect? {
        if (cells.isEmpty()) return null
        val u0 = cells.minOf { it.u }
        val u1 = cells.maxOf { it.u }
        val v0 = cells.minOf { it.v }
        val v1 = cells.maxOf { it.v }
        val w = u1 - u0 + 1
        val h = v1 - v0 + 1
        if (w < 2 || h < 3 || w > maxWidth || h > maxHeight) return null
        val missing = w * h - cells.size
        if (missing == 0) return Rect(u0, v0, u1, v1)
        if (missing > 4 || w < 4 || h < 5) return null
        // Everything missing must be a corner.
        val corners = setOf(Cell(u0, v0), Cell(u1, v0), Cell(u0, v1), Cell(u1, v1))
        val inCorners = corners.count { it in cells }
        return if (4 - inCorners == missing) Rect(u0, v0, u1, v1) else null
    }

    data class P(val x: Int, val y: Int, val z: Int)

    /** One walkable column of a passage: its floor block's top at [pos], [height] blocks of room, the columns it opens to. */
    class Column(val pos: P, val height: Int, val links: List<P>)

    /** Inclusive block bounds. */
    data class Box(val x0: Int, val y0: Int, val z0: Int, val x1: Int, val y1: Int, val z1: Int)

    class Passage(val boxes: List<Box>, val length: Int, val stairs: Boolean, val middle: P)

    /**
     * Columns joined by their links into passages of at least [minLength] columns, nearly all of one height (at least
     * [UNIFORM] of them): someone digging keeps to one height, a natural crack doesn't. A passage is stairs when most of
     * its steps change level.
     */
    fun passages(columns: Collection<Column>, minLength: Int): List<Passage> {
        val byPos = HashMap<P, Column>()
        for (c in columns) byPos[c.pos] = c
        val seen = HashSet<P>()
        val out = ArrayList<Passage>()
        for (start in columns) {
            if (!seen.add(start.pos)) continue
            val group = ArrayList<Column>()
            val queue = ArrayDeque<Column>()
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group += c
                for (l in c.links) {
                    val n = byPos[l] ?: continue
                    if (seen.add(l)) queue.addLast(n)
                }
            }
            if (group.size < minLength) continue
            val common = group.groupingBy { it.height }.eachCount().values.max()
            if (common < group.size * UNIFORM) continue
            var steps = 0
            var sloped = 0
            for (c in group) for (l in c.links) if (l in byPos) {
                steps++
                if (l.y != c.pos.y) sloped++
            }
            val middle = group.sortedWith(compareBy({ it.pos.x }, { it.pos.z }, { it.pos.y }))[group.size / 2].pos
            out += Passage(merge(group), group.size, sloped * 2 > steps, middle)
        }
        return out
    }

    /** [group]'s columns as few boxes: straight runs of one floor level and height make one box. */
    fun merge(group: List<Column>): List<Box> {
        val left = HashMap<P, Column>()
        for (c in group) left[c.pos] = c
        val out = ArrayList<Box>()
        for (c in group.sortedWith(compareBy({ it.pos.x }, { it.pos.z }, { it.pos.y }))) {
            if (c.pos !in left) continue
            fun run(dx: Int, dz: Int): List<Column> {
                val r = arrayListOf(c)
                var i = 1
                while (true) {
                    val n = left[P(c.pos.x + dx * i, c.pos.y, c.pos.z + dz * i)] ?: break
                    if (n.height != c.height) break
                    r += n
                    i++
                }
                return r
            }
            val alongX = run(1, 0)
            val alongZ = run(0, 1)
            val best = if (alongX.size >= alongZ.size) alongX else alongZ
            for (b in best) left.remove(b.pos)
            val end = best.last().pos
            out += Box(c.pos.x, c.pos.y, c.pos.z, end.x, c.pos.y + c.height - 1, end.z)
        }
        return out
    }

    private const val UNIFORM = 0.8
}
