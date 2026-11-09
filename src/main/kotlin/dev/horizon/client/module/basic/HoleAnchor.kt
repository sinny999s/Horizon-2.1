package dev.horizon.client.module.basic

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.HoleCache
import dev.horizon.client.combat.MoveFixKind
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.Rubberband
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Looking down at least [pitch], it pulls the player to the nearest visible hole below
 * (one in [prioritizedFov] first). With move-fix on it does that by turning the server rotation to the hole (a FORCE
 * request, so the keys walk towards it); otherwise it overrides the horizontal part of each move.
 */
class HoleAnchor : Module("hole-anchor", Categories.BASIC) {
    val disableOverHole = bool("disable-over-hole", false)
    val pitch = int("pitch", 60, 0..90)
    val maxSpeed = float("max-speed", 2.0f, 0.1f..5.0f) { !Modules.get<MoveFix>().enabled }
    val rubberbandSpeed = float("rubberband-speed", 1.0f, 0.1f..5.0f) { !Modules.get<MoveFix>().enabled }
    val rubberbandTimeout = int("rubberband-timeout", 10, 1..30) { !Modules.get<MoveFix>().enabled }
    val useTimer = bool("use-timer", false) { !Modules.get<MoveFix>().enabled }
    val timerValue = float("timer-value", 1.0f, 1.0f..10.0f) { useTimer.value && !Modules.get<MoveFix>().enabled }
    val horizontalRange = int("horizontal-range", 3, 1..3)
    val verticalRange = int("vertical-range", 3, 1..3)
    val prioritizedFov = int("prioritized-fov", 30, 10..180)

    init {
        on<PlayerUpdateEvent> {
            if (!Modules.get<MoveFix>().enabled) return@on
            val target = findTarget() ?: return@on
            RotationManager.rotate(Rotation.lookAt(Vec3.atCenterOf(target.below())).withMoveFix(MoveFixKind.FORCE), 0)
        }
        on<MoveEvent>(-10) { e ->
            if (Modules.get<MoveFix>().enabled) return@on
            val target = findTarget() ?: return@on
            val p = mc.player ?: return@on
            val to = Vec3.atCenterOf(target.below())
            val from = p.position()
            val v = to.subtract(from).normalize().scale(minOf(speed(), from.distanceTo(to)))
            e.x = v.x
            e.z = v.z
            e.cancel()
            if (useTimer.value) TimerManager.factor = timerValue.value
        }
    }

    /** The cell above the chosen hole, or null; standing in a hole may switch the module off. */
    private fun findTarget(): BlockPos? {
        val p = mc.player ?: return null
        val from = p.position()
        val feet = BlockPos.containing(from)
        if (p.xRot < pitch.value.toFloat()) return null
        if (HoleCache.contains(feet)) {
            if (disableOverHole.value) disable()
            return null
        }
        val h = horizontalRange.value
        val candidates = ArrayList<BlockPos>()
        for (x in feet.x - h..feet.x + h) {
            for (z in feet.z - h..feet.z + h) {
                for (y in feet.y - verticalRange.value..feet.y) {
                    val c = BlockPos(x, y, z)
                    val hole = c.below()
                    if (Explosions.raycast(PlayerState.eyeOf(p), Vec3.atCenterOf(hole), false, emptyList(), emptyList()) != null) continue
                    if (!HoleCache.contains(hole)) continue
                    candidates += c
                }
            }
        }
        return nearest(candidates.filter { fovAngle(it) <= prioritizedFov.value.toFloat() }) ?: nearest(candidates)
    }

    private fun nearest(list: List<BlockPos>): BlockPos? {
        val from = mc.player!!.position()
        var best: BlockPos? = null
        var bestDist = 0.0
        for (c in list) {
            val d = from.distanceTo(Vec3.atCenterOf(c))
            if (best == null || d < bestDist) {
                best = c
                bestDist = d
            }
        }
        return best
    }

    /** The yaw difference to [pos], not wrapped again (so 0..360). */
    private fun fovAngle(pos: BlockPos): Float {
        val p = mc.player!!
        val c = Vec3.atCenterOf(pos)
        return abs(Mth.wrapDegrees(Math.toDegrees(atan2(c.z - p.z, c.x - p.x)) - 90.0) - Mth.wrapDegrees(p.yRot)).toFloat()
    }

    /** Blocks per move, slower for a while after the server corrected our position. */
    private fun speed(): Double {
        val base = 0.2873
        return if (Rubberband.sinceLast() < rubberbandTimeout.value * 1000) base * rubberbandSpeed.value else base * maxSpeed.value
    }
}
