package dev.horizon.client.module.equip

import dev.horizon.client.combat.Inventory
import dev.horizon.client.event.BreakDeltaEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * While attacking a block, selects the hotbar tool that mines it fastest (efficiency
 * level + destroy speed), and 300 ms after attacking stops goes back to the slot held before. Silent mode also mines
 * with the chosen tool's speed whatever is held.
 */
class AutoTool : Module("auto-tool", Categories.EQUIP) {
    val silent = bool("silent", false)
    val swapBack = bool("swap-back", true) { !silent.value }
    val saveItem = bool("save-item", true)
    val echestSilk = bool("echest-silk", true)

    /** The slot switched to last; silent mode mines with its stack. */
    private var tool = 0
    private var switched = false
    private var switchedAt = 0L
    private val previous = ArrayList<Int>()

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val hit = mc.hitResult as? BlockHitResult ?: return@on
            val pos = hit.blockPos
            if (level.getBlockState(pos).isAir) return@on
            if (best(pos) != -1 && mc.options.keyAttack.isDown) {
                previous += p.inventory.selectedSlot
                select(best(pos))
                tool = best(pos)
                switched = true
                switchedAt = System.currentTimeMillis()
            } else if (switched && previous.isNotEmpty() && System.currentTimeMillis() >= switchedAt + 300 && (swapBack.value || silent.value)) {
                select(previous[0])
                tool = previous[0]
                previous.clear()
                switched = false
            }
        }
        on<BreakDeltaEvent> { e ->
            if (!silent.value) return@on
            val level = mc.level ?: return@on
            val p = mc.player ?: return@on
            val hardness = e.state.getDestroySpeed(level, e.pos)
            if (hardness < 0f) {
                e.delta = 0f
                return@on
            }
            val stack = p.inventory.getItem(tool)
            val speed = speed(e.state, stack) / hardness
            e.delta = if (stack.isCorrectToolForDrops(e.state)) speed / 30f else speed / 100f
            e.cancel()
        }
    }

    private fun select(slot: Int) {
        if (silent.value) Inventory.selectHotbar(slot) else mc.player?.inventory?.selectedSlot = slot
    }

    /** Destroy speed, plus efficiency² + 1 when the tool is faster than a hand. */
    private fun speed(state: BlockState, stack: ItemStack): Float {
        val s = stack.getDestroySpeed(state).toDouble()
        val eff = level(stack, Enchantments.EFFICIENCY)
        return maxOf(s + (if (s > 1.0) eff * eff + 1.0 else 0.0), 0.0).toFloat()
    }

    /** The hotbar slot scoring highest above 1 (efficiency level + destroy speed), or -1. */
    private fun best(pos: BlockPos): Int {
        val p = mc.player ?: return -1
        val level = mc.level ?: return -1
        var best = -1
        var score = 1f
        for (i in 0 until 9) {
            val s = p.inventory.getItem(i)
            if (s === ItemStack.EMPTY) continue
            if (s.maxDamage - s.damageValue <= 10 && saveItem.value) continue
            val state = level.getBlockState(pos)
            val eff = level(s, Enchantments.EFFICIENCY)
            val speed = s.getDestroySpeed(state)
            if (state.block is AirBlock) return -1
            if (state.block is EnderChestBlock && echestSilk.value) {
                if (hasEnchant(s, Enchantments.SILK_TOUCH) && eff + speed > score) {
                    score = eff + speed
                    best = i
                }
                continue
            }
            if (eff + speed > score) {
                score = eff + speed
                best = i
            }
        }
        return best
    }

    private fun level(stack: ItemStack, key: ResourceKey<Enchantment>): Int {
        val holder = mc.level?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.getOrThrow(key) ?: return 0
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack)
    }

    /** The enchantment is on the stack (from its crafting enchantments). */
    private fun hasEnchant(stack: ItemStack, key: ResourceKey<Enchantment>): Boolean {
        val holder = mc.level?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.getOrThrow(key) ?: return false
        return EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet().contains(holder)
    }
}
