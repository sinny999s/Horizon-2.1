package dev.horizon.client.module.legit

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.compat.Via
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.attack.LegacyClicker
import dev.horizon.client.module.attack.viewVec
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.MaceItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Not in the original: zero-rotation triggerbot. Attacks only when your real crosshair crosses a valid target within
 * [range], automatically switching between 1.8.x spam-clicking (ViaFabricPlus auto-detect) and 1.9+ humanized weapon
 * cooldown + falling-crit timing, while skipping friends and [Teams].
 */
class TriggerBot : Module("trigger-bot", Categories.LEGIT, listOf("trigger", "auto-attack")) {
    enum class CombatMode { AUTO, COOLDOWN, LEGACY }

    val range = float("range", 3.0f, 2.5f..6.0f)
    val combatMode = enum("combat-mode", CombatMode.AUTO)
    val minCps = int("min-cps", 10, 1..25) { isLegacyCombat() }
    val maxCps = int("max-cps", 14, 1..25) { isLegacyCombat() }
    val clickPattern = enum("click-pattern", LegacyClicker.Pattern.HUMAN) { isLegacyCombat() }

    val minCooldown = float("min-cooldown", 0.92f, 0.5f..1.0f) { !isLegacyCombat() }
    val smartCrits = bool("smart-crits", true) { !isLegacyCombat() }
    val extraDelayMax = int("extra-delay-max", 2, 0..6) { !isLegacyCombat() }

    val reactionMin = int("reaction-min", 15, 0..200)
    val reactionMax = int("reaction-max", 45, 0..200)
    val weaponOnly = bool("weapon-only", false)
    val requireClick = bool("require-click", false)
    val pauseWhileEating = bool("pause-while-eating", true)
    val pauseWhileShielding = bool("pause-while-shielding", true)

    val targets = group("targets", GroupMode.BOOLS)
    val players = bool("players", true, parent = targets.value)
    val hostiles = bool("hostiles", true, parent = targets.value)
    val animals = bool("animals", false, parent = targets.value)
    val ignoreInvisible = bool("ignore-invisible", true, parent = targets.value)
    val ignoreNaked = bool("ignore-naked", false, parent = targets.value)

    private val clicker = LegacyClicker.State()
    private var hoveredTarget: Entity? = null
    private var enteredAtMs = 0L
    private var reactionWaitMs = 0L
    private var extraTicksLeft = 0

    fun isLegacyCombat(): Boolean = when (combatMode.value) {
        CombatMode.AUTO -> Via.isLegacyCombat()
        CombatMode.LEGACY -> true
        CombatMode.COOLDOWN -> false
    }

    override fun onEnable() = clearState()
    override fun onDisable() = clearState()

    private fun clearState() {
        clicker.reset()
        hoveredTarget = null
        enteredAtMs = 0L
        reactionWaitMs = 0L
        extraTicksLeft = 0
    }

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    private fun tick() {
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        if (!gatesPass(p)) {
            hoveredTarget = null
            return
        }

        val target = pickCrosshairTarget(p) ?: run {
            hoveredTarget = null
            return
        }

        val nowMs = System.nanoTime() / 1_000_000L
        if (target !== hoveredTarget) {
            hoveredTarget = target
            enteredAtMs = nowMs
            val lo = min(reactionMin.value, reactionMax.value)
            val hi = max(reactionMin.value, reactionMax.value)
            reactionWaitMs = if (hi <= 0) 0L else Rng.int(lo, hi).toLong()
        }
        if (nowMs - enteredAtMs < reactionWaitMs) return

        val legacy = isLegacyCombat()
        if (legacy) {
            if (!clicker.ready(nowMs)) return
            (mc as MinecraftAccessor).horizon_setMissTime(0)
            val stack = p.mainHandItem
            p.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, false)
            gm.attack(p, target)
            clicker.onClick(nowMs, minCps.value, maxCps.value, clickPattern.value)
        } else {
            if (p.getAttackStrengthScale(0.5f) < minCooldown.value) return
            if (smartCrits.value && shouldWaitForCrit(p)) return
            if (extraTicksLeft > 0) {
                extraTicksLeft--
                return
            }
            (mc as MinecraftAccessor).horizon_setMissTime(0)
            val stack = p.mainHandItem
            gm.attack(p, target)
            p.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, false)
            extraTicksLeft = if (extraDelayMax.value > 0) Rng.int(0, extraDelayMax.value) else 0
        }
    }

    private fun gatesPass(p: Player): Boolean {
        if (mc.gui.screen() != null) return false
        if (Modules.get<Aura>().isAttacking()) return false
        if (weaponOnly.value && !isWeapon(p.mainHandItem)) return false
        if (requireClick.value && !(PlayerState.physical(mc.options.keyAttack) || mc.options.keyAttack.isDown)) return false
        if (p.isUsingItem) {
            val blocking = p.useItem.get(DataComponents.BLOCKS_ATTACKS) != null || p.useItem.useAnimation == ItemUseAnimation.BLOCK
            if (blocking && pauseWhileShielding.value) return false
            if (!blocking && pauseWhileEating.value) return false
        }
        return true
    }

    private fun shouldWaitForCrit(p: Player): Boolean {
        if (p.onGround() || p.isInLiquid || p.abilities.flying || p.isFallFlying || p.onClimbable()) return false
        // Rising toward jump apex: wait a couple ticks until falling starts so the hit lands as a vanilla critical.
        return p.deltaMovement.y > -0.02 && p.fallDistance <= 0.0
    }

    private fun pickCrosshairTarget(self: Player): LivingEntity? {
        val level = mc.level ?: return null
        var reach = range.value.toDouble()
        var reachSq = reach * reach
        val eye = self.eyePosition
        val dir = viewVec(self.xRot, self.yRot)
        val blockHit = level.clip(ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self))
        if (blockHit.type != HitResult.Type.MISS) {
            reachSq = blockHit.location.distanceToSqr(eye)
            reach = sqrt(reachSq)
        }
        val searchBox = self.boundingBox.expandTowards(dir.scale(reach)).inflate(1.0)
        val hit = ProjectileUtil.getEntityHitResult(self, eye, eye.add(dir.scale(reach)), searchBox, { it.isPickable && isValidTarget(self, it) }, reachSq)
            ?: return null
        if (!hit.location.closerThan(eye, range.value.toDouble())) return null
        return hit.entity as? LivingEntity
    }

    private fun isValidTarget(self: Player, e: Entity): Boolean {
        if (e === self || e !is LivingEntity || !e.isAlive || e.isDeadOrDying || e is ArmorStand) return false
        if (ignoreInvisible.value && e.isInvisible) return false
        return when (e) {
            is Player -> {
                if (!players.value || e.isCreative || e.isSpectator) return false
                if (Friends.isFriend(e.gameProfile.name) || Teams.isTeammateOrNew(e)) return false
                !(ignoreNaked.value && e.armorValue == 0)
            }
            is Enemy -> hostiles.value
            is Animal -> animals.value
            else -> hostiles.value
        }
    }

    private fun isWeapon(s: ItemStack): Boolean =
        s.`is`(ItemTags.SWORDS) || s.`is`(ItemTags.AXES) || s.item is TridentItem || s.item is MaceItem
}
