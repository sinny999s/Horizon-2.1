package dev.horizon.client.account

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Microsoft sign-in's steps against canned answers: what is sent where, and what each failure is taken for. */
class MicrosoftAuthTest {
    private class Sent(val method: String, val url: String, val headers: Map<String, String>, val contentType: String?, val body: String?)

    /** Answers by URL, and remembers what was asked. */
    private class Fake(private val answers: Map<String, MicrosoftAuth.Response>) : MicrosoftAuth.Transport {
        val sent = ArrayList<Sent>()

        override fun send(method: String, url: String, headers: Map<String, String>, contentType: String?, body: String?): MicrosoftAuth.Response {
            sent += Sent(method, url, headers, contentType, body)
            return answers[url] ?: throw IOException("no route to $url")
        }

        fun to(url: String) = sent.single { it.url == url }
    }

    private fun ok(body: String) = MicrosoftAuth.Response(200, body)

    private val token = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private val xboxUser = "https://user.auth.xboxlive.com/user/authenticate"
    private val xsts = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private val login = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private val profile = "https://api.minecraftservices.com/minecraft/profile"

    private val profileBody = """{"id":"bf0b7bbfc26b4c3887602f921952ec1f","name":"softtop","skins":[
        {"id":"a","state":"INACTIVE","url":"http://textures.minecraft.net/texture/old","variant":"CLASSIC"},
        {"id":"b","state":"ACTIVE","url":"http://textures.minecraft.net/texture/ff8a","variant":"SLIM"}],"capes":[]}"""

    private fun chain(vararg replace: Pair<String, MicrosoftAuth.Response>) = Fake(mapOf(
        token to ok("""{"token_type":"Bearer","expires_in":3600,"access_token":"ms-access","refresh_token":"ms-refresh-2"}"""),
        xboxUser to ok("""{"Token":"xbl-token","DisplayClaims":{"xui":[{"uhs":"hash123"}]}}"""),
        xsts to ok("""{"Token":"xsts-token","DisplayClaims":{"xui":[{"uhs":"hash123"}]}}"""),
        login to ok("""{"username":"x","access_token":"mc-token","token_type":"Bearer","expires_in":86400}"""),
        profile to ok(profileBody),
    ) + replace)

    private fun form(body: String) = body.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun authorizeUrlAsksForXboxWithPkce() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val url = MicrosoftAuth.authorizeUrl("http://localhost:4711/auth", "st4te", verifier)
        assertTrue(url.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"))
        val q = form(url.substringAfter('?'))
        assertEquals(MicrosoftAuth.CLIENT_ID, q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://localhost:4711/auth", q["redirect_uri"])
        assertEquals("XboxLive.signin XboxLive.offline_access offline_access", q["scope"])
        assertEquals("st4te", q["state"])
        assertEquals("select_account", q["prompt"])
        assertEquals("S256", q["code_challenge_method"])
        // RFC 7636's own example pair.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", q["code_challenge"])
        // The verifier itself never goes to the browser.
        assertFalse(verifier in url)
    }

    @Test
    fun randomTokensAreUrlSafeAndDiffer() {
        val a = MicrosoftAuth.randomToken(48)
        val b = MicrosoftAuth.randomToken(48)
        assertTrue(Regex("[A-Za-z0-9_-]{64}").matches(a), a)
        assertTrue(a != b)
        assertEquals(48, Base64.getUrlDecoder().decode(a).size)
    }

    @Test
    fun redeemsACodeAsAPublicClient() {
        val http = chain()
        val t = MicrosoftAuth.redeem("the-code", "http://localhost:4711/auth", "the-verifier", http)
        assertEquals("ms-access", t.access)
        assertEquals("ms-refresh-2", t.refresh)
        val sent = http.to(token)
        assertEquals("POST", sent.method)
        assertEquals("application/x-www-form-urlencoded", sent.contentType)
        val f = form(sent.body!!)
        assertEquals("authorization_code", f["grant_type"])
        assertEquals("the-code", f["code"])
        assertEquals("the-verifier", f["code_verifier"])
        assertEquals("http://localhost:4711/auth", f["redirect_uri"])
        assertEquals(MicrosoftAuth.CLIENT_ID, f["client_id"])
        // A public client: no secret.
        assertNull(f["client_secret"])
    }

    @Test
    fun aCodeMicrosoftTurnsDownIsJustAFailedSignIn() {
        val http = chain(token to MicrosoftAuth.Response(400, """{"error":"invalid_grant","error_description":"AADSTS70000"}"""))
        val f = assertFailsWith<MicrosoftAuth.Failure> { MicrosoftAuth.redeem("bad", "http://localhost:1/auth", "v", http) }
        assertEquals(MicrosoftAuth.Reason.OTHER, f.reason)
        assertTrue("Try it again" in f.message!!)
    }

    @Test
    fun refreshesWithTheSavedToken() {
        val http = chain()
        val t = MicrosoftAuth.refresh("ms-refresh-1", http)
        assertEquals("ms-refresh-2", t.refresh)
        val f = form(http.to(token).body!!)
        assertEquals("refresh_token", f["grant_type"])
        assertEquals("ms-refresh-1", f["refresh_token"])
    }

    @Test
    fun aSpentSignInAsksForANewOne() {
        for (error in listOf("invalid_grant", "interaction_required")) {
            val http = chain(token to MicrosoftAuth.Response(400, """{"error":"$error","error_description":"AADSTS70000: expired"}"""))
            assertEquals(MicrosoftAuth.Reason.SIGN_IN_AGAIN, assertFailsWith<MicrosoftAuth.Failure> { MicrosoftAuth.refresh("old", http) }.reason)
        }
        val other = chain(token to MicrosoftAuth.Response(400, """{"error":"invalid_client"}"""))
        val f = assertFailsWith<MicrosoftAuth.Failure> { MicrosoftAuth.refresh("old", other) }
        assertEquals(MicrosoftAuth.Reason.OTHER, f.reason)
        assertTrue("invalid_client" in f.message!!)
        // No network at all.
        assertEquals(MicrosoftAuth.Reason.OFFLINE, assertFailsWith<MicrosoftAuth.Failure> { MicrosoftAuth.refresh("old", Fake(emptyMap())) }.reason)
    }

    @Test
    fun walksXboxToMinecraft() {
        val http = chain()
        val p = MicrosoftAuth.minecraft("ms-access", http, now = 1_000_000L)
        assertEquals(UUID.fromString("bf0b7bbf-c26b-4c38-8760-2f921952ec1f"), p.uuid)
        assertEquals("softtop", p.name)
        assertEquals("mc-token", p.accessToken)
        assertEquals(1_000_000L + 86_400_000L, p.expiresAt)
        // The skin in use, not the first listed.
        assertEquals("http://textures.minecraft.net/texture/ff8a", p.skin)
        assertTrue(p.slim)

        val user = JsonParser.parseString(http.to(xboxUser).body).asJsonObject
        assertEquals("http://auth.xboxlive.com", user["RelyingParty"].asString)
        assertEquals("JWT", user["TokenType"].asString)
        val props = user["Properties"].asJsonObject
        assertEquals("RPS", props["AuthMethod"].asString)
        assertEquals("user.auth.xboxlive.com", props["SiteName"].asString)
        // An Entra app's token goes in with "d=".
        assertEquals("d=ms-access", props["RpsTicket"].asString)

        val x = JsonParser.parseString(http.to(xsts).body).asJsonObject
        assertEquals("rp://api.minecraftservices.com/", x["RelyingParty"].asString)
        assertEquals("RETAIL", x["Properties"].asJsonObject["SandboxId"].asString)
        assertEquals("xbl-token", x["Properties"].asJsonObject["UserTokens"].asJsonArray.single().asString)

        assertEquals("XBL3.0 x=hash123;xsts-token", JsonParser.parseString(http.to(login).body).asJsonObject["identityToken"].asString)
        val asked = http.to(profile)
        assertEquals("GET", asked.method)
        assertEquals("Bearer mc-token", asked.headers["Authorization"])
        // Each token only went to the service it is for.
        assertEquals(listOf(xboxUser, xsts, login, profile), http.sent.map { it.url })
        assertTrue(http.sent.none { it.url != xboxUser && it.body?.contains("ms-access") == true })
    }

    @Test
    fun xboxSaysWhyAnAccountCannotPlay() {
        fun fails(xerr: Long) = assertFailsWith<MicrosoftAuth.Failure> {
            MicrosoftAuth.minecraft("ms", chain(xsts to MicrosoftAuth.Response(401, """{"Identity":"0","XErr":$xerr,"Message":"","Redirect":"https://start.ui.xboxlive.com/CreateAccount"}""")))
        }
        val none = fails(2148916233L)
        assertEquals(MicrosoftAuth.Reason.XBOX, none.reason)
        assertTrue("no Xbox profile" in none.message!!)
        assertTrue("child account" in fails(2148916238L).message!!)
        assertTrue("country" in fails(2148916235L).message!!)
        assertEquals(MicrosoftAuth.Reason.XBOX, fails(1L).reason)
    }

    @Test
    fun minecraftsOwnFailures() {
        val busy = assertFailsWith<MicrosoftAuth.Failure> { MicrosoftAuth.minecraft("ms", chain(login to MicrosoftAuth.Response(429, ""))) }
        assertEquals(MicrosoftAuth.Reason.RATE_LIMITED, busy.reason)
        val noGame = assertFailsWith<MicrosoftAuth.Failure> {
            MicrosoftAuth.minecraft("ms", chain(profile to MicrosoftAuth.Response(404, """{"path":"/minecraft/profile","errorType":"NOT_FOUND","error":"NOT_FOUND"}""")))
        }
        assertEquals(MicrosoftAuth.Reason.NO_MINECRAFT, noGame.reason)
        val stale = assertFailsWith<MicrosoftAuth.Failure> { MicrosoftAuth.profile("old", 0L, chain(profile to MicrosoftAuth.Response(401, ""))) }
        assertEquals(MicrosoftAuth.Reason.SIGN_IN_AGAIN, stale.reason)
        // A gateway's HTML instead of an answer.
        assertEquals(MicrosoftAuth.Reason.OTHER, assertFailsWith<MicrosoftAuth.Failure> {
            MicrosoftAuth.minecraft("ms", chain(login to MicrosoftAuth.Response(502, "<html>bad gateway</html>")))
        }.reason)
    }

    @Test
    fun noFailureQuotesAToken() {
        val secrets = listOf("ms-access", "ms-refresh-1", "xbl-token", "xsts-token", "mc-token")
        val failures = listOf(
            { MicrosoftAuth.refresh("ms-refresh-1", chain(token to MicrosoftAuth.Response(400, """{"error":"invalid_grant","error_description":"ms-refresh-1 expired"}"""))) },
            { MicrosoftAuth.minecraft("ms-access", chain(xsts to MicrosoftAuth.Response(401, """{"XErr":2148916233,"Message":"xbl-token"}"""))) },
            { MicrosoftAuth.minecraft("ms-access", chain(login to MicrosoftAuth.Response(500, """{"error":"oops xsts-token"}"""))) },
            { MicrosoftAuth.minecraft("ms-access", chain(profile to MicrosoftAuth.Response(500, "mc-token"))) },
        )
        for (f in failures) {
            val message = assertFailsWith<MicrosoftAuth.Failure> { f() }.message!!
            assertTrue(secrets.none { it in message }, message)
        }
    }

    @Test
    fun readsProfileIds() {
        assertEquals(UUID.fromString("f90f94b1-d497-48d0-8264-528e53bd7e2b"), MicrosoftAuth.uuid("f90f94b1d49748d08264528e53bd7e2b"))
        assertEquals(UUID.fromString("f90f94b1-d497-48d0-8264-528e53bd7e2b"), MicrosoftAuth.uuid("f90f94b1-d497-48d0-8264-528e53bd7e2b"))
        assertNull(MicrosoftAuth.uuid("nope"))
        assertNull(MicrosoftAuth.uuid("zz0f94b1d49748d08264528e53bd7e2b"))
    }
}
