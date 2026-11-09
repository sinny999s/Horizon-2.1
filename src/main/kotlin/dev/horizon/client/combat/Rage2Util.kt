package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FireworkVectorEvent
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.equip.ElytraSwap
import dev.horizon.client.module.rage.Blink
import dev.horizon.client.module.rage.ElytraTarget
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val mc get() = Minecraft.getInstance()

/**
 * A module is about to send START_FALL_FLYING itself, so elytra-swap must not fire its own
 * firework for that packet (elytra-swap's flag, which it reads and clears on START_FALL_FLYING).
 */
object ElytraSwapHook {
    var moduleStart: Boolean
        get() = Modules.get<ElytraSwap>().skipFirework
        set(value) {
            Modules.get<ElytraSwap>().skipFirework = value
        }
}

/** Gui-move's internal-click flag: set around clicks a module makes itself; GuiMove reads it. */
object GuiMoveHook {
    var internalClick: Boolean
        get() = Modules.get<GuiMove>().internalClick
        set(value) {
            Modules.get<GuiMove>().internalClick = value
        }
}

/**
 * The firework helpers elytra-target, mace-elytra-target and elytra-plus's silent firework mode call: using a firework
 * and the built-in boost table (pitch → strength).
 */
object ElytraFirework {
    /** The built-in boost table, a `HashMap` on purpose so ties go to the same key. */
    val table: HashMap<Float, Float> = hashMapOf(
        0.0f to 0.155f, 5.0f to 0.16f, 10.0f to 0.19f, 15.0f to 0.22f, 20.0f to 0.22f, 25.0f to 0.27f, 30.0f to 0.28f,
        35.0f to 0.34f, 40.0f to 0.37f, 45.0f to 0.37f, 50.0f to 0.34f, 55.0f to 0.34f, 60.0f to 0.2f, 65.0f to 0.18f,
        70.0f to 0.16f, 75.0f to 0.16f, 80.0f to 0.13f, 85.0f to 0.13f, 90.0f to 0.13f,
    )

    /**
     * The firework boost with the per-pitch strength from [speeds] (the first closest key in iteration order), or
     * 0.1 with [speeds] null: vanilla's formula with 0.1 replaced.
     */
    fun velocity(look: Vec3, speeds: Map<Float, Float>?): Vec3 {
        val p = mc.player ?: return Vec3.ZERO
        val pitch = abs(Math.toDegrees(-atan2(look.y, hypot(look.x, look.z))).toFloat())
        var best: Map.Entry<Float, Float>? = null
        var bestDiff = 0f
        if (speeds != null) for (e in speeds.entries) {
            val d = abs(pitch - e.key)
            if (best == null || bestDiff.compareTo(d) > 0) {
                best = e
                bestDiff = d
            }
        }
        val speed = if (speeds != null) best?.value ?: 0.1f else 0.1f
        val m = p.deltaMovement
        return m.add(look.x * speed + (look.x * 1.5f - m.x) * 0.5, look.y * speed + (look.y * 1.5f - m.y) * 0.5,
            look.z * speed + (look.z * 1.5f - m.z) * 0.5)
    }

    /** The look the firework boosts along: FireworkVectorEvent's (the rotation manager's) or the player's. */
    fun look(): Vec3 {
        val p = mc.player ?: return Vec3.ZERO
        val e = EventBus.post(FireworkVectorEvent(p.lookAngle))
        return if (e.cancelled) e.vector else p.lookAngle
    }

    /**
     * Use a firework, through the offhand (a swap click, or three pickups with
     * [altSwap]) or the hotbar (moving one in from the inventory). With none, it disables elytra-target and says so
     * under its name, whoever asked.
     */
    fun use(offhand: Boolean, altSwap: Boolean) {
        val p = mc.player ?: return
        val hot = Inventory.hotbarFind(Items.FIREWORK_ROCKET)
        val inv = Inventory.find(Items.FIREWORK_ROCKET)
        fun hand() = if (p.offhandItem.item == Items.FIREWORK_ROCKET) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        if (offhand && !Modules.get<Blink>().enabled) {
            if (p.offhandItem.item == Items.FIREWORK_ROCKET) {
                mc.gameMode?.useItem(p, hand())
                return
            }
            if (inv == null) return noFireworks()
            GuiMoveHook.internalClick = true
            if (altSwap) {
                containerClick(inv.slot)
                containerClick(45)
                containerClick(inv.slot)
            } else {
                containerClick(inv.slot, 40, ContainerInput.SWAP)
            }
            mc.gameMode?.useItem(p, hand())
            containerClick(inv.slot, 40, ContainerInput.SWAP, true)
            GuiMoveHook.internalClick = false
            return
        }
        val prev = p.inventory.selectedSlot
        if (hot != null) {
            Inventory.saveAndSelect(hot.slot)
        } else if (inv != null) {
            val b = prev % 8 + 1
            containerClick(inv.slot, b, ContainerInput.SWAP, true)
            Inventory.saveAndSelect(b)
        } else if (p.offhandItem.item != Items.FIREWORK_ROCKET) {
            return noFireworks()
        }
        mc.gameMode?.useItem(p, hand())
        Inventory.restore()
        // Swaps back with the restored slot, not the one it moved the firework into (on purpose).
        if (hot == null && inv != null) containerClick(inv.slot, p.inventory.selectedSlot, ContainerInput.SWAP, true)
    }

    private fun noFireworks() {
        val target = Modules.get<ElytraTarget>()
        target.disable()
        moduleMessage(target.name.trim(), "You've got no fireworks!")
    }
}
