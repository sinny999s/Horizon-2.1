package dev.horizon.client.module.rage

import dev.horizon.client.event.TridentUseEvent
import dev.horizon.client.event.TridentWeatherEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.Holder
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.MoverType
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Replaces the riptide release. After [delay] ticks of use it pushes the player along
 * the look with [factor] (Motion) or [factor] × 3 × (1 + riptide) / 4 (Factor); with [rainSpoof] it works out of water
 * and rain too.
 */
class TridentBoost : Module("trident-boost", Categories.RAGE) {
    @Suppress("EnumEntryName")
    enum class Mode { Motion, Factor }

    val mode = enum("mode", Mode.Motion)
    val factor = float("factor", 1.0f, 0.1f..20.0f)
    val delay = int("delay", 10, 0..20)
    val rainSpoof = bool("rain-spoof", true)

    /** The boost, when held long enough with riptide (and water or rain, unless spoofed). */
    private fun boost() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (p.ticksUsingItem < delay.value) return
        val strength = EnchantmentHelper.getTridentSpinAttackStrength(p.useItem, p)
        if (!rainSpoof.value && !p.isInWaterOrRain) return
        if (strength <= 0.0f) return
        val rad = Math.PI.toFloat() / 180.0f
        val yaw = (p.yRot * rad).toDouble()
        val pitch = (p.xRot * rad).toDouble()
        var x = -sin(yaw).toFloat() * cos(pitch).toFloat()
        var y = -sin(pitch).toFloat()
        var z = cos(yaw).toFloat() * cos(pitch).toFloat()
        val len = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val mult = if (mode.value == Mode.Factor) factor.value * 3.0f * ((1.0f + strength) / 4.0f) else factor.value
        x *= mult / len
        y *= mult / len
        z *= mult / len
        p.push(x.toDouble(), y.toDouble(), z.toDouble())
        p.startAutoSpinAttack(20, 8.0f, p.useItem)
        if (p.onGround()) p.move(MoverType.SELF, Vec3(0.0, 1.1999999f.toDouble(), 0.0))
        val sound: Holder<SoundEvent> = EnchantmentHelper.pickHighestLevel(p.useItem, EnchantmentEffectComponents.TRIDENT_SOUND)
            .orElse(SoundEvents.TRIDENT_THROW)
        level.playSound(null, p, sound.value(), SoundSource.PLAYERS, 1.0f, 1.0f)
    }

    init {
        // Vanilla's release never runs while this is on, boost or not.
        on<TridentUseEvent> { e ->
            boost()
            e.cancel()
        }
        on<TridentWeatherEvent> { e ->
            if (rainSpoof.value) e.cancel()
        }
    }
}
