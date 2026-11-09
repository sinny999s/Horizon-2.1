package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.BoxContractEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The local player's box goes through BoxContractEvent. */
@Mixin(Entity.class)
public abstract class EntityBoxMixin {
	@Inject(method = "getBoundingBox", at = @At("HEAD"), cancellable = true)
	private void horizon$boxContract(CallbackInfoReturnable<AABB> cir) {
		if ((Object) this != Minecraft.getInstance().player) return;
		BoxContractEvent e = EventBus.INSTANCE.post(new BoxContractEvent());
		if (e.getCancelled()) cir.setReturnValue(e.getValue());
	}
}
