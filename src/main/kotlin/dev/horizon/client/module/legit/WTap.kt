package dev.horizon.client.module.legit

import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.attackTarget
import dev.horizon.client.config.Friends
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.SprintEvent
import dev.horizon.client.mixin.ClientInputAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.Sprint
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * Legit sprint-reset & combo-spacing ("W-Tap" / "S-Tap"). Releases `W` (or taps `S` in [Mode.S_TAP])
 * through [KeyboardInputEvent] for a couple of ticks right after landing a hit within combo range so vanilla sends a
 * real sprint reset without extra packets.
 */
class WTap : Module("w-tap", Categories.LEGIT, listOf("s-tap", "sprint-reset", "wtap", "stap")) {
    enum class Mode { W_TAP, S_TAP }

    val mode = enum("mode", Mode.W_TAP)
    val chance = int("chance", 90, 0..100)
    val minDelay = int("min-delay", 0, 0..5)
    val maxDelay = int("max-delay", 1, 0..5)
    val minHold = int("min-hold", 1, 1..6)
    val maxHold = int("max-hold", 2, 1..6)
    val minCooldown = int("min-cooldown", 7, 2..20)
    val minDistance = float("min-distance", 1.2f, 0.0f..6.0f)
    val maxDistance = float("max-distance", 3.4f, 1.5f..6.0f)
    val onlyWhileSprinting = bool("only-while-sprinting", true)
    val onlyOnGround = bool("only-on-ground", false)

    private var delayLeft = -1
    private var holdLeft = 0
    private var lastTapTick = -100

    val activeTap: Boolean get() = holdLeft > 0 && delayLeft < 0

    override fun onEnable() = clearState()
    override fun onDisable() = clearState()

    private fun clearState() {
        delayLeft = -1
        holdLeft = 0
        lastTapTick = -100
    }

    init {
        on<SendPacketEvent> { e ->
            val pk = e.packet as? ServerboundAttackPacket ?: return@on
            val p = mc.player ?: return@on
            val target = attackTarget(pk) ?: return@on
            if (target is EndCrystal || target !is LivingEntity || target is ArmorStand) return@on
            if (target is Player && (Friends.isFriend(target.gameProfile.name) || Teams.isTeammate(target))) return@on
            if (target.hurtTime > 2) return@on
            if (holdLeft > 0 || delayLeft >= 0) return@on
            if (p.tickCount - lastTapTick < minCooldown.value) return@on
            if (onlyWhileSprinting.value && !p.isSprinting) return@on
            if (onlyOnGround.value && !p.onGround()) return@on
            if (!p.input.keyPresses.forward()) return@on

            val dLo = min(minDistance.value, maxDistance.value)
            val dHi = max(minDistance.value, maxDistance.value)
            val dist = p.distanceTo(target)
            if (dist < dLo || dist > dHi) return@on
            if (chance.value <= 0 || (chance.value < 100 && Rng.int(1, 100) > chance.value)) return@on

            delayLeft = Rng.int(min(minDelay.value, maxDelay.value), max(minDelay.value, maxDelay.value))
            holdLeft = Rng.int(min(minHold.value, maxHold.value), max(minHold.value, maxHold.value))
            lastTapTick = p.tickCount
        }

        on<SprintEvent> { e ->
            if (activeTap) e.sprinting = false
        }

        on<KeyboardInputEvent>(-10) {
            val p = mc.player ?: return@on
            if (delayLeft > 0) {
                delayLeft--
                return@on
            }
            if (delayLeft == 0) delayLeft = -1
            if (holdLeft <= 0) return@on

            holdLeft--
            val keys = p.input.keyPresses
            if (!keys.forward() || mc.gui.screen() != null) {
                holdLeft = 0
                return@on
            }

            val sTap = mode.value == Mode.S_TAP
            p.input.keyPresses = Input(
                false,
                sTap || keys.backward(),
                keys.left(),
                keys.right(),
                keys.jump(),
                keys.shift(),
                false
            )
            val mv = p.input.moveVector
            (p.input as ClientInputAccessor).horizon_setMoveVector(Vec2(mv.x, if (sTap) -1.0f else 0.0f))
            p.isSprinting = false
            Modules.get<Sprint>().skipOnce = true
            Modules.get<Sprint>().releaseKey()
        }
    }
}
