package dev.horizon.client.module.attack

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val mc get() = Minecraft.getInstance()

private fun deg(r: Double) = Math.toDegrees(r)

private fun rad(d: Double) = Math.toRadians(d)

private fun clampF(x: Float, a: Float, b: Float): Float {
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    return if (x < lo) lo else if (x > hi) hi else x
}

private fun clampD(x: Double, a: Double, b: Double): Double {
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    return if (x < lo) lo else if (x > hi) hi else x
}

private fun eyeY(): Double {
    val p = mc.player!!
    return p.position().y + p.getEyeHeight(p.pose)
}

/** The original's `kk.T`: the look vector, in float maths. */
fun viewVec(pitch: Float, yaw: Float): Vec3 {
    val r = (PI / 180).toFloat()
    return Vec3(
        (sin((-yaw * r).toDouble()).toFloat() * cos((pitch * r).toDouble()).toFloat()).toDouble(),
        (-sin((pitch * r).toDouble()).toFloat()).toDouble(),
        (cos((-yaw * r).toDouble()).toFloat() * cos((pitch * r).toDouble()).toFloat()).toDouble(),
    )
}

/** The original's `w4.f`: looking along [rot], would the crosshair land on [target] within [range]? Other entities never block. */
fun rayHitsTarget(target: Entity, rot: Rotation, range: Float, wallsCheck: Boolean): Boolean {
    val p = mc.player ?: return false
    val level = mc.level ?: return false
    var reach = range.toDouble()
    var reachSq = reach * reach
    val eye = p.position().add(0.0, p.getEyeHeight(p.pose).toDouble(), 0.0)
    var blockHit: HitResult? = null
    var blockDistSq = 0.0
    if (wallsCheck) {
        val from = p.getEyePosition(1f)
        blockHit = level.clip(ClipContext(from, from.add(viewVec(rot.pitch, rot.yaw).scale(range.toDouble())), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, p))
        blockDistSq = blockHit.location.distanceToSqr(eye)
        if (blockHit.type != HitResult.Type.MISS) {
            reachSq = blockDistSq
            reach = sqrt(blockDistSq)
        }
    }
    val dir = viewVec(rot.pitch, rot.yaw)
    val box = p.boundingBox.expandTowards(dir.scale(reach)).inflate(1.0, 1.0, 1.0)
    val eHit = ProjectileUtil.getEntityHitResult(p, eye, eye.add(dir.scale(reach)), box, { it.isPickable && it == target }, reachSq)
        ?: return false
    var hit: HitResult = if (blockHit != null && !(eHit.location.distanceToSqr(eye) < blockDistSq)) blockHit else eHit
    if (!hit.location.closerThan(eye, range.toDouble())) {
        hit = BlockHitResult.miss(hit.location, Direction.getApproximateNearest(hit.location.subtract(eye)), BlockPos.containing(hit.location))
    }
    return hit is EntityHitResult && hit.entity == target
}

/** The point of [target]'s box closest to the eyes. */
private fun closestPoint(target: Entity): Vec3 {
    val b = target.boundingBox
    val e = mc.player!!.eyePosition
    return Vec3(e.x.coerceIn(b.minX, b.maxX), e.y.coerceIn(b.minY, b.maxY), e.z.coerceIn(b.minZ, b.maxZ))
}

private fun camera() = mc.gameRenderer.mainCamera()

/** The original's `ud`: one of Aura's rotation modes. */
abstract class AuraRotation {
    abstract fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation

    abstract fun aimPoint(aura: Aura, target: Entity): Vec3

    /** Check the hit with a ray along the rotation (else line of sight). */
    open fun needsRaycast(aura: Aura) = true

    /** Look for targets 2 blocks beyond the attack range. */
    open fun extendsSearch(aura: Aura) = true

    companion object {
        fun of(mode: Aura.RotationMode): AuraRotation = when (mode) {
            Aura.RotationMode.SNAP -> SnapRotation
            Aura.RotationMode.DEFAULT -> DefaultRotation
            Aura.RotationMode.NONE, Aura.RotationMode.GRIM_SILENT -> NoRotation
            Aura.RotationMode.VULCAN -> VulcanRotation
            Aura.RotationMode.HVH -> HvhRotation
            Aura.RotationMode.POLAR_SEP_25 -> PolarRotation
            Aura.RotationMode.ADVANCED -> AdvancedRotation
            Aura.RotationMode.CUSTOM -> CustomRotation
            Aura.RotationMode.LEGIT -> LegitRotation
        }
    }
}

/** The original's `ur`. */
object SnapRotation : AuraRotation() {
    override fun aimPoint(aura: Aura, target: Entity) = closestPoint(target)

    override fun extendsSearch(aura: Aura) = false

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation {
        val p = mc.player!!
        val aim = aimPoint(aura, target)
        val dx = aim.x - p.x
        val dz = aim.z - p.z
        val yawDelta: Double = Mth.wrapDegrees(deg(atan2(dz, dx)) - 90.0f - prev.yaw)
        val pitchDelta: Double = -deg(atan2(aim.y - p.eyePosition.y, hypot(dx, dz))) - prev.pitch
        var yaw = prev.yaw + yawDelta.toFloat()
        // The sine of a degree value, read as radians (as in the original).
        var pitch = prev.pitch + pitchDelta.toFloat() + sin(yawDelta).toFloat()
        if (!attackReady) {
            yaw = p.yRot
            pitch = p.xRot
        }
        if (aura.priorityManual.value && rayHitsTarget(aura.target!!, prev, aura.reach.value, !aura.ignoreWalls.value)) {
            return Rotation(camera().yRot(), camera().xRot())
        }
        return Rotation(yaw, pitch)
    }
}

/** The original's `ut`. */
object DefaultRotation : AuraRotation() {
    override fun aimPoint(aura: Aura, target: Entity): Vec3 {
        if (mc.player!!.isFallFlying) return target.eyePosition
        val tc = target.tickCount.toDouble()
        val b = target.boundingBox
        return target.position().add(b.xsize / 2.1 * sin(rad(tc)), target.bbHeight / 2.0 + b.ysize / 8.0 * sin(rad(tc + 10.0)),
            b.zsize / 2.1 * -cos(rad(tc)))
    }

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation {
        val p = mc.player!!
        val aim = aimPoint(aura, target)
        target.boundingBox = target.boundingBox.inflate(-0.15, 0.0, -0.15)
        val serverOnTarget = rayHitsTarget(target, Rotation(Rotation.lastYaw, Rotation.lastPitch), aura.range.value + 2.0f, !aura.ignoreWalls.value)
        target.boundingBox = target.boundingBox.inflate(0.15, 0.0, 0.15)
        val dx = aim.x - p.x
        val dz = aim.z - p.z
        val yawDelta: Double = Mth.wrapDegrees(deg(atan2(dz, dx)) - 90 - prev.yaw) + Rng.float(-1f, 1f)
        val pitchDelta: Double = -deg(atan2(aim.y - eyeY(), hypot(dx, dz))) - prev.pitch
        val far = p.isFallFlying || aura.range.value >= 4.0
        val yawLimit: Double = when {
            far -> 360.0
            serverOnTarget -> Rng.double(0.15, 0.21)
            else -> abs(yawDelta) / Rng.float(1.4f, 1.8f)
        }
        val pitchLimit: Double = if (far) 180.0
        else max(abs(pitchDelta) - 15.0, 0.0) / 2.0 + (if (p.tickCount % 25 == 0) 2.0 * Math.random() else 0.0)
        val yaw = prev.yaw + Math.clamp(yawDelta, -yawLimit, yawLimit)
        val pitch = prev.pitch + Math.clamp(pitchDelta, -pitchLimit, pitchLimit) + sin(rad(yawDelta))
        return Rotation(yaw.toFloat(), pitch.toFloat())
    }
}

/** The original's `u7` (NONE and GRIM_SILENT): no aiming. */
object NoRotation : AuraRotation() {
    override fun aimPoint(aura: Aura, target: Entity): Vec3 = target.eyePosition

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean) = Rotation(mc.player!!.yRot, mc.player!!.xRot)

    override fun needsRaycast(aura: Aura) = false

    override fun extendsSearch(aura: Aura) = false
}

/** The original's `uq`. */
object VulcanRotation : AuraRotation() {
    override fun aimPoint(aura: Aura, target: Entity) = closestPoint(target)

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation {
        val p = mc.player!!
        val aim = aimPoint(aura, target)
        val dx = aim.x - p.x
        val dz = aim.z - p.z
        val yawDelta: Double = Mth.wrapDegrees(deg(atan2(dz, dx)) - 90 - prev.yaw) + Rng.float(-1f, 1f)
        val pitchDelta: Double = -deg(atan2(aim.y - eyeY(), hypot(dx, dz))) - prev.pitch
        val onTarget = rayHitsTarget(target, prev, aura.range.value + 2.0f, !aura.ignoreWalls.value)
        val yawLimit: Double = when {
            p.isFallFlying -> 180.0
            onTarget -> Rng.double(0.15, 0.21)
            else -> abs(yawDelta) / Rng.float(1.4f, 1.8f)
        }
        val pitchLimit: Double = if (!p.isFallFlying)
            max(abs(pitchDelta) - 2.0, 0.0) / 2.0 + (if (p.tickCount % 25 == 0) 2.0 * Math.random() else 0.0)
        else 90.0
        val yawMove = if (abs(yawDelta) > 0.3) yawDelta else 0.0
        var yaw: Double = prev.yaw + Math.clamp(yawMove, -yawLimit, yawLimit) + sin(rad(p.tickCount.toDouble() * 20.0f)) * 0.5f
        val pitch: Double = prev.pitch + Math.clamp(pitchDelta, -pitchLimit, pitchLimit) + sin(rad(yaw)) * 1.5f
        // The original's |round(d) - d| < 1 test is always true.
        yaw += if (p.tickCount % 2 == 0) 1 else -2
        return Rotation(yaw.toFloat(), pitch.toFloat())
    }
}

/** The original's `up`: an instant snap. */
object HvhRotation : AuraRotation() {
    override fun aimPoint(aura: Aura, target: Entity) = if (mc.player!!.isFallFlying) target.eyePosition else closestPoint(target)

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation {
        val p = mc.player!!
        val aim = aimPoint(aura, target)
        val dx = aim.x - p.x
        val dz = aim.z - p.z
        val yawDelta: Double = Mth.wrapDegrees(deg(atan2(dz, dx)) - 90 - prev.yaw)
        val pitchDelta: Double = -deg(atan2(aim.y - eyeY(), hypot(dx, dz))) - prev.pitch
        val yaw = prev.yaw + Math.clamp(yawDelta, -360.0, 360.0)
        val pitch = prev.pitch + Math.clamp(pitchDelta, -180.0, 180.0) + sin(rad(yawDelta))
        return Rotation(yaw.toFloat(), pitch.toFloat())
    }
}

/** The original's `u5`. */
object PolarRotation : AuraRotation() {
    private var ticksSinceHit = 0
    private var saved = Rotation(0f, 0f)

    override fun aimPoint(aura: Aura, target: Entity): Vec3 = target.boundingBox.center

    private fun wobble(yawAxis: Boolean): Float {
        val p = mc.player!!
        if (yawAxis) return (sin(p.tickCount.toFloat().toDouble()).toFloat() + Rng.float(-0.1f, 0.1f)) * 15.0f
        val t = p.tickCount + 2.0f
        val a = sin(t.toDouble()).toFloat()
        val b = 0.2 * sin(0.4 * t * 0.7 + 1.23)
        val c = 1 + 0.3 * sin(0.4 * t * 0.5 + 2.45)
        return (c * a + b).toFloat() * 6.0f
    }

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation {
        val p = mc.player!!
        ticksSinceHit += 1
        val aim = aimPoint(aura, target)
        val yaw: Float
        var pitch = wobble(false) + saved.pitch
        if (attackReady && p.eyePosition.distanceTo(target.eyePosition) <= aura.reach.value + 0.3f) {
            ticksSinceHit = 0
            val dx = aim.x - p.x
            val dz = aim.z - p.z
            val yd = Mth.wrapDegrees(deg(atan2(dz, dx)) - 90.0f - prev.yaw).toFloat()
            val pd = -(deg(atan2(aim.y - eyeY(), hypot(dx, dz))).toFloat()) - prev.pitch
            yaw = prev.yaw + yd.coerceIn(-95f, 95f)
            pitch = prev.pitch + pd
            saved = Rotation(yaw, pitch)
        } else if (ticksSinceHit > 8) {
            val maxStep = Rng.float(25f, 45f)
            val d = Mth.wrapDegrees(wobble(true) + camera().yRot() - prev.yaw).coerceIn(-maxStep, maxStep)
            return Rotation(prev.yaw + d, pitch)
        } else {
            yaw = wobble(true) + saved.yaw
        }
        val t = p.tickCount % 400
        if (t in 0 until 11) pitch = -90.0f + Rng.float(18f, 20f) * (t / 10.0f) + Rng.float(-1f, 1f)
        return Rotation(yaw, pitch)
    }
}

/** The original's `u3`: an addon's rotation; there's no addon API here, so it keeps the previous rotation. */
object CustomRotation : AuraRotation() {
    override fun aimPoint(aura: Aura, target: Entity): Vec3 = target.eyePosition

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean) = prev
}

/** The original's `y1`: a random walk in [-1, 1]. */
private class Noise {
    var value = 0f

    fun step(): Float {
        value = clampF(value + Rng.float(-0.1f, 0.1f), -1f, 1f)
        return value
    }
}

/** The original's `yr`: the easings ADVANCED's smoothers apply to the speed factor. */
fun ease(e: Aura.YawSmoother, x: Double): Double = when (e) {
    Aura.YawSmoother.LINEAR -> x
    Aura.YawSmoother.IN_QUAD -> x * x
    Aura.YawSmoother.OUT_QUAD -> x * (2 - x)
    Aura.YawSmoother.IN_OUT_QUAD -> if (x < 0.5) 2 * x * x else -1 + (4 - 2 * x) * x
    Aura.YawSmoother.IN_CUBIC -> x.pow(3)
    Aura.YawSmoother.OUT_CUBIC -> (x - 1).pow(3) + 1
    Aura.YawSmoother.IN_QUART -> x.pow(4)
    Aura.YawSmoother.OUT_QUART -> 1 - (x - 1).pow(4)
    Aura.YawSmoother.IN_OUT_QUART -> if (x < 0.5) 8.0f * x.pow(4) else 1.0f - (-2.0f * x + 2.0f).pow(4) / 2.0f
    Aura.YawSmoother.IN_QUINT -> x.pow(5)
    Aura.YawSmoother.OUT_QUINT -> 1 + (x - 1).pow(5)
    Aura.YawSmoother.IN_OUT_QUINT -> if (x < 0.5) 16.0f * x.pow(5) else 1.0f - (-2.0f * x + 2.0f).pow(5) / 2.0f
    Aura.YawSmoother.IN_SINE, Aura.YawSmoother.IN_OUT_SINE -> 1 - cos(x * PI / 2)
    Aura.YawSmoother.OUT_SINE -> sin(x * PI / 2)
    Aura.YawSmoother.IN_EXPO -> if (x == 0.0) 0.0 else 2.0.pow(10 * x - 10)
    Aura.YawSmoother.OUT_EXPO -> if (x == 1.0) 1.0 else 1 - 2.0.pow(-10 * x)
    Aura.YawSmoother.IN_OUT_EXPO -> if (x == 0.0) 0.0 else if (x == 1.0) 1.0
    else if (x < 0.5) 2.0.pow(20 * x - 10) / 2 else (2 - 2.0.pow(-20 * x + 10)) / 2
    Aura.YawSmoother.IN_CIRC -> 1 - sqrt(1 - x * x)
    Aura.YawSmoother.OUT_CIRC -> sqrt(1 - (x - 1).pow(2))
    Aura.YawSmoother.IN_OUT_CIRC -> if (x < 0.5) (1 - sqrt(1 - (2 * x).pow(2))) / 2 else (sqrt(1 - (-2 * x + 2).pow(2)) + 1) / 2
    Aura.YawSmoother.SIGMOID -> sigmoid(x, 10.0)
    Aura.YawSmoother.SIGMOID_2 -> sigmoid(x, 8.0)
    Aura.YawSmoother.SIGMOID_3 -> sigmoid(x, 12.0)
    Aura.YawSmoother.SIGMOID_4 -> sigmoid(x, 6.0)
    Aura.YawSmoother.SIGMOID_5 -> sigmoid(x, 14.0)
    Aura.YawSmoother.SIGMOID_6 -> sigmoid(x, 4.0)
    Aura.YawSmoother.SIGMOID_7 -> sigmoid(x, 16.0)
    Aura.YawSmoother.OUT_ELASTIC -> if (x == 0.0) 0.0 else if (x == 1.0) 1.0
    else 2.0.pow(-10 * x) * sin((10 * x - 0.75) * (2 * PI / 3)) * 0.5 + 1
    Aura.YawSmoother.IN_BACK -> 2.70158 * x.pow(3) - 1.70158 * x.pow(2)
    Aura.YawSmoother.OUT_BACK -> 1 + 2.70158 * (x - 1).pow(3) + 1.70158 * (x - 1).pow(2)
    Aura.YawSmoother.DECELERATE -> 1 - (x - 1).pow(2)
    Aura.YawSmoother.IN_OUT_CUBIC -> if (x < 0.5) 4.0f * x.pow(3) else 1.0f - (-2.0f * x + 2.0f).pow(3) / 2.0f
}

private fun sigmoid(x: Double, k: Double) = 1 / (1 + exp(-k * (x - 0.5)))

/** The original's `um`: speed-capped, eased, jittered rotations, with overshoot, spins, head-ups and holds. */
object AdvancedRotation : AuraRotation() {
    private var yawFactor = 1f
    private var pitchFactor = 1f
    private var overshootTicksLeft = 0
    private var overshootStreakCount = 0
    private var overshootDir = 0f
    private var overshootBase = 0f
    private var headUpTicksLeft = 0
    private var headUpCounter = 0

    /** Rolled with the defaults (165..180) when the class loads, before any config (as in the original). */
    private var headUpInterval = Rng.int(165, 180)
    private var spinTicks = 0
    private var spinTicksLeft = 0
    private var spinAngle = 0f
    private var spinDir = 0f
    private var clickJitterStep = 0
    private var clickJitterPattern = 0
    private var holdTicks = 0
    private var roamOffset: Vec3 = Vec3.ZERO
    private var roamVelocity: Vec3 = Vec3.ZERO
    private var rustOffset: Vec3 = Vec3.ZERO
    private val yawNoise = Noise()
    private val pitchNoise = Noise()
    private val pointNoiseX = Noise()
    private val pointNoiseY = Noise()
    private val pointNoiseZ = Noise()

    private val PATTERNS = listOf(
        listOf(0.15000153 to 0.14999962, -0.15000153 to -0.14999962, -0.6000061 to 0.0, -0.6000061 to 0.0, 0.45000458 to 0.0),
        listOf(-0.15000153 to 0.0, -0.30000305 to 0.0, 0.30000305 to 0.14999962, 0.15000153 to -0.14999962, 0.0 to 0.0),
        listOf(0.15000153 to -0.14999962, -0.6000061 to 0.0, 0.15000153 to 0.14999962, -0.15000153 to 0.0, 0.30000305 to 0.0),
        listOf(-0.6000061 to 0.29999924, 0.6000061 to 0.29999924, -0.30000305 to -0.44999886, 0.15000153 to 0.0, 0.0 to 0.0),
        listOf(-0.30000305 to 0.0, -0.29999542 to 0.6000004, 0.30000305 to 0.0, -0.7500076 to -0.44999886, 0.30000305 to 0.0),
    )

    /** The original's `um.R`: flat, or a normal distribution truncated to the range. */
    private fun distRandom(aura: Aura, a: Float, b: Float, jitterDist: Boolean): Float {
        val dist = if (jitterDist) aura.jitterDistribution.value else aura.speedDistribution.value
        if (dist == Aura.SpeedDistribution.FLAT) return Rng.float(a, b)
        val g = clampF((Rng.gaussian().toFloat() + 3.0f) / 6.0f, 0f, 1f)
        return a + (b - a) * g
    }

    override fun aimPoint(aura: Aura, target: Entity): Vec3 {
        val p = mc.player!!
        if (p.isFallFlying) return target.eyePosition
        val t = (System.currentTimeMillis() % 10000L).toFloat() / 500.0f
        val hR = aura.pointHRange.value
        val vR = aura.pointVRange.value
        val off: Vec3 = when (aura.pointJitter.value) {
            Aura.PointJitter.SINE -> Vec3((sin((t * aura.pointHFreq.value).toDouble()).toFloat() * hR).toDouble(),
                (sin((t * aura.pointVFreq.value).toDouble()).toFloat() * vR).toDouble(),
                (sin((t * aura.pointHFreq.value).toDouble()).toFloat() * hR).toDouble())
            Aura.PointJitter.ROAMING -> Vec3((pointNoiseX.step() * hR).toDouble(), (pointNoiseY.step() * vR).toDouble(),
                (pointNoiseZ.step() * hR).toDouble())
            Aura.PointJitter.DRUNK_SINE -> Vec3(
                ((sin((p.tickCount * aura.pointHFreq.value).toDouble()).toFloat() + distRandom(aura, -0.1f, 0.1f, true)) * hR).toDouble(),
                ((sin((p.tickCount * aura.pointVFreq.value).toDouble()).toFloat() + distRandom(aura, -0.1f, 0.1f, true)) * vR).toDouble(),
                ((sin((p.tickCount * aura.pointHFreq.value).toDouble()).toFloat() + distRandom(aura, -0.1f, 0.1f, true)) * hR).toDouble())
            Aura.PointJitter.RANDOM -> Vec3(distRandom(aura, -hR, hR, true).toDouble(), distRandom(aura, -vR, vR, true).toDouble(),
                distRandom(aura, -hR, hR, true).toDouble())
            Aura.PointJitter.NONE -> Vec3.ZERO
        }
        return when (aura.point.value) {
            Aura.Point.CENTER -> target.boundingBox.center.add(off)
            Aura.Point.EYES -> target.eyePosition.add(off)
            Aura.Point.ROAMING -> target.position().add(roamOffset).add(off)
            Aura.Point.RUST -> target.position().add(rustOffset).add(off)
            Aura.Point.CLOSEST -> {
                // The offset is drawn but not added; vanilla contract() only moves the max sides.
                val b = target.boundingBox.contract(hR + 0.1, vR + 0.2, hR + 0.1)
                val e = p.eyePosition
                Vec3(clampD(e.x, b.minX, b.maxX), clampD(e.y, b.minY, b.maxY), clampD(e.z, b.minZ, b.maxZ))
            }
        }
    }

    private fun updateRoaming(aura: Aura, target: Entity) {
        val hR = aura.pointHRange.value.toDouble()
        val w = target.boundingBox.xsize
        val h = target.boundingBox.ysize
        val d = target.boundingBox.zsize
        if (roamVelocity == Vec3.ZERO) roamVelocity = Vec3(Rng.double(-hR, hR), Rng.double(-hR, hR), Rng.double(-hR, hR))
        var vx = roamVelocity.x
        var vy = roamVelocity.y
        var vz = roamVelocity.z
        if (roamOffset.x >= (w - hR) / 2.0f) vx = -Rng.double(0.003, 0.03)
        if (roamOffset.x <= -(w - hR) / 2.0f) vx = Rng.double(0.003, 0.03)
        if (roamOffset.y >= h) vy = -Rng.double(0.001, 0.03)
        if (roamOffset.y <= hR) vy = Rng.double(0.001, 0.03)
        if (roamOffset.z >= (d - hR) / 2.0f) vz = -Rng.double(0.003, 0.03)
        if (roamOffset.z <= -(d - hR) / 2.0f) vz = Rng.double(0.003, 0.03)
        if (vx in -0.03..0.03 || vy in -0.03..0.03 || vz in -0.03..0.03) {
            // A re-roll the original overwrites straight away; the draws still happen.
            Rng.double(-hR, hR)
            Rng.double(-hR, hR)
            Rng.double(-hR, hR)
        }
        roamVelocity = Vec3(vx, vy, vz)
        roamOffset = roamOffset.add(roamVelocity)
        roamOffset = Vec3(clampD(roamOffset.x, -w, w), clampD(roamOffset.y, 0.0, h), clampD(roamOffset.z, -d, d))
        Rng.double(-0.03, 0.03)
        Rng.double(-0.03, 0.03)
    }

    private fun applyJitter(aura: Aura, yaw0: Float, pitch0: Float): Rotation {
        val p = mc.player!!
        val t = (System.currentTimeMillis() % 10000L).toFloat() / 500.0f
        val yawFreq = Rng.float(aura.yawFreqMin.value, aura.yawFreqMax.value)
        val yawAmpl = Rng.float(aura.yawAmplMin.value, aura.yawAmplMax.value)
        val pitchFreq = Rng.float(aura.pitchFreqMin.value, aura.pitchFreqMax.value)
        val pitchAmpl = Rng.float(aura.pitchAmplMin.value, aura.pitchAmplMax.value)
        fun jitter(mode: Aura.PointJitter, freq: Float, ampl: Float, noise: Noise): Float = when (mode) {
            Aura.PointJitter.SINE -> sin((t * freq).toDouble()).toFloat() * ampl
            Aura.PointJitter.ROAMING -> noise.step() * ampl
            Aura.PointJitter.DRUNK_SINE -> (sin((p.tickCount * freq).toDouble()).toFloat() + distRandom(aura, -0.1f, 0.1f, true)) * ampl
            Aura.PointJitter.RANDOM -> distRandom(aura, -ampl, ampl, true)
            Aura.PointJitter.NONE -> 0f
        }
        val yaw = yaw0 + jitter(aura.yawJitter.value, yawFreq, yawAmpl, yawNoise)
        val pitch = pitch0 + jitter(aura.pitchJitter.value, pitchFreq, pitchAmpl, pitchNoise)
        return Rotation(yaw, pitch)
    }

    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean): Rotation {
        val p = mc.player!!
        if (aura.holdAfterHit.value) {
            holdTicks -= 1
            if (holdTicks > 0) {
                return if (aura.holdTiming.value == Aura.HoldTiming.BEFORE_RANDOM) prev else applyJitter(aura, prev.yaw, prev.pitch)
            }
        }
        val aim = aimPoint(aura, target)
        updateRoaming(aura, target)
        val expand = aura.smoothBoxExpand.value.toDouble()
        target.boundingBox = target.boundingBox.inflate(expand)
        val onTarget = rayHitsTarget(target, prev, aura.range.value + 2.0f, !aura.ignoreWalls.value)
        target.boundingBox = target.boundingBox.inflate(-expand)
        if (onTarget) {
            yawFactor -= aura.yawDecel.value
            pitchFactor -= aura.pitchDecel.value
        } else {
            yawFactor += aura.yawAccel.value
            pitchFactor += aura.pitchAccel.value
        }
        yawFactor = clampF(yawFactor, aura.minFactorYaw.value, 1f)
        pitchFactor = clampF(pitchFactor, aura.minFactorPitch.value, 1f)
        var yawTarget: Double = deg(atan2(aim.z - p.z, aim.x - p.x)) - 90.0f
        val pitchTarget: Double = -deg(atan2(aim.y - eyeY(), hypot(aim.x - p.x, aim.z - p.z)))
        if (aura.overShoot.value && overshootTicksLeft > 0) {
            yawTarget += (overshootBase + aura.osValue.value * overshootDir).toDouble()
            overshootTicksLeft -= 1
        } else if (aura.do360.value && spinTicksLeft > 0) {
            spinAngle += 360.0f / spinTicks
            yawTarget += (spinAngle * spinDir).toDouble()
            spinTicksLeft -= 1
        }
        val yawDelta: Double = Mth.wrapDegrees(yawTarget - prev.yaw)
        val pitchDelta: Double = pitchTarget - prev.pitch
        val yawSpeed = distRandom(aura, aura.minYawSpeed.value, aura.maxYawSpeed.value, false).toDouble() *
            ease(aura.yawSmoother.value, yawFactor.toDouble())
        val pitchSpeed = distRandom(aura, aura.minPitchSpeed.value, aura.maxPitchSpeed.value, false).toDouble() *
            ease(aura.pitchSmoother.value, pitchFactor.toDouble())
        val yawStep = clampD(abs(yawDelta), 0.0, yawSpeed).toFloat()
        val pitchStep = clampD(abs(pitchDelta), 0.0, pitchSpeed).toFloat()
        var yaw = prev.yaw + (if (yawDelta > 0.0) yawStep else -yawStep)
        var pitch = prev.pitch + (if (pitchDelta > 0.0) pitchStep else -pitchStep)
        applyJitter(aura, yaw, pitch).let {
            yaw = it.yaw
            pitch = it.pitch
        }
        if (aura.overShoot.value && yawStep > aura.osTrigger.value && overshootTicksLeft <= 0) {
            overshootStreakCount += 1
            overshootBase = 0f
            if (overshootStreakCount >= aura.osStreak.value) {
                overshootStreakCount = 0
                overshootTicksLeft = Rng.int(aura.osMinTicks.value, aura.osMaxTicks.value)
                overshootDir = if (yaw - prev.yaw > 0f) 1f else -1f
            }
        }
        if (aura.headUp.value) {
            val old = headUpCounter
            headUpCounter += 1
            if (old > headUpInterval) {
                headUpInterval = Rng.int(aura.huIntervalMin.value, aura.huIntervalMax.value)
                headUpCounter = 0
                headUpTicksLeft = Rng.int(aura.huTicksMin.value, aura.huTicksMax.value)
            }
            if (headUpTicksLeft > 0) {
                headUpTicksLeft -= 1
                pitch -= distRandom(aura, aura.huSpeedMin.value, aura.huSpeedMax.value, true)
                // 10 % of the absolute pitch, added to the yaw (as in the original).
                yaw += pitch * 0.1f
            }
        }
        if (aura.clickJitter.value && clickJitterStep >= 0) {
            val (dy, dp) = PATTERNS[clickJitterPattern][4 - clickJitterStep]
            yaw += dy.toFloat()
            pitch += dp.toFloat()
            clickJitterStep -= 1
        }
        val filter = aura.filterLowDeltas.value
        if (filter != Aura.FilterLowDeltas.OFF) {
            val thr = aura.lowDeltaThreshold.value
            val dyaw = abs(prev.yaw - yaw)
            if (dyaw >= 0f && dyaw <= thr) {
                yaw = if (filter == Aura.FilterLowDeltas.REMOVE) prev.yaw else prev.yaw + Rng.float(aura.replaceMin.value, aura.replaceMax.value)
            }
            val dpitch = abs(prev.pitch - pitch)
            if (dpitch >= 0f && dpitch <= thr) {
                pitch = if (filter == Aura.FilterLowDeltas.REMOVE) prev.pitch
                else prev.pitch + Rng.float(aura.replaceMin.value, aura.replaceMax.value)
            }
        }
        val inside = p.boundingBox.intersects(target.boundingBox)
        val lo = if (inside) aura.pitchInTargetMin.value else aura.pitchMin.value
        val hi = if (inside) aura.pitchInTargetMax.value else aura.pitchMax.value
        return Rotation(yaw, clampF(pitch, lo, hi))
    }

    /** The original's `um.pZ`: after every attack (in every mode): roll a spin, a click-jitter pattern, a hold, a new RUST point. */
    fun onAttack(aura: Aura) {
        val p = mc.player ?: return
        if (aura.do360.value) {
            val roll = Rng.int(0, 100)
            if (roll >= 100 - aura.do360Chance.value) {
                spinTicks = Rng.int(7, 9)
                spinTicksLeft = spinTicks
                spinDir = if (p.tickCount % 2 == 0) -1f else 1f
            }
        }
        if (aura.clickJitter.value) {
            clickJitterStep = 4
            clickJitterPattern = Rng.int(0, 4)
        }
        if (aura.holdAfterHit.value) {
            val roll = Rng.int(0, 100)
            if (roll >= 100 - aura.holdChance.value) holdTicks = Rng.int(aura.holdMin.value, aura.holdMax.value)
        }
        val hx = 0.3 - aura.pointHRange.value / 2.0
        val vy = 1.81 - aura.pointVRange.value / 2.0
        rustOffset = Vec3(clampD(Rng.double(-hx, hx), -0.3, 0.3), clampD(Rng.double(0.0, vy), 0.0, 1.81), clampD(Rng.double(-hx, hx), -0.3, 0.3))
    }
}

/**
 * Not in the original: aims like a hand on a mouse. Nothing is silent: the camera itself turns, every frame, and Aura only
 * hits when the crosshair is on the target. Tunable through Aura's `legit` group (aim spot, min/max speed, smoothness,
 * pitch-assist scale, hitbox-relax so it stops fighting your mouse once on target, reaction time, tremor, and
 * require-click). Each turn is made in whole mouse counts at your sensitivity, so it is always a turn a mouse could make.
 * `aim-model` picks how the hand moves: SPRING (a damped spring under a speed cap, the default) or HUMAN ([HumanAim]:
 * your own movement made up for at once, the target's seen late; Fitts-timed minimum-jerk submovements that undershoot or
 * overshoot and correct, curved paths, lagging pursuit, noise tremor, and a glide out when the target goes).
 */
object LegitRotation : AuraRotation() {
    private var target: Entity? = null
    private var acquired = 0L
    private var reaction = 0.0
    private var last = 0L

    // The spot aimed at, as fractions of the target's box (x, z across, y up), and where it's wandering to.
    private var spot = Vec3(0.5, 0.7, 0.5)
    private var spotTo = spot
    private var nextSpot = 0L

    // Per-target slight hand variation around the configured speed & smoothness.
    private var speedBias = 0.5
    private var stiffJitter = 1.0
    private var dampJitter = 1.0
    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var tremorPhase = 0.0
    private var clickImpulse = 0.0

    /** SPRING's `tracking`: the target's box centre from your eyes last frame, and how fast that moves (blocks/s). */
    private var lastSeen: Vec3? = null
    private var relVel = Vec3.ZERO

    /** aim-model HUMAN's hand (see [HumanAim]); SPRING is the code below it. */
    private val human = HumanAim(Rng.random)

    /**
     * Not in the original: Aura's `silent`. While [silentActive], the hand turns this look (what Aura sends) instead of
     * the camera; it starts from the camera when a target is picked up, and turns back to it when the target goes.
     */
    var silentActive = false
        private set
    var silentYaw = 0f
        private set
    var silentPitch = 0f
        private set

    private fun lookYaw(p: LocalPlayer) = if (silentActive) silentYaw else p.yRot
    private fun lookPitch(p: LocalPlayer) = if (silentActive) silentPitch else p.xRot

    /** Vanilla's `Entity.turn` (0.15 degrees a count), on the hidden look while [silentActive], else the camera. */
    private fun turn(p: LocalPlayer, x: Double, y: Double) {
        if (!silentActive) return p.turn(x, y)
        silentYaw += (x * 0.15).toFloat()
        silentPitch = (silentPitch + (y * 0.15).toFloat()).coerceIn(-90f, 90f)
    }

    /** `silent` with no target: turn the hidden look back onto the camera, at most max-aim-speed; done within 1 degree. */
    private fun silentBack(aura: Aura, p: LocalPlayer, dt: Double) {
        val dYaw = Mth.wrapDegrees(p.yRot - silentYaw).toDouble()
        val dPitch = (p.xRot - silentPitch).toDouble()
        if (abs(dYaw) < 1.0 && abs(dPitch) < 1.0) {
            silentActive = false
            return
        }
        val k = 1.0 - exp(-dt * 10.0)
        var sy = dYaw * k
        var sp = dPitch * k
        val cap = maxOf(aura.minAimSpeed.value, aura.maxAimSpeed.value) * dt
        val len = hypot(sy, sp)
        if (len > cap && len > 1e-6) {
            sy *= cap / len
            sp *= cap / len
        }
        turnBy(doubleArrayOf(sy, sp))
    }

    fun reset() {
        silentActive = false
        target = null
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        clickImpulse = 0.0
        human.reset()
    }

    /** Small organic click micro-vibration when a swing fires. */
    fun onSwing() {
        clickImpulse = 1.0
        human.click()
    }

    override fun aimPoint(aura: Aura, target: Entity): Vec3 {
        val b = target.boundingBox
        if (aura.aimSpot.value == Aura.LegitSpot.CLOSEST) {
            val p = mc.player ?: return b.center
            val eye = p.eyePosition
            val dist = eye.distanceTo(b.center)
            val look = viewVec(lookPitch(p), lookYaw(p))
            val rayPt = eye.add(look.scale(dist))
            val mx = b.xsize * 0.18
            val my = b.ysize * 0.12
            val mz = b.zsize * 0.18
            val driftX = (spot.x - 0.5) * b.xsize * 0.12
            val driftY = (spot.y - 0.68) * b.ysize * 0.10
            val driftZ = (spot.z - 0.5) * b.zsize * 0.12
            return Vec3(
                clampD(rayPt.x + driftX, b.minX + mx, b.maxX - mx),
                clampD(rayPt.y + driftY, b.minY + my, b.maxY - my),
                clampD(rayPt.z + driftZ, b.minZ + mz, b.maxZ - mz)
            )
        }
        return Vec3(b.minX + b.xsize * spot.x, b.minY + b.ysize * spot.y, b.minZ + b.zsize * spot.z)
    }

    /** What Aura sends is where you look (or, with `silent`, the hidden look). */
    override fun rotate(aura: Aura, target: Entity, prev: Rotation, attackReady: Boolean) =
        Rotation(lookYaw(mc.player!!), lookPitch(mc.player!!))

    /** Each frame (MouseTurnEvent), with no screen open. */
    fun frame(aura: Aura) {
        val p = mc.player ?: return
        val now = System.nanoTime()
        // A hidden look left over from before Aura last stopped calling this (off, another rotation) starts over.
        if (!aura.silent.value || (last != 0L && now - last > 250_000_000L)) silentActive = false
        val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceIn(0.0, 0.05)
        last = now
        val t = aura.target
        val humanAim = aura.aimModel.value == Aura.AimModel.HUMAN
        if (mc.gui.screen() != null) human.reset()
        if (t == null || mc.gui.screen() != null || (aura.requireClick.value && !PlayerState.physical(mc.options.keyAttack))) {
            target = null
            vYaw = 0.0
            vPitch = 0.0
            if (silentActive) {
                silentBack(aura, p, dt)
                return
            }
            // A hand doesn't stop dead when the target goes: it glides out.
            if (humanAim) turnBy(human.release(dt))
            return
        }
        if (aura.silent.value && !silentActive) {
            silentActive = true
            silentYaw = p.yRot
            silentPitch = p.xRot
            carryX = 0.0
            carryY = 0.0
        }
        if (t !== target) {
            target = t
            acquired = now
            lastSeen = null
            relVel = Vec3.ZERO
            val rLo = minOf(aura.reactionMin.value, aura.reactionMax.value) / 1000.0
            val rHi = maxOf(aura.reactionMin.value, aura.reactionMax.value) / 1000.0
            reaction = if (rHi <= 0.0) 0.0 else Rng.double(rLo, rHi)
            speedBias = Rng.double(0.0, 1.0)
            stiffJitter = Rng.double(0.90, 1.10)
            dampJitter = Rng.double(0.93, 1.07)
            tremorPhase = Rng.double(0.0, 100.0)
            spot = pickSpot(aura.aimSpot.value)
            spotTo = spot
            nextSpot = now
            human.retarget(humanParams(aura))
        }
        if ((now - acquired) / 1e9 < reaction) {
            if (humanAim) turnBy(human.release(dt))
            return
        }
        // HUMAN keeps one spot per target: re-aiming at a new spot on someone standing still is a flick for nothing,
        // and where each flick lands is scattered anyway (aim-error).
        if (now >= nextSpot && !humanAim) {
            spotTo = pickSpot(aura.aimSpot.value)
            nextSpot = now + (Rng.double(0.35, 0.8) * 1e9).toLong()
        }
        val k = 1.0 - exp(-dt * 3.0)
        spot = spot.add(spotTo.subtract(spot).scale(k))
        clickImpulse *= exp(-dt * 28.0)

        val aim = aimPoint(aura, t)
        if (humanAim) {
            // Where you and it are this frame, as drawn (positions move once a tick; the frame is between two ticks).
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            val shift = t.getPosition(pt).subtract(t.position())
            val c = t.boundingBox.center.add(shift)
            val spotNow = aim.add(shift)
            val eyeNow = p.getEyePosition(pt)
            val eyeVel = p.position().subtract(p.xo, p.yo, p.zo).scale(20.0)
            val flat = max(hypot(c.x - eyeNow.x, c.z - eyeNow.z), 0.3)
            val width = deg(2.0 * kotlin.math.atan(t.boundingBox.xsize / 2.0 / flat))
            val height = deg(2.0 * kotlin.math.atan(t.boundingBox.ysize / 2.0 / flat))
            val onBox = rayHitsTarget(t, Rotation(lookYaw(p), lookPitch(p)), aura.range.value + 0.75f, true)
            turnBy(
                human.step(
                    now / 1e9, dt, lookYaw(p).toDouble(), lookPitch(p).toDouble(), hv(eyeNow), hv(eyeVel), hv(spotNow), hv(c),
                    width, height, onBox, humanParams(aura)
                )
            )
            return
        }
        // Where you and it are this frame, as drawn (positions move once a tick; the frame is between two ticks).
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val shift = t.getPosition(pt).subtract(t.position())
        val eyeNow = p.getEyePosition(pt)

        // Not in the original: `tracking`. How its box moves against your eyes (it strafing, you moving), smoothed over
        // about 0.1 s as a person reads it, turned into how fast the aim point swings across your view. The hand matches
        // that share of it, so it keeps up with a strafe instead of trailing the spring's lag behind it, and aims that
        // share of a tick ahead: a hit is judged by the look sent the tick before, so on a strafer a look that is on
        // them now is a tick behind them by then.
        val rel = t.boundingBox.center.add(shift).subtract(eyeNow)
        val seen = lastSeen
        if (seen != null && dt > 0.0) {
            val inst = rel.subtract(seen).scale(1.0 / dt)
            // A jump of more than 2 blocks in a frame is a teleport, not movement.
            if (inst.lengthSqr() * dt * dt < 4.0) relVel = relVel.add(inst.subtract(relVel).scale(1.0 - exp(-dt / 0.1)))
        }
        lastSeen = rel
        val ff = aura.tracking.value / 100.0
        val aimNow = aim.add(shift).add(relVel.scale(0.05 * ff))
        val ax = aimNow.x - eyeNow.x
        val ay = aimNow.y - eyeNow.y
        val az = aimNow.z - eyeNow.z
        val flat2 = max(ax * ax + az * az, 0.09)
        val flat = sqrt(flat2)
        val errYaw = Mth.wrapDegrees(deg(atan2(az, ax)) - 90.0 - lookYaw(p))
        val errPitch = -deg(atan2(ay, flat)) - lookPitch(p)
        val yawRate = deg((ax * relVel.z - az * relVel.x) / flat2)
        val flatRate = (ax * relVel.x + az * relVel.z) / flat
        val pitchRate = -deg((flat * relVel.y - ay * flatRate) / (flat2 + ay * ay))

        val smooth = aura.smoothness.value.toDouble().coerceIn(0.05, 1.0)
        val stiffness = (155.0 * (1.0 - smooth * 0.84)) * stiffJitter
        val damping = (0.72 + smooth * 0.30) * dampJitter
        val sLo = minOf(aura.minAimSpeed.value, aura.maxAimSpeed.value).toDouble()
        val sHi = maxOf(aura.minAimSpeed.value, aura.maxAimSpeed.value).toDouble()
        val topSpeed = sLo + (sHi - sLo) * speedBias

        val onBox = rayHitsTarget(t, Rotation(lookYaw(p), lookPitch(p)), aura.range.value + 0.75f, true)
        val relax = aura.hitboxRelax.value / 100.0
        val pullScale = if (onBox) 1.0 - relax else 1.0
        val dampBoost = if (onBox) 1.0 + relax * 2.0 else 1.0
        val pitchScale = aura.pitchAssist.value / 100.0

        val c = 2.0 * sqrt(stiffness) * damping * dampBoost
        vYaw += (stiffness * pullScale * errYaw - c * (vYaw - ff * yawRate)) * dt
        if (pitchScale <= 0.0) {
            vPitch = 0.0
        } else {
            vPitch += (stiffness * 0.85 * pitchScale * pullScale * errPitch - c * (vPitch - ff * pitchScale * pitchRate)) * dt
        }
        val speed = hypot(vYaw, vPitch)
        if (speed > topSpeed && speed > 1e-6) {
            vYaw *= topSpeed / speed
            vPitch *= topSpeed / speed
        }

        tremorPhase += dt
        val tr = aura.tremor.value.toDouble() * (1.0 + clickImpulse * 0.65)
        val shakeYaw = (sin(tremorPhase * 9.1) * 1.1 + sin(tremorPhase * 23.7) * 0.55) * tr
        val shakePitch = (sin(tremorPhase * 7.3 + 1.7) * 0.9 + sin(tremorPhase * 19.9) * 0.45) * tr * max(pitchScale, 0.25)

        // In whole mouse counts: vanilla turns 1.2 x f^3 degrees a count, f being 0.6 x sensitivity + 0.2.
        val f = mc.options.sensitivity().get() * 0.6 + 0.2
        val perCount = f * f * f * 8.0
        val step = perCount * 0.15
        carryX += (vYaw + shakeYaw) * dt / step
        carryY += if (pitchScale <= 0.0 && tr <= 0.0) 0.0 else (vPitch + shakePitch) * dt / step
        val countsX = carryX.toInt()
        val countsY = carryY.toInt()
        carryX -= countsX
        carryY -= countsY
        if (countsX != 0 || countsY != 0) turn(p, countsX * perCount, countsY * perCount)
    }

    /** Turns by [d] (yaw, pitch degrees) in whole mouse counts at your sensitivity, carrying the remainder. */
    private fun turnBy(d: DoubleArray) {
        val p = mc.player ?: return
        val f = mc.options.sensitivity().get() * 0.6 + 0.2
        val perCount = f * f * f * 8.0
        val step = perCount * 0.15
        carryX += d[0] / step
        carryY += d[1] / step
        val countsX = carryX.toInt()
        val countsY = carryY.toInt()
        carryX -= countsX
        carryY -= countsY
        if (countsX != 0 || countsY != 0) turn(p, countsX * perCount, countsY * perCount)
    }

    private fun hv(v: Vec3) = HumanAim.V(v.x, v.y, v.z)

    private fun humanParams(aura: Aura) = HumanAim.Params(
        speedLo = aura.minAimSpeed.value.toDouble(),
        speedHi = aura.maxAimSpeed.value.toDouble(),
        fittsB = 0.05 + 0.15 * aura.smoothness.value.toDouble().coerceIn(0.05, 1.0),
        perceptionDelay = aura.perceptionDelay.value / 1000.0,
        overshoot = aura.overshoot.value / 100.0,
        aimError = aura.aimError.value / 100.0,
        curve = aura.curve.value / 100.0,
        tracking = aura.tracking.value / 100.0,
        pitchScale = aura.pitchAssist.value / 100.0,
        relax = aura.hitboxRelax.value / 100.0,
        tremor = aura.tremor.value.toDouble(),
    )

    private fun pickSpot(mode: Aura.LegitSpot): Vec3 = when (mode) {
        Aura.LegitSpot.WANDER, Aura.LegitSpot.CLOSEST -> Vec3(Rng.double(0.30, 0.70), Rng.double(0.56, 0.86), Rng.double(0.30, 0.70))
        Aura.LegitSpot.CHEST -> Vec3(Rng.double(0.42, 0.58), Rng.double(0.62, 0.73), Rng.double(0.42, 0.58))
        Aura.LegitSpot.HEAD -> Vec3(Rng.double(0.42, 0.58), Rng.double(0.80, 0.90), Rng.double(0.42, 0.58))
    }
}
