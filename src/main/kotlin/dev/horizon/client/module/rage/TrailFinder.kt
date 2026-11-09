package dev.horizon.client.module.rage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Elytra Autopilot's trail steering, kept free of the game so it can be tested on its own. A trail is a
 * line of old chunks (ones that existed before you loaded them, so someone has been there) running through new ones.
 * [steer] looks along every direction within `spread` degrees of the current heading and takes the one with the most
 * old chunks ahead, if any has at least `minOld`. Where almost everything around is old (explored land) there is no
 * trail to see, and the heading is kept.
 */
object TrailFinder {
    enum class State { FOLLOWING, EXPLORED, LOST }

    class Result(val state: State, val yaw: Float, val old: Int)

    /**
     * [yaw] is Minecraft's (0 faces +Z, 90 faces -X). [old] and [known] take chunk coordinates: whether a chunk is old,
     * and whether it has been loaded and classified at all. Looks [reach] chunks ahead.
     */
    fun steer(px: Double, pz: Double, yaw: Float, old: (Int, Int) -> Boolean, known: (Int, Int) -> Boolean,
              spread: Int = 60, reach: Int = 10, minOld: Int = 4): Result {
        val pcx = floor(px / 16).toInt()
        val pcz = floor(pz / 16).toInt()
        var knownCount = 0
        var oldCount = 0
        for (dx in -reach..reach) for (dz in -reach..reach) {
            if (!known(pcx + dx, pcz + dz)) continue
            knownCount++
            if (old(pcx + dx, pcz + dz)) oldCount++
        }
        if (knownCount >= MIN_KNOWN && oldCount >= knownCount * EXPLORED_SHARE) return Result(State.EXPLORED, yaw, 0)
        var bestYaw = yaw
        var best = -1
        var a = -spread
        while (a <= spread) {
            val y = yaw + a
            val n = oldAlong(px, pz, y, old, reach)
            // Ties go to the straighter way.
            if (n > best || n == best && abs(a) < abs(bestYaw - yaw)) {
                best = n
                bestYaw = y
            }
            a += STEP
        }
        return if (best >= minOld) Result(State.FOLLOWING, wrap(bestYaw), best) else Result(State.LOST, yaw, best.coerceAtLeast(0))
    }

    /** Old chunks met along [yaw] from 1.5 to [reach] chunks out, each counted once (sampled every half chunk). */
    fun oldAlong(px: Double, pz: Double, yaw: Float, old: (Int, Int) -> Boolean, reach: Int): Int {
        val r = Math.toRadians(yaw.toDouble())
        val dx = -sin(r)
        val dz = cos(r)
        val seen = HashSet<Long>()
        var n = 0
        var d = 24.0
        while (d <= reach * 16.0) {
            val cx = floor((px + dx * d) / 16).toInt()
            val cz = floor((pz + dz * d) / 16).toInt()
            if (seen.add(cx.toLong() shl 32 or (cz.toLong() and 0xFFFFFFFFL)) && old(cx, cz)) n++
            d += 8.0
        }
        return n
    }

    /**
     * How far along [yaw] the farthest old chunk is (looking 1.5 to [reach] chunks out), in blocks; 0 with none. Taken
     * on the heading [steer] follows, it's how far ahead the trail is seen to go: where it ends, once it stops going on.
     */
    fun farthestOld(px: Double, pz: Double, yaw: Float, old: (Int, Int) -> Boolean, reach: Int): Double {
        val r = Math.toRadians(yaw.toDouble())
        val dx = -sin(r)
        val dz = cos(r)
        var far = 0.0
        var d = 24.0
        while (d <= reach * 16.0) {
            if (old(floor((px + dx * d) / 16).toInt(), floor((pz + dz * d) / 16).toInt())) far = d
            d += 8.0
        }
        return far
    }

    private fun wrap(yaw: Float): Float {
        var y = yaw % 360f
        if (y >= 180f) y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    /** Chunks that must be classified around you before "everything is old" can mean explored land. */
    private const val MIN_KNOWN = 40

    /** Share of old chunks from which the land counts as explored, with no trail to pick out. */
    private const val EXPLORED_SHARE = 0.8

    /** Degrees between the directions tried. */
    private const val STEP = 5
}
