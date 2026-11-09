package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.RenderArmorEvent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One armour piece on a humanoid model. */
@Mixin(HumanoidArmorLayer.class)
public abstract class NoRenderArmorMixin {
	@Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
	private void horizon$armor(PoseStack pose, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int light,
	                           HumanoidRenderState state, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderArmorEvent()).getCancelled()) ci.cancel();
	}
}
