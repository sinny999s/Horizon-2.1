package dev.horizon.client.module.legit

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Clutch's planning, kept free of the game so it can be simulated:
 * - [predict] plays the fall out and finds the cells a new block would catch the player on;
 * - [plan] picks the line of blocks to build out to one (often a single block against the island's side) and the spot
 *   on a face to turn to for its next click, timing it the way a hand turns ([Timing]);
 * - [clickCell] says whether a click now places one of those blocks. The click reaches the server before this tick's
 *   look does, so the server judges it by the look it already has: that look must be on the block as well as the
 *   crosshair on the face.
 */
object ClutchPlanner {
    /** The world as Clutch sees it, block by block. */
    interface World {
        /** Has a collision box: a fall stops on it. */
        fun solid(x: Int, y: Int, z: Int): Boolean

        /** A placed block can go here (air, grass, water...). */
        fun free(x: Int, y: Int, z: Int): Boolean

        /** A click on one of its faces places a block beside it (solid, and not something that opens a screen). */
        fun support(x: Int, y: Int, z: Int): Boolean

        /** The first block a view from the eyes along the unit vector (dx, dy, dz) meets within [reach]. */
        fun ray(ex: Double, ey: Double, ez: Double, dx: Double, dy: Double, dz: Double, reach: Double): BridgePlanner.Hit?
    }

    /** A cell a new block would catch the player on: the tick the feet pass its top, and how deep the box is over it. */
    class Catch(val tick: Int, val depth: Double)

    /**
     * [save]: no landing within the drop. [catches] by [BridgePlanner.key]. [path]: the feet after each tick played out
     * (x, y, z), the first after this tick's move.
     */
    class Fall(val save: Boolean, val catches: Map<Long, Catch>, val path: List<DoubleArray>) {
        /** The feet when a click [k] ticks from now goes (before that tick's move). */
        fun feetAt(k: Int, x: Double, y: Double, z: Double): DoubleArray =
            if (k <= 0 || path.isEmpty()) doubleArrayOf(x, y, z) else path[min(k, path.size) - 1]
    }

    /**
     * How the hand turns, for timing a plan: a movement takes [fittsA] + [fittsB] x log2(1 + distance / size) seconds
     * (Fitts's law) and no faster than [topSpeed] allows, as [dev.horizon.client.module.attack.HumanAim] moves. The hand
     * starts [startIn] seconds from now, a click can come [wait] ticks from now at the earliest, then one every [minGap].
     */
    class Timing(
        val fittsA: Double, val fittsB: Double, val topSpeed: Double,
        val startIn: Double, val wait: Int, val minGap: Int,
    ) {
        fun seconds(angle: Double, size: Double): Double =
            max(fittsA + fittsB * ln(1.0 + angle / max(size, 1.0)) / ln(2.0), 1.875 * angle / topSpeed)
    }

    /**
     * The line to build: [line] the cells from the support out to the catch, the next to place first; the next click
     * goes on [face] of the block at ([bx], [by], [bz]), aimed at ([ax], [ay], [az]), whose angular size is about
     * [size] degrees. [first] is when (ticks from now) its next click is expected, [ticks] when the last block of it
     * is, [slack] how many ticks before the feet pass it.
     */
    class Plan(
        val line: List<Long>, val bx: Int, val by: Int, val bz: Int, val face: Int,
        val ax: Double, val ay: Double, val az: Double, val size: Double, val ticks: Int, val slack: Int, val first: Int,
    ) {
        val inTime get() = slack >= 0
    }

    /** Ticks the fall is played out for: about two seconds, far enough to tell a drop from a step down. */
    const val LOOK_AHEAD = 40
    /** Longest line built out to a catch. */
    const val MAX_LINE = 6
    /** How squarely (the cosine to the face's normal) a face is seen for it to be an easy click. */
    const val GRAZING = 0.35
    /** How far around the eyes (in blocks, each way) a line can start. */
    const val SEARCH = 6
    /** Ticks between the hand settling on a face and the click: the server must have that look first. */
    const val DWELL = 1
    /** Box depth over a cell (blocks) under which a catch is easily lost to a slightly different fall. */
    const val SHALLOW = 0.15

    /**
     * Plays the fall out from [start] (vanilla's physics, the keys' push as [params] has it, or as [push] gives it for
     * each tick: the keys push along the camera, which turns) and returns whether it needs saving: no landing within
     * [minDrop] blocks below [fromY]. The catches are the cells under the box at the tick the feet pass their top, for
     * tops no higher than the feet now.
     */
    fun predict(
        start: BridgePlanner.State, params: BridgePlanner.Params, world: World, fromY: Double, minDrop: Int,
        push: ((Int) -> DoubleArray)? = null,
    ): Fall {
        val catches = HashMap<Long, Catch>()
        val path = ArrayList<DoubleArray>()
        val s = start.copy()
        val solid = { x: Int, y: Int, z: Int -> world.solid(x, y, z) }
        val hw = params.halfWidth
        val floorY = fromY - minDrop
        for (t in 0 until LOOK_AHEAD) {
            val x0 = s.x
            val y0 = s.y
            val z0 = s.z
            val air = push?.invoke(t)
            val pt = if (air == null) params else BridgePlanner.Params(0.0, -1.0, 0.0, 0.0, 0.0, air[0], air[1],
                halfWidth = hw, height = params.height, gravity = params.gravity, jumpPower = params.jumpPower)
            BridgePlanner.step(s, pt, solid, false, walls = true)
            path += doubleArrayOf(s.x, s.y, s.z)
            if (s.onGround) return Fall(s.y < floorY, catches, path)
            // Every block top the feet sink past this tick, under where the box was (vanilla moves along Y first).
            var top = floor(y0).toInt()
            while (top > s.y) {
                if (top <= start.y + 1e-6) {
                    for (cx in floor(x0 - hw + 1e-7).toInt()..floor(x0 + hw - 1e-7).toInt()) {
                        for (cz in floor(z0 - hw + 1e-7).toInt()..floor(z0 + hw - 1e-7).toInt()) {
                            val depth = min(min(x0 + hw, cx + 1.0) - max(x0 - hw, cx.toDouble()),
                                min(z0 + hw, cz + 1.0) - max(z0 - hw, cz.toDouble()))
                            catches.putIfAbsent(BridgePlanner.key(cx, top - 1, cz), Catch(t, depth))
                        }
                    }
                }
                top--
            }
            if (s.y < floorY - 2) break
        }
        return Fall(true, catches, path)
    }

    /** The first click of a line: the face of a support beside its first cell. */
    private class Anchor(
        val bx: Int, val by: Int, val bz: Int, val face: Int,
        val px: Double, val py: Double, val pz: Double, val angle: Double, val cost: Double, val size: Double,
    )

    /**
     * The best line from a support out to a catch, every cell free and in [reach] of the eyes at ([ex], [ey], [ez]),
     * the camera looking along ([lx], [ly], [lz]). Its first block goes against a support's face (facing the eyes, in
     * reach, the first thing a view there meets; one still edge-on counts, last), each next one against the last. A
     * line done (by [timing]) before the feet pass its catch wins, then the fewest blocks, then a catch the box is well
     * over, then the most time to spare and the least turning; [keep] (the cell being turned to) is kept when it is as
     * good. With none in time, the best of the rest is returned only when it is late by at most [lateBy] ticks.
     * [spotU]/[spotV] say where on a face to aim (fractions across it). Each click is timed and checked (in reach, its
     * face turned towards the eyes) from where the eyes will be when it goes, along [fall]'s path: a face edge-on now
     * comes into view as the knock carries you out.
     */
    fun plan(
        ex: Double, ey: Double, ez: Double, lx: Double, ly: Double, lz: Double, reach: Double, eyeHeight: Double,
        fall: Fall, world: World, timing: Timing, keep: Long?, spotU: Double, spotV: Double, lateBy: Int,
    ): Plan? {
        val catches = fall.catches
        if (catches.isEmpty()) return null
        val feetY = ey - eyeHeight

        /** The face's size (degrees) seen from the eyes [k] ticks from now, or null when out of reach or not facing them. */
        fun sizeAt(k: Int, pt: DoubleArray, face: Int): Double? {
            val f = fall.feetAt(k, ex, feetY, ez)
            val tx = pt[0] - f[0]
            val ty = pt[1] - (f[1] + eyeHeight)
            val tz = pt[2] - f[2]
            val len = sqrt(tx * tx + ty * ty + tz * tz)
            if (len > reach) return null
            val facing = -(tx * FX[face] + ty * FY[face] + tz * FZ[face]) / len
            if (facing <= 0.02) return null
            return faceSize(len, facing)
        }

        /** The tick a click lands that needs a turn of [angle] onto [face] at [pt], started [from] ticks from now. */
        fun clickTick(from: Int, angle: Double, pt: DoubleArray, face: Int, now: Double): Int? {
            var k = from + ceil(timing.seconds(angle, now) * 20.0).toInt()
            repeat(2) {
                val size = sizeAt(k, pt, face) ?: return null
                k = from + ceil(timing.seconds(angle, size) * 20.0).toInt()
            }
            sizeAt(k, pt, face) ?: return null
            return k + DWELL
        }

        fun anchor(cx: Int, cy: Int, cz: Int): Anchor? {
            var best: Anchor? = null
            for (d in 0..5) {
                val nx = cx + FX[d]
                val ny = cy + FY[d]
                val nz = cz + FZ[d]
                if (!world.support(nx, ny, nz)) continue
                val face = d xor 1
                val pt = facePoint(nx, ny, nz, face, spotU, spotV)
                val tx = pt[0] - ex
                val ty = pt[1] - ey
                val tz = pt[2] - ez
                val len = sqrt(tx * tx + ty * ty + tz * tz)
                if (len > reach - 0.1 || len < 1e-6) continue
                // Seen from the open side with nothing in the way, a click there works now. A face still edge-on (the
                // player hasn't yet crossed its plane) is turned to all the same: a player starts the flick before the
                // face comes into view.
                val dot = tx * FX[face] + ty * FY[face] + tz * FZ[face]
                val open = dot < 0
                var visible = false
                if (open) {
                    val h = world.ray(ex, ey, ez, tx / len, ty / len, tz / len, len + 0.05)
                    visible = h != null && h.x == nx && h.y == ny && h.z == nz && h.face == face
                    if (!visible) continue
                }
                val facing = if (open) -dot / len else 0.0
                val angle = angleDeg(lx, ly, lz, tx / len, ty / len, tz / len)
                // A face seen nearly edge-on is a sliver on screen: a fraction of a degree off misses it, and the fall
                // keeps changing the angle. Those come last among the faces in view.
                val cost = angle + (if (visible) 0.0 else 300.0) + (if (facing < GRAZING) (GRAZING - facing) * 1000.0 else 0.0)
                if (best == null || cost < best.cost) {
                    best = Anchor(nx, ny, nz, face, pt[0], pt[1], pt[2], angle, cost, faceSize(len, facing))
                }
            }
            return best
        }

        fun inReach(x: Int, y: Int, z: Int): Boolean {
            val dx = x + 0.5 - ex
            val dy = y + 0.5 - ey
            val dz = z + 0.5 - ez
            return dx * dx + dy * dy + dz * dz <= (reach + 0.3) * (reach + 0.3)
        }

        var best: Plan? = null
        var bestScore = Double.MAX_VALUE
        val ex0 = floor(ex).toInt()
        val ez0 = floor(ez).toInt()
        val levels = catches.keys.map { unkeyY(it) }.distinct()
        for (y in levels) {
            val anchors = HashMap<Long, Anchor>()
            val prev = HashMap<Long, Long>()
            val dist = HashMap<Long, Int>()
            val queue = ArrayDeque<Long>()
            for (x in ex0 - SEARCH..ex0 + SEARCH) for (z in ez0 - SEARCH..ez0 + SEARCH) {
                if (!world.free(x, y, z) || !inReach(x, y, z)) continue
                val a = anchor(x, y, z) ?: continue
                val k = BridgePlanner.key(x, y, z)
                anchors[k] = a
                dist[k] = 1
                queue += k
            }
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val d = dist[c]!!
                if (d >= MAX_LINE) continue
                val cx = unkeyX(c)
                val cz = unkeyZ(c)
                for (dir in 2..5) {
                    val nx = cx + FX[dir]
                    val nz = cz + FZ[dir]
                    val n = BridgePlanner.key(nx, y, nz)
                    if (n in dist || !world.free(nx, y, nz) || !inReach(nx, y, nz)) continue
                    dist[n] = d + 1
                    prev[n] = c
                    queue += n
                }
            }
            for ((k, catch) in catches) {
                if (unkeyY(k) != y) continue
                val d = dist[k] ?: continue
                val path = ArrayList<Long>()
                var c: Long? = k
                while (c != null) {
                    path += c
                    c = prev[c]
                }
                path.reverse()
                val first = anchors[path[0]] ?: continue
                // The first click: the hand starts, makes the flick, settles; then each next block a short turn from the
                // last face to the next one out.
                val start = ceil(timing.startIn * 20.0).toInt()
                var ticks = clickTick(start, first.angle, doubleArrayOf(first.px, first.py, first.pz), first.face, first.size)
                    ?.let { max(timing.wait, it) } ?: continue
                val firstTick = ticks
                var px = first.px
                var py = first.py
                var pz = first.pz
                var ok = true
                for (i in 1 until path.size) {
                    val a = path[i - 1]
                    val b = path[i]
                    val face = faceToward(unkeyX(b) - unkeyX(a), unkeyZ(b) - unkeyZ(a))
                    val pt = facePoint(unkeyX(a), y, unkeyZ(a), face, spotU, spotV)
                    val f = fall.feetAt(ticks, ex, feetY, ez)
                    val fy = f[1] + eyeHeight
                    val step = angleDeg(px - f[0], py - fy, pz - f[2], pt[0] - f[0], pt[1] - fy, pt[2] - f[2])
                    val at = clickTick(ticks, step, pt, face, sizeAt(ticks, pt, face) ?: 1.0)
                    if (at == null) {
                        ok = false
                        break
                    }
                    ticks = max(ticks + timing.minGap, at)
                    px = pt[0]
                    py = pt[1]
                    pz = pt[2]
                }
                if (!ok) continue
                val slack = catch.tick - ticks
                if (slack < -lateBy) continue
                // Late is worst (by how late), then each block more, a catch the box is barely over, little time to
                // spare; staying with the face being turned to is worth a lot: switching never lets the flick land.
                val score = (if (slack >= 0) max(0, 4 - slack) * 150.0 else 5000.0 - slack * 600.0) + (d - 1) * 400.0 +
                    (if (catch.depth < SHALLOW) 300.0 else 0.0) + first.cost * 0.5 + (if (path[0] == keep) -700.0 else 0.0)
                if (score < bestScore) {
                    bestScore = score
                    best = Plan(path, first.bx, first.by, first.bz, first.face, first.px, first.py, first.pz, first.size, ticks,
                        slack, firstTick)
                }
            }
        }
        return best
    }

    /**
     * The cell a click now fills, if it is one in [wanted]: [now] is what the crosshair is on (vanilla's own pick, which
     * the click uses), [sent] what the look the server already has points at from the same eyes. That look must be on
     * the clicked block too (a server checking the click against your look takes it; falling fast, the side and the
     * top of the block are both fair: one tick's fall slides a look from one to the other). The block must be a support,
     * the cell beside it free, and the new block clear of the player's box at ([x], [y], [z]) (vanilla refuses one that
     * isn't).
     */
    fun clickCell(
        now: BridgePlanner.Hit?, sent: BridgePlanner.Hit?, wanted: (Long) -> Boolean, world: World,
        x: Double, y: Double, z: Double, halfWidth: Double, height: Double,
    ): Long? {
        if (now == null || sent == null) return null
        if (now.x != sent.x || now.y != sent.y || now.z != sent.z) return null
        if (!world.support(now.x, now.y, now.z)) return null
        val cx = now.x + FX[now.face]
        val cy = now.y + FY[now.face]
        val cz = now.z + FZ[now.face]
        if (!world.free(cx, cy, cz)) return null
        val k = BridgePlanner.key(cx, cy, cz)
        if (!wanted(k)) return null
        if (cx < x + halfWidth && cx + 1 > x - halfWidth && cz < z + halfWidth && cz + 1 > z - halfWidth &&
            cy < y + height && cy + 1 > y) return null
        return k
    }

    /** A point on [face] of the block at ([x], [y], [z]), at ([u], [v]) across it, kept off the edges. */
    fun facePoint(x: Int, y: Int, z: Int, face: Int, u: Double, v: Double): DoubleArray {
        val cx = x + 0.5
        val cy = y + 0.5
        val cz = z + 0.5
        val du = (u - 0.5) * 0.8
        val dv = (v - 0.5) * 0.8
        return when (face) {
            WEST, EAST -> doubleArrayOf(cx + FX[face] * 0.5, cy + dv, cz + du)
            NORTH, SOUTH -> doubleArrayOf(cx + du, cy + dv, cz + FZ[face] * 0.5)
            else -> doubleArrayOf(cx + du, cy + FY[face] * 0.5, cz + dv)
        }
    }

    /** About how big (degrees, its smaller side) a block's face looks from [dist] away, seen at [facing] to its normal. */
    fun faceSize(dist: Double, facing: Double): Double = Math.toDegrees(1.0 / max(dist, 0.5)) * max(facing, 0.15)

    /** The first solid block along a view (a voxel walk), and the face it enters through. */
    fun voxelRay(
        solid: (Int, Int, Int) -> Boolean, ex: Double, ey: Double, ez: Double, dx: Double, dy: Double, dz: Double, reach: Double,
    ): BridgePlanner.Hit? {
        var x = floor(ex).toInt()
        var y = floor(ey).toInt()
        var z = floor(ez).toInt()
        if (solid(x, y, z)) return null
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val inf = Double.POSITIVE_INFINITY
        val deltaX = if (dx != 0.0) abs(1 / dx) else inf
        val deltaY = if (dy != 0.0) abs(1 / dy) else inf
        val deltaZ = if (dz != 0.0) abs(1 / dz) else inf
        var maxX = if (dx != 0.0) (if (dx > 0) x + 1 - ex else ex - x) * deltaX else inf
        var maxY = if (dy != 0.0) (if (dy > 0) y + 1 - ey else ey - y) * deltaY else inf
        var maxZ = if (dz != 0.0) (if (dz > 0) z + 1 - ez else ez - z) * deltaZ else inf
        while (true) {
            val face: Int
            val t: Double
            if (maxX <= maxY && maxX <= maxZ) {
                t = maxX; x += stepX; maxX += deltaX; face = if (stepX > 0) WEST else EAST
            } else if (maxY <= maxZ) {
                t = maxY; y += stepY; maxY += deltaY; face = if (stepY > 0) DOWN else UP
            } else {
                t = maxZ; z += stepZ; maxZ += deltaZ; face = if (stepZ > 0) NORTH else SOUTH
            }
            if (t > reach) return null
            if (solid(x, y, z)) return BridgePlanner.Hit(x, y, z, face, t)
        }
    }

    /** The view direction (a unit vector) for a camera yaw and pitch, as Minecraft counts them. */
    fun look(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(-kotlin.math.sin(y) * kotlin.math.cos(p), -kotlin.math.sin(p), kotlin.math.cos(y) * kotlin.math.cos(p))
    }

    /** The angle (degrees) between two directions. */
    fun angleDeg(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val la = sqrt(ax * ax + ay * ay + az * az)
        val lb = sqrt(bx * bx + by * by + bz * bz)
        if (la < 1e-9 || lb < 1e-9) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by + az * bz) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    /** The side face of a block toward its neighbour (dx, dz) away. */
    private fun faceToward(dx: Int, dz: Int): Int = when {
        dx > 0 -> EAST
        dx < 0 -> WEST
        dz > 0 -> SOUTH
        else -> NORTH
    }

    fun unkeyX(k: Long) = (k shr 38).toInt()
    fun unkeyY(k: Long) = (k shl 52 shr 52).toInt()
    fun unkeyZ(k: Long) = (k shl 26 shr 38).toInt()

    // Faces in vanilla's Direction order (as BridgePlanner's).
    const val DOWN = 0
    const val UP = 1
    const val NORTH = 2
    const val SOUTH = 3
    const val WEST = 4
    const val EAST = 5
    val FX = intArrayOf(0, 0, 0, 0, -1, 1)
    val FY = intArrayOf(-1, 1, 0, 0, 0, 0)
    val FZ = intArrayOf(0, 0, -1, 1, 0, 0)
}
