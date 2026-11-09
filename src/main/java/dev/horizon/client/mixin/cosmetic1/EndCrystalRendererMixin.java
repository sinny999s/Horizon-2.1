package dev.horizon.client.mixin.cosmetic1;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.CrystalRenderEvent;
import dev.horizon.client.event.CrystalYOffsetEvent;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SetCrystalScaleEvent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Crystals can be hidden, scaled and bob differently. */
@Mixin(EndCrystalRenderer.class)
public abstract class EndCrystalRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void horizon$render(EndCrystalRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new CrystalRenderEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void horizon$scale(EndCrystalRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		SetCrystalScaleEvent e = EventBus.INSTANCE.post(new SetCrystalScaleEvent(state.ageInTicks));
		if (e.getCancelled()) poseStack.scale(e.getScale(), e.getScale(), e.getScale());
	}

	@Inject(method = "getY", at = @At("HEAD"), cancellable = true)
	private static void horizon$getY(float timeInTicks, CallbackInfoReturnable<Float> cir) {
		CrystalYOffsetEvent e = EventBus.INSTANCE.post(new CrystalYOffsetEvent(timeInTicks));
		if (e.getCancelled()) cir.setReturnValue(e.getOffset());
	}
}
