package dev.horizon.client.module.world

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.SwitchUtil
import dev.horizon.client.event.EntitySpawnEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.attack.AutoCrystal
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * When someone else's ender pearl flies, works out where it lands and throws our own
 * pearl to land within 6 blocks of it. With only-target, only pearls nearest to a recent Aura / Auto Crystal target.
 */
class PearlChaser : Module("pearl-chaser", Categories.WORLD) {
    val onlyTarget = bool("only-target", false)
    val switch = enum("switch", Switch.SILENT_FULL)

    private val timer = MsTimer()

    /** Where the chased pearl lands (cleared every tick). */
    private var landing: BlockPos? = null

    /** The pearl last chased, and our own last thrown pearl. */
    private var chasedId = 0
    private var ownId = 0

    /** Players that were an Aura / Auto Crystal target, with when they were first seen (kept 10 s). */
    private val targets = HashMap<Player, Long>()

    init {
        // A new pearl whose nearest player is us is our own throw.
        on<EntitySpawnEvent> { e ->
            if (e.entity !is ThrownEnderpearl) return@on
            val level = mc.level ?: return@on
            level.players().minByOrNull { it.distanceToSqr(e.entity.position()) }?.let {
                if (it == mc.player) ownId = e.entity.id
            }
        }

        on<PlayerUpdateEvent>(-10) { tick() }
    }

    private fun tick() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        landing = null
        if (onlyTarget.value) {
            val aura = Modules.get<Aura>()
            val at = aura.target
            if (at != null && aura.enabled && at is Player && !targets.containsKey(at)) targets[at] = System.currentTimeMillis()
            val ac = Modules.get<AutoCrystal>()
            val ct = ac.target
            if (ct != null && ac.enabled && !targets.containsKey(ct)) targets[ct] = System.currentTimeMillis()
            for ((k, t) in HashMap(targets)) if (System.currentTimeMillis() - t > 10000L) targets.remove(k)
        }

        if (player.health < 5.0f) return
        if (!timer.passed(500)) return
        for (e in level.entitiesForRendering()) {
            if (e !is ThrownEnderpearl || e.id == chasedId || e.id == ownId) continue
            val nearest = level.players().filter { (targets.containsKey(it) || !onlyTarget.value) && it != player }
                .minByOrNull { it.distanceToSqr(e.position()) }
            if (nearest != null) {
                landing = simulate(e.position(), e.deltaMovement)
                chasedId = e.id
            }
        }

        val goal = landing ?: return
        if (player.distanceToSqr(Vec3.atCenterOf(goal)) < 49.0) return
        val yaw = Rotation.lookAt(Vec3.atCenterOf(goal)).yaw
        val pitch = pitchFor(goal, yaw)
        if (pitch.isNaN()) return
        val ours = simulate(player.eyePosition, throwVelocity(pitch, yaw)) ?: return
        if (goal.distToCenterSqr(Vec3.atCenterOf(ours)) > 36.0) return
        val rot = Rotation(yaw, Math.clamp(pitch, -89.0f, 89.0f))
        RotationManager.queue(RotationRequest(rot, 150) { throwPearl() })
        landing = null
        timer.reset()
    }

    /** Switch to a pearl and use it (no swing packet, which doesn't exist in 26.3). */
    private fun throwPearl() {
        SwitchUtil.run(Items.ENDER_PEARL, switch.value) {
            val p = mc.player ?: return@run
            mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        }
    }

    /**
     * For whole yaws ±10 around [yaw] and pitches 10 down to -85, the pitch whose throw lands nearest
     * [goal] (within 5 blocks, first one on a tie); NaN when none does. Only the pitch is kept.
     */
    private fun pitchFor(goal: BlockPos, yaw: Float): Float {
        val eye = mc.player?.eyePosition ?: return Float.NaN
        val hits = ArrayList<Pair<BlockPos, Float>>()
        var y = yaw.toInt() - 10
        val yMax = yaw.toInt() + 10
        while (y <= yMax) {
            var pitch = 10
            while (-86 < pitch) {
                val hit = simulate(eye, throwVelocity(pitch.toFloat(), y.toFloat()))
                if (hit != null && Vec3.atCenterOf(hit).distanceTo(Vec3.atCenterOf(goal)) < 5.0) hits += hit to pitch.toFloat()
                pitch--
            }
            y++
        }
        return hits.minByOrNull { Vec3.atCenterOf(it.first).distanceTo(Vec3.atCenterOf(goal)) }?.second ?: Float.NaN
    }

    /** A pearl's start velocity for [pitch]/[yaw] plus the player's movement (vertical only off the ground). */
    private fun throwVelocity(pitch: Float, yaw: Float): Vec3 {
        val p = mc.player!!
        val r = Math.PI / 180.0
        val x = -sin(yaw * r) * cos(pitch * r)
        val y = -sin(pitch * r)
        val z = cos(yaw * r) * cos(pitch * r)
        val known = p.knownMovement
        return Vec3(x, y, z).scale(1.5).add(known.x, if (p.onGround()) 0.0 else known.y, known.z)
    }

    /**
     * Up to 201 steps of gravity 0.03 and drag (0.8 in a liquid, else 0.99); the block it hits first, or
     * the point where it enters an entity (other than us) within 8 blocks.
     */
    private fun simulate(start: Vec3, velocity: Vec3): BlockPos? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        var v = velocity
        var pos = start
        var prev = start
        for (i in 0 until 201) {
            v = v.add(0.0, -0.03, 0.0)
            v = v.scale(if (level.getBlockState(BlockPos.containing(pos)).block is LiquidBlock) 0.8 else 0.99)
            val hit = level.clip(ClipContext(prev, pos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            if (hit.type != HitResult.Type.MISS) return hit.blockPos
            for (e in level.entitiesForRendering()) {
                if (e.distanceToSqr(pos) < 64.0 && e != player) {
                    val clip = e.boundingBox.clip(pos, pos.add(v))
                    if (clip.isPresent) return BlockPos.containing(clip.get())
                }
            }
            prev = pos
            pos = pos.add(v)
        }
        return null
    }
}
