package dev.horizon.client.module.legit

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Found
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.Switching
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * When a Flame bow is released it predicts where the arrow lands and, in the same
 * tick, places a rail and a TNT minecart there (rotation request, priority 100) for the burning arrow to set off.
 * Trajectories draws the target box from [active] and [inRange].
 */
class BowMinecart : Module("bow-minecart", Categories.LEGIT) {
    val switch = enum("switch", Switch.SILENT_FULL)
    val checkSelfDamage = bool("check-self-damage", false)

    private val rails = arrayOf(Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL)

    init {
        // Vanilla sends RELEASE_USE_ITEM before the player stops using, so the draw time is still readable here.
        on<AfterSendPacketEvent> { e ->
            val pk = e.packet
            if (pk !is ServerboundPlayerActionPacket || pk.action != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) return@on
            val p = mc.player ?: return@on
            if (p.ticksUsingItem <= 2) return@on
            if (!p.mainHandItem.`is`(Items.BOW)) return@on
            if (!hasFlame(p.mainHandItem)) return@on
            val pos = predictLanding() ?: return@on
            if (!inRange(pos)) return@on
            val hotbarOnly = switch.value == Switch.SILENT || switch.value == Switch.NORMAL
            val rail = if (hotbarOnly) Inventory.hotbarFind { it.item in rails } else Inventory.find { it.item in rails }
            val cart = if (hotbarOnly) Inventory.hotbarFind(Items.TNT_MINECART) else Inventory.find(Items.TNT_MINECART)
            if (rail == null || cart == null) return@on
            RotationManager.queue(RotationRequest(Rotation.lookAt(Vec3.atCenterOf(pos.above())), 100) { place(rail, pos) })
        }
    }

    /** On, with a Flame bow in the main hand (read by Trajectories). */
    fun active(): Boolean {
        val p = mc.player ?: return false
        return enabled && p.mainHandItem.`is`(Items.BOW) && hasFlame(p.mainHandItem)
    }

    /** Whether a landing spot can be used: 2.3 to `blockInteractionRange()` blocks from the eyes to its centre. */
    fun inRange(pos: BlockPos): Boolean {
        val p = mc.player ?: return false
        val range = p.blockInteractionRange().toFloat()
        val dist = p.eyePosition.distanceTo(Vec3.atCenterOf(pos))
        return dist >= 2.3f && dist <= range
    }

    private fun hasFlame(stack: ItemStack): Boolean {
        val level = mc.level ?: return false
        val flame = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FLAME)
        return flame in EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()
    }

    /**
     * The rotation's action: a rail on top of [pos] with the found slot, then a TNT minecart on the
     * rail, searched again per the switch mode. The rail's hit vector has z + 0.0 (on purpose).
     */
    private fun place(rail: Found, pos: BlockPos) {
        Switching.run(rail.slot, switch.value) {
            val p = mc.player ?: return@run
            mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3(pos.x + 0.5, pos.above().y.toDouble(), pos.z + 0.0), Direction.UP, pos, false))
        }
        Switching.run(switch.value, Items.TNT_MINECART) {
            val p = mc.player ?: return@run
            mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3(pos.x + 0.5, pos.above().y + 0.125, pos.z + 0.5), Direction.UP, pos.above(), false))
        }
    }

    /**
     * Steps an arrow from the feet X/Z at eye height − 0.1 along the camera rotation; only Y motion
     * is inherited, fluids are ignored, and any non-arrow entity near a step point cancels the prediction. With
     * `check-self-damage`, a spot whose minecart would out-damage health + absorption is skipped and the arrow
     * carries on through it.
     */
    private fun predictLanding(): BlockPos? {
        val p = mc.player ?: return null
        val level = mc.level ?: return null
        var x = p.x
        var y = p.eyePosition.y - 0.1f.toDouble()
        var z = p.z
        val yaw = Math.toRadians(p.yRot.toDouble())
        val pitch = Math.toRadians(p.xRot.toDouble())
        var mx = -sin(yaw) * cos(pitch)
        var my = -sin(pitch)
        var mz = cos(yaw) * cos(pitch)
        val f = p.ticksUsingItem / 20.0f
        var power = (f * f + f * 2.0f) / 3.0f
        if (power > 1.0f || power == 0.0f) power = 1.0f
        val len = sqrt(mx * mx + my * my + mz * mz)
        mx /= len
        my /= len
        mz /= len
        val v = power * 3
        mx *= v.toDouble()
        my *= v.toDouble()
        mz *= v.toDouble()
        if (!p.onGround()) my += p.deltaMovement.y
        for (i in 0 until 300) {
            val prev = Vec3(x, y, z)
            x += mx
            y += my
            z += mz
            mx *= 0.99
            my *= 0.99
            mz *= 0.99
            my -= 0.05f.toDouble()
            val cur = Vec3(x, y, z)
            val box = AABB(x - 0.3, y - 0.3, z - 0.3, x + 0.3, y + 0.3, z + 0.3)
            for (e in level.entitiesForRendering()) {
                if (e is Arrow || e == p) continue
                if (e.boundingBox.intersects(box)) return null
            }
            val hit = level.clip(ClipContext(prev, cur, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
            if (hit.type == HitResult.Type.BLOCK) {
                if (!checkSelfDamage.value) return hit.blockPos
                val dmg = Explosions.damage(Vec3.atCenterOf(hit.blockPos.above()), p, 0, false, emptyList(), emptyList())
                if (dmg <= p.health + p.absorptionAmount) return hit.blockPos
            } else if (y <= -65.0) {
                return null
            }
        }
        return null
    }
}
