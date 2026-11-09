package dev.horizon.client.module.player

import dev.horizon.client.combat.HoleCache
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Motion
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Predictor
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.AutoCrystal
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import kotlin.math.ceil

/**
 * Throws XP bottles at its own feet while the most worn mending armour piece is below
 * `min-durability`, until every piece is at `max-durability`.
 */
class AutoMend : Module("auto-mend", Categories.PLAYER) {
    val minDurability = int("min-durability", 30, 0..100)
    val maxDurability = int("max-durability", 90, 0..100)
    val overrideOnLoot = bool("override-on-loot", true)
    val xpFactor = int("xp-factor", 1, 1..16)
    val pause = group("pause", GroupMode.BOOLS)
    val ifMove = bool("if-move", true, parent = pause.value)
    val ifMining = bool("if-mining", false, parent = pause.value)
    val ifEating = bool("if-eating", false, parent = pause.value)
    val ifNotInHole = bool("if-not-in-hole", false, parent = pause.value)

    /** Reset by every placer-module placement: Auto Mend waits 500 ms after one. */
    val placeTimer = MsTimer()

    /** True while mending; AutoCrystal pauses meanwhile. */
    @Volatile
    var mending = false

    init {
        HoleCache.init()

        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val xp = Inventory.hotbarFind(Items.EXPERIENCE_BOTTLE)

            val speedMine = Modules.get<SpeedMine>()
            if (ifMining.value && (mc.gameMode?.isDestroying == true || (speedMine.queue.isNotEmpty() && speedMine.resetOnSwitch.value))) return@on
            if (!placeTimer.passed(500)) return@on
            if (ifMove.value && Motion.isMoving()) return@on
            if (ifEating.value && p.isUsingItem) return@on
            if (ifNotInHole.value && !HoleCache.contains(BlockPos.containing(p.position()))) return@on

            val ac = Modules.get<AutoCrystal>()
            if (ac.enabled && ac.target != null) {
                val pr = ac.placeResult.get()
                if (pr != null && pr.res.damage > 4.0f) {
                    mending = false
                    return@on
                }
            }

            val loot = level.entitiesForRendering().any {
                it is ItemEntity && it.item.item == Items.EXPERIENCE_BOTTLE && it.position().distanceTo(p.position()) < 1.0
            }
            val maxT = if (loot && overrideOnLoot.value) 100f else maxDurability.value.toFloat()
            val minT = if (loot && overrideOnLoot.value) 100f else minDurability.value.toFloat()

            val inv = p.inventory
            val pieces = listOf(inv.getItem(39), inv.getItem(38), inv.getItem(37), inv.getItem(36)).filter(::hasMending)
            if (!mending && (pieces.minOfOrNull(::durabilityPct) ?: 100f) < minT) mending = true
            if (mending && (pieces.minOfOrNull(::durabilityPct) ?: 100f) >= maxT) mending = false

            val feet = BlockPos.containing(p.position())
            if (p.isFallFlying || (level.getBlockState(feet.below()).canBeReplaced() && level.getBlockState(feet.below(2)).canBeReplaced()) ||
                xp == null) mending = false

            if (mending && xp != null) {
                val ticks = ceil(PlayerState.ping / 50.0f).toInt()
                val aim = Predictor.feet(p, ticks)
                val slot = xp.slot
                RotationManager.queue(RotationRequest(Rotation.lookAt(aim), 150) { useInstant(slot, xpFactor.value) })
            }
        }
    }

    override fun onDisable() {
        mending = false
    }

    /** Select [slot], use it ([count] times for XP bottles), select the previous slot again. */
    private fun useInstant(slot: Int, count: Int) {
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        val prev = p.inventory.selectedSlot
        if (slot == -1) return
        Inventory.selectHotbar(slot)
        val n = if (p.inventory.getItem(slot).item == Items.EXPERIENCE_BOTTLE) count else 1
        repeat(n) { gm.useItem(p, InteractionHand.MAIN_HAND) }
        // 26.3 has no swing packet to send here.
        Inventory.selectHotbar(prev)
    }

    private fun hasMending(s: ItemStack) = EnchantmentHelper.getEnchantmentsForCrafting(s).keySet().any { it.`is`(Enchantments.MENDING) }

    private fun durabilityPct(s: ItemStack): Float =
        if (!s.isEmpty && s.maxDamage != 0) (s.maxDamage - s.damageValue).toFloat() / s.maxDamage * 100.0f else 100.0f
}
