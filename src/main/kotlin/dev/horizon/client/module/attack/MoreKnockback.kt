package dev.horizon.client.module.attack

import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.attackTarget
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.mixin.pvp.PvpLocalPlayerAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.level.block.Blocks

/**
 * Right before an attack packet on a living entity, (STOP_SPRINTING and)
 * START_SPRINTING, so the server counts every hit as a sprint hit — unless the hit would be a real critical.
 */
class MoreKnockback : Module("more-knockback", Categories.ATTACK) {
    val allowWhileMove = bool("allow-while-move", true)
    val chance = int("chance", 100, 0..100)

    init {
        on<SendPacketEvent> { e ->
            val pk = e.packet as? ServerboundAttackPacket ?: return@on
            val pl = mc.player ?: return@on
            val target = attackTarget(pk)
            val mv = pl.input.moveVector
            if ((mv.y != 0f || mv.x != 0f) && !allowWhileMove.value) return@on
            if (target is EndCrystal) return@on
            if (target !is LivingEntity) return@on
            // Rng.int(0, 100) is 0..100, so even a chance of 0 passes on a 100.
            if (Rng.int(0, 100) < 100 - chance.value) return@on
            if (willCrit()) return@on
            if (pl.isSprinting) Packets.send(ServerboundPlayerCommandPacket(pl, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING))
            Packets.send(ServerboundPlayerCommandPacket(pl, ServerboundPlayerCommandPacket.Action.START_SPRINTING))
            pl.isSprinting = true
            (pl as PvpLocalPlayerAccessor).horizon_setWasSprinting(true)
        }
    }

    /** This hit would be a vanilla critical (falling, charged, nothing in the way). */
    private fun willCrit(): Boolean {
        val pl = mc.player ?: return false
        val level = mc.level ?: return false
        val impossible = pl.abilities.flying || pl.isFallFlying || pl.hasEffect(MobEffects.BLINDNESS) ||
            level.getBlockState(BlockPos.containing(pl.position())).block == Blocks.COBWEB || pl.isInLava || pl.isUnderWater
        if (pl.getAttackStrengthScale(0.5f) - 0.9f < 0f) return false
        if (impossible) return false
        if (pl.onGround()) return false
        return pl.fallDistance > 0.0
    }
}
