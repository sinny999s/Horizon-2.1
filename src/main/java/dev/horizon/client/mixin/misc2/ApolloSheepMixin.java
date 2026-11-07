package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloFeatures;
import net.minecraft.client.renderer.entity.SheepRenderer;
import net.minecraft.client.renderer.entity.state.SheepRenderState;
import net.minecraft.world.entity.animal.sheep.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Not in the original: sheep an Apollo server made rainbow (Apollo's entity module): drawn as vanilla's jeb_ sheep. */
@Mixin(SheepRenderer.class)
public abstract class ApolloSheepMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/sheep/Sheep;Lnet/minecraft/client/renderer/entity/state/SheepRenderState;F)V",
		at = @At("TAIL"))
	private void horizon$apolloRainbow(Sheep sheep, SheepRenderState state, float partialTick, CallbackInfo ci) {
		if (ApolloFeatures.isRainbowSheep(sheep.getId())) state.isJebSheep = true;
	}
}
