package dev.horizon.client.module.attack

import dev.horizon.client.combat.Found
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.attackTarget
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MaceItem
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Swaps the hotbar slot on `ServerboundAttackPacket` to an axe, mace, or knockback
 * item (or schedules a follow-up mace hit after an axe hit with [axeMaceHit]), then restores the previous slot after
 * [delay] ticks (or immediately on `AfterSendPacketEvent` when [delay] is 0).
 */
class AttributeSwap : Module("attribute-swap", Categories.ATTACK) {
    enum class Mace { BREACH, DENSITY, ANY, OFF }

    val delay = int("delay", 0, 0..5)
    val mace = enum("mace", Mace.ANY)
    val axe = bool("axe", true)
    val onlyWhenTargetBlocking = bool("only-when-target-blocking", true) { axe.value }
    val knockBackItem = bool("knock-back-item", true)
    val axeMaceHit = bool("axe-mace-hit", true)
    val axeMaceHitDelay = int("axe-mace-hit-delay", 4, 1..8) { axeMaceHit.value }

    /** Hotbar slot before the swap, or -1. */
    private var prevSlot = -1

    init {
        on<SendPacketEvent> { e ->
            val pk = e.packet as? ServerboundAttackPacket ?: return@on
            val pl = mc.player ?: return@on
            val target = attackTarget(pk) ?: return@on
            if (target is EndCrystal) return@on

            val axeSlot = bestHotbarAxe()
            val anyMace = Inventory.hotbarFind { it.`is`(Items.MACE) }
            val breachMace = Inventory.hotbarFind { it.`is`(Items.MACE) && enchantLevel(it, Enchantments.BREACH) >= 1 }
            val densityMace = Inventory.hotbarFind { it.`is`(Items.MACE) && enchantLevel(it, Enchantments.DENSITY) >= 1 }
            val kbItem = Inventory.hotbarFind { enchantLevel(it, Enchantments.KNOCKBACK) >= 1 }

            if (axeMaceHit.value && pl.mainHandItem.`is`(ItemTags.AXES) && anyMace != null) {
                val macePick = densityMace ?: anyMace
                Scheduler.ticks(axeMaceHitDelay.value) {
                    val p = mc.player ?: return@ticks
                    if (macePick.slot < 9) Inventory.selectHotbar(macePick.slot)
                    mc.gameMode?.attack(p, target)
                    p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                }
                return@on
            }

            var chosen: Found? = null
            if (axe.value && !pl.mainHandItem.`is`(ItemTags.AXES)) {
                val blocking = target is Player && target.isUsingItem &&
                    (target.offhandItem.`is`(Items.SHIELD) || target.mainHandItem.`is`(Items.SHIELD))
                if (!onlyWhenTargetBlocking.value || blocking) {
                    chosen = axeSlot
                }
            }
            if (chosen == null && mace.value != Mace.OFF && pl.mainHandItem.item !is MaceItem) {
                chosen = when (mace.value) {
                    Mace.BREACH -> breachMace
                    Mace.DENSITY -> densityMace
                    else -> anyMace
                }
            }
            if (chosen == null && knockBackItem.value) {
                chosen = kbItem
            }

            val pick = chosen ?: return@on
            prevSlot = pl.inventory.selectedSlot
            if (pick.slot < 9) Inventory.selectHotbar(pick.slot)
            val restoreSlot = prevSlot
            if (delay.value > 0) {
                Scheduler.ticks(delay.value) { Inventory.selectHotbar(restoreSlot) }
            }
        }

        on<AfterSendPacketEvent> { e ->
            if (e.packet !is ServerboundAttackPacket) return@on
            if (prevSlot == -1 || delay.value != 0) return@on
            Inventory.selectHotbar(prevSlot)
            prevSlot = -1
        }
    }

    /** Highest `(maxDamage + sharpness)` axe in hotbar `0..8`. */
    private fun bestHotbarAxe(): Found? {
        val inv = mc.player?.inventory ?: return null
        var bestSlot = -1
        var bestScore = 1.0f
        for (i in 0..8) {
            val stack = inv.getItem(i)
            if (stack.`is`(ItemTags.AXES)) {
                val maxDamage = (stack.item.components().get(DataComponents.MAX_DAMAGE) ?: 0).toFloat()
                val score = maxDamage + enchantLevel(stack, Enchantments.SHARPNESS)
                if (score > bestScore) {
                    bestScore = score
                    bestSlot = i
                }
            }
        }
        return if (bestSlot == -1) null else Found(bestSlot, inv.getItem(bestSlot))
    }

    private fun enchantLevel(stack: ItemStack, key: ResourceKey<Enchantment>): Int {
        val level = mc.level ?: return 0
        val holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key)
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack)
    }
}
