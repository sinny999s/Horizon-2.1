package dev.horizon.client.module.legit

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Swings
import dev.horizon.client.compat.Via
import dev.horizon.client.config.Friends
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.mixin.player2.UseDelayAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.attack.LegacyClicker
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MaceItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * Humanized Left & Right auto-clicker powered by [LegacyClicker]. Left-clicking yields cleanly to
 * vanilla block mining when `break-blocks` is on, respects friends & [Teams], and automatically sends 1.8 swing-first
 * packet order on ViaFabricPlus `<= 1.8.x`. Right-clicking pairs with [Eagle] for fast-bridging.
 */
class AutoClicker : Module("auto-clicker", Categories.LEGIT, listOf("clicker", "left-clicker", "right-clicker")) {
    val left = bool("left", true)
    val minCps = int("min-cps", 10, 1..25) { left.value }
    val maxCps = int("max-cps", 14, 1..25) { left.value }
    val clickPattern = enum("click-pattern", LegacyClicker.Pattern.HUMAN) { left.value }
    val breakBlocks = bool("break-blocks", true) { left.value }
    val weaponOnly = bool("weapon-only", false) { left.value }
    val respectCooldown = bool("respect-cooldown", false) { left.value }
    val ignoreTeammates = bool("ignore-teammates", true) { left.value }

    val right = bool("right", false)
    val rightMinCps = int("right-min-cps", 12, 1..25) { right.value }
    val rightMaxCps = int("right-max-cps", 16, 1..25) { right.value }
    val rightPattern = enum("right-pattern", LegacyClicker.Pattern.BUTTERFLY) { right.value }
    val blocksOnly = bool("blocks-only", true) { right.value }

    private val leftState = LegacyClicker.State()
    private val rightState = LegacyClicker.State()
    private var wasLeftHeld = false
    private var wasRightHeld = false

    override fun onEnable() = resetTimers()
    override fun onDisable() = resetTimers()

    private fun resetTimers() {
        leftState.reset()
        rightState.reset()
        wasLeftHeld = false
        wasRightHeld = false
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (mc.gui.screen() != null) {
                resetTimers()
                return@on
            }
            val nowMs = System.nanoTime() / 1_000_000L

            if (left.value) {
                val held = PlayerState.physical(mc.options.keyAttack) || mc.options.keyAttack.isDown
                if (!held) {
                    wasLeftHeld = false
                    leftState.reset()
                } else if (canLeftClick(p, level)) {
                    if (!wasLeftHeld) {
                        // Physical press already triggered vanilla's initial click; schedule the next one.
                        wasLeftHeld = true
                        leftState.onClick(nowMs, minCps.value, maxCps.value, clickPattern.value)
                    } else if (leftState.ready(nowMs)) {
                        doLeftClick(p)
                        leftState.onClick(nowMs, minCps.value, maxCps.value, clickPattern.value)
                    }
                }
            }

            if (right.value) {
                val held = PlayerState.physical(mc.options.keyUse) || mc.options.keyUse.isDown
                if (!held) {
                    wasRightHeld = false
                    rightState.reset()
                } else if (canRightClick(p)) {
                    if (!wasRightHeld) {
                        wasRightHeld = true
                        rightState.onClick(nowMs, rightMinCps.value, rightMaxCps.value, rightPattern.value)
                    } else if (rightState.ready(nowMs)) {
                        (mc as UseDelayAccessor).horizonP2_setRightClickDelay(0)
                        (mc as MinecraftAccessor).horizon_startUseItem()
                        rightState.onClick(nowMs, rightMinCps.value, rightMaxCps.value, rightPattern.value)
                    }
                }
            }
        }
    }

    private fun canLeftClick(p: Player, level: net.minecraft.world.level.Level): Boolean {
        if (p.isUsingItem) return false
        if (weaponOnly.value && !isWeapon(p.mainHandItem)) return false
        if (respectCooldown.value && !Via.isLegacyCombat() && p.getAttackStrengthScale(0.5f) < 0.92f) return false
        val hit = mc.hitResult
        if (breakBlocks.value && hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && !level.isEmptyBlock(hit.blockPos)) {
            return false
        }
        if (ignoreTeammates.value && hit is EntityHitResult) {
            val target = hit.entity
            if (target is Player && (Friends.isFriend(target.gameProfile.name) || Teams.isTeammateOrNew(target))) return false
        }
        return true
    }

    private fun canRightClick(p: Player): Boolean {
        if (p.isUsingItem) return false
        if (blocksOnly.value && p.mainHandItem.item !is BlockItem && p.offhandItem.item !is BlockItem) return false
        return true
    }

    private fun doLeftClick(p: Player) {
        val gm = mc.gameMode ?: return
        (mc as MinecraftAccessor).horizon_setMissTime(0)
        val hit = mc.hitResult
        val legacy = Via.isLegacyCombat()
        if (hit is EntityHitResult) {
            val stack = p.mainHandItem
            if (legacy) p.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, false)
            gm.attack(p, hit.entity)
            if (!legacy) p.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, false)
        } else {
            p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
            Swings.punch()
            if (!legacy) p.resetOnlyAttackStrengthTicker()
        }
    }

    private fun isWeapon(s: ItemStack): Boolean =
        s.`is`(ItemTags.SWORDS) || s.`is`(ItemTags.AXES) || s.item is TridentItem || s.item is MaceItem
}
