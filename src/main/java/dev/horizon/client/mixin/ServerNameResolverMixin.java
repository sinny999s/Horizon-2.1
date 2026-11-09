package dev.horizon.client.mixin;

import dev.horizon.client.AnarchyServers;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 6b6t and the other anarchy servers get past Mojang's blocklist ({@link AnarchyServers}). */
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
	@Shadow @Final @Mutable private AddressCheck addressCheck;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void horizon$unblock(ServerAddressResolver resolver, ServerRedirectHandler redirectHandler, AddressCheck check, CallbackInfo ci) {
		this.addressCheck = AnarchyServers.unblock(this.addressCheck);
	}
}
