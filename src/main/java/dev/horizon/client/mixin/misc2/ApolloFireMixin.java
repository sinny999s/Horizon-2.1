package dev.horizon.client.mixin.misc2;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.horizon.client.lunar.ApolloFeatures;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Not in the original: a player's fire in the colour an Apollo server set (Apollo's coloredfire module). */
@Mixin(FlameFeatureRenderer.class)
public abstract class ApolloFireMixin {
	@Unique
	private static int horizon$fireColor = -1;

	@Inject(method = "prepare", at = @At("HEAD"))
	private void horizon$apolloFireColor(FlameFeatureRenderer.Submit submit, VertexConsumer consumer, TextureAtlasSprite a, TextureAtlasSprite b,
	                                     CallbackInfo ci) {
		Integer c = submit.entityRenderState() instanceof AvatarRenderState avatar ? ApolloFeatures.fireColor(avatar.id) : null;
		horizon$fireColor = c == null ? -1 : c;
	}

	@ModifyArg(method = "fireVertex",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private static int horizon$apolloFireTint(int color) {
		return horizon$fireColor;
	}
}
