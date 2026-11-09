package dev.horizon.client.module.legit

import dev.horizon.client.module.attack.HumanAim
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clutch played out against knockbacks off an island's edge, tick by tick and frame by frame, with the same
 * [ClutchBrain] the module uses: vanilla's air physics ([BridgePlanner.step]), frames at about 144 fps turning the
 * camera in whole mouse counts, the look sent to the server at the end of each tick, and a server that only takes a
 * click when the look it already has is on the clicked face (as a 1.8 server's anticheat judges it).
 *
 * The island: x -10..0, y -3..-1 (its top at y 0, its east side the face at x 1), z -8..8. The player stands near the
 * east edge and is knocked east.
 */
class ClutchSimTest {
    class SimWorld : ClutchPlanner.World {
        val cells = HashSet<Long>()
        val solidFn = { x: Int, y: Int, z: Int -> BridgePlanner.key(x, y, z) in cells }
        override fun solid(x: Int, y: Int, z: Int) = BridgePlanner.key(x, y, z) in cells
        override fun free(x: Int, y: Int, z: Int) = !solid(x, y, z)
        override fun support(x: Int, y: Int, z: Int) = solid(x, y, z)
        override fun ray(ex: Double, ey: Double, ez: Double, dx: Double, dy: Double, dz: Double, reach: Double) =
            ClutchPlanner.voxelRay(solidFn, ex, ey, ez, dx, dy, dz, reach)
    }

    /** A knock: where the player stands (x over the edge block, z), the motion given, the camera, and the keys held. */
    class Case(
        val x: Double, val z: Double, val vx: Double, val vy: Double, val vz: Double, val yaw: Double, val pitch: Double,
        val forward: Double = 0.0, val strafe: Double = 0.0, val sprint: Boolean = false, val seed: Long = 1,
    ) {
        override fun toString() = "x %.2f v (%.2f %.2f %.2f) yaw %.0f pitch %.0f keys %.0f/%.0f%s".format(
            x, vx, vy, vz, yaw, pitch, forward, strafe, if (sprint) " sprint" else "")
    }

    class Result(
        val saved: Boolean, val landY: Double, val placed: Int, val refused: Int, val turns: List<Double>,
        val clickTicks: List<Int>, val firstLine: Int = 0, val firstSlack: Int = 0, val firstOnFace: Int = -1,
    ) {
        val peak get() = turns.maxOrNull() ?: 0.0
    }

    companion object {
        const val EYE = 1.62
        const val REACH = 4.5
        val SETTINGS = ClutchBrain.Settings(reaction = 0.04, minDrop = 10, turnSpeed = 900.0)

        fun island(w: SimWorld) {
            for (x in -10..0) for (y in -3..-1) for (z in -8..8) w.cells += BridgePlanner.key(x, y, z)
        }

        /** Plays one knock out: up to four seconds, or until the player lands or has fallen past saving. */
        fun run(c: Case, settings: ClutchBrain.Settings = SETTINGS, lateBy: Int = 8, log: Boolean = false): Result {
            val rng = Random(c.seed)
            val world = SimWorld()
            island(world)
            val brain = ClutchBrain(Random(c.seed * 31 + 7))
            val s = BridgePlanner.State(c.x, 0.0, c.z, c.vx, c.vy, c.vz, true)
            var yaw = c.yaw
            var pitch = c.pitch
            var sentYaw = yaw
            var sentPitch = pitch
            var carryX = 0.0
            var carryY = 0.0
            var now = 10.0
            var frameClock = now
            val turns = ArrayList<Double>()
            val clicks = ArrayList<Int>()
            var placed = 0
            var refused = 0
            var px = s.x
            var py = s.y
            var pz = s.z
            var firstLine = 0
            var firstSlack = 0
            var firstOnFace = -1
            for (tick in 0 until 80) {
                // The tick: Clutch first (Minecraft.tick's head), then the player's move, then the look is sent.
                val eyeY = s.y + EYE
                val look = ClutchPlanner.look(yaw, pitch)
                val pick = world.ray(s.x, eyeY, s.z, look[0], look[1], look[2], REACH)
                val sl = ClutchPlanner.look(sentYaw, sentPitch)
                val sent = world.ray(s.x, eyeY, s.z, sl[0], sl[1], sl[2], REACH)
                val player = ClutchBrain.Player(s.x, s.y, s.z, s.vx, s.vy, s.vz, s.onGround, c.strafe, c.forward, c.sprint,
                    yaw, pitch, 0.3, 1.8, EYE, 0.08, 0.42, REACH, tick)
                val cell = brain.tick(now, player, world, settings, true, pick, sent, false, lateBy)
                brain.plan?.let { pl ->
                    if (firstLine == 0) {
                        firstLine = pl.line.size
                        firstSlack = pl.slack
                    }
                    if (firstOnFace < 0 && pick != null && pick.x == pl.bx && pick.y == pl.by && pick.z == pl.bz && pick.face == pl.face) firstOnFace = tick
                }
                if (cell != null) {
                    // The server: the look it has (sent last tick) must be on the clicked block, within reach.
                    val ok = sent != null && pick != null && sent.x == pick.x && sent.y == pick.y && sent.z == pick.z &&
                        sent.distance <= REACH
                    if (ok) {
                        world.cells += cell
                        placed++
                        clicks += tick
                        brain.placed(tick, cell)
                        if (log) println("  t$tick place ${ClutchPlanner.unkeyX(cell)} ${ClutchPlanner.unkeyY(cell)} ${ClutchPlanner.unkeyZ(cell)}")
                    } else refused++
                }
                px = s.x
                py = s.y
                pz = s.z
                val ground = BridgePlanner.push(c.strafe, c.forward, yaw, if (c.sprint) 0.13 else 0.1)
                val air = BridgePlanner.push(c.strafe, c.forward, yaw, if (c.sprint) 0.026 else 0.02)
                val params = BridgePlanner.Params(0.0, -1.0, 0.0, ground[0], ground[1], air[0], air[1])
                BridgePlanner.step(s, params, world.solidFn, false, walls = true)
                turns += hypot(HumanAim.wrap(yaw - sentYaw), pitch - sentPitch)
                sentYaw = yaw
                sentPitch = pitch
                if (log) {
                    val p = brain.plan
                    fun hs(h: BridgePlanner.Hit?) = h?.let { "${it.x},${it.y},${it.z}:${it.face}" } ?: "-"
                    println("  t$tick feet %.2f %.2f %.2f  look %.1f %.1f  turn %.1f  pick %s sent %s  plan %s".format(s.x, s.y, s.z, yaw, pitch,
                        turns.last(), hs(pick), hs(sent), p?.let { "${it.line.size} blocks slack ${it.slack} face ${it.bx},${it.by},${it.bz}:${it.face}" } ?: "-"))
                }
                if (tick > 0 && s.onGround) return Result(true, s.y, placed, refused, turns, clicks, firstLine, firstSlack, firstOnFace)
                if (s.y < -16) return Result(false, s.y, placed, refused, turns, clicks, firstLine, firstSlack, firstOnFace)
                // The frames until the next tick: the eyes drawn between the last position and this one.
                val tickEnd = now + 0.05
                while (frameClock < tickEnd) {
                    val dt = (1.0 / 144) * (0.8 + 0.4 * rng.nextDouble())
                    frameClock += dt
                    val pt = ((frameClock - now) / 0.05).coerceIn(0.0, 1.0)
                    val d = brain.frame(frameClock, dt, yaw, pitch, s.x, s.y, s.z, px + (s.x - px) * pt, py + (s.y - py) * pt + EYE,
                        pz + (s.z - pz) * pt, (s.x - px) * 20, (s.y - py) * 20, (s.z - pz) * 20, EYE, REACH, world)
                    // Whole mouse counts at sensitivity 0.5 (0.15 degrees a count).
                    carryX += d[0] / 0.15
                    carryY += d[1] / 0.15
                    val cx = carryX.toInt()
                    val cy = carryY.toInt()
                    carryX -= cx
                    carryY -= cy
                    yaw += cx * 0.15
                    pitch = (pitch + cy * 0.15).coerceIn(-90.0, 90.0)
                }
                now = tickEnd
            }
            return Result(false, s.y, placed, refused, turns, clicks, firstLine, firstSlack, firstOnFace)
        }

        /** A plain (not sprinting) hit as Hypixel gives it, from a random facing, with random keys held. */
        fun plainHit(rng: Random, seed: Long): Case {
            val h = 0.26 + 0.18 * rng.nextDouble()
            val ang = Math.toRadians(-30.0 + 60.0 * rng.nextDouble())
            val keys = rng.nextDouble()
            val (fwd, str) = when {
                keys < 0.4 -> 0.0 to 0.0
                keys < 0.65 -> 0.98 to 0.0
                keys < 0.75 -> -0.98 to 0.0
                keys < 0.88 -> 0.0 to 0.98
                else -> 0.0 to -0.98
            }
            // Mostly facing whoever is on the island (yaw 90 looks west), sometimes anywhere.
            val yaw = if (rng.nextDouble() < 0.6) 90.0 + (-45.0 + 90.0 * rng.nextDouble()) else 360.0 * rng.nextDouble() - 180.0
            return Case(
                x = 0.3 + 0.95 * rng.nextDouble(), z = -2.0 + 4.0 * rng.nextDouble(),
                vx = h * cos(ang), vy = 0.30 + 0.12 * rng.nextDouble(), vz = h * sin(ang),
                yaw = yaw, pitch = -10.0 + 35.0 * rng.nextDouble(),
                forward = fwd, strafe = str, sprint = fwd > 0 && rng.nextDouble() < 0.4, seed = seed,
            )
        }

        fun plainHits(n: Int, seed: Long = 42): List<Case> {
            val rng = Random(seed)
            return (0 until n).map { plainHit(rng, seed * 1000 + it) }
        }
    }

    /**
     * Plain hits from near the edge, whatever the facing and keys: most are saved, and nearly all of those one block
     * against the island's side saves. A line of two or three blocks out is far harder: its last face is seen almost
     * edge-on from right above it (an instant aim saves nearly all of them; a hand, a good share).
     */
    @Test
    fun plainHitsAreSaved() {
        val cases = plainHits(400)
        val results = cases.map { run(it) }
        val rate = results.count { it.saved }.toDouble() / cases.size
        val single = results.filter { it.firstLine == 1 }
        val singleRate = single.count { it.saved }.toDouble() / single.size
        println("plain hits: saved %.1f%%, one-block saves %.1f%% of %d".format(rate * 100, singleRate * 100, single.size))
        assertTrue(rate >= 0.78, "saved only %.1f%%".format(rate * 100))
        assertTrue(singleRate >= 0.93, "one-block saves only %.1f%%".format(singleRate * 100))
    }

    /** Every click the module makes is one the server takes: the look it already had was on the clicked block. */
    @Test
    fun everyClickIsOnTheBlockWithTheLookAlreadySent() {
        val results = plainHits(200, seed = 7).map { run(it) }
        assertEquals(0, results.sumOf { it.refused })
        assertTrue(results.sumOf { it.placed } > 100)
    }

    /**
     * The turn as the server sees it (per tick) is a hand's: no turn over 50 degrees a tick, a median peak well under
     * the old clutch's 45, no sweep at the turn speed (three ticks in a row at it, as the old one turned), and
     * flicks that mostly start slower than their peak (a bell; one over in a tick and a half can't show it per tick).
     */
    @Test
    fun turnsLikeAHand() {
        val results = plainHits(200, seed = 11).map { run(it) }
        val peaks = results.map { it.peak }.sorted()
        println("peak turn per tick: median %.1f, 90th %.1f, max %.1f".format(peaks[peaks.size / 2], peaks[peaks.size * 9 / 10], peaks.last()))
        assertTrue(peaks.last() <= 50.0, "a turn of %.1f degrees in a tick".format(peaks.last()))
        assertTrue(peaks[peaks.size / 2] <= 35.0, "median peak %.1f".format(peaks[peaks.size / 2]))
        val starts = ArrayList<Double>()
        for (r in results) {
            val peak = r.peak
            if (peak <= 20.0) continue
            // turn-speed 900 is 45 degrees a tick.
            val cap = SETTINGS.turnSpeed / 20.0 * 0.95
            for (i in 0 until r.turns.size - 2) {
                val flat = r.turns[i] >= cap && r.turns[i + 1] >= cap && r.turns[i + 2] >= cap
                assertTrue(!flat, "a sweep at the turn speed: " + r.turns.joinToString(" ") { "%.1f".format(it) })
            }
            val first = r.turns.indexOfFirst { it > 0.5 }
            starts += r.turns[first] / peak
        }
        starts.sort()
        assertTrue(starts[starts.size / 2] < 0.6, "flicks start at %.2f of their peak".format(starts[starts.size / 2]))
    }

    /** Knocked too far out for any block in reach: nothing is placed (Pearl Save's turn), with no late line allowed. */
    @Test
    fun nothingPlacedForAHitTooFarToSave() {
        val r = run(Case(0.6, 0.0, 0.9, 0.5, 0.0, 90.0, 10.0), lateBy = 0)
        assertTrue(!r.saved)
        assertEquals(0, r.placed)
    }

    /** A light hit right at the edge, facing the island (whoever hit you is on it): one block, every time. */
    @Test
    fun lightHitFacingTheIslandIsAlwaysSaved() {
        for (seed in 1L..30L) {
            val r = run(Case(0.9, 0.5, 0.28, 0.3, 0.0, 90.0, 10.0, seed = seed))
            assertTrue(r.saved, "seed $seed: fell, placed ${r.placed}")
        }
    }

    /**
     * The dev client's case A: facing the island, a plain hit 0.4 out and 0.4 up from 0.4 inside the edge, which takes
     * a line of two. About half are saved; facing away (a turn all the way round first) far fewer.
     */
    @Test
    fun plainHitFromInsideTheEdge() {
        var facing = 0
        var away = 0
        for (seed in 1L..40L) {
            if (run(Case(0.6, 0.5, 0.4, 0.4, 0.0, 90.0, 10.0, seed = seed)).saved) facing++
            if (run(Case(0.9, 0.5, 0.28, 0.3, 0.0, -90.0, 5.0, seed = seed)).saved) away++
        }
        println("case A facing the island: $facing/40; a light hit facing away: $away/40")
        assertTrue(facing >= 14, "case A saved $facing of 40")
        assertTrue(away >= 10, "facing away saved $away of 40")
    }
}
