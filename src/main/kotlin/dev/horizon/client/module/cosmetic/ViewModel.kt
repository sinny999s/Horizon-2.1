package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.event.HandModifyEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * Moves, scales and turns the first-person held items (main and off hand), optionally
 * spinning them a little every tick, and replaces the eating/drinking jiggle with a configurable one.
 */
class ViewModel : Module("view-model", Categories.COSMETIC) {
    val syncHands = bool("sync-hands", true)
    val mainHand = group("main-hand")
    val x = float("x", 0.0f, -3.0f..3.0f, parent = mainHand.value)
    val y = float("y", 0.0f, -3.0f..3.0f, parent = mainHand.value)
    val z = float("z", 0.0f, -3.0f..3.0f, parent = mainHand.value)
    val scale = float("scale", 1.0f, 0.1f..1.5f, parent = mainHand.value)
    val yaw = float("yaw", 0.0f, -180.0f..180.0f, parent = mainHand.value)
    val pitch = float("pitch", 0.0f, -180.0f..180.0f, parent = mainHand.value)
    val roll = float("roll", 0.0f, -180.0f..180.0f, parent = mainHand.value)
    val rotateYaw = bool("rotate-yaw", false, parent = mainHand.value)
    val rotatePitch = bool("rotate-pitch", false, parent = mainHand.value)
    val rotateRoll = bool("rotate-roll", false, parent = mainHand.value)
    val rotationSpeed = float("rotation-speed", 1.0f, 1.0f..5.0f, parent = mainHand.value)
    val offHand = group("off-hand") { !syncHands.value }
    val offX = float("off-x", 0.0f, -3.0f..3.0f, parent = offHand.value) { !syncHands.value }
    val offY = float("off-y", 0.0f, -3.0f..3.0f, parent = offHand.value) { !syncHands.value }
    val offZ = float("off-z", 0.0f, -3.0f..3.0f, parent = offHand.value) { !syncHands.value }
    val offScale = float("off-scale", 1.0f, 0.1f..1.5f, parent = offHand.value) { !syncHands.value }
    val offYaw = float("off-yaw", 0.0f, -180.0f..180.0f, parent = offHand.value) { !syncHands.value }
    val offPitch = float("off-pitch", 0.0f, -180.0f..180.0f, parent = offHand.value) { !syncHands.value }
    val offRoll = float("off-roll", 0.0f, -180.0f..180.0f, parent = offHand.value) { !syncHands.value }
    val offRotateYaw = bool("off-rotate-yaw", false, parent = offHand.value) { !syncHands.value }
    val offRotatePitch = bool("off-rotate-pitch", false, parent = offHand.value) { !syncHands.value }
    val offRotateRoll = bool("off-rotate-roll", false, parent = offHand.value) { !syncHands.value }
    val offRotationSpeed = float("off-rotation-speed", 1.0f, 1.0f..5.0f, parent = offHand.value) { !syncHands.value }
    val eating = group("eating")
    val eatX = float("eat-x", 1.0f, -1.0f..2.0f, parent = eating.value)
    val eatY = float("eat-y", 1.0f, -1.0f..2.0f, parent = eating.value)
    val eatShake = float("eat-shake", 0.005f, 0.0f..0.2f, parent = eating.value)

    // Last tick's angles, for interpolation.
    private var prevPitch = 0f
    private var prevYaw = 0f
    private var prevRoll = 0f
    private var prevOffPitch = 0f
    private var prevOffYaw = 0f
    private var prevOffRoll = 0f

    init {
        on<PlayerUpdateEvent> {
            prevPitch = pitch.value
            prevYaw = yaw.value
            prevRoll = roll.value
            prevOffPitch = offPitch.value
            prevOffYaw = offYaw.value
            prevOffRoll = offRoll.value
            if (rotateYaw.value) yaw.value = step(yaw.value, rotationSpeed.value)
            if (rotatePitch.value) pitch.value = step(pitch.value, rotationSpeed.value)
            if (rotateRoll.value) roll.value = step(roll.value, rotationSpeed.value)
            if (offRotateYaw.value) offYaw.value = step(offYaw.value, offRotationSpeed.value)
            if (offRotatePitch.value) offPitch.value = step(offPitch.value, offRotationSpeed.value)
            if (offRotateRoll.value) offRoll.value = step(offRoll.value, offRotationSpeed.value)
        }
        on<HandModifyEvent> { e -> modify(e) }
    }

    /** One tick of spin, wrapping to 180 at the bottom. */
    private fun step(angle: Float, speed: Float): Float {
        if (angle - speed - 180f > 0f) return 180f
        val v = angle - speed
        if (v <= -180f) return 180f
        return v
    }

    private fun modify(e: HandModifyEvent) {
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        if (syncHands.value) {
            offX.value = x.value
            offY.value = y.value
            offZ.value = z.value
            offScale.value = scale.value
            offYaw.value = -yaw.value
            offPitch.value = pitch.value
            offRoll.value = -roll.value
        }
        val s = e.stack
        if (e.eating) {
            eat(s, pt, e.hand, e.item)
            e.cancel()
            return
        }
        if (e.hand == InteractionHand.MAIN_HAND) {
            s.translate(x.value, y.value, z.value)
            s.scale(scale.value, scale.value, scale.value)
            s.rotate(Axis.XP.rotationDegrees(Mth.lerp(if (abs(prevPitch - pitch.value) > 90f) 1f else pt, prevPitch, pitch.value)))
            s.rotate(Axis.YP.rotationDegrees(Mth.lerp(if (abs(prevYaw - yaw.value) > 90f) 1f else pt, prevYaw, yaw.value)))
            s.rotate(Axis.ZP.rotationDegrees(Mth.lerp(if (abs(prevRoll - roll.value) > 90f) 1f else pt, prevRoll, roll.value)))
            return
        }
        s.translate(-offX.value, offY.value, offZ.value)
        s.scale(offScale.value, offScale.value, offScale.value)
        s.rotate(Axis.XP.rotationDegrees(Mth.lerp(pt, prevOffPitch, offPitch.value)))
        s.rotate(Axis.YP.rotationDegrees(Mth.lerp(pt, prevOffYaw, offYaw.value)))
        s.rotate(Axis.ZP.rotationDegrees(Mth.lerp(pt, prevOffRoll, offRoll.value)))
    }

    /** Vanilla's eat/drink transform with the eat-x/eat-y/eat-shake amounts, mirrored by hand. */
    private fun eat(s: PoseStack, pt: Float, hand: InteractionHand, item: ItemStack) {
        val p = mc.player ?: return
        val time = p.useItemRemainingTicks - pt + 1f
        val progress = time / item.getUseDuration(p)
        if (progress < 0.8f) s.translate(0f, Mth.abs(cos(time / 4f * Math.PI.toFloat()) * eatShake.value), 0f)
        val k = 1f - progress.toDouble().pow(27.0).toFloat()
        val inv = if (hand == InteractionHand.MAIN_HAND) 1 else -1
        s.translate(k * 0.6f * inv * eatX.value, k * -0.5f * eatY.value, k * 0f)
        s.rotate(Axis.YP.rotationDegrees(inv * k * 90f))
        s.rotate(Axis.XP.rotationDegrees(k * 10f))
        s.rotate(Axis.ZP.rotationDegrees(inv * k * 30f))
    }

    /** The main-hand offset (for Swing Animations), while enabled. */
    fun translate(s: PoseStack) {
        if (enabled) s.translate(x.value, y.value, z.value)
    }

    /** Undoes [translate]. */
    fun untranslate(s: PoseStack) {
        if (enabled) s.translate(-x.value, -y.value, -z.value)
    }
}
