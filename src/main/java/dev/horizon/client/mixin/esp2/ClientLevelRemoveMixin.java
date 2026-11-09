package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.EntityRemoveEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An entity is about to leave the client level. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelRemoveMixin {
	@Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
	private void horizon$removeEntity(int id, Entity.RemovalReason reason, CallbackInfo ci) {
		Entity entity = ((ClientLevel) (Object) this).getEntity(id);
		if (EventBus.INSTANCE.post(new EntityRemoveEvent(entity)).getCancelled()) ci.cancel();
	}
}
