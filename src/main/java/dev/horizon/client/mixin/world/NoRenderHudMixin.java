package dev.horizon.client.mixin.world;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.ItemNameRenderEvent;
import dev.horizon.client.event.NauseaRenderEvent;
import dev.horizon.client.event.RenderPortalOverlayEvent;
import dev.horizon.client.event.RenderVignetteOverlayEvent;
import dev.horizon.client.event.ScoreBoardRenderEvent;
import dev.horizon.client.event.StatusEffectsRenderEvent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks for no-render: held item name, scoreboard, vignette, portal, effects and nausea overlays. */
@Mixin(Hud.class)
public abstract class NoRenderHudMixin {
	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void horizon$itemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new ItemNameRenderEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
		at = @At("HEAD"), cancellable = true)
	private void horizon$scoreboard(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new ScoreBoardRenderEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void horizon$vignette(GuiGraphicsExtractor graphics, Entity camera, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderVignetteOverlayEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void horizon$portal(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderPortalOverlayEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void horizon$effects(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.player.getActiveEffects().isEmpty() && mc.gui.screen() == null
			&& EventBus.INSTANCE.post(new StatusEffectsRenderEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
	private void horizon$nausea(GuiGraphicsExtractor graphics, float strength, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new NauseaRenderEvent()).getCancelled()) ci.cancel();
	}
}
