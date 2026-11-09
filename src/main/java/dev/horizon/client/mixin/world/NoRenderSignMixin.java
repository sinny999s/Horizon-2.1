package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SignTextRenderEvent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One side of a sign's text. */
@Mixin(AbstractSignRenderer.class)
public abstract class NoRenderSignMixin {
	@Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
	private void horizon$signText(SignRenderState state, PoseStack pose, SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new SignTextRenderEvent(state.blockPos)).getCancelled()) ci.cancel();
	}
}
