package dev.horizon.client.mixin.misc2;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.lunar.ApolloFeatures;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Not in the original: armour pieces an Apollo server hides on a player (Apollo's limb module, HideArmorPiecesMessage). */
@Mixin(HumanoidArmorLayer.class)
public abstract class ApolloArmorMixin {
	@Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
	private void horizon$apolloHideArmor(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int light,
	                                     HumanoidRenderState state, CallbackInfo ci) {
		if (!(state instanceof AvatarRenderState avatar)) return;
		int piece = switch (slot) {
			case HEAD -> 1;
			case CHEST -> 2;
			case LEGS -> 3;
			case FEET -> 4;
			default -> 0;
		};
		if (piece != 0 && ApolloFeatures.armorHidden(avatar.id, piece)) ci.cancel();
	}
}
