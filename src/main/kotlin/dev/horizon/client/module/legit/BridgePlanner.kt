package dev.horizon.client.module.legit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Not in the original: GodBridge's jump timing, kept free of the game so it can be tested on its own.
 *
 * With the camera held still, a click only extends the bridge while the eyes are a little past its end: before that
 * the crosshair is on the top of the last block, and once the feet have left that block the player sinks too low to
 * place under themselves. Every block moves the ticks about 0.08 of a block further into that window, so every few
 * blocks no tick lands in it and a held angle walks off the end. That is why god bridging needs its jumps: in the air
 * the eyes are higher and the window moves out with them. [shouldJump] plays the next ticks out with vanilla's walking
 * physics and the module's own click rule, once without a jump and once with one now, and jumps only when the jump is
 * what keeps the bridge going.
 */
object BridgePlanner {
    /** Ticks played out: a whole jump, its landing and the next couple of blocks. */
    const val HORIZON = 30

    /** The player at the start of a tick, after this tick's click: feet position and velocity as vanilla keeps them. */
    class State(
        var x: Double, var y: Double, var z: Double,
        var vx: Double, var vy: Double, var vz: Double,
        var onGround: Boolean,
    ) {
        fun copy() = State(x, y, z, vx, vy, vz, onGround)
    }

    /**
     * What stays the same over the look-ahead: the view direction (a unit vector), the push the movement keys give per
     * tick on the ground and in the air (world space), the player's size and physics, and how often the module clicks
     * ([clickGap] ticks between clicks at least, the next one allowed [nextClickIn] ticks from now). The look-ahead
     * only counts on clicks that land at least [margin] inside the face's edges, so a mouse count's change of aim
     * (or a hand on the mouse) can't turn a planned click into a miss. With [climb], a jump also steps the bridge up
     * (see [clickTarget]).
     */
    class Params(
        val lookX: Double, val lookY: Double, val lookZ: Double,
        val groundX: Double, val groundZ: Double,
        val airX: Double, val airZ: Double,
        val eye: Double = 1.62, val halfWidth: Double = 0.3, val height: Double = 1.8,
        val reach: Double = 4.5, val jumpPower: Double = 0.42, val gravity: Double = 0.08,
        val groundFriction: Double = 0.6 * 0.91, val airFriction: Double = 0.91,
        val clickGap: Int = 1, val nextClickIn: Int = 0, val margin: Double = 0.03,
        val climb: Boolean = false,
    )

    /** A block the crosshair is on, the face it entered through, and where along the view it was hit. */
    class Hit(val x: Int, val y: Int, val z: Int, val face: Int, val distance: Double)

    /** Whether to jump this tick: walking on leaves the bridge behind within the look-ahead and a jump now does not. */
    fun shouldJump(s: State, p: Params, solid: (Int, Int, Int) -> Boolean, horizon: Int = HORIZON): Boolean {
        if (!s.onGround) return false
        if (survives(s, p, solid, false, horizon) >= horizon) return false
        return survives(s, p, solid, true, horizon) >= horizon
    }

    /** Whether a jump now keeps the bridge going for the whole look-ahead (for a jump nothing forces). */
    fun jumpIsSafe(s: State, p: Params, solid: (Int, Int, Int) -> Boolean, horizon: Int = HORIZON): Boolean =
        s.onGround && survives(s, p, solid, true, horizon) >= horizon

    /**
     * How many ticks the player keeps their height for, up to [horizon], clicking whenever the crosshair would extend
     * the bridge. After the first tick (a jump or not, as [jumpNow] says) jumps follow the quick rule in [mustJump].
     * A jump still in the air at the end is played on until it lands, so hopping off the end doesn't count as staying.
     */
    fun survives(start: State, p: Params, solid: (Int, Int, Int) -> Boolean, jumpNow: Boolean, horizon: Int): Int {
        val s = start.copy()
        val placed = HashSet<Long>()
        val world = { x: Int, y: Int, z: Int -> key(x, y, z) in placed || solid(x, y, z) }
        val floorY = s.y
        var nextClick = p.nextClickIn
        var i = 0
        while (i < horizon || !s.onGround && i < horizon + LANDING) {
            if (i > 0 && nextClick <= 0) {
                val t = clickTarget(s, p, world, p.margin)
                if (t != null) {
                    placed += t
                    nextClick = p.clickGap
                }
            }
            val jump = if (i == 0) jumpNow else mustJump(s, p, world)
            step(s, p, world, jump)
            nextClick--
            if (s.y < floorY - DROP) return min(i, horizon - 1)
            i++
        }
        return horizon
    }

    /**
     * The quick rule inside a look-ahead: on the ground, jump when the feet are already off the end with no block
     * placed under them, or when the next tick walks them off it and the crosshair won't catch it there.
     */
    fun mustJump(s: State, p: Params, world: (Int, Int, Int) -> Boolean): Boolean {
        if (!s.onGround) return false
        if (!supported(s.x, s.y, s.z, p, world)) return true
        val next = s.copy()
        next.x += s.vx + p.groundX
        next.z += s.vz + p.groundZ
        if (supported(next.x, next.y, next.z, p, world)) return false
        return clickTarget(next, p, world, p.margin) == null
    }

    /** One tick of vanilla movement: the jump, the keys' push, the fall onto anything under the feet, friction. */
    fun step(s: State, p: Params, world: (Int, Int, Int) -> Boolean, jump: Boolean) {
        val ground = s.onGround
        if (jump && ground) s.vy = p.jumpPower
        s.vx += if (ground) p.groundX else p.airX
        s.vz += if (ground) p.groundZ else p.airZ
        // Vanilla moves along Y first, with the box where it stands before the step.
        var dy = s.vy
        var landed = false
        if (dy < 0) {
            val top = floorUnder(s.x, s.y, s.z, s.y + dy, p, world)
            if (top != null) {
                dy = top - s.y
                landed = true
            }
        }
        s.y += dy
        s.x += s.vx
        s.z += s.vz
        s.onGround = landed
        val f = if (ground) p.groundFriction else p.airFriction
        s.vx *= f
        s.vz *= f
        s.vy = ((if (landed) 0.0 else s.vy) - p.gravity) * 0.98
    }

    /**
     * The block a click would place this tick, packed by [key], or null: the crosshair's first block must be hit on a
     * side (not the bottom) at least [margin] inside its edges, and the free cell beside it must be below the feet, at
     * most two blocks down and clear of the player. The top is only clicked with [Params.climb], in the air, for a
     * block that will be under the feet (they're over it and already above it): the jump's step up.
     */
    fun clickTarget(s: State, p: Params, world: (Int, Int, Int) -> Boolean, margin: Double = 0.0): Long? {
        val ey = s.y + p.eye
        val hit = raycast(s.x, ey, s.z, p, world) ?: return null
        val face = hit.face
        if (face == DOWN) return null
        val step = face == UP
        if (step && (!p.climb || s.onGround)) return null
        if (margin > 0) {
            val hx = s.x + p.lookX * hit.distance - hit.x
            val hy = ey + p.lookY * hit.distance - hit.y
            val hz = s.z + p.lookZ * hit.distance - hit.z
            val (u, v) = when (face) {
                NORTH, SOUTH -> hx to hy
                UP -> hx to hz
                else -> hz to hy
            }
            if (u < margin || u > 1 - margin || v < margin || v > 1 - margin) return null
        }
        val tx = hit.x + FACE_X[face]
        val tz = hit.z + FACE_Z[face]
        val ty = hit.y + FACE_Y[face]
        if (world(tx, ty, tz)) return null
        if (ty.toDouble() >= s.y) return null
        if (ty < floor(s.y).toInt() - 2) return null
        val hw = p.halfWidth
        val over = tx < s.x + hw && tx + 1 > s.x - hw && tz < s.z + hw && tz + 1 > s.z - hw
        // A step is only worth it under the feet, and only with them clearly above it: a hair above is enough for the
        // client but not for the server, which then refuses it.
        if (step && (!over || s.y < ty + 1 + STEP_CLEARANCE)) return null
        // Vanilla refuses a block that would overlap the player.
        if (over && ty < s.y + p.height && ty + 1 > s.y) return null
        return key(tx, ty, tz)
    }

    /** First solid block along the view within reach. */
    fun raycast(ex: Double, ey: Double, ez: Double, p: Params, world: (Int, Int, Int) -> Boolean): Hit? {
        var x = floor(ex).toInt()
        var y = floor(ey).toInt()
        var z = floor(ez).toInt()
        if (world(x, y, z)) return null
        val dx = p.lookX
        val dy = p.lookY
        val dz = p.lookZ
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val inf = Double.POSITIVE_INFINITY
        val deltaX = if (dx != 0.0) abs(1 / dx) else inf
        val deltaY = if (dy != 0.0) abs(1 / dy) else inf
        val deltaZ = if (dz != 0.0) abs(1 / dz) else inf
        var maxX = if (dx != 0.0) ((if (dx > 0) x + 1 - ex else ex - x)) * deltaX else inf
        var maxY = if (dy != 0.0) ((if (dy > 0) y + 1 - ey else ey - y)) * deltaY else inf
        var maxZ = if (dz != 0.0) ((if (dz > 0) z + 1 - ez else ez - z)) * deltaZ else inf
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
            if (t > p.reach) return null
            if (world(x, y, z)) return Hit(x, y, z, face, t)
        }
    }

    /** Whether any block holds up a player standing at feet height [y]. */
    fun supported(x: Double, y: Double, z: Double, p: Params, world: (Int, Int, Int) -> Boolean): Boolean {
        val top = Math.round(y)
        if (abs(y - top) > 1e-4) return false
        return columns(x, z, p) { cx, cz -> world(cx, top.toInt() - 1, cz) }
    }

    /** The highest block top between the feet at [y] and [toY] under the player's box, or null. */
    private fun floorUnder(x: Double, y: Double, z: Double, toY: Double, p: Params, world: (Int, Int, Int) -> Boolean): Double? {
        var by = floor(y - EPS).toInt()
        val lowest = floor(toY).toInt()
        while (by >= lowest) {
            val top = by + 1.0
            if (top <= y + EPS && top > toY && columns(x, z, p) { cx, cz -> world(cx, by, cz) }) return top
            by--
        }
        return null
    }

    private inline fun columns(x: Double, z: Double, p: Params, test: (Int, Int) -> Boolean): Boolean {
        val x0 = floor(x - p.halfWidth + EPS).toInt()
        val x1 = floor(x + p.halfWidth - EPS).toInt()
        val z0 = floor(z - p.halfWidth + EPS).toInt()
        val z1 = floor(z + p.halfWidth - EPS).toInt()
        for (cx in x0..x1) for (cz in z0..z1) if (test(cx, cz)) return true
        return false
    }

    /**
     * The keys' push per tick in world space, as vanilla's `getInputVector` turns it: [strafe] and [forward] are the
     * player's `xxa` and `zza`, [yaw] in degrees, [speed] the walking speed (or the air speed).
     */
    fun push(strafe: Double, forward: Double, yaw: Double, speed: Double): DoubleArray {
        val len2 = strafe * strafe + forward * forward
        if (len2 < 1e-7) return doubleArrayOf(0.0, 0.0)
        val n = if (len2 > 1) 1 / sqrt(len2) else 1.0
        val s = strafe * n * speed
        val f = forward * n * speed
        val r = Math.toRadians(yaw)
        return doubleArrayOf(s * cos(r) - f * sin(r), f * cos(r) + s * sin(r))
    }

    fun key(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)

    // Faces in vanilla's Direction order.
    const val DOWN = 0
    const val UP = 1
    const val NORTH = 2
    const val SOUTH = 3
    const val WEST = 4
    const val EAST = 5
    private val FACE_X = intArrayOf(0, 0, 0, 0, -1, 1)
    private val FACE_Y = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val FACE_Z = intArrayOf(0, 0, -1, 1, 0, 0)

    /** A drop this far below the starting height counts as having left the bridge. */
    private const val DROP = 0.3
    /** How far above a step's top the feet must be for it to be placed. */
    const val STEP_CLEARANCE = 0.1
    /** Longest a jump can still be in the air after the look-ahead ends. */
    private const val LANDING = 25
    private const val EPS = 1e-7
}
