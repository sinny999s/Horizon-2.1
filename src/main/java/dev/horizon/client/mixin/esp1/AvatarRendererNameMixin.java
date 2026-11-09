package dev.horizon.client.mixin.esp1;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.RenderNameTagEvent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderNameTagEvent for players, cancellable. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererNameMixin {
	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void horizon$nameTag(AvatarRenderState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderNameTagEvent(state)).getCancelled()) ci.cancel();
	}
}
