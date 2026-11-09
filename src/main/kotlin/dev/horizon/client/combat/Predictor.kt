package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The motion predictor: every tick, each player's hitbox for the next 20 ticks, walking on at their current
 * horizontal speed (clamped to walk/sprint speeds) and falling with vanilla gravity.
 */
object Predictor {
    private val mc get() = Minecraft.getInstance()
    private val boxes = ConcurrentHashMap<Int, List<AABB>>()

    fun init() {
        EventBus.on<PlayerUpdateEvent>(this, 20) {
            val level = mc.level ?: return@on
            val seen = HashSet<Int>()
            for (p in level.players()) {
                boxes[p.id] = simulate(p)
                seen += p.id
            }
            boxes.keys.retainAll(seen)
        }
    }

    private fun simulate(p: Player): List<AABB> {
        val level = mc.level!!
        var vx: Double
        var vy: Double
        var vz: Double
        if (p === mc.player) {
            val d = p.deltaMovement
            vx = d.x; vy = d.y; vz = d.z
        } else {
            vx = p.x - p.xo; vy = p.y - p.yo; vz = p.z - p.zo
        }
        val h = hypot(vx, vz)
        val c = Mth.clamp(h, if (p.isShiftKeyDown) 0.08511 else 0.2837, 0.45392)
        if (h != 0.0) {
            vx = vx / h * c
            vz = vz / h * c
        }
        if (level.noBlockCollision(p, p.boundingBox.expandTowards(0.0, -0.1, 0.0)) && vy > 0) vy = -0.0784
        var box = if (p.xo == 0.0 && p.yo == 0.0 && p.zo == 0.0) p.boundingBox else p.boundingBox.move(p.xo - p.x, p.yo - p.y, p.zo - p.z)
        if (vy > 0) vy = (vy - 0.08) * 0.98
        if (h < 0.05674) {
            vx = 0.0
            vz = 0.0
            if (abs(vy) < 0.1) return List(20) { box }
        }
        val out = ArrayList<AABB>(20)
        repeat(20) {
            vy = if (!level.noBlockCollision(p, box.move(0.0, -0.01, 0.0))) -0.0784 else (vy - 0.08) * 0.98
            val v = Vec3(vx, vy, vz)
            val moved = Entity.collideBoundingBox(p, v, box, level, level.getEntityCollisions(p, box.expandTowards(v)))
            box = box.move(moved)
            out += box
        }
        return out
    }

    /** The predicted hitbox [ticks] ahead (the current one for 0, or when the player wasn't simulated). */
    fun box(p: Entity, ticks: Int): AABB {
        if (ticks == 0) return p.boundingBox
        val list = boxes[p.id] ?: return p.boundingBox
        return list[minOf(ticks, list.size - 1)]
    }

    fun feet(p: Entity, ticks: Int): Vec3 {
        val b = box(p, ticks)
        return b.center.subtract(0.0, p.boundingBox.ysize / 2, 0.0)
    }

    /** About one tick per 7.8 ms of our ping plus theirs, above ~54 ms. */
    fun linearTicks(p: Player): Int {
        val latency = mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
        val sum = PlayerState.ping + latency
        return (0.12857f * sum.toFloat() - 7.0f).roundToInt().coerceIn(0, 100)
    }

    /** Last tick's movement carried on until a non-air block is in the way (then it stops for good). */
    fun linear(p: Player, ticks: Int): Vec3 {
        val level = mc.level ?: return p.position()
        var pos = p.position()
        var dx = p.x - p.xo
        var dz = p.z - p.zo
        repeat(ticks) {
            val ox = if (dx > 0) 0.3f.toDouble() else (-0.3f).toDouble()
            val oz = if (dz > 0) 0.3f.toDouble() else (-0.3f).toDouble()
            if (!level.isEmptyBlock(BlockPos.containing(pos.add(dx + ox, 0.25, dz + oz))) ||
                !level.isEmptyBlock(BlockPos.containing(pos.add(dx + ox, 1.0, dz + oz)))) {
                dx = 0.0
                dz = 0.0
            }
            pos = pos.add(dx, 0.0, dz)
        }
        return pos
    }
}
