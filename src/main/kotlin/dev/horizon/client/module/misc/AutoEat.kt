package dev.horizon.client.module.misc

import dev.horizon.client.combat.Inventory
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.StopUsingItemEvent
import dev.horizon.client.event.UsingItemEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * At or below [hunger] food, eats what is in a hand, else switches to the first allowed
 * food on the hotbar; while eating, the item counts as in use and isn't released. With [swapBack] the slot it switched
 * from is selected again once food is above the threshold.
 */
class AutoEat : Module("auto-eat", Categories.MISC) {
    val hunger = int("Hunger", 8, 0..20)
    val swapBack = bool("swap-back", true)
    val gapple = bool("gapple", true)
    val chorus = bool("chorus", false)
    val rottenFlesh = bool("rotten-flesh", false)
    val spiderEye = bool("spider-eye", false)
    val pufferFish = bool("puffer-fish", false)

    private var eating = false
    private var prevSlot = -1

    init {
        on<PlayerUpdateEvent>(-10) {
            val player = mc.player ?: return@on
            eating = false
            if (player.foodData.foodLevel <= hunger.value) {
                if (allowed(InteractionHand.MAIN_HAND) || allowed(InteractionHand.OFF_HAND) || selectFood()) eat()
            } else if (prevSlot != -1 && swapBack.value) {
                Inventory.selectHotbar(prevSlot)
                prevSlot = -1
            }
        }
        on<UsingItemEvent> { if (eating) it.cancel() }
        on<StopUsingItemEvent> { if (eating) it.cancel() }
    }

    /** Starts using the food (off-hand if that one is allowed) unless already using something. */
    private fun eat() {
        val player = mc.player ?: return
        if (!player.isUsingItem) {
            mc.gameMode?.useItem(player, if (allowed(InteractionHand.OFF_HAND)) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND)
        }
        eating = true
    }

    /** Selects the first allowed food on the hotbar, remembering the slot it came from. */
    private fun selectFood(): Boolean {
        val player = mc.player ?: return false
        for (i in 0 until 9) {
            if (allowed(player.inventory.getItem(i))) {
                prevSlot = player.inventory.selectedSlot
                Inventory.selectHotbar(i)
                return true
            }
        }
        return false
    }

    private fun allowed(hand: InteractionHand): Boolean {
        val player = mc.player ?: return false
        return allowed(if (hand == InteractionHand.MAIN_HAND) player.mainHandItem else player.offhandItem)
    }

    /** Food, and not one of the kinds switched off. */
    private fun allowed(stack: ItemStack): Boolean {
        if (!stack.components.has(DataComponents.FOOD)) return false
        val item = stack.item
        if (!gapple.value && (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE)) return false
        if (!chorus.value && item == Items.CHORUS_FRUIT) return false
        if (!rottenFlesh.value && item == Items.ROTTEN_FLESH) return false
        if (!spiderEye.value && item == Items.SPIDER_EYE) return false
        return pufferFish.value || item != Items.PUFFERFISH
    }
}
