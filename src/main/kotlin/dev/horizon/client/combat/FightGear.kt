package dev.horizon.client.combat

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.CombatRules
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import kotlin.math.max

/** What a fighter's gear does, for [FightOdds]: how much of a hit gets through it, and how hard it hits. */
object FightGear {
    private val mc get() = Minecraft.getInstance()

    /** A diamond sword's hit: the one [taken] measures armour against. */
    private const val REFERENCE_HIT = 7f

    private val ARMOR_SLOTS = arrayOf(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)

    /** The share of a sword's hit that gets through [e]'s armour, toughness, Protection and Resistance (0 to 1). */
    fun taken(e: LivingEntity): Float {
        val level = mc.level ?: return 1f
        var armor = e.armorValue.toFloat()
        var toughness = e.getAttributeValue(Attributes.ARMOR_TOUGHNESS).toFloat()
        // Another player's armour attributes aren't always sent; what they wear says it too.
        var wornArmor = 0.0
        var wornToughness = 0.0
        for (slot in ARMOR_SLOTS) {
            e.getItemBySlot(slot).get(DataComponents.ATTRIBUTE_MODIFIERS)?.forEach(slot) { a, m ->
                if (a == Attributes.ARMOR) wornArmor += m.amount()
                if (a == Attributes.ARMOR_TOUGHNESS) wornToughness += m.amount()
            }
        }
        armor = max(armor, wornArmor.toFloat())
        toughness = max(toughness, wornToughness.toFloat())
        var dmg = CombatRules.getDamageAfterAbsorb(e, REFERENCE_HIT, level.damageSources().generic(), armor, toughness)
        val resistance = e.getEffect(MobEffects.RESISTANCE)
        if (resistance != null) dmg *= max(0f, 1f - (resistance.amplifier + 1) * 0.2f)
        var epf = 0
        for (slot in ARMOR_SLOTS) epf += level(e.getItemBySlot(slot), Enchantments.PROTECTION)
        if (epf > 0) dmg = CombatRules.getDamageAfterMagicAbsorb(dmg, epf.toFloat())
        return (dmg / REFERENCE_HIT).coerceIn(0f, 1f)
    }

    /** [e]'s hit before armour: a player's weapon, Sharpness, Strength and Weakness; a mob's attack damage. */
    fun hit(e: LivingEntity): Float {
        if (e !is Player) {
            return if (e.attributes.hasAttribute(Attributes.ATTACK_DAMAGE)) e.getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() else 0f
        }
        val weapon = e.mainHandItem
        var dmg = 1.0
        weapon.get(DataComponents.ATTRIBUTE_MODIFIERS)?.forEach(EquipmentSlot.MAINHAND) { a, m ->
            if (a == Attributes.ATTACK_DAMAGE) dmg += m.amount()
        }
        val sharpness = level(weapon, Enchantments.SHARPNESS)
        if (sharpness > 0) dmg += 0.5 * sharpness + 0.5
        e.getEffect(MobEffects.STRENGTH)?.let { dmg += 3.0 * (it.amplifier + 1) }
        e.getEffect(MobEffects.WEAKNESS)?.let { dmg -= 4.0 * (it.amplifier + 1) }
        return max(0.0, dmg).toFloat()
    }

    private fun level(stack: ItemStack, key: ResourceKey<Enchantment>): Int {
        if (stack.isEmpty) return 0
        val holder = mc.level?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.get(key)?.orElse(null) ?: return 0
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack)
    }
}
