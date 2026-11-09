package dev.horizon.client.mixin.cosmetic2;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.combat.Cosmetic2Hooks;
import dev.horizon.client.event.EquipArmEvent;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.HandModifyEvent;
import dev.horizon.client.event.SwingArmEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On 26.3's first-person renderer: HandModifyEvent before each held item is drawn
 * and around the eat transform, SwingArmEvent at swingArm, EquipArmEvent at applyItemArmTransform.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void horizon$item(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
	                          InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
	                          SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		EventBus.INSTANCE.post(new HandModifyEvent(hand, poseStack, itemStack, false));
	}

	/** 26.3 passes the eat transform no item, so it is wrapped where the item is known. */
	@WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;applyEatTransform(Lcom/mojang/blaze3d/vertex/PoseStack;FLnet/minecraft/world/entity/HumanoidArm;FI)V"))
	private void horizon$eat(FirstPersonHandsAndItemsRenderer self, PoseStack poseStack, float partialTicks, HumanoidArm arm,
	                         float useItemRemainingTicks, int useDuration, Operation<Void> original, @Local(argsOnly = true) ItemStack itemStack) {
		boolean rightHanded = Minecraft.getInstance().options.mainHand().get() == HumanoidArm.RIGHT;
		InteractionHand hand = rightHanded
			? (arm == HumanoidArm.RIGHT ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND)
			: (arm == HumanoidArm.RIGHT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		if (EventBus.INSTANCE.post(new HandModifyEvent(hand, poseStack, itemStack, true)).getCancelled()) return;
		original.call(self, poseStack, partialTicks, arm, useItemRemainingTicks, useDuration);
	}

	@Inject(method = "swingArm", at = @At("HEAD"), cancellable = true)
	private void horizon$swing(float animation, PoseStack poseStack, int invert, HumanoidArm arm, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new SwingArmEvent(animation, poseStack, invert, arm)).getCancelled()) ci.cancel();
	}

	@Inject(method = "applyItemArmTransform", at = @At("HEAD"), cancellable = true)
	private void horizon$equip(PoseStack poseStack, HumanoidArm arm, float inverseArmHeight, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new EquipArmEvent(inverseArmHeight, arm)).getCancelled()) ci.cancel();
	}

	/**
	 * 26.2 called swingArm every frame for a whacking item (with no swing, progress 0); 26.3 only while that hand
	 * swings. Swing Animations' poses rely on the former, so while it is on, a hand with no swing of its own gets one
	 * with progress 0 (the attack value is already 0 for it).
	 */
	@ModifyExpressionValue(method = "submitArmWithItem", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;currentSwing:Lnet/minecraft/world/entity/LivingEntity$SwingDescription;"))
	private LivingEntity.SwingDescription horizon$swingEveryFrame(LivingEntity.SwingDescription swing, @Local(argsOnly = true) InteractionHand hand,
	                                                              @Local(argsOnly = true) ItemStack itemStack) {
		if (!Cosmetic2Hooks.swingEveryFrame() || swing != null && swing.hand() == hand) return swing;
		return new LivingEntity.SwingDescription(hand, itemStack.getAttackAnimation(), 0);
	}
}
