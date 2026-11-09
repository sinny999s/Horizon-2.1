package dev.horizon.client.module.legit

import dev.horizon.client.combat.Rng
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.basic.Sprint
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fast-bridge ("Eagle") assist. Holds the sneak input as your feet approach a drop-off while
 * bridging, then lets go a tick or two after a block lands underneath so backward and 45° diagonal ninja-bridging
 * stay safe without constant manual Shift timing.
 */
class Eagle : Module("eagle", Categories.LEGIT, listOf("fast-bridge", "bridge-assist")) {
    val edgeDistance = float("edge-distance", 0.18f, 0.05f..0.29f)
    val minHold = int("min-hold", 1, 0..10)
    val maxHold = int("max-hold", 2, 0..10)
    val blocksOnly = bool("blocks-only", true)
    val backwardsOnly = bool("backwards-only", true)
    val pitchCheck = bool("pitch-check", true)
    val minPitch = float("min-pitch", 50.0f, 25.0f..85.0f) { pitchCheck.value }
    val groundOnly = bool("ground-only", true)

    private var sneaking = false
    private var holdTicks = 0

    override fun onEnable() {
        sneaking = false
        holdTicks = 0
    }

    override fun onDisable() {
        sneaking = false
        holdTicks = 0
    }

    init {
        on<KeyboardInputEvent>(-10) {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val keys = p.input.keyPresses

            if (!activeForState(p, keys)) {
                sneaking = false
                holdTicks = 0
                return@on
            }

            val edge = overEdge(p, level, keys)
            if (edge && (!groundOnly.value || p.onGround() || sneaking)) {
                sneaking = true
                val lo = min(minHold.value, maxHold.value)
                val hi = max(minHold.value, maxHold.value)
                holdTicks = Rng.int(lo, hi)
            } else if (sneaking) {
                if (holdTicks > 0) {
                    holdTicks--
                } else {
                    sneaking = false
                }
            }

            if (sneaking && !keys.shift()) {
                Modules.get<Sprint>().releaseKey()
                p.input.keyPresses = Input(
                    keys.forward(),
                    keys.backward(),
                    keys.left(),
                    keys.right(),
                    keys.jump(),
                    true,
                    false
                )
            }
        }
    }

    private fun activeForState(p: Player, keys: Input): Boolean {
        if (p.abilities.flying || p.isInLiquid || p.isPassenger || p.isFallFlying) return false
        if (mc.gui.screen() != null && !Modules.get<GuiMove>().enabled) return false
        if (blocksOnly.value && !holdingBlocks(p)) return false
        if (pitchCheck.value && p.xRot < minPitch.value) return false
        val moving = keys.forward() || keys.backward() || keys.left() || keys.right()
        if (backwardsOnly.value) {
            // Keep holding if we already caught an edge right as the player paused S to place.
            if (!keys.backward() && !sneaking) return false
        } else if (!moving && !sneaking) {
            return false
        }
        return true
    }

    private fun holdingBlocks(p: Player): Boolean {
        val main = p.mainHandItem
        return main.item is BlockItem || (main.isEmpty && p.offhandItem.item is BlockItem)
    }

    /**
     * Shrinks the player's 0.6x0.6 footprint inward by [edgeDistance] on X and Z and checks both the current spot and
     * one tick of predicted horizontal travel for supporting voxel shapes within 0.5 blocks below the feet.
     */
    private fun overEdge(p: Player, level: Level, keys: Input): Boolean {
        val d = edgeDistance.value.toDouble().coerceIn(0.05, 0.29)
        val b = p.boundingBox
        val yMin = p.y - 0.5
        val yMax = p.y + 0.02
        val here = AABB(b.minX + d, yMin, b.minZ + d, b.maxX - d, yMax, b.maxZ - d)
        if (level.noCollision(p, here)) return true

        val impulse = inputVector(p.yRot, keys, 0.08)
        val dx = p.deltaMovement.x + impulse[0]
        val dz = p.deltaMovement.z + impulse[1]
        if (dx * dx + dz * dz < 1e-6) return false
        val ahead = AABB(b.minX + d + dx, yMin, b.minZ + d + dz, b.maxX - d + dx, yMax, b.maxZ - d + dz)
        return level.noCollision(p, ahead)
    }

    private fun inputVector(yawDeg: Float, keys: Input, scale: Double): DoubleArray {
        val fwd = (if (keys.forward()) 1.0 else 0.0) - (if (keys.backward()) 1.0 else 0.0)
        val str = (if (keys.left()) 1.0 else 0.0) - (if (keys.right()) 1.0 else 0.0)
        if (fwd == 0.0 && str == 0.0) return doubleArrayOf(0.0, 0.0)
        val len = Mth.invSqrt(fwd * fwd + str * str)
        val nf = fwd * len * scale
        val ns = str * len * scale
        val rad = Math.toRadians(yawDeg.toDouble())
        val s = sin(rad)
        val c = cos(rad)
        return doubleArrayOf(ns * c - nf * s, nf * c + ns * s)
    }
}
