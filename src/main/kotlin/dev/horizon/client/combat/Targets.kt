package dev.horizon.client.combat

import dev.horizon.client.config.Friends
import dev.horizon.client.module.legit.Teams
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

private val mc get() = Minecraft.getInstance()

/** The player to fight. */
object TargetFinder {
    enum class Sort { DISTANCE, FOV, HEALTH }

    fun find(range: Double, sort: Sort, ignoreNaked: Boolean, ignoreCreative: Boolean): Player? {
        val self = mc.player ?: return null
        val level = mc.level ?: return null
        val list = level.players().filter { p ->
            !p.isDeadOrDying && !Friends.isFriend(p.gameProfile.name) && !Teams.isTeammateOrNew(p) && p !== self && self.distanceTo(p) < range &&
                !(ignoreNaked && p.armorValue <= 0) && !(ignoreCreative && p.isCreative)
        }.sortedBy { self.distanceTo(it) }
        return when (sort) {
            Sort.DISTANCE -> list.minByOrNull { self.distanceTo(it) }
            Sort.HEALTH -> list.minByOrNull { it.health + it.absorptionAmount }
            Sort.FOV -> list.minByOrNull { fov(self, it) }
        }
    }

    /** Yaw difference only, not wrapped again after the subtraction (on purpose). */
    fun fov(self: Player, p: Player): Float {
        val dx = p.x - self.x
        val dz = p.z - self.z
        return abs(Mth.wrapDegrees(Math.toDegrees(atan2(dz, dx)).toFloat() - 90f) - Mth.wrapDegrees(self.yRot))
    }
}

/** Grim's attack-range check against a crystal at [pos], aiming at [interact]. */
object GrimRange {
    fun check(pos: Vec3, interact: Vec3): Boolean {
        val p = mc.player ?: return false
        val box = AABB(pos.x - 1, pos.y, pos.z - 1, pos.x + 1, pos.y + 2, pos.z + 1)
        val r = p.entityInteractionRange()
        val dir = interact.subtract(p.eyePosition).normalize().scale(r)
        val base = p.position()
        var minD = Double.MAX_VALUE
        for (h in doubleArrayOf(1.62, 0.4)) {
            val e = base.add(0.0, h, 0.0)
            val end = e.add(dir)
            if (box.contains(end) || box.contains(e)) return true
            val hit = box.clip(e, end)
            if (hit.isPresent) minD = min(minD, e.distanceToSqr(hit.get()))
        }
        return minD <= r * r
    }
}
