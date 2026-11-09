package dev.horizon.client.module.legit

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.config.Friends
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.attack.rayHitsTarget
import dev.horizon.client.module.attack.viewVec
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MaceItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Not in the original: standalone ghost mouse aim assist. Never attacks or sends silent rotations — gently nudges the
 * real camera each frame in whole sensitivity-GCD mouse counts, yielding automatically if [Aura] is fighting.
 */
class AimAssist : Module("aim-assist", Categories.LEGIT, listOf("aimbot", "smooth-aim")) {
    enum class AimSpot { CLOSEST, CHEST, HEAD, WANDER }

    val range = float("range", 4.2f, 2.5f..6.0f)
    val fov = int("fov", 75, 10..180)
    val aimSpot = enum("aim-spot", AimSpot.CLOSEST)
    val horizontalSpeed = float("horizontal-speed", 220.0f, 15.0f..600.0f)
    val verticalSpeed = float("vertical-speed", 85.0f, 0.0f..400.0f)
    val smoothness = float("smoothness", 0.55f, 0.05f..1.0f)
    val strafeBoost = float("strafe-boost", 25.0f, 0.0f..100.0f)
    val stopOnHitbox = bool("stop-on-hitbox", false)
    val hitboxRelax = int("hitbox-relax", 70, 0..100) { !stopOnHitbox.value }
    val onlyWhileMovingMouse = bool("only-while-moving-mouse", false)
    val requireClick = bool("require-click", true)
    val weaponOnly = bool("weapon-only", true)
    val checkLineOfSight = bool("check-line-of-sight", true)
    val playersOnly = bool("players-only", true)
    val ignoreInvisible = bool("ignore-invisible", true)
    val ignoreNaked = bool("ignore-naked", false)

    private var locked: LivingEntity? = null
    private var lastNs = 0L
    private var lastClickNs = 0L
    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var spot = Vec3(0.5, 0.68, 0.5)
    private var spotTo = spot
    private var nextSpotNs = 0L

    override fun onEnable() = clearState()
    override fun onDisable() = clearState()

    private fun clearState() {
        locked = null
        lastNs = 0L
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
    }

    init {
        on<MouseTurnEvent> { frame() }
    }

    private fun frame() {
        val p = mc.player ?: return
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
        lastNs = now

        if (PlayerState.physical(mc.options.keyAttack) || mc.options.keyAttack.isDown) {
            lastClickNs = now
        }

        if (!gatesPass(p, now)) {
            vYaw = 0.0
            vPitch = 0.0
            locked = null
            return
        }

        val t = selectTarget(p) ?: run {
            vYaw = 0.0
            vPitch = 0.0
            locked = null
            return
        }
        locked = t

        if (now >= nextSpotNs) {
            spotTo = pickSpot(aimSpot.value)
            nextSpotNs = now + (Rng.double(0.35, 0.85) * 1e9).toLong()
        }
        val k = 1.0 - exp(-dt * 3.0)
        spot = spot.add(spotTo.subtract(spot).scale(k))

        val onBox = rayHitsTarget(t, Rotation(p.yRot, p.xRot), range.value + 0.5f, checkLineOfSight.value)
        if (onBox && stopOnHitbox.value) {
            vYaw *= exp(-dt * 18.0)
            vPitch *= exp(-dt * 18.0)
            return
        }

        val aim = computeAimPoint(p, t)
        val eye = p.eyePosition
        val dx = aim.x - eye.x
        val dz = aim.z - eye.z
        val wantYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
        val wantPitch = -Math.toDegrees(atan2(aim.y - eye.y, hypot(dx, dz)))
        val errYaw = Mth.wrapDegrees(wantYaw - p.yRot)
        val errPitch = wantPitch - p.xRot

        val smooth = smoothness.value.toDouble().coerceIn(0.05, 1.0)
        val stiffness = 140.0 * (1.0 - smooth * 0.82)
        val damping = 0.78 + smooth * 0.28
        val relax = if (onBox) 1.0 - (hitboxRelax.value / 100.0) else 1.0
        val dampBoost = if (onBox) 1.0 + (hitboxRelax.value / 100.0) * 1.8 else 1.0
        val strafeMult = if (abs(p.input.moveVector.x) > 0.1f) 1.0 + strafeBoost.value / 100.0 else 1.0

        val c = 2.0 * sqrt(stiffness) * damping * dampBoost
        val maxH = horizontalSpeed.value.toDouble() * strafeMult
        val maxV = verticalSpeed.value.toDouble()

        vYaw = (vYaw + (stiffness * relax * strafeMult * errYaw - c * vYaw) * dt).coerceIn(-maxH, maxH)
        vPitch = if (maxV <= 0.5) 0.0 else (vPitch + (stiffness * 0.85 * relax * errPitch - c * vPitch) * dt).coerceIn(-maxV, maxV)

        val f = mc.options.sensitivity().get() * 0.6 + 0.2
        val perCount = f * f * f * 8.0
        val step = perCount * 0.15
        carryX += vYaw * dt / step
        carryY += if (maxV <= 0.5) 0.0 else vPitch * dt / step
        val countsX = carryX.toInt()
        val countsY = carryY.toInt()
        carryX -= countsX
        carryY -= countsY
        if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
    }

    private fun gatesPass(p: Player, nowNs: Long): Boolean {
        if (mc.gui.screen() != null) return false
        if (Modules.get<Aura>().isAttacking()) return false
        if (weaponOnly.value && !isWeapon(p.mainHandItem)) return false
        if (requireClick.value && (nowNs - lastClickNs) > 180_000_000L) return false
        if (onlyWhileMovingMouse.value && !MouseTurnEvent.movedWithinMs(125)) return false
        return true
    }

    private fun selectTarget(self: Player): LivingEntity? {
        val level = mc.level ?: return null
        val prev = locked
        if (prev != null && isValidTarget(self, prev, (fov.value * 1.15f).coerceAtMost(180f))) {
            return prev
        }
        var best: LivingEntity? = null
        var bestAngle = Double.MAX_VALUE
        for (e in level.entitiesForRendering()) {
            if (e !is LivingEntity || !isValidTarget(self, e, fov.value.toFloat())) continue
            val angle = angleTo(self, e.boundingBox.center)
            if (angle < bestAngle) {
                bestAngle = angle
                best = e
            }
        }
        return best
    }

    private fun isValidTarget(self: Player, e: LivingEntity, maxFov: Float): Boolean {
        if (e === self || !e.isAlive || e.isDeadOrDying || e is ArmorStand) return false
        if (ignoreInvisible.value && e.isInvisible) return false
        if (e is Player) {
            if (e.isCreative || e.isSpectator) return false
            if (Friends.isFriend(e.gameProfile.name) || Teams.isTeammateOrNew(e)) return false
            if (ignoreNaked.value && e.armorValue == 0) return false
        } else {
            if (playersOnly.value) return false
            if (e !is Enemy && e !is net.minecraft.world.entity.Mob) return false
        }
        val eye = self.eyePosition
        val c = e.boundingBox.center
        if (eye.distanceTo(c) > range.value + 0.5) return false
        if (angleTo(self, c) > maxFov) return false
        if (checkLineOfSight.value && !canSee(self, e)) return false
        return true
    }

    private fun angleTo(self: Player, pt: Vec3): Double {
        val eye = self.eyePosition
        val dx = pt.x - eye.x
        val dz = pt.z - eye.z
        val wantYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
        val wantPitch = -Math.toDegrees(atan2(pt.y - eye.y, hypot(dx, dz)))
        val dy = Mth.wrapDegrees(wantYaw - self.yRot)
        val dp = wantPitch - self.xRot
        return hypot(dy, dp * 0.75)
    }

    private fun canSee(self: Player, e: Entity): Boolean {
        val level = mc.level ?: return false
        val eye = self.eyePosition
        val b = e.boundingBox
        val c = b.center
        for (y in doubleArrayOf(b.maxY - 0.1, c.y, b.minY + 0.15)) {
            val hit = level.clip(ClipContext(eye, Vec3(c.x, y, c.z), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self))
            if (hit.type == HitResult.Type.MISS) return true
        }
        return false
    }

    private fun computeAimPoint(self: Player, target: Entity): Vec3 {
        val b = target.boundingBox
        if (aimSpot.value == AimSpot.CLOSEST) {
            val eye = self.eyePosition
            val dist = eye.distanceTo(b.center)
            val rayPt = eye.add(viewVec(self.xRot, self.yRot).scale(dist))
            val mx = b.xsize * 0.18
            val my = b.ysize * 0.14
            val mz = b.zsize * 0.18
            return Vec3(
                rayPt.x.coerceIn(b.minX + mx, b.maxX - mx),
                rayPt.y.coerceIn(b.minY + my, b.maxY - my),
                rayPt.z.coerceIn(b.minZ + mz, b.maxZ - mz)
            )
        }
        return Vec3(b.minX + b.xsize * spot.x, b.minY + b.ysize * spot.y, b.minZ + b.zsize * spot.z)
    }

    private fun pickSpot(mode: AimSpot): Vec3 = when (mode) {
        AimSpot.WANDER, AimSpot.CLOSEST -> Vec3(Rng.double(0.32, 0.68), Rng.double(0.56, 0.84), Rng.double(0.32, 0.68))
        AimSpot.CHEST -> Vec3(Rng.double(0.43, 0.57), Rng.double(0.62, 0.72), Rng.double(0.43, 0.57))
        AimSpot.HEAD -> Vec3(Rng.double(0.43, 0.57), Rng.double(0.80, 0.89), Rng.double(0.43, 0.57))
    }

    private fun isWeapon(s: ItemStack): Boolean =
        s.`is`(ItemTags.SWORDS) || s.`is`(ItemTags.AXES) || s.item is TridentItem || s.item is MaceItem
}
