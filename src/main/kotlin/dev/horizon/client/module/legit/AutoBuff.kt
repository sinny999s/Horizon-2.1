package dev.horizon.client.module.legit

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.Switching
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.attack.Aura
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SplashPotionItem
import net.minecraft.world.item.alchemy.PotionContents
import kotlin.math.floor

/**
 * Looks straight down (rotation request, priority 200) and throws splash potions to keep
 * the chosen effects up, at most `potions-per-tick` per pass and one pass per `6 + ping / 50` ticks.
 */
class AutoBuff : Module("auto-buff", Categories.LEGIT) {
    @Suppress("EnumEntryName")
    enum class Trigger { LackOfRegen, Health }

    val strength = bool("strength", true)
    val speed = bool("speed", true)
    val fireResistance = bool("fire-resistance", true)
    val instantHealing = bool("instant-healing", true)
    val health = int("health", 8, 0..20)
    val regeneration = bool("regeneration", true)
    val trigger = enum("trigger", Trigger.LackOfRegen)
    val hp = int("hp", 8, 0..20) { trigger.value == Trigger.Health }
    val potionsPerTick = int("potions-per-tick", 3, 1..3)
    val switch = enum("switch", Switch.SILENT_FULL)

    /** Throws this tick. */
    private var thrown = 0

    /** Cooldown between throw passes. */
    private val cooldown = TickTimer()

    init {
        on<PlayerUpdateEvent>(-10) {
            thrown = 0
            if (wantBuff()) RotationManager.queue(RotationRequest(Rotation(Rotation.lastYaw, 90f), 200) { throwPass() })
        }
    }

    private fun currentHp(): Float = mc.player?.let { it.health + it.absorptionAmount } ?: 0f

    private fun wantBuff(): Boolean {
        val p = mc.player ?: return false
        if (!cooldown.passed(6 + floor(PlayerState.ping / 50.0).toInt())) return false
        if (p.tickCount <= 100) return false
        val aura = Modules.get<Aura>()
        if (aura.enabled && aura.target != null && p.getAttackStrengthScale(1.0f) > 0.5f) return false
        if (p.isFallFlying) return false
        if (available(MobEffects.SPEED) && speed.value) return true
        if (available(MobEffects.STRENGTH) && strength.value) return true
        if (available(MobEffects.FIRE_RESISTANCE) && fireResistance.value) return true
        if (currentHp() < health.value && available(MobEffects.INSTANT_HEALTH) && instantHealing.value) return true
        if (trigger.value == Trigger.LackOfRegen && available(MobEffects.REGENERATION) && regeneration.value) return true
        return currentHp() < hp.value && trigger.value == Trigger.Health && available(MobEffects.REGENERATION) && regeneration.value
    }

    private fun available(e: Holder<MobEffect>): Boolean = mc.player?.hasEffect(e) == false && findPotion(e) != -1

    /** Where the switch mode finds a splash potion of [e], or −1. */
    private fun findPotion(e: Holder<MobEffect>): Int = Switching.search(switch.value) { isSplashWith(it, e) }?.slot ?: -1

    /** Lingering potions aren't splash potions. */
    private fun isSplashWith(stack: ItemStack, e: Holder<MobEffect>): Boolean =
        stack.item is SplashPotionItem && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).allEffects.any { it.effect == e }

    /**
     * The rotation's action (PostSync, still looking down). With trigger Health the regeneration
     * throw doesn't check for an active effect (on purpose); the cooldown restarts even when nothing was thrown.
     */
    private fun throwPass() {
        val p = mc.player ?: return
        if (!p.hasEffect(MobEffects.SPEED) && speed.value && thrown < potionsPerTick.value) throwPotion(MobEffects.SPEED)
        if (!p.hasEffect(MobEffects.STRENGTH) && strength.value && thrown < potionsPerTick.value) throwPotion(MobEffects.STRENGTH)
        if (!p.hasEffect(MobEffects.FIRE_RESISTANCE) && fireResistance.value && thrown < potionsPerTick.value) throwPotion(MobEffects.FIRE_RESISTANCE)
        if (currentHp() < health.value && instantHealing.value && thrown < potionsPerTick.value) throwPotion(MobEffects.INSTANT_HEALTH)
        if (((!p.hasEffect(MobEffects.REGENERATION) && trigger.value == Trigger.LackOfRegen) || (currentHp() < hp.value && trigger.value == Trigger.Health)) &&
            regeneration.value && thrown < potionsPerTick.value
        ) {
            throwPotion(MobEffects.REGENERATION)
        }
        cooldown.reset()
    }

    /** No swing packet: 26.3 has none (and there's no client swing). */
    private fun throwPotion(e: Holder<MobEffect>) {
        val slot = findPotion(e)
        if (slot == -1) return
        Switching.run(slot, switch.value) {
            val p = mc.player
            if (p != null) mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
            thrown++
        }
    }
}
