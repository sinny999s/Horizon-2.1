package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Throws an ender pearl back to safety when a fall can't be saved with a block (knocked too far
 * out for [Clutch]). With no ground within [maxFall] blocks below where you left it, it searches throw angles back
 * towards that spot, playing each pearl's flight out with vanilla's projectile physics (your own motion added, and the
 * server's random spread allowed for), and keeps the throws that land on top of a block with room to stand. It turns
 * the camera there in whole mouse counts and throws through vanilla's right click, but only a throw that teleports you
 * before you've fallen [maxFall] blocks, your ping counted. Takes a pearl from the hotbar.
 */
class PearlSave : Module("pearl-save", Categories.LEGIT) {
    /** The pearl has to land before the fall gets this deep, measured from where you left the ground. */
    val maxFall = int("max-fall", 20, 8..60)
    val turnSpeed = float("turn-speed", 900.0f, 200.0f..2000.0f)
    /** Milliseconds between seeing the fall and starting to turn. */
    val reaction = int("reaction", 60, 0..300)

    private var since = 0L
    private var thrown = false
    /** Where the feet last stood, and the block under them: the fall is measured from there and aimed back at it. */
    private var groundY = 0.0
    private var groundPos: BlockPos? = null
    private var aimYaw = 0.0
    private var aimPitch = 0.0
    private var aiming = false
    private val probe = BlockPos.MutableBlockPos()
    private val cache = HashMap<Long, Boolean>()

    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    /** Turning for a throw: [FireballDeflect] leaves the camera to it. */
    val busy get() = enabled && aiming

    override fun onEnable() = reset()
    override fun onDisable() = reset()

    private fun reset() {
        since = 0L
        thrown = false
        aiming = false
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (p.onGround()) {
                groundY = p.y
                groundPos = p.onPos.immutable()
            }
            if (mc.gui.screen() != null || p.onGround() || p.abilities.flying || p.isInLiquid || p.isPassenger ||
                p.isFallFlying || p.isSpectator || godBridging(p)) {
                reset()
                return@on
            }
            if (thrown) return@on
            cache.clear()
            val path = fall(p, if (aiming) aimYaw else homeYaw(p) ?: p.yRot.toDouble())
            val deadline = path.deadline
            if (deadline == null) {
                aiming = false
                return@on
            }
            // A block under the feet beats a pearl: while Clutch has one to place, it goes first.
            if (Modules.get<Clutch>().busy) {
                aiming = false
                return@on
            }
            val pearl = Inventory.hotbarFind(Items.ENDER_PEARL) ?: return@on
            if (p.cooldowns.isOnCooldown(pearl.stack)) return@on
            if (since == 0L) since = System.nanoTime()
            val budget = deadline - (PlayerState.ping / 50.0 + 1.0)
            // Throw now if the camera is already on a throw that makes it (within the server's usual spread).
            val now = landing(path.at(0), p.yRot.toDouble(), p.xRot.toDouble(), THROW_SPREAD)
            if (now != null && now <= budget && p.mainHandItem.`is`(Items.ENDER_PEARL)) {
                (mc as MinecraftAccessor).horizon_startUseItem()
                thrown = true
                aiming = false
                return@on
            }
            if (!p.mainHandItem.`is`(Items.ENDER_PEARL)) Inventory.selectHotbar(pearl.slot)
            // Keep the throw being turned to while it still works, so the camera settles on it instead of chasing a
            // new best every tick as the fall goes on; look for another only when it stops working.
            val kept = aiming && arrival(p, aimYaw, aimPitch).let { t ->
                landing(path.at(t), aimYaw, aimPitch, PLAN_SPREAD).let { it != null && t + it <= budget }
            }
            if (!kept) {
                val plan = solve(p, path, budget)
                aiming = plan != null
                if (plan != null) {
                    aimYaw = plan[0]
                    aimPitch = plan[1]
                }
            }
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            if (!aiming || Modules.get<Clutch>().busy || (System.nanoTime() - since) / 1e6 < reaction.value) return@on
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now
            var errYaw = Mth.wrapDegrees(aimYaw - p.yRot)
            var errPitch = aimPitch - p.xRot
            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            val perCount = f * f * f * 8.0
            val step = perCount * 0.15
            if (abs(errYaw) < step) {
                errYaw = 0.0
                vYaw = 0.0
                carryX = 0.0
            }
            if (abs(errPitch) < step) {
                errPitch = 0.0
                vPitch = 0.0
                carryY = 0.0
            }
            val maxSpeed = turnSpeed.value.toDouble()
            val stiffness = 900.0
            val c = 2.0 * sqrt(stiffness) * 0.9
            vYaw = (vYaw + (stiffness * errYaw - c * vYaw) * dt).coerceIn(-maxSpeed, maxSpeed)
            vPitch = (vPitch + (stiffness * errPitch - c * vPitch) * dt).coerceIn(-maxSpeed, maxSpeed)
            carryX += vYaw * dt / step
            carryY += vPitch * dt / step
            val countsX = carryX.toInt()
            val countsY = carryY.toInt()
            carryX -= countsX
            carryY -= countsY
            if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
        }
    }

    private fun godBridging(p: LocalPlayer): Boolean {
        val gb = Modules.get<GodBridge>()
        if (!gb.enabled || gb.mode.value != GodBridge.Mode.GOD_BRIDGE) return false
        return Movement.moving() && (p.input.keyPresses.backward() || p.input.moveVector.y < 0f)
    }

    private fun solid(x: Int, y: Int, z: Int): Boolean = cache.getOrPut(BridgePlanner.key(x, y, z)) {
        val level = mc.level!!
        probe.set(x, y, z)
        !level.getBlockState(probe).getCollisionShape(level, probe).isEmpty
    }

    /** Where a pearl leaves from at some tick of the fall: the eyes, and the step the server knows the player made. */
    private class From(val x: Double, val eyeY: Double, val z: Double, val mx: Double, val my: Double, val mz: Double)

    /**
     * The fall played out: where a throw would leave from at each coming tick, and [deadline], the ticks until the
     * feet are [maxFall] blocks below where they left the ground (null when it lands on something first: nothing to
     * save).
     */
    private class Fall(val deadline: Int?, val froms: List<From>) {
        fun at(t: Int) = froms[t.coerceIn(0, froms.size - 1)]
    }

    private fun fall(p: LocalPlayer, yaw: Double): Fall {
        val eye = p.eyeHeight.toDouble()
        val froms = arrayListOf(From(p.x, p.y + eye, p.z, p.x - p.xo, p.y - p.yo, p.z - p.zo))
        val v = p.deltaMovement
        val s = BridgePlanner.State(p.x, p.y, p.z, v.x, v.y, v.z, false)
        // Held keys push along the camera, and the camera is turning: they're taken to push along where it's going.
        val air = BridgePlanner.push(p.xxa.toDouble(), p.zza.toDouble(), yaw, if (p.isSprinting) 0.026 else 0.02)
        val params = BridgePlanner.Params(0.0, -1.0, 0.0, 0.0, 0.0, air[0], air[1],
            halfWidth = p.bbWidth / 2.0, height = p.bbHeight.toDouble(), gravity = p.gravity,
            jumpPower = p.getAttributeValue(Attributes.JUMP_STRENGTH))
        val floorY = groundY - maxFall.value
        for (t in 1..200) {
            val x0 = s.x
            val y0 = s.y
            val z0 = s.z
            BridgePlanner.step(s, params, ::solid, false, walls = true)
            if (s.onGround) return Fall(null, froms)
            froms += From(s.x, s.y + eye, s.z, s.x - x0, s.y - y0, s.z - z0)
            if (s.y < floorY) return Fall(t, froms)
        }
        return Fall(null, froms)
    }

    /** The yaw facing back to where the feet last stood. */
    private fun homeYaw(p: LocalPlayer): Double? {
        val home = groundPos ?: return null
        return Math.toDegrees(atan2(home.z + 0.5 - p.z, home.x + 0.5 - p.x)) - 90.0
    }

    /** Ticks until the camera can be on this rotation and throw: what's left of the reaction, then the turn. */
    private fun arrival(p: LocalPlayer, yaw: Double, pitch: Double): Int {
        val left = (reaction.value - (System.nanoTime() - since) / 1e6).coerceAtLeast(0.0) / 50.0
        val turn = (abs(Mth.wrapDegrees(yaw - p.yRot)) + abs(pitch - p.xRot)) / (turnSpeed.value / 20.0)
        return ceil(left + turn).toInt() + 1
    }

    /**
     * The best throw as (yaw, pitch): searched around the direction back to where the feet last stood, keeping
     * throws that land in time on every spread, then the soonest teleport counting the time to turn there.
     */
    private fun solve(p: LocalPlayer, path: Fall, budget: Double): DoubleArray? {
        val baseYaw = homeYaw(p) ?: return null
        var best: DoubleArray? = null
        var bestScore = Double.MAX_VALUE
        var yawOff = -24.0
        while (yawOff <= 24.0) {
            val yaw = baseYaw + yawOff
            var pitch = -60.0
            while (pitch <= 88.0) {
                // Thrown from where the player will be once the camera gets there, not from where they are now.
                val turnTicks = arrival(p, yaw, pitch)
                val from = path.at(turnTicks)
                val land = landing(from, yaw, pitch, 0.0)
                if (land != null && land + turnTicks <= budget && land + turnTicks < bestScore &&
                    landing(from, yaw, pitch, PLAN_SPREAD).let { it != null && it + turnTicks <= budget }) {
                    bestScore = (land + turnTicks).toDouble()
                    best = doubleArrayOf(yaw, pitch)
                }
                pitch += 2.5
            }
            yawOff += 6.0
        }
        return best
    }

    /**
     * Ticks a pearl thrown at this rotation flies before it lands safely (on top of a block, two clear blocks above,
     * no lower than the fall may go), or null. With a [spread], the throws that far off in each direction must land
     * safely too (the latest of them counts).
     */
    private fun landing(from: From, yaw: Double, pitch: Double, spread: Double): Int? {
        val base = flight(from, yaw, pitch, 0.0, 0.0) ?: return null
        if (spread <= 0.0) return base
        var worst = base
        for ((a, b) in listOf(spread to 0.0, -spread to 0.0, 0.0 to spread, 0.0 to -spread)) {
            val t = flight(from, yaw, pitch, a, b) ?: return null
            if (t > worst) worst = t
        }
        return worst
    }

    /** One pearl's flight as vanilla moves it: gravity, then drag, then the move; [ex]/[ey] tilt the throw (spread). */
    private fun flight(from: From, yaw: Double, pitch: Double, ex: Double, ey: Double): Int? {
        val yr = Math.toRadians(yaw)
        val pr = Math.toRadians(pitch)
        var dirX = -sin(yr) * cos(pr)
        var dirY = -sin(pr)
        var dirZ = cos(yr) * cos(pr)
        // Spread: across the throw sideways and upwards.
        dirX += cos(yr) * ex
        dirZ += sin(yr) * ex
        dirY += ey
        val len = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        // The server adds the movement it knows of: the last tick's step.
        var vx = dirX / len * SPEED + from.mx
        var vy = dirY / len * SPEED + from.my
        var vz = dirZ / len * SPEED + from.mz
        var x = from.x
        var y = from.eyeY - 0.1
        var z = from.z
        for (t in 1..MAX_FLIGHT) {
            vy -= GRAVITY
            vx *= DRAG
            vy *= DRAG
            vz *= DRAG
            val n = ceil(sqrt(vx * vx + vy * vy + vz * vz) / 0.4).toInt().coerceAtLeast(1)
            for (i in 1..n) {
                val px = x
                val py = y
                val pz = z
                x += vx / n
                y += vy / n
                z += vz / n
                val bx = floor(x).toInt()
                val by = floor(y).toInt()
                val bz = floor(z).toInt()
                if (!solid(bx, by, bz)) continue
                // Came down through the top (where the path crosses the top's height is over this block), with room
                // above to stand: a landing. Anything else is a miss.
                val top = by + 1.0
                val k = if (py > top && py > y) (py - top) / (py - y) else -1.0
                val fromAbove = k in 0.0..1.0 && floor(px + (x - px) * k).toInt() == bx && floor(pz + (z - pz) * k).toInt() == bz
                val safe = fromAbove && by + 1 >= groundY - maxFall.value && !solid(bx, by + 1, bz) && !solid(bx, by + 2, bz)
                return if (safe) t else null
            }
            if (y < (mc.level?.minY ?: -64) - 8) return null
        }
        return null
    }

    private companion object {
        const val SPEED = 1.5
        const val GRAVITY = 0.03
        const val DRAG = 0.99
        const val MAX_FLIGHT = 80
        /** Vanilla's spread is 0.0172 a unit of inaccuracy, per axis: a throw is made within one sigma of it... */
        const val THROW_SPREAD = 0.0172
        /** ...and aimed with room for two, so it isn't one that only just works. */
        const val PLAN_SPREAD = 0.035
    }
}
