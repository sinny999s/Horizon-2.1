package dev.horizon.client.mixin.world;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SetupTerrainEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The free camera's detach (its rotation/position hooks are mixin/esp2/CameraMixin). */
@Mixin(Camera.class)
public abstract class FreeCamCameraMixin {
	@Shadow
	private boolean detached;

	@Inject(method = "update", at = @At("RETURN"))
	private void horizon$setupTerrain(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new SetupTerrainEvent()).getCancelled()) this.detached = true;
	}


}
