package dev.horizon.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.ProfileResult;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Everything the game keeps for the account it was started with, so another account can take its place while the game
 * runs (account/GameSession): who is playing, their profile and account settings, and the services signed in as them.
 */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {
	@Mutable
	@Accessor("user")
	void horizon_setUser(User user);

	@Mutable
	@Accessor("profileFuture")
	void horizon_setProfileFuture(CompletableFuture<ProfileResult> profile);

	@Mutable
	@Accessor("userApiService")
	void horizon_setUserApiService(UserApiService service);

	@Mutable
	@Accessor("userPropertiesFuture")
	void horizon_setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> properties);

	@Accessor("remoteFriendListUpdateHandler")
	RemoteFriendListUpdateHandler horizon_friendUpdates();

	@Mutable
	@Accessor("remoteFriendListUpdateHandler")
	void horizon_setFriendUpdates(RemoteFriendListUpdateHandler handler);

	@Mutable
	@Accessor("playerSocialManager")
	void horizon_setPlayerSocialManager(PlayerSocialManager manager);

	@Mutable
	@Accessor("telemetryManager")
	void horizon_setTelemetryManager(ClientTelemetryManager manager);

	@Mutable
	@Accessor("profileKeyPairManager")
	void horizon_setProfileKeyPairManager(ProfileKeyPairManager manager);

	@Accessor("reportingContext")
	void horizon_setReportingContext(ReportingContext context);

	@Mutable
	@Accessor("realmsDataFetcher")
	void horizon_setRealmsDataFetcher(RealmsDataFetcher fetcher);
}
