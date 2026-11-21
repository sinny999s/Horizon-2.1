package dev.horizon.client.mixin;

import com.mojang.realmsclient.client.RealmsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Realms keeps one client, signed in as whoever was playing when it was made; cleared, the next use makes a new one. */
@Mixin(RealmsClient.class)
public interface RealmsClientAccessor {
	@Accessor("realmsClientInstance")
	static void horizon_setInstance(RealmsClient client) {
		throw new AssertionError();
	}
}
