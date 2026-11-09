package dev.horizon.client.mixin.legit;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.PreEntitySpawnEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An entity about to be added to the client level. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelLegitMixin {
	@Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
	private void horizon$preAddEntity(Entity entity, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new PreEntitySpawnEvent(entity)).getCancelled()) ci.cancel();
	}
}
