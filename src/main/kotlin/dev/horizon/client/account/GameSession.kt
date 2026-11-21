package dev.horizon.client.account

import com.mojang.authlib.exceptions.AuthenticationException
import com.mojang.authlib.minecraft.UserApiService
import com.mojang.authlib.services.MinecraftServicesDiscoveryService
import com.mojang.authlib.services.ProfileResult
import com.mojang.realmsclient.client.RealmsClient
import com.mojang.realmsclient.gui.RealmsDataFetcher
import dev.horizon.client.HorizonClient
import dev.horizon.client.mixin.MinecraftSessionAccessor
import dev.horizon.client.mixin.RealmsAvailabilityAccessor
import dev.horizon.client.mixin.RealmsClientAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.User
import net.minecraft.client.gui.screens.social.PlayerSocialManager
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler
import net.minecraft.client.multiplayer.ProfileKeyPairManager
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment
import net.minecraft.client.multiplayer.chat.report.ReportingContext
import net.minecraft.client.telemetry.ClientTelemetryManager
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The account the running game plays as, and putting another in its place without a restart. The game is handed its
 * account once, at launch, and builds a handful of things around it: who it joins servers as, the profile its own
 * skin comes from, the account's settings (whether it may play online or chat, a ban), the keys chat is signed with,
 * the friends list and block list, chat reporting, telemetry and Realms. All of them are made again for the new
 * account, as the game makes them at startup, so nothing still acts as the old one.
 *
 * Only out of a world: a connection is to a server as the account that joined it.
 */
object GameSession {
    private val mc get() = Minecraft.getInstance()

    /** The account the game was started with. It can always be gone back to. */
    val launch: User = mc.user

    private val discovery by lazy { MinecraftServicesDiscoveryService.create(mc.proxy, true) }

    /** What a new account needs fetched before it can take over, so the game thread doesn't wait on the network. */
    class Prepared internal constructor(val user: User, internal val api: UserApiService, internal val properties: UserApiService.UserProperties,
                                        internal val profile: ProfileResult?)

    /**
     * Fetches the account's settings and profile from Minecraft's services (blocking: off the game thread). An account
     * they can't be fetched for (offline, or a token that isn't one) plays as the game treats such an account at launch.
     */
    fun prepare(name: String, uuid: UUID, accessToken: String): Prepared {
        // Neither the launch account's Xbox id nor its launcher id carries over: both would tie the accounts together.
        val user = if (accessToken == launch.accessToken && uuid == launch.profileId) launch
            else User(name, uuid, accessToken, Optional.empty(), Optional.empty())
        val api = discovery.createUserApiService(accessToken)
        val properties = try {
            api.fetchProperties()
        } catch (e: AuthenticationException) {
            HorizonClient.LOG.warn("Couldn't fetch the account's settings ({})", e.javaClass.simpleName)
            UserApiService.OFFLINE_PROPERTIES
        }
        val profile = try {
            mc.services().sessionService().fetchProfile(uuid, true)
        } catch (e: Exception) {
            null
        }
        return Prepared(user, api, properties, profile)
    }

    /** Makes [p] the account the game plays as (game thread, out of a world). */
    fun apply(p: Prepared) {
        check(mc.isSameThread) { "not the game thread" }
        check(mc.level == null) { "in a world" }
        val acc = mc as MinecraftSessionAccessor
        val user = p.user

        acc.horizon_setUser(user)
        acc.horizon_setProfileFuture(CompletableFuture.completedFuture(p.profile))
        acc.horizon_setUserApiService(p.api)
        acc.horizon_setUserPropertiesFuture(CompletableFuture.completedFuture(p.properties))

        // The old account goes offline for its friends and stops being polled; the new one's lists start empty.
        quietly("presence") { mc.playerSocialManager.presenceHandler.sendOfflinePresence() }
        quietly("friends") { acc.horizon_friendUpdates().close() }
        val friends = discovery.createFriendsService(user.accessToken)
        val updates = RemoteFriendListUpdateHandler(friends, mc)
        val social = PlayerSocialManager(mc, p.api, friends, updates)
        acc.horizon_setFriendUpdates(updates)
        acc.horizon_setPlayerSocialManager(social)
        if (social.isFriendListEnabled) updates.start()

        quietly("telemetry") { mc.telemetryManager.close() }
        acc.horizon_setTelemetryManager(ClientTelemetryManager(mc, p.api, user))
        acc.horizon_setProfileKeyPairManager(ProfileKeyPairManager.create(p.api, user, mc.gameDirectory.toPath()))
        acc.horizon_setReportingContext(ReportingContext.create(ReportEnvironment.local(), p.api))

        // Realms made its client as the old account, and remembered whether that account could use it.
        RealmsClientAccessor.horizon_setInstance(null)
        RealmsAvailabilityAccessor.horizon_setFuture(null)
        acc.horizon_setRealmsDataFetcher(RealmsDataFetcher(RealmsClient.getOrCreate(mc)))

        HorizonClient.LOG.info("Setting user: {}", user.name)
    }

    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Account switch: {} ({})", what, e.javaClass.simpleName)
        }
    }
}
