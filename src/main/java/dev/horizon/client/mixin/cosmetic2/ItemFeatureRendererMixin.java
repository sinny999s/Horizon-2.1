package dev.horizon.client.mixin.cosmetic2;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.ItemCommandEvent;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Item quads can be sent to ItemCommandEvent's provider, without glint (26.2 drew
 * the glint in a separate pass; 26.3 picks a glint render type in the main pass, so the foil type is forced there).
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
	@ModifyExpressionValue(method = {"prepareMainSubmit", "prepareOutlineSubmit"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer horizon$redirect(VertexConsumer consumer) {
		ItemCommandEvent e = EventBus.INSTANCE.post(new ItemCommandEvent());
		return e.getCancelled() && e.getProvider() != null ? e.getProvider() : consumer;
	}

	@ModifyExpressionValue(method = "prepareMainSubmit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;foilType()Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;"))
	private ItemStackRenderState.FoilType horizon$noFoil(ItemStackRenderState.FoilType foil) {
		return EventBus.INSTANCE.post(new ItemCommandEvent()).getCancelled() ? ItemStackRenderState.FoilType.NONE : foil;
	}
}
