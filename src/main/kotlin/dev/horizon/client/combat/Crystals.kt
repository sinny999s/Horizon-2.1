package dev.horizon.client.combat

import dev.horizon.client.hud.ServerInfo
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

private val mc get() = Minecraft.getInstance()

/** Tries at breaking a crystal or placing on a base; too many and AutoCrystal gives up on it. */
class Attempt(val time: Long, val pos: Vec3, val place: Boolean, private val notify: () -> Boolean) {
    var attempts = 1
        private set
    private val initDistSq = mc.player?.distanceToSqr(pos) ?: 0.0

    fun failed() = attempts >= max(5, ceil(PlayerState.ping / 25f).toInt())

    fun expired(): Boolean {
        val d = mc.player?.distanceToSqr(pos) ?: 0.0
        return abs(initDistSq - d) >= (if (failed()) 1 else 10) || System.currentTimeMillis() - time > 3000
    }

    fun increment() {
        attempts++
        if (notify() && failed()) mc.player?.sendSystemMessage(
            Component.literal("[Auto Crystal] Failed to " + (if (place) "place" else "break") + ", change your config!").withStyle(ChatFormatting.RED))
    }
}

/** Crystals attacked (or doomed), attack and place attempts, and boxes recently placed into. */
object CrystalTracker {
    /** Entity id → when it was attacked (or marked doomed by an explosion). */
    val attacked = ConcurrentHashMap<Int, Long>()
    private val attackAttempts = ConcurrentHashMap<Int, Attempt>()
    private val placeAttempts = ConcurrentHashMap<BlockPos, Attempt>()
    private val boxes = ConcurrentHashMap<AABB, Long>()

    /** AutoCrystal's `fail-notify`. */
    var notify: () -> Boolean = { false }

    fun isAttacked(id: Int) = attacked.containsKey(id)

    fun attackTime(id: Int): Long = attacked.getOrDefault(id, -1L)

    fun markAttacked(id: Int, pos: Vec3) {
        attacked.putIfAbsent(id, System.currentTimeMillis())
        attackAttempts.compute(id) { _, a ->
            if (a == null) Attempt(System.currentTimeMillis(), pos, false, notify) else a.also { it.increment() }
        }
    }

    fun attackFailed(id: Int) = attackAttempts[id]?.failed() ?: false

    fun placeAttemptFailed(pos: BlockPos) = placeAttempts[pos]?.failed() ?: false

    private fun boxOf(pos: BlockPos) = AABB(pos.x - 0.5, pos.y + 1.0, pos.z - 0.5, pos.x + 1.5, pos.y + 3.0, pos.z + 1.5)

    fun recordPlace(pos: BlockPos) {
        val level = mc.level ?: return
        val above2 = AABB(pos.above(2))
        val present = level.entitiesForRendering().any { it is EndCrystal && it.boundingBox.intersects(above2) }
        placeAttempts.compute(pos) { _, a ->
            if (a == null) Attempt(System.currentTimeMillis(), Vec3.atCenterOf(pos), true, notify)
            else a.also { if (!present) it.increment() }
        }
        boxes.putIfAbsent(boxOf(pos), System.currentTimeMillis())
    }

    /** A crystal was placed into an overlapping box just now. */
    fun recentBoxConflict(pos: BlockPos, limitPlace: Boolean): Boolean {
        val box = boxOf(pos)
        return boxes.keys.any { it.intersects(box) && (limitPlace || it.center != box.center) }
    }

    /** The place attempt a crystal spawning at [spawn] answers, if any. */
    fun matchSpawn(spawn: Vec3): Attempt? {
        var rec: Attempt? = null
        for ((base, a) in placeAttempts) {
            if (spawn.distanceToSqr(Vec3.atCenterOf(base)) < 0.3) {
                placeAttempts.remove(base)
                PlacementLock.unlock(base)
                rec = a
                break
            }
        }
        boxes.keys.removeIf { spawn.distanceToSqr(it.center) < 2.25 }
        if (rec != null) placeAttempts.keys.removeIf { spawn.distanceToSqr(Vec3.atCenterOf(it)) < 0.3 }
        return rec
    }

    /** Every world frame. */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val ping = PlayerState.ping
        attacked.values.removeIf { now - it > ping * 2 }
        attackAttempts.values.removeIf { it.expired() }
        placeAttempts.values.removeIf { it.expired() }
        boxes.values.removeIf { now - it > max(ping * 1.5, 50.0) }
    }

    fun clear() {
        attacked.clear()
        attackAttempts.clear()
        placeAttempts.clear()
    }
}

/** Holds back a place that isn't clearly better than the last one until ~510 ms have passed. */
object DamageSync {
    enum class Mode { ALWAYS, CONDITIONS, OFF }

    private var lastDamage = 0f
    private val placed = MsTimer()
    private var flip = false

    /** Reset by any anvil sound. */
    val anvilTimer = MsTimer()

    fun onPlaced(d: Float) {
        lastDamage = d
        placed.reset()
        flip = !flip
    }

    fun reset() {
        lastDamage = 0f
    }

    fun allow(damage: Float?, lethal: Boolean, extra: Boolean, mode: Mode, faceSync: Boolean, lowCrystals: Boolean, stacks: Float,
              minDmg: Float, aboutToBreak: Boolean): Boolean {
        if (damage == null || mode == Mode.OFF) return true
        if (mode == Mode.CONDITIONS) {
            val cond = (lowCrystals && crystalCount() <= 64 * stacks) || (faceSync && !lethal && damage < minDmg)
            if (!cond) return true
        }
        if (damage > lastDamage + 2.0f) return true
        if (placed.passed(((510 + (if (extra) 60 else 0)) / ServerInfo.tpsFactor()).toLong())) return true
        if (aboutToBreak) return true
        val ip = mc.currentServer?.ip ?: return false
        if (ip.contains("tntland")) return flip || !anvilTimer.passed(15500)
        return false
    }

    /** Crystals in the inventory (main, armour, off-hand), with the off-hand counted twice (on purpose). */
    private fun crystalCount(): Int {
        val p = mc.player ?: return 0
        return Inventory.count(Items.END_CRYSTAL) + p.offhandItem.let { if (it.`is`(Items.END_CRYSTAL)) it.count else 0 }
    }
}

/** Attacks the entity ids the next crystals will get, right after placing. */
object IdPredict {
    @Volatile
    var highest = -1
    private val timer = MsTimer()

    fun observe(id: Int) {
        highest = max(highest, id)
    }

    fun burst(offset: Int, attacks: Int, delay: Int) {
        if (!timer.passed(delay.toLong())) return
        timer.reset()
        if (offset > attacks) return
        val level = mc.level ?: return
        for (i in offset..attacks) {
            val id = highest + i
            val e = level.getEntity(id)
            if (e != null && e !is EndCrystal) continue
            Packets.send(ServerboundAttackPacket(id))
            // No swing packet after each (or only the last, with swing-once): 26.3 has none.
        }
    }
}
