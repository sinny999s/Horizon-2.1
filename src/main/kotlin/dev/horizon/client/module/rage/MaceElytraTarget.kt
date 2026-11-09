package dev.horizon.client.module.rage

import dev.horizon.client.combat.ElytraFirework
import dev.horizon.client.combat.ElytraSwapHook
import dev.horizon.client.combat.GuiMoveHook
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.combat.containerClick
import dev.horizon.client.event.FireworkVelocityEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.SetPoseEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Pose
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Wearing a chestplate, it jumps off the ground, swaps the elytra on for one
 * tick in the air to start gliding (and fires a rocket every [fireworkDelay] ticks), then with [peekAssist] climbs
 * [height] blocks above Aura's target (rocket, rotation at priority 200) to drop on it. The firework boost uses the
 * per-pitch table with [boost]; the gliding pose and armour-equip sounds are hidden.
 */
class MaceElytraTarget : Module("mace-elytra-target", Categories.RAGE) {
    val height = int("height", 25, 25..60)
    val elytraDelay = int("elytra-delay", 2, 0..40)
    val fireworkDelay = int("firework-delay", 8, 0..40)
    val offhandFirework = bool("offhand-firework", true)
    val altFireworkSwap = bool("alt-firework-swap", false) { offhandFirework.value }
    val pauseWhileEating = bool("pause-while-eating", true)
    val minimizeClicks = bool("minimize-clicks", false)
    val peekAssist = bool("peek-assist", true)
    val windBurstSync = bool("wind-burst-sync", true) { peekAssist.value }
    val boost = bool("boost", false)
    val customSpeed = bool("custom-speed", false) { boost.value }
    val n0Degrees = float("0-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n5Degrees = float("5-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n10Degrees = float("10-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n15Degrees = float("15-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n20Degrees = float("20-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n25Degrees = float("25-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n30Degrees = float("30-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n35Degrees = float("35-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n40Degrees = float("40-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n45Degrees = float("45-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n50Degrees = float("50-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n55Degrees = float("55-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n60Degrees = float("60-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n65Degrees = float("65-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n70Degrees = float("70-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n75Degrees = float("75-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n80Degrees = float("80-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n85Degrees = float("85-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n90Degrees = float("90-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }

    private val fireworkTimer = TickTimer()
    private val elytraTimer = TickTimer()

    /** Never reset (on purpose), so it has passed 10 ticks from the player's 10th tick on. */
    private val startTimer = TickTimer()

    /** The custom boost table, rebuilt from the settings on every boost with [customSpeed]. */
    private var customTable = HashMap<Float, Float>()

    /** Climbing for a drop: a rocket was fired and the target is not [height] below yet. */
    private var climbing = false

    /** Ticks to wait after a wind-burst hit. */
    private var pause = 0

    /** On, with [windBurstSync], holding a Wind Burst weapon. */
    fun windBurst(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (!enabled || !windBurstSync.value) return false
        val windBurst = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.WIND_BURST)
        return EnchantmentHelper.getItemEnchantmentLevel(windBurst, p.mainHandItem) > 0
    }

    private fun TickTimer.passedAndReset(n: Int): Boolean {
        if (!passed(n)) return false
        reset()
        return true
    }

    /** -2 when an elytra (damage < 430) is worn, else the first such elytra's slot id, else -1. */
    private fun elytraSlot(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in intArrayOf(39, 38, 37, 36)) {
            val s = inv.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return -2
        }
        for (i in 0 until 36) {
            val s = inv.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return if (i < 9) i + 36 else i
        }
        return -1
    }

    private fun startFlying() {
        val p = mc.player ?: return
        ElytraSwapHook.moduleStart = true
        p.startFallFlying()
        Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
        if (fireworkTimer.passedAndReset(fireworkDelay.value)) ElytraFirework.use(offhandFirework.value, altFireworkSwap.value)
    }

    /** The elytra on for one take-off, then back. */
    private fun deploy(slot: Int) {
        GuiMoveHook.internalClick = true
        if (slot >= 36) {
            containerClick(6, slot - 36, ContainerInput.SWAP, true)
            startFlying()
            containerClick(6, slot - 36, ContainerInput.SWAP, true)
        } else {
            containerClick(slot)
            containerClick(6)
            if (!minimizeClicks.value) containerClick(slot, close = true)
            startFlying()
            if (!minimizeClicks.value) containerClick(slot)
            containerClick(6)
            containerClick(slot, close = true)
        }
        GuiMoveHook.internalClick = false
    }

    /** Climb above the target after a hit (or while the attack isn't ready). */
    private fun peek() {
        val p = mc.player ?: return
        val aura = Modules.get<Aura>()
        val t = aura.target ?: return
        val rot = Rotation.lookAt(t.eyePosition.add(0.0, height.value.toDouble(), 0.0))
        val above = p.y - t.y > height.value
        if (aura.timer.countdown > 0 || !startTimer.passed(10)) {
            if (!climbing) ElytraFirework.use(offhandFirework.value, altFireworkSwap.value)
            climbing = true
        }
        if (above) climbing = false
        if (climbing) RotationManager.rotate(rot, 200)
    }

    private fun rebuildTable() {
        customTable = hashMapOf(
            0.0f to n0Degrees.value, 5.0f to n5Degrees.value, 10.0f to n10Degrees.value, 15.0f to n15Degrees.value,
            20.0f to n20Degrees.value, 25.0f to n25Degrees.value, 30.0f to n30Degrees.value, 35.0f to n35Degrees.value,
            40.0f to n40Degrees.value, 45.0f to n45Degrees.value, 50.0f to n50Degrees.value, 55.0f to n55Degrees.value,
            60.0f to n60Degrees.value, 65.0f to n65Degrees.value, 70.0f to n70Degrees.value, 75.0f to n75Degrees.value,
            80.0f to n80Degrees.value, 85.0f to n85Degrees.value, 90.0f to n90Degrees.value,
        )
    }

    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            if (pause > 0) {
                pause--
                return@on
            }
            val elytra = elytraSlot()
            val rockets = Inventory.find(Items.FIREWORK_ROCKET)
            val aura = Modules.get<Aura>()
            val t = aura.target
            val diving = aura.enabled && t != null && p.eyePosition.distanceTo(t.position()) < 4.0 &&
                p.deltaMovement.y < -1.0 && aura.timer.countdown <= 0
            if (elytra < 0 || rockets == null || p.isInWater || p.isInLava) return@on
            if (pauseWhileEating.value && p.isUsingItem &&
                (p.mainHandItem.components.has(DataComponents.FOOD) || p.mainHandItem.components.has(DataComponents.POTION_CONTENTS))
            ) return@on
            if (p.onGround()) {
                if (!mc.options.keyJump.isDown) {
                    mc.options.keyJump.isDown = true
                    Scheduler.ticks(3) { if (!Keys.down(32)) mc.options.keyJump.isDown = false }
                }
            } else {
                if (!diving && !p.isFallFlying && elytraTimer.passedAndReset(elytraDelay.value)) deploy(elytra)
                if (peekAssist.value) peek()
            }
        }
        on<FireworkVelocityEvent> { e ->
            e.cancel()
            val look = ElytraFirework.look()
            if (customSpeed.value) rebuildTable()
            val table = if (!Modules.get<Blink>().enabled && boost.value) (if (customSpeed.value) customTable else ElytraFirework.table) else null
            e.velocity = ElytraFirework.velocity(look, table)
        }
        on<SetPoseEvent> { e ->
            e.cancel()
            e.pose = Pose.STANDING
        }
        on<ReceivePacketEvent> { e ->
            val pk = e.packet as? ClientboundSoundPacket ?: return@on
            val s = pk.sound
            if (s == SoundEvents.ARMOR_EQUIP_ELYTRA || s == SoundEvents.ARMOR_EQUIP_NETHERITE || s == SoundEvents.ARMOR_EQUIP_DIAMOND ||
                s == SoundEvents.ARMOR_EQUIP_GOLD || s == SoundEvents.ARMOR_EQUIP_IRON || s == SoundEvents.ARMOR_EQUIP_GENERIC ||
                s == SoundEvents.ARMOR_EQUIP_CHAIN || s == SoundEvents.ARMOR_EQUIP_LEATHER
            ) e.cancel()
        }
        on<SendPacketEvent> { e ->
            val pk = e.packet as? ServerboundAttackPacket ?: return@on
            if (!windBurst()) return@on
            if (mc.level?.getEntity(pk.entityId()) != Modules.get<Aura>().target || PlayerState.fallDistance < 3.0) return@on
            pause = 7
        }
    }
}
