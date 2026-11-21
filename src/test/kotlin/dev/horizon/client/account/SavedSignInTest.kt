package dev.horizon.client.account

import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Switching to an account signed in from the game: when its last token is used, when a new one is asked for, what is kept. */
class SavedSignInTest {
    private val id = UUID.fromString("bf0b7bbf-c26b-4c38-8760-2f921952ec1f")
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private val token = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private val xboxUser = "https://user.auth.xboxlive.com/user/authenticate"
    private val xsts = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private val login = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private val profile = "https://api.minecraftservices.com/minecraft/profile"

    private fun plain(text: String) = "plain:" + Base64.getEncoder().encodeToString(text.toByteArray())

    private fun saved(tokenExpires: Long, name: String = "softtop", refresh: String = "refresh-1") =
        AccountStore.Saved(id.toString(), name, "http://textures.minecraft.net/texture/ff8a", true, plain(refresh), plain("mc-old"), tokenExpires)

    private fun profileBody(name: String = "softtop", uuid: String = "bf0b7bbfc26b4c3887602f921952ec1f") =
        """{"id":"$uuid","name":"$name","skins":[{"state":"ACTIVE","url":"http://textures.minecraft.net/texture/ff8a","variant":"SLIM"}]}"""

    private class Fake(private val answers: Map<String, MicrosoftAuth.Response>, private val byToken: Map<String, MicrosoftAuth.Response> = emptyMap()) : MicrosoftAuth.Transport {
        val asked = ArrayList<String>()

        override fun send(method: String, url: String, headers: Map<String, String>, contentType: String?, body: String?): MicrosoftAuth.Response {
            asked += url
            byToken[headers["Authorization"]]?.let { return it }
            return answers[url] ?: throw IOException("no route")
        }
    }

    /** What was written back. */
    private class Kept : Accounts.Keeper {
        val log = ArrayList<String>()

        override fun put(profile: MicrosoftAuth.Profile, refreshToken: String): AccountStore.Saved {
            log += "put ${profile.name} ${profile.accessToken} $refreshToken"
            return AccountStore.Saved(profile.uuid.toString(), profile.name)
        }

        override fun refreshed(uuid: UUID, refreshToken: String) {
            log += "refreshed $refreshToken"
        }

        override fun signedOut(uuid: UUID) {
            log += "signedOut"
        }
    }

    private fun ok(body: String) = MicrosoftAuth.Response(200, body)

    private fun chain(vararg replace: Pair<String, MicrosoftAuth.Response>, profiles: Map<String, MicrosoftAuth.Response> = emptyMap()) = Fake(mapOf(
        token to ok("""{"access_token":"ms-access","refresh_token":"refresh-2"}"""),
        xboxUser to ok("""{"Token":"xbl","DisplayClaims":{"xui":[{"uhs":"h"}]}}"""),
        xsts to ok("""{"Token":"xsts","DisplayClaims":{"xui":[{"uhs":"h"}]}}"""),
        login to ok("""{"access_token":"mc-new","expires_in":86400}"""),
        profile to ok(profileBody()),
    ) + replace, profiles)

    @Test
    fun aGoodTokenIsUsedAsItIs() {
        val http = chain()
        val keep = Kept()
        val p = Accounts.SavedSignIn.fresh(saved(now + 5 * hour), http, now, keep)
        assertEquals("mc-old", p.accessToken)
        // Minecraft was asked whether it still takes it, and nothing else: no sign-in, so no rate limit.
        assertEquals(listOf(profile), http.asked)
        assertTrue(keep.log.isEmpty())
    }

    @Test
    fun aChangedNameIsKeptWithTheSameSignIn() {
        val http = chain(profile to ok(profileBody("newname")))
        val keep = Kept()
        val p = Accounts.SavedSignIn.fresh(saved(now + 5 * hour), http, now, keep)
        assertEquals("newname", p.name)
        assertEquals(listOf("put newname mc-old refresh-1"), keep.log)
    }

    @Test
    fun anExpiredTokenIsRenewedQuietly() {
        for (expires in listOf(now - hour, now + 5 * 60_000L)) {
            val http = chain()
            val keep = Kept()
            val p = Accounts.SavedSignIn.fresh(saved(expires), http, now, keep)
            assertEquals("mc-new", p.accessToken)
            assertEquals(now + 24 * hour, p.expiresAt)
            // The old token isn't tried at all; Microsoft's new refresh token replaces the old one.
            assertEquals(listOf(token, xboxUser, xsts, login, profile), http.asked)
            assertEquals(listOf("refreshed refresh-2", "put softtop mc-new refresh-2"), keep.log)
        }
    }

    @Test
    fun aTokenMinecraftTurnsDownIsRenewed() {
        val http = chain(profiles = mapOf("Bearer mc-old" to MicrosoftAuth.Response(401, "")))
        val keep = Kept()
        val p = Accounts.SavedSignIn.fresh(saved(now + 5 * hour), http, now, keep)
        assertEquals("mc-new", p.accessToken)
        assertEquals(listOf(profile, token, xboxUser, xsts, login, profile), http.asked)
    }

    @Test
    fun aSpentSignInIsMarkedAndSaysSo() {
        val http = chain(token to MicrosoftAuth.Response(400, """{"error":"invalid_grant"}"""))
        val keep = Kept()
        val f = assertFailsWith<MicrosoftAuth.Failure> { Accounts.SavedSignIn.fresh(saved(now - hour), http, now, keep) }
        assertEquals(MicrosoftAuth.Reason.SIGN_IN_AGAIN, f.reason)
        assertEquals("Sign in to softtop again", f.message)
        assertEquals(listOf("signedOut"), keep.log)
    }

    @Test
    fun aBusyMinecraftKeepsTheNewRefreshToken() {
        // Microsoft has already swapped the refresh token when Minecraft's sign-in answers 429: losing the new one
        // would sign the account out for good.
        val http = chain(login to MicrosoftAuth.Response(429, ""))
        val keep = Kept()
        val f = assertFailsWith<MicrosoftAuth.Failure> { Accounts.SavedSignIn.fresh(saved(now - hour), http, now, keep) }
        assertEquals(MicrosoftAuth.Reason.RATE_LIMITED, f.reason)
        assertEquals(listOf("refreshed refresh-2"), keep.log)
    }

    @Test
    fun beingOfflineChangesNothing() {
        val keep = Kept()
        val f = assertFailsWith<MicrosoftAuth.Failure> { Accounts.SavedSignIn.fresh(saved(now - hour), Fake(emptyMap()), now, keep) }
        assertEquals(MicrosoftAuth.Reason.OFFLINE, f.reason)
        assertTrue(keep.log.isEmpty())
        // With a token that should still be good, too: it isn't thrown away for a network error.
        val g = assertFailsWith<MicrosoftAuth.Failure> { Accounts.SavedSignIn.fresh(saved(now + 5 * hour), Fake(emptyMap()), now, keep) }
        assertEquals(MicrosoftAuth.Reason.OFFLINE, g.reason)
        assertTrue(keep.log.isEmpty())
    }

    @Test
    fun aSignInForAnotherAccountIsRefused() {
        val http = chain(profile to ok(profileBody("other", "f90f94b1d49748d08264528e53bd7e2b")))
        val keep = Kept()
        assertFailsWith<MicrosoftAuth.Failure> { Accounts.SavedSignIn.fresh(saved(now - hour), http, now, keep) }
        assertEquals(listOf("refreshed refresh-2"), keep.log)
    }

    @Test
    fun secretsRoundTrip() {
        val sealed = Secrets.seal("a refresh token")
        assertTrue(sealed.startsWith("dpapi:") || sealed.startsWith("plain:"))
        assertTrue("a refresh token" !in sealed)
        assertEquals("a refresh token", Secrets.open(sealed))
        assertEquals("x", Secrets.open(plain("x")))
        assertEquals(null, Secrets.open("garbage"))
        assertEquals(null, Secrets.open("dpapi:AAAA"))
    }
}
