package dev.horizon.client.module.legit

import dev.horizon.client.module.attack.HumanAim
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Clutch's decisions and its hand, free of the game so the same code runs in the simulation (ClutchSimTest) as in
 * [Clutch]. Each tick [tick] gets the player, the world, what the crosshair is on and what the look the server already
 * has points at, plans with [ClutchPlanner], and says which cell (if any) to click now. Each frame [frame] says how far
 * to turn the camera: [HumanAim]'s hand, so a flick is a quick bell-shaped move that lands a little off and corrects,
 * not a constant-speed sweep, and it rests once on the face (the click waits until the look the server has is on that
 * block too).
 */
class ClutchBrain(private val rng: Random) {
    /** [reaction] seconds before the hand moves, a fall deeper than [minDrop] blocks to save, [turnSpeed] the hand's top speed (deg/s). */
    class Settings(val reaction: Double, val minDrop: Int, val turnSpeed: Double)

    /** The player at the start of a tick (feet, motion per tick, keys as vanilla's xxa/zza, look, size and physics). */
    class Player(
        val x: Double, val y: Double, val z: Double, val vx: Double, val vy: Double, val vz: Double, val onGround: Boolean,
        val strafe: Double, val forward: Double, val sprinting: Boolean, val yaw: Double, val pitch: Double,
        val halfWidth: Double, val height: Double, val eyeHeight: Double, val gravity: Double, val jumpPower: Double,
        val reach: Double, val tickCount: Int,
    )

    private val hand = HumanAim(rng)

    /** When the fall was first seen (seconds), NaN when not clutching. */
    var since = Double.NaN
        private set
    /** The line being built and the face being turned to for its next block, null with nothing to aim at. */
    var plan: ClutchPlanner.Plan? = null
        private set
    var catches: Map<Long, ClutchPlanner.Catch> = emptyMap()
        private set
    /** A fall to save, with blocks to save it: other modules leave the camera and the look sent to the server alone. */
    var clutching = false
        private set

    private var spotU = 0.5
    private var spotV = 0.5
    private var lastPlaceTick = -100
    private var keep: Long? = null
    private var groundY = Double.NaN
    /** The face the hand is on its way to (block key and face), to tell a new target from the same one. */
    private var target = Long.MIN_VALUE
    /** Ticks seen ([tick] calls), to tell which tick comes next. */
    private var tickNo = 0
    /** The fall as last played out (its path says where the eyes will be). */
    private var fall: ClutchPlanner.Fall? = null
    /** The tick the clutch started (the player's tick count). */
    private var sinceTick = 0
    /** The face the click tick is for, and the tick ([tickNo] count) its click is expected. */
    private var clickFace = Long.MIN_VALUE
    private var clickAt = 0
    private var settings = Settings(0.04, 4, 900.0)

    fun reset() {
        since = Double.NaN
        plan = null
        catches = emptyMap()
        clutching = false
        keep = null
        target = Long.MIN_VALUE
        hand.reset()
    }

    /**
     * One tick at [now] seconds. [blocks]: there are blocks to place (held or in the hotbar). [pick] is what the
     * crosshair is on (the click uses it), [sent] what the look last sent to the server points at from the same eyes.
     * [lateBy]: with no line done in time, how late (ticks) one may still be tried. Returns the cell to click now.
     */
    fun tick(
        now: Double, p: Player, world: ClutchPlanner.World, s: Settings, blocks: Boolean,
        pick: BridgePlanner.Hit?, sent: BridgePlanner.Hit?, usingItem: Boolean, lateBy: Int,
    ): Long? {
        settings = s
        tickNo++
        if (p.onGround) {
            groundY = p.y
            reset()
            return null
        }
        if (groundY.isNaN()) groundY = p.y
        val ey = p.y + p.eyeHeight
        // Held keys push along the camera, and the camera is turning: they're taken to push along where it's going
        // once the turn there (as long as the hand takes for it) is done, and along the way meanwhile.
        val airSpeed = if (p.sprinting) 0.026 else 0.02
        val to = plan?.let { HumanAim.yawTo(HumanAim.V(p.x, ey, p.z), HumanAim.V(it.ax, it.ay, it.az)) }
        val turn = if (to == null) 0.0 else HumanAim.wrap(to - p.yaw)
        val left = if (since.isNaN()) s.reaction else max(0.0, s.reaction - (now - since))
        val turnTicks = max(1.0, (left + FITTS_A + FITTS_B * ln(1.0 + abs(turn) / 5.0) / ln(2.0)) * 20.0)
        val air = BridgePlanner.push(p.strafe, p.forward, p.yaw, airSpeed)
        val params = BridgePlanner.Params(0.0, -1.0, 0.0, 0.0, 0.0, air[0], air[1],
            halfWidth = p.halfWidth, height = p.height, gravity = p.gravity, jumpPower = p.jumpPower)
        val push: ((Int) -> DoubleArray)? = if (to == null || (p.strafe == 0.0 && p.forward == 0.0)) null else { t ->
            BridgePlanner.push(p.strafe, p.forward, p.yaw + turn * min(1.0, (t + 1) / turnTicks), airSpeed)
        }
        val fall = ClutchPlanner.predict(BridgePlanner.State(p.x, p.y, p.z, p.vx, p.vy, p.vz, false), params, world,
            min(p.y, groundY), s.minDrop, push)
        catches = fall.catches
        this.fall = fall
        if (!fall.save) {
            // A safe landing ahead, for now: turning while a key is held swings the path about, so the clutch (and its
            // reaction and aim spot) is only over once the feet are down.
            plan = null
            return null
        }
        if (since.isNaN()) {
            since = now
            sinceTick = p.tickCount
            spotU = 0.3 + 0.4 * rng.nextDouble()
            spotV = 0.3 + 0.4 * rng.nextDouble()
            hand.retarget(handParams(s))
        }
        // Nothing to build with: the camera is left alone (a pearl, if Pearl Save has one, is the way out).
        if (!blocks) {
            plan = null
            clutching = false
            return null
        }
        clutching = true
        val wait = max(0, MIN_GAP - (p.tickCount - lastPlaceTick))
        val timing = ClutchPlanner.Timing(FITTS_A, FITTS_B, s.turnSpeed * SPEED_LO, max(0.0, s.reaction - (now - since)), wait, MIN_GAP)
        val look = ClutchPlanner.look(p.yaw, p.pitch)
        val next = ClutchPlanner.plan(p.x, ey, p.z, look[0], look[1], look[2], p.reach, p.eyeHeight, fall, world, timing,
            keep, spotU, spotV, lateBy)
        plan = next
        keep = next?.line?.firstOrNull()
        if (next != null) {
            // The click tick stays put for a face (the hand is aiming for the eyes then); one that has gone by without the
            // click is the next tick.
            val face = BridgePlanner.key(next.bx, next.by, next.bz) * 8 + next.face
            if (face != clickFace) {
                clickFace = face
                clickAt = tickNo + max(next.first, 1)
            } else if (clickAt <= tickNo) clickAt = tickNo + 1
        }
        val line = next?.line
        val cell = ClutchPlanner.clickCell(pick, sent, { k -> k in fall.catches || (line != null && k in line) }, world,
            p.x, p.y, p.z, p.halfWidth, p.height) ?: return null
        if (usingItem || p.tickCount - lastPlaceTick < MIN_GAP) return null
        return cell
    }

    /** A click at [tick] put [cell] in: the next block of the line goes against it. */
    fun placed(tick: Int, cell: Long) {
        lastPlaceTick = tick
        clickFace = Long.MIN_VALUE
        val line = plan?.line
        val i = line?.indexOf(cell) ?: -1
        keep = if (line != null && i >= 0 && i + 1 < line.size) line[i + 1] else null
        // Turn on to the next face at once (the next tick plans it properly), or rest with nothing left to place.
        val p = plan
        plan = if (p != null && line != null && i >= 0 && i + 1 < line.size) {
            val a = line[i]
            val b = line[i + 1]
            val dx = ClutchPlanner.unkeyX(b) - ClutchPlanner.unkeyX(a)
            val dz = ClutchPlanner.unkeyZ(b) - ClutchPlanner.unkeyZ(a)
            val face = when {
                dx > 0 -> ClutchPlanner.EAST
                dx < 0 -> ClutchPlanner.WEST
                dz > 0 -> ClutchPlanner.SOUTH
                else -> ClutchPlanner.NORTH
            }
            val ax = ClutchPlanner.unkeyX(a)
            val ay = ClutchPlanner.unkeyY(a)
            val az = ClutchPlanner.unkeyZ(a)
            val pt = ClutchPlanner.facePoint(ax, ay, az, face, spotU, spotV)
            ClutchPlanner.Plan(line.drop(i + 1), ax, ay, az, face, pt[0], pt[1], pt[2], p.size, p.ticks, p.slack, MIN_GAP)
        } else null
    }

    /**
     * One frame at [now] ([dt] seconds since the last): how far to turn (yaw, pitch degrees) from [yaw]/[pitch]. The feet
     * are at ([x], [y], [z]) when the next tick starts (the player's position, not the one drawn); the eyes are drawn at
     * ([ex], [ey], [ez]) this frame, moving ([evx], [evy], [evz]) blocks a second. Before the reaction is over, and with
     * nothing to aim at, the hand's last motion dies away instead.
     *
     * A click goes at the start of a tick, judged by the crosshair and by the look the server already has (sent the
     * tick before), both from where the eyes are at that moment. For the first block, the hand aims from where the eyes
     * will be at the tick its click is planned for (as the fall was played out): a target that holds still while the
     * knock carries you, so the flick lands and rests on it, and the look sent the tick before the click is already on
     * it from there. Right over the edge, the face is straight down and its direction from where you are swings about
     * with every step; from there it doesn't. Building on out, each next face is followed from the eyes as they're
     * drawn, your own fall made up for as it happens.
     */
    fun frame(
        now: Double, dt: Double, yaw: Double, pitch: Double, x: Double, y: Double, z: Double,
        ex: Double, ey: Double, ez: Double, evx: Double, evy: Double, evz: Double,
        eyeHeight: Double, reach: Double, world: ClutchPlanner.World,
    ): DoubleArray {
        val p = plan
        if (since.isNaN() || p == null || now - since < settings.reaction || dt <= 0.0) return hand.release(dt)
        val fl = fall
        val first = lastPlaceTick < sinceTick && fl != null
        val eye: HumanAim.V
        val eyeVel: HumanAim.V
        if (first) {
            val at = fl!!.feetAt(max(clickAt - tickNo, 1), x, y, z)
            eye = HumanAim.V(at[0], at[1] + eyeHeight, at[2])
            eyeVel = STILL
        } else {
            eye = HumanAim.V(ex, ey, ez)
            eyeVel = HumanAim.V(evx, evy, evz)
        }
        val key = BridgePlanner.key(p.bx, p.by, p.bz) * 8 + p.face
        val params = handParams(settings)
        if (key != target) {
            // A new face: what was seen of the last one doesn't carry over.
            target = key
            hand.retarget(params)
        }
        val c = ClutchPlanner.facePoint(p.bx, p.by, p.bz, p.face, 0.5, 0.5)
        val ext = faceExtent(eye.x, eye.y, eye.z, p.bx, p.by, p.bz, p.face)
        val l = ClutchPlanner.look(yaw, pitch)
        val h = world.ray(eye.x, eye.y, eye.z, l[0], l[1], l[2], reach)
        val onFace = h != null && h.x == p.bx && h.y == p.by && h.z == p.bz && h.face == p.face
        return hand.step(now, dt, yaw, pitch, eye, eyeVel, HumanAim.V(p.ax, p.ay, p.az), HumanAim.V(c[0], c[1], c[2]),
            ext[0], ext[1], onFace, params, size = max(min(ext[0], ext[1]), MIN_SIZE))
    }

    /** How wide and tall (degrees of yaw and pitch) a face looks from the eyes. */
    private fun faceExtent(ex: Double, ey: Double, ez: Double, bx: Int, by: Int, bz: Int, face: Int): DoubleArray {
        val eye = HumanAim.V(ex, ey, ez)
        val c = ClutchPlanner.facePoint(bx, by, bz, face, 0.5, 0.5)
        val cy = HumanAim.yawTo(eye, HumanAim.V(c[0], c[1], c[2]))
        var y0 = Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        var p0 = Double.MAX_VALUE
        var p1 = -Double.MAX_VALUE
        for (u in 0..1) for (v in 0..1) {
            // facePoint scales the spot by 0.8 (off the edges): these reach the corners.
            val pt = ClutchPlanner.facePoint(bx, by, bz, face, u * 1.25 - 0.125, v * 1.25 - 0.125)
            val at = HumanAim.V(pt[0], pt[1], pt[2])
            val dy = HumanAim.wrap(HumanAim.yawTo(eye, at) - cy)
            val pp = HumanAim.pitchTo(eye, at)
            y0 = min(y0, dy)
            y1 = max(y1, dy)
            p0 = min(p0, pp)
            p1 = max(p1, pp)
        }
        return doubleArrayOf(max(y1 - y0, 0.3), max(p1 - p0, 0.3))
    }

    private fun handParams(s: Settings) = HumanAim.Params(
        speedLo = s.turnSpeed * SPEED_LO, speedHi = s.turnSpeed, fittsA = FITTS_A, fittsB = FITTS_B,
        perceptionDelay = 0.03, overshoot = 0.08, aimError = 0.03, curve = 0.05, tracking = 0.0, pitchScale = 1.0,
        relax = 0.5, tremor = 0.25,
    )

    companion object {
        /** Fewest ticks between two placements: ten a second, a quick player's right-click. */
        const val MIN_GAP = 2
        private val STILL = HumanAim.V(0.0, 0.0, 0.0)
        /** Fitts's law for the hand: a flick of 60 degrees onto a face a few degrees across takes about a fifth of a second. */
        const val FITTS_A = 0.04
        const val FITTS_B = 0.05
        /**
         * The smallest target (degrees) a movement is timed for: a face seen edge-on grows as the knock carries you out,
         * so the hand moves for the face as it's about to look, not the sliver it is now.
         */
        const val MIN_SIZE = 3.0
        /** The slowest top speed the hand picks, as a share of `turn-speed`. */
        const val SPEED_LO = 0.75
    }
}
