package dev.horizon.client.mixin;

import com.mojang.realmsclient.RealmsAvailability;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether Realms can be used is checked once per account and kept; cleared, it's checked again. */
@Mixin(RealmsAvailability.class)
public interface RealmsAvailabilityAccessor {
	@Accessor("future")
	static void horizon_setFuture(CompletableFuture<RealmsAvailability.Result> future) {
		throw new AssertionError();
	}
}
