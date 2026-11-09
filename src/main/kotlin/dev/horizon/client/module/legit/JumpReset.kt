package dev.horizon.client.module.legit

import dev.horizon.client.combat.Rng
import dev.horizon.client.config.Friends
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Legit velocity reduction ("Jump Reset"). Inputs a real jump on the tick you take combat
 * knockback while sprinting on the ground so vanilla's sprint-jump forward impulse cuts horizontal knockback without
 * modifying velocity packets.
 */
class JumpReset : Module("jump-reset", Categories.LEGIT, listOf("legit-velocity", "jump-velocity")) {
    val chance = int("chance", 85, 0..100)
    val minDelay = int("min-delay", 0, 0..3)
    val maxDelay = int("max-delay", 0, 0..3)
    val holdTicks = int("hold-ticks", 1, 1..4)
    val onlyWhileSprinting = bool("only-while-sprinting", true)
    val onlyMovingForward = bool("only-moving-forward", true)
    val fov = int("fov", 180, 30..180)
    val pauseWhileEating = bool("pause-while-eating", true)

    private var prevHurtTime = 0
    private var armedHurt = false
    private var lastMotionTick = -100
    private var delayLeft = -1
    private var holdLeft = 0

    override fun onEnable() = clearState()
    override fun onDisable() = clearState()

    private fun clearState() {
        prevHurtTime = 0
        armedHurt = false
        lastMotionTick = -100
        delayLeft = -1
        holdLeft = 0
    }

    init {
        on<AfterReceivePacketEvent> { e ->
            val p = mc.player ?: return@on
            val pk = e.packet as? ClientboundSetEntityMotionPacket ?: return@on
            if (pk.id() != p.id) return@on
            if (hypot(pk.movement().x, pk.movement().z) > 0.02) {
                lastMotionTick = p.tickCount
            }
        }

        on<KeyboardInputEvent>(-10) {
            val p = mc.player ?: return@on
            val keys = p.input.keyPresses
            val hurt = p.hurtTime
            if (hurt >= 8 && hurt > prevHurtTime) {
                armedHurt = true
            } else if (hurt < 5) {
                armedHurt = false
            }
            prevHurtTime = hurt

            val realKb = (p.tickCount - lastMotionTick) in 0..3
            if (armedHurt && realKb && holdLeft == 0 && delayLeft < 0 && canTrigger(p, keys)) {
                armedHurt = false
                lastMotionTick = -100
                if (chance.value >= 100 || (chance.value > 0 && Rng.int(1, 100) <= chance.value)) {
                    val lo = min(minDelay.value, maxDelay.value)
                    val hi = max(minDelay.value, maxDelay.value)
                    delayLeft = Rng.int(lo, hi)
                    holdLeft = holdTicks.value
                }
            }

            if (delayLeft > 0) {
                delayLeft--
                return@on
            }
            if (delayLeft == 0) delayLeft = -1

            if (holdLeft > 0) {
                holdLeft--
                if (p.onGround() && !keys.jump() && !keys.shift()) {
                    p.input.keyPresses = Input(
                        keys.forward(),
                        keys.backward(),
                        keys.left(),
                        keys.right(),
                        true,
                        keys.shift(),
                        keys.sprint()
                    )
                }
            }
        }
    }

    private fun canTrigger(p: Player, keys: Input): Boolean {
        if (!p.onGround() || p.isInLiquid || p.isPassenger || p.abilities.flying || p.isFallFlying) return false
        if (mc.gui.screen() != null || keys.shift() || keys.jump()) return false
        if (pauseWhileEating.value && p.isUsingItem) return false
        if (onlyWhileSprinting.value && !p.isSprinting) return false
        if (onlyMovingForward.value && !keys.forward()) return false
        if (fov.value < 180 && !attackerInFov(p, fov.value)) return false
        return true
    }

    private fun attackerInFov(self: Player, maxAngle: Int): Boolean {
        val level = mc.level ?: return true
        for (e in level.entitiesForRendering()) {
            if (e === self || e !is LivingEntity || e is ArmorStand || !e.isAlive) continue
            if (e is Player && (Friends.isFriend(e.gameProfile.name) || Teams.isTeammate(e))) continue
            if (self.distanceToSqr(e) > 49.0) continue
            val yawTo = Math.toDegrees(atan2(e.z - self.z, e.x - self.x)) - 90.0
            val diff = abs(Mth.wrapDegrees(yawTo - self.yRot))
            if (diff <= maxAngle) return true
        }
        return false
    }
}
