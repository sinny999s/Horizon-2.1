package dev.horizon.client.mixin.world;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.RenderSpawnerEntityEvent;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mob spinning inside a spawner. */
@Mixin(SpawnerRenderer.class)
public abstract class NoRenderSpawnerMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/SpawnerRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void horizon$spawner(CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderSpawnerEntityEvent()).getCancelled()) ci.cancel();
	}
}
