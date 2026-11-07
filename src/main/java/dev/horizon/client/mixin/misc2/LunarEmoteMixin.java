package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.lunar.LunarEmotes;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Not in the original: a player using a Lunar emote is drawn as Lunar's emote model instead of their own
 * ({@link LunarEmotes}): the body model is swapped for it and the layers (armour, cape, held items, cosmetics) left
 * out, as Lunar Client does. The name tag stays.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LunarEmoteMixin {
	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"
		)
	)
	private void horizon$emoteModel(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack, RenderType type,
									int light, int overlay, int color, UvMapping uv, int outline, Operation<Void> original) {
		if (state instanceof AvatarRenderState avatar && LunarEmotes.emoting(avatar)) {
			LunarEmotes.submit(avatar, poseStack, collector, light, overlay);
			return;
		}
		original.call(collector, model, state, poseStack, type, light, overlay, color, uv, outline);
	}

	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"
		)
	)
	private boolean horizon$emoteLayers(LivingEntityRenderer<?, ?, ?> self, LivingEntityRenderState state, Operation<Boolean> original) {
		if (state instanceof AvatarRenderState avatar && LunarEmotes.emoting(avatar)) return false;
		return original.call(self, state);
	}
}
