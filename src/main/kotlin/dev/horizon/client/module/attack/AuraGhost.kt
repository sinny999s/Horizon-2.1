package dev.horizon.client.module.attack

import dev.horizon.client.combat.FightGear
import dev.horizon.client.combat.FightOdds
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.config.Friends
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.Sprint
import dev.horizon.client.module.legit.Teams
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

private val mc get() = Minecraft.getInstance()

/** The sums behind Aura's `ghost` settings, free of the game so they can be tested. */
object GhostMath {
    /**
     * How hard a fight presses you, 0 (you're well ahead) to 1 (you're behind or outnumbered). [score] is the fight's odds
     * ([FightOdds.score]: the log of their time to kill you over yours to kill them, so above 0 is winning), and
     * [othersNear] counts enemies besides the target close enough to join in.
     */
    fun pressure(score: Double, othersNear: Int): Double {
        val odds = 1.0 / (1.0 + exp(score / 0.25))
        return (odds + 0.2 * othersNear.coerceIn(0, 2)).coerceIn(0.0, 1.0)
    }

    /** One step of [current] towards [goal]: up within about [up] seconds, down within [down] (a person tries hard at once, and eases off slowly). */
    fun follow(current: Double, goal: Double, dt: Double, up: Double = 0.4, down: Double = 2.5): Double {
        val tau = if (goal > current) up else down
        return current + (goal - current) * (1.0 - exp(-dt / tau))
    }

    /** How strong Aura plays (from [easy] to 1) under [pressure]. */
    fun strength(easy: Double, pressure: Double): Double = easy + (1.0 - easy) * pressure.coerceIn(0.0, 1.0)

    // What a strength below 1 does to the hand and the clicking.
    fun speedScale(s: Double) = 0.55 + 0.45 * s
    fun lagScale(s: Double) = 1.0 + (1.0 - s) * 0.8
    fun errorScale(s: Double) = 1.0 + (1.0 - s) * 1.2
    fun trackScale(s: Double) = 0.7 + 0.3 * s
    fun cpsScale(s: Double) = 0.7 + 0.3 * s

    /**
     * The chance a ready hit turns into a swing that misses, from what makes a person miss. [base] is `smart-misses`, the
     * rate it stays near in an ordinary fight. More: in the last half block of [reach] ([dist] from your eyes to their box),
     * the target crossing your view fast ([crossDegS], degrees a second), a flick just made ([flickDeg], how far the look
     * turned over the last few ticks), being off balance ([unsettled]: in the air or just hit) and a low [strength]. Next
     * to none: someone standing still close in front of you.
     */
    fun missChance(base: Double, dist: Double, reach: Double, crossDegS: Double, flickDeg: Double, unsettled: Boolean, strength: Double): Double {
        if (base <= 0.0) return 0.0
        val edge = ((dist - (reach - 0.5)) / 0.5).coerceIn(0.0, 1.0)
        val cross = (crossDegS / 120.0).coerceIn(0.0, 1.0)
        val flick = (flickDeg / 40.0).coerceIn(0.0, 1.0)
        // Weighted so that over an ordinary fight's moments it averages about [base].
        var m = base * (0.15 + edge * 0.8 + cross * 0.6 + flick * 0.8 + if (unsettled) 0.35 else 0.0)
        m *= 1.0 + (1.0 - strength).coerceIn(0.0, 1.0) * 1.5
        return m.coerceIn(0.0, 0.6)
    }

    /**
     * Degrees to aim to the side of the target's middle (+ towards higher yaw) so a hit's knockback leans towards a drop.
     * A hit pushes along your yaw: straight at them is [pushYaw]. [dropYaw] is the direction from them of the nearest drop
     * and [dropDist] how far it is; [halfWidth] is half their box's width in degrees. Nothing when the drop is behind them as
     * you see them (you'd push them away from it) or already straight ahead; never past most of their box.
     */
    fun voidOffset(pushYaw: Double, dropYaw: Double, dropDist: Double, halfWidth: Double): Double {
        val d = wrap(dropYaw - pushYaw)
        if (abs(d) > 100.0 || abs(d) < 1.0) return 0.0
        val near = (1.0 - (dropDist - 0.6) / 3.0).coerceIn(0.3, 1.0)
        return sign(d) * min(abs(d), halfWidth * 0.7 * near)
    }

    /**
     * `sprint-sync`'s call for one ready hit: wait a tick for sprint? Only when the hit would land ([canBeHurt]), you're not
     * [sprinting] but [wantSprint], and this window's [budget] of waits isn't used up ([used]). Not when they look about to
     * hit you: a read made [threatTicks] ticks in a row counts once it has held for two (noticed a tick late), and is
     * wrong whenever [misjudgeRoll] falls under [misjudge].
     */
    fun holdForSprint(
        canBeHurt: Boolean, sprinting: Boolean, wantSprint: Boolean, used: Int, budget: Int, threatTicks: Int,
        misjudgeRoll: Double, misjudge: Double = 0.15,
    ): Hold {
        if (!canBeHurt || sprinting || !wantSprint || used >= budget) return Hold.NO
        val seen = threatTicks >= 2
        val threat = if (misjudgeRoll < misjudge) !seen else seen
        return if (threat) Hold.THREAT else Hold.WAIT
    }

    enum class Hold { NO, WAIT, THREAT }

    /** Minecraft's yaw (degrees) of a horizontal direction: 0 is +z, 90 is -x. */
    fun yawOf(dx: Double, dz: Double): Double = Math.toDegrees(atan2(dz, dx)) - 90.0

    fun wrap(a: Double): Double {
        var r = a % 360.0
        if (r >= 180.0) r -= 360.0
        if (r < -180.0) r += 360.0
        return r
    }
}

/**
 * Aura's `ghost` settings, for its LEGIT rotations: how hard the fight is pressing you ([strength], for
 * `adaptive-strength`), when a ready hit turns into a miss (`smart-misses`), where on the target to aim so the hit's
 * knockback goes towards a drop (`void-knockback`), and holding a hit a moment for sprint (`sprint-sync`).
 */
class GhostHook(private val aura: Aura) : AuraHook {
    /** How strong Aura plays now, from `easy-strength` to 1; 1 without `adaptive-strength`. */
    var strength = 1.0
        private set
    var pressure = 0.5
        private set

    private val odds = FightOdds()
    private var oddsFor: Entity? = null
    private var lastTargetHurt = 0
    private var lastTick = 0L
    private var lastFight = 0L

    // smart-misses: how far the look sent turned in each of the last ticks, and how fast the target crosses the view.
    private val turns = DoubleArray(3)
    private var turnIdx = 0
    private var lastYaw = Float.NaN
    private var crossFor: Entity? = null
    private var lastDir: Vec3? = null
    private var crossDegS = 0.0

    // sprint-sync: ticks held since the target could last be hurt, how many this window may take, the target's last swing,
    // and for how many ticks it has looked like they're about to hit you.
    private var holdUsed = 0
    private var holdBudget = 2
    private var swingFor: Entity? = null
    private var lastSwing = 0L
    private var threatTicks = 0

    // void-knockback: the drop nearest the target (direction from them and distance), looked for every few ticks.
    private var dropFor: Entity? = null
    private var dropAt = -100
    private var dropYaw: Double? = null
    private var dropDist = 0.0
    /** void-knockback is leaning the aim this frame: the hand then corrects within its dead zone too ([relaxScale]). */
    var steering = false
        private set
    private var ticks = 0

    private fun legit() = aura.effectiveRotation() === LegitRotation

    override fun tick() {
        ticks++
        val p = mc.player ?: return
        val now = System.nanoTime()
        val dt = if (lastTick == 0L) 0.05 else ((now - lastTick) / 1e9).coerceIn(0.0, 0.25)
        lastTick = now
        val sent = Rotation.lastYaw
        if (!lastYaw.isNaN()) {
            turns[turnIdx] = abs(Mth.wrapDegrees(sent - lastYaw)).toDouble()
            turnIdx = (turnIdx + 1) % turns.size
        }
        lastYaw = sent
        val t = aura.target as? LivingEntity
        if (t == null) {
            holdUsed = 0
            threatTicks = 0
            crossFor = null
            steering = false
            return
        }
        fightOdds(p, t, now, dt)
        crossing(t, dt)
        if (t !== swingFor) {
            swingFor = t
            lastSwing = 0L
        }
        if (t.isSwinging) lastSwing = now
        threatTicks = if (threatNow(p, t, now)) threatTicks + 1 else 0
        // Nothing to wait for: they can't be hurt right now, or you're sprinting already.
        if (t.hurtTime > 1 || PlayerState.serverSprinting) holdUsed = 0
        // Looked for with void-knockback off too (it's cheap), so the hits' lean towards a drop can be compared.
        if (legit() && (t !== dropFor || ticks - dropAt >= 3)) findDrop(t)
    }

    /** `adaptive-strength`: the fight's odds as Target Info judges them, and how many others are close. */
    private fun fightOdds(p: LocalPlayer, t: LivingEntity, now: Long, dt: Double) {
        if (!aura.adaptiveStrength.value || !legit()) {
            strength = 1.0
            return
        }
        val fresh = now - lastFight > 3_000_000_000L
        if (t !== oddsFor) {
            odds.reset()
            oddsFor = t
            lastTargetHurt = t.hurtTime
        }
        val landed = t.hurtTime > lastTargetHurt
        lastTargetHurt = t.hurtTime
        odds.update(FightOdds.Sample(
            myHp = p.health + p.absorptionAmount, theirHp = t.health + t.absorptionAmount,
            myTaken = FightGear.taken(p), theirTaken = FightGear.taken(t),
            myHit = FightGear.hit(p), theirHit = FightGear.hit(t),
            landed = landed, theirMax = t.maxHealth + t.absorptionAmount,
        ))
        val goal = GhostMath.pressure(odds.score.toDouble(), othersNear(p, t))
        // A new fight after a lull starts where it stands; within one, it moves like a person warming up or easing off.
        pressure = if (fresh) goal else GhostMath.follow(pressure, goal, dt)
        lastFight = now
        strength = GhostMath.strength(aura.easyStrength.value / 100.0, pressure)
    }

    /** Enemy players besides [t] within 6 blocks: they can join in. */
    private fun othersNear(p: LocalPlayer, t: Entity): Int {
        var n = 0
        for (e in PlayerState.players) {
            if (e === p || e === t || !e.isAlive || e.isSpectator || e.isCreative) continue
            if (p.distanceToSqr(e) > 36.0) continue
            if (mc.connection?.getPlayerInfo(e.uuid) == null) continue
            if (Friends.isFriend(e.gameProfile.name) || Teams.isTeammateOrNew(e)) continue
            n++
        }
        return n
    }

    /** How fast the target's middle moves across your view, smoothed over about a tenth of a second. */
    private fun crossing(t: Entity, dt: Double) {
        val dir = t.boundingBox.center.subtract(PlayerState.eye).normalize()
        val prev = lastDir
        if (t === crossFor && prev != null && dt > 0.0) {
            val inst = Math.toDegrees(acos(prev.dot(dir).coerceIn(-1.0, 1.0))) / dt
            crossDegS += (inst - crossDegS) * 0.5
        } else {
            crossDegS = 0.0
        }
        crossFor = t
        lastDir = dir
    }

    /**
     * They look ready to hit you: a player who swung lately, within reach of you, facing you, while you can be hurt. What
     * [holdForSprint] reads a tick late, as a person would, and now and then gets wrong.
     */
    private fun threatNow(p: LocalPlayer, t: LivingEntity, now: Long): Boolean {
        val e = t as? Player ?: return false
        if (p.hurtTime > 0 || now - lastSwing > 450_000_000L) return false
        val eye = e.eyePosition
        val box = p.boundingBox
        if (eye.distanceTo(closest(box, eye)) > 3.3) return false
        val to = box.center.subtract(eye).normalize()
        return e.getViewVector(1f).dot(to) > THREAT_COS
    }

    /**
     * `sprint-sync` (1.8 combat): a hit that is about to land while you're not sprinting but trying to waits a tick or
     * two for sprint to come back, so it lands with sprint's knockback. Not when they look ready to hit you first: then the
     * hit goes now, to land before theirs. Not every time, and the wait is never the same.
     */
    fun holdForSprint(t: Entity): Boolean {
        if (!aura.sprintSync.value || !legit() || !aura.isLegacyCombat()) return false
        val p = mc.player ?: return false
        val lt = t as? LivingEntity ?: return false
        return when (GhostMath.holdForSprint(
            lt.hurtTime <= 1, PlayerState.serverSprinting, wantsSprint(p), holdUsed, holdBudget, threatTicks, Random.nextDouble(), MISJUDGE
        )) {
            GhostMath.Hold.NO -> false
            GhostMath.Hold.THREAT -> {
                stats.threatHits++
                false
            }
            GhostMath.Hold.WAIT -> {
                holdUsed++
                stats.holds++
                true
            }
        }
    }

    /** Forward and sprint held (or the Sprint module on), and nothing that stops sprinting. */
    private fun wantsSprint(p: LocalPlayer): Boolean {
        val o = mc.options
        val forward = o.keyUp.isDown || PlayerState.physical(o.keyUp)
        val sprint = o.keySprint.isDown || PlayerState.physical(o.keySprint) || Modules.get<Sprint>().enabled
        return forward && sprint && !p.isUsingItem && !p.isShiftKeyDown && p.foodData.foodLevel > 6 && !p.horizontalCollision &&
            !p.isInWater && !p.hasEffect(MobEffects.BLINDNESS)
    }

    /** `smart-misses`: the chance this ready hit becomes a miss-swing ([GhostMath.missChance]). */
    fun missChance(t: Entity): Double {
        val base = aura.smartMisses.value / 100.0
        if (base <= 0.0 || !legit()) return 0.0
        val p = mc.player ?: return 0.0
        val eye = PlayerState.eye
        val dist = eye.distanceTo(closest(t.boundingBox, eye))
        val unsettled = !p.onGround() || p.hurtTime > 0
        return GhostMath.missChance(base, dist, aura.reach.value.toDouble(), crossDegS, turns.sum(), unsettled, strength)
    }

    /**
     * `void-knockback`: the spot on [t]'s box (fractions across x and z) to aim at from [eye] so the hit pushes them
     * towards the nearest drop, or null to leave the aim alone.
     */
    fun voidSpot(t: Entity, eye: Vec3): Vec3? {
        steering = false
        if (!aura.voidKnockback.value || t !== dropFor) return null
        val dy = dropYaw ?: return null
        val b = t.boundingBox
        val c = b.center
        val dx = c.x - eye.x
        val dz = c.z - eye.z
        val flat = max(hypot(dx, dz), 0.3)
        val push = GhostMath.yawOf(dx, dz)
        val half = Math.toDegrees(atan(b.xsize / 2.0 / flat))
        val off = GhostMath.voidOffset(push, dy, dropDist, half)
        if (off == 0.0) return null
        val y = Math.toRadians(push + off)
        val px = eye.x - sin(y) * flat
        val pz = eye.z + cos(y) * flat
        steering = true
        lastOffset = off
        return Vec3(((px - b.minX) / b.xsize).coerceIn(0.15, 0.85), 0.0, ((pz - b.minZ) / b.zsize).coerceIn(0.15, 0.85))
    }

    private var lastOffset = 0.0

    /**
     * `hitbox-relax` leaves aim errors within most of the box alone; the lean is smaller than that, so while it's wanted
     * the dead zone shrinks and the hand makes the small correction to that side.
     */
    fun relaxScale() = if (steering) 0.2 else 1.0

    /**
     * The nearest drop around [t]: in 16 directions, out to 3.6 blocks, the first column with nothing to stand on for 10
     * blocks down (Bedwars' void, or a fall that ends the fight). A drop the hit would push them along counts as nearer.
     */
    private fun findDrop(t: Entity) {
        dropFor = t
        dropAt = ticks
        dropYaw = null
        val level = mc.level ?: return
        val feet = t.position()
        val eye = PlayerState.eye
        val push = GhostMath.yawOf(feet.x - eye.x, feet.z - eye.z)
        var best = Double.MAX_VALUE
        for (k in 0 until 16) {
            val a = k * 22.5
            val diff = abs(GhostMath.wrap(a - push))
            if (diff > 100.0) continue
            val fx = -sin(Math.toRadians(a))
            val fz = cos(Math.toRadians(a))
            for (d in DROP_STEPS) {
                if (standable(level, feet.x + fx * d, feet.y, feet.z + fz * d)) continue
                val score = d / max(0.25, cos(Math.toRadians(diff)))
                if (score < best) {
                    best = score
                    dropYaw = a
                    dropDist = d
                }
                break
            }
        }
    }

    /** Something to stand on within 10 blocks below ([x], [y], [z]), or a wall at that height (no drop past it). */
    private fun standable(level: Level, x: Double, y: Double, z: Double): Boolean {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val by = floor(y + 0.01).toInt()
        val pos = BlockPos.MutableBlockPos()
        for (yy in by + 1 downTo by - 10) {
            pos.set(bx, yy, bz)
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return true
        }
        return false
    }

    override fun post(rotation: Rotation) {
        // A hit went out: the next window may wait for sprint again, a different number of ticks (or not at all).
        holdUsed = 0
        val r = Random.nextDouble()
        holdBudget = when {
            r < 0.12 -> 0
            r < 0.6 -> 1
            r < 0.9 -> 2
            else -> 3
        }
        stats.hits++
        if (steering) stats.steered++
        // How far this hit's yaw leaned towards the drop beside the target, if there is one.
        val t = aura.target ?: return
        val dy = dropYaw ?: return
        if (t !== dropFor) return
        val c = t.boundingBox.center
        val eye = PlayerState.eye
        val push = GhostMath.yawOf(c.x - eye.x, c.z - eye.z)
        val d = GhostMath.wrap(dy - push)
        if (abs(d) < 1.0 || abs(d) > 100.0) return
        stats.lean += GhostMath.wrap(rotation.yaw - push) * sign(d)
        stats.leanHits++
    }

    /** A ready hit turned into a miss-swing. */
    fun missed() {
        stats.misses++
    }

    /** Counters for the test hook. */
    class Stats {
        var holds = 0
        var threatHits = 0
        var hits = 0
        var misses = 0
        var steered = 0
        var swaps = 0
        var swapsBack = 0
        var fumbles = 0
        /** Sum of hits' yaw lean towards a drop beside the target (degrees), over [leanHits] hits. */
        var lean = 0.0
        var leanHits = 0
    }

    companion object {
        private val DROP_STEPS = doubleArrayOf(0.6, 1.2, 1.8, 2.4, 3.0, 3.6)
        /** Within 25 degrees of looking straight at you. */
        private val THREAT_COS = cos(Math.toRadians(25.0))
        /** How often a read of whether they're about to hit is wrong. */
        private const val MISJUDGE = 0.15

        @JvmField
        val stats = Stats()

        private fun closest(b: AABB, from: Vec3) =
            Vec3(from.x.coerceIn(b.minX, b.maxX), from.y.coerceIn(b.minY, b.maxY), from.z.coerceIn(b.minZ, b.maxZ))

        /** Test hook: `call dev.horizon.client.module.attack.GhostHook report`. */
        @JvmStatic
        fun report(): String {
            val g = Modules.get<Aura>().ghost
            val s = stats
            val lean = if (s.leanHits == 0) 0.0 else s.lean / s.leanHits
            val line = ("GHOST strength=%.2f pressure=%.2f score=%.2f cross=%.0f hits=%d misses=%d holds=%d threatHits=%d steered=%d " +
                "lastOffset=%.1f lean=%.2f/%d drop=%s swaps=%d back=%d fumbles=%d").format(
                g.strength, g.pressure, g.odds.score, g.crossDegS, s.hits, s.misses, s.holds, s.threatHits, s.steered, g.lastOffset,
                lean, s.leanHits, g.dropYaw?.let { "%.0f@%.1f".format(it, g.dropDist) } ?: "none", s.swaps, s.swapsBack, s.fumbles
            )
            println(line)
            return line
        }

        /** Test hook: zero the counters. */
        @JvmStatic
        fun clear() {
            stats.holds = 0
            stats.threatHits = 0
            stats.hits = 0
            stats.misses = 0
            stats.steered = 0
            stats.swaps = 0
            stats.swapsBack = 0
            stats.fumbles = 0
            stats.lean = 0.0
            stats.leanHits = 0
            println("GHOST cleared")
        }
    }
}
