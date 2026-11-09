package dev.horizon.client.mixin.world;

import dev.horizon.client.event.BossBarRenderEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/** The boss bars, while there are any. */
@Mixin(BossHealthOverlay.class)
public abstract class NoRenderBossBarMixin {
	@Shadow
	@Final
	private Map<UUID, LerpingBossEvent> events;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void horizon$bossBar(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (!events.isEmpty() && EventBus.INSTANCE.post(new BossBarRenderEvent()).getCancelled()) ci.cancel();
	}
}
