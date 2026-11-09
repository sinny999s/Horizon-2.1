package dev.horizon.client.module.player

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.mixin.player2.JumpRidingAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.rage.TridentBoost
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.animal.equine.Horse as HorseEntity
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Lets go of a trident, crossbow or bow the tick it is fully charged, and charges a
 * ridden horse's jump fully ([Horse.Rage]: set to full every tick; [Horse.Legit]: the jump key is let go once full).
 */
class PerfectDelay : Module("perfect-delay", Categories.PLAYER) {
    @Suppress("EnumEntryName")
    enum class Horse { Legit, Rage, Off }

    val horse = enum("horse", Horse.Legit)
    val bow = bool("bow", true)
    val crossbow = bool("crossbow", true)
    val trident = bool("trident", true)

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val gm = mc.gameMode ?: return@on
            if (trident.value && p.useItem.item is TridentItem) {
                val boost = Modules.get<TridentBoost>()
                if (p.ticksUsingItem > (if (boost.enabled) boost.delay.value else 9)) gm.releaseUsingItem(p)
            }
            if (p.useItem.item is CrossbowItem && crossbow.value &&
                p.ticksUsingItem.toDouble() - (25 - 0.25 * quickCharge(p.useItem) * 20) >= 0) gm.releaseUsingItem(p)
            if (p.useItem.item is BowItem && bow.value && p.ticksUsingItem > 19) gm.releaseUsingItem(p)
            if (p.controlledVehicle !is HorseEntity) return@on
            if (horse.value == Horse.Rage) (p as JumpRidingAccessor).horizonP2_setJumpRidingScale(1.0f)
            if (horse.value == Horse.Legit && p.jumpRidingScale >= 1.0f) mc.options.keyJump.isDown = false
        }
    }

    /** The stack's Quick Charge level. */
    private fun quickCharge(stack: ItemStack): Float {
        val level = mc.level ?: return 0f
        val holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.QUICK_CHARGE)
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack).toFloat()
    }
}
