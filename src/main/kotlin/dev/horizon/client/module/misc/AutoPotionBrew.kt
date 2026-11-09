package dev.horizon.client.module.misc

import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.blockEntitiesAround
import dev.horizon.client.combat.containerClick
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.BrewingStandMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.AirItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.item.alchemy.Potion as McPotion

/**
 * In a brewing stand it adds blaze powder, water bottles, nether wart, the
 * [potion]'s ingredients, its glowstone/redstone upgrade and (with [throwable]) gunpowder, one click set per [delay] ms,
 * closes the stand once it brews, and when done takes or throws the potions. With [open] it opens brewing stands in
 * reach that aren't brewing, every 10 ticks.
 */
class AutoPotionBrew : Module("auto-potion-brew", Categories.MISC) {
    /** Base ingredient, second ingredient (AIR when none), upgrade. */
    enum class Potion(val base: Item, val second: Item, val upgrade: Item) {
        SPEED(Items.SUGAR, Items.AIR, Items.GLOWSTONE_DUST),
        STRENGTH(Items.BLAZE_POWDER, Items.AIR, Items.GLOWSTONE_DUST),
        FIRE_RESISTANCE(Items.MAGMA_CREAM, Items.AIR, Items.REDSTONE),
        INVISIBILITY(Items.GOLDEN_CARROT, Items.FERMENTED_SPIDER_EYE, Items.REDSTONE),
    }

    enum class AfterComplete { THROW, TAKE, NONE }

    val potion = enum("potion", Potion.STRENGTH)
    val delay = int("delay", 100, 0..1000)
    val throwable = bool("throwable", true)
    val afterComplete = enum("after-complete", AfterComplete.TAKE)
    val open = bool("open", true)

    private val timer = MsTimer()

    /** Brewing stand → player tick when its brew is done. */
    private val brewing = LinkedHashMap<BlockPos, Int>()

    /** The last brewing stand this module opened. */
    private var lastStand: BlockPos? = null

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val menu = player.containerMenu
            if (menu !is BrewingStandMenu) {
                if (open.value) openStands()
                return@on
            }
            if (menu.slots[4].item.item is AirItem) {
                if (find(Items.BLAZE_POWDER) != -1) moveOne(Items.BLAZE_POWDER, 4)
                return@on
            }
            for (i in 0 until 3) {
                if (menu.slots[i].item.item != Items.AIR) continue
                val water = findPotion(Potions.WATER.value())
                if (timer.passed(delay.value.toLong()) && water != -1) {
                    containerClick(water)
                    containerClick(i)
                    containerClick(water)
                    timer.reset()
                    return@on
                }
            }
            val ingredient = menu.slots[3].item.item
            val p = potion.value
            if (all(Potions.WATER.value()) && find(Items.NETHER_WART) != -1 && ingredient != Items.NETHER_WART) {
                moveOne(Items.NETHER_WART, 3)
                return@on
            }
            if (all(Potions.AWKWARD.value()) && find(p.base) != -1 && ingredient != p.base) {
                moveOne(p.base, 3)
                return@on
            }
            if (all(Potions.NIGHT_VISION.value()) && p.second != Items.AIR && find(p.second) != -1 && ingredient != p.second) {
                moveOne(p.second, 3)
                return@on
            }
            // Tests SWIFTNESS twice here (and never FIRE_RESISTANCE), so fire resistance isn't upgraded (on purpose).
            val base = all(Potions.STRENGTH.value()) || all(Potions.SWIFTNESS.value()) || all(Potions.SWIFTNESS.value()) ||
                all(Potions.INVISIBILITY.value())
            var canUpgrade = true
            if (base && ingredient != p.upgrade) {
                if (find(p.upgrade) != -1) {
                    moveOne(p.upgrade, 3)
                    return@on
                }
                canUpgrade = false
            }
            var done = all(Potions.STRONG_STRENGTH.value()) || all(Potions.STRONG_SWIFTNESS.value()) ||
                all(Potions.LONG_FIRE_RESISTANCE.value()) || all(Potions.LONG_INVISIBILITY.value())
            if (base && !canUpgrade) done = true
            if (done) {
                if (!throwable.value || find(Items.GUNPOWDER) == -1 || allSplash()) {
                    finish()
                    return@on
                }
                if (ingredient != Items.GUNPOWDER) {
                    moveOne(Items.GUNPOWDER, 3)
                    return@on
                }
            }
            val stand = lastStand
            if (menu.brewingTicks > 0 && stand != null) {
                player.closeContainer()
                brewing[stand] = player.tickCount + menu.brewingTicks
            }
        }
    }

    /** Every brewing stand in reach that isn't brewing gets a rotation + use, on ticks % 10. */
    private fun openStands() {
        val player = mc.player ?: return
        for (be in blockEntitiesAround()) {
            if (be !is BrewingStandBlockEntity) continue
            val pos = be.blockPos
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > player.blockInteractionRange() * player.blockInteractionRange()) continue
            val until = brewing[pos]
            if (until != null && player.tickCount - until <= 0) continue
            if (player.tickCount % 10 == 0) {
                val hit = BlockHitResult(Vec3(pos.x + 0.5, pos.y + 0.125, pos.z + 0.5), Direction.UP, pos, false)
                RotationManager.queue(RotationRequest(Rotation.lookAt(hit.location), 150) {
                    mc.player?.let { mc.gameMode?.useItemOn(it, InteractionHand.MAIN_HAND, hit) }
                    lastStand = pos
                })
            }
        }
    }

    /** Per bottle slot, once [delay] has passed, takes (shift-click) or throws the potion. */
    private fun finish() {
        for (i in 0 until 3) {
            if (!timer.passed(delay.value.toLong())) continue
            when (afterComplete.value) {
                AfterComplete.TAKE -> containerClick(i, 0, ContainerInput.QUICK_MOVE)
                AfterComplete.THROW -> containerClick(i, 0, ContainerInput.THROW)
                AfterComplete.NONE -> {}
            }
        }
    }

    /** Once [delay] has passed, picks up the stack, drops one into [slot], puts it back. */
    private fun moveOne(item: Item, slot: Int) {
        if (!timer.passed(delay.value.toLong())) return
        val from = find(item)
        if (from == -1) return
        containerClick(from)
        containerClick(slot, 1)
        containerClick(from)
        timer.reset()
    }

    /** The first inventory slot (5..40) of the stand's menu holding [item], else -1. */
    private fun find(item: Item): Int {
        val slots = mc.player?.containerMenu?.slots ?: return -1
        for (i in 5 until 41) if (slots[i].item.item == item) return i
        return -1
    }

    /** The first inventory slot (5..40) holding a [potion] potion, else -1. */
    private fun findPotion(potion: McPotion): Int {
        val slots = mc.player?.containerMenu?.slots ?: return -1
        for (i in 5 until 41) {
            val contents = slots[i].item.get(DataComponents.POTION_CONTENTS) ?: continue
            if (contents.potion().isPresent && contents.potion().get().value() == potion) return i
        }
        return -1
    }

    /** All three bottle slots hold a [potion] potion. */
    private fun all(potion: McPotion): Boolean {
        val slots = mc.player?.containerMenu?.slots ?: return false
        for (i in 0 until 3) {
            val contents = slots[i].item.get(DataComponents.POTION_CONTENTS) ?: return false
            val holder: Holder<McPotion> = contents.potion().orElse(null) ?: return false
            if (holder.value() != potion) return false
        }
        return true
    }

    /** All three bottle slots hold splash potions. */
    private fun allSplash(): Boolean {
        val slots = mc.player?.containerMenu?.slots ?: return false
        for (i in 0 until 3) if (slots[i].item.item != Items.SPLASH_POTION) return false
        return true
    }
}
