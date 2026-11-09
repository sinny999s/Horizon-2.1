package dev.horizon.client.event

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import org.joml.Vector4f

/** `Camera.alignWithEntity` (third person): cancelling puts the camera [offset] blocks back, without clipping. */
class CameraOffsetEvent : Event() {
    var offset = 0f
}

/** Each fog value `FogRenderer.updateBuffer` writes; cancelling replaces it with these. */
class ApplyFogEvent : Event() {
    var color = Vector4f(1f, 1f, 1f, 1f)
    var envStart = 0f
    var envEnd = 0f
    var renderStart = 0f
    var renderEnd = 0f
}

/** `LivingEntity.getModifiedSwingDuration` (any entity): cancelling makes swings last [value] ticks. */
class SwingDurationEvent : Event() {
    var value = 0
}

/** The first-person hand's `swingArm` HEAD; cancelling skips vanilla's swing transform. */
class SwingArmEvent(val swingProgress: Float, val matrices: PoseStack, val armX: Int, val arm: HumanoidArm) : Event()

/** The first-person hand's `applyItemArmTransform` HEAD ([progress] = the inverse arm height); cancelling skips it. */
class EquipArmEvent(val progress: Float, val arm: HumanoidArm) : Event()

/**
 * A first-person held item about to be drawn ([eating] false), or its eat/drink transform about to be applied ([eating]
 * true; cancelling skips vanilla's).
 */
class HandModifyEvent(val hand: InteractionHand, val stack: PoseStack, val item: ItemStack, val eating: Boolean) : Event()

/** `LocalPlayer.displayItemActivation` HEAD (the totem pop item); cancelling hides vanilla's animation. */
class ShowFloatingItemEvent(val item: ItemStack) : Event()

/** Item quads being built: cancelling with a [provider] sends them there instead, with no glint. */
class ItemCommandEvent : Event() {
    var provider: VertexConsumer? = null
}
