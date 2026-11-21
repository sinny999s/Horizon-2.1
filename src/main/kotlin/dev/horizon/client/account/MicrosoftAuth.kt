package dev.horizon.client.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Signing a Microsoft account in to Minecraft, the way launchers do: a Microsoft sign-in in the browser gives a code,
 * the code a Microsoft token (and a refresh token, which gets a new one later without signing in again), that an Xbox
 * Live token, that an XSTS token for Minecraft's services, and that the Minecraft token the game joins servers with.
 * The account's Minecraft profile (name, id, skin) is then read with it.
 *
 * Tokens only ever go to the endpoints below (Microsoft, Xbox Live, Minecraft's services). Everything here blocks:
 * call it off the game thread. Requests go through a [Transport], so the steps can be tested without the network.
 */
object MicrosoftAuth {
    /**
     * The app a sign-in is for. Minecraft's services only accept apps Mojang has approved; this is the one the Horizon
     * Client launcher signs in with (X Minecraft Launcher's), so an account is asked for the same consent in both.
     */
    const val CLIENT_ID = "1363d629-5b06-48a9-a5fb-c65de945f13e"
    private const val SCOPE = "XboxLive.signin XboxLive.offline_access offline_access"
    private const val AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
    private const val TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private const val XBOX_USER = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XBOX_XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MINECRAFT_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MINECRAFT_PROFILE = "https://api.minecraftservices.com/minecraft/profile"

    class Response(val status: Int, val body: String)

    /** One HTTP request; [body] is sent as [contentType] when given. Throws [IOException] when the server can't be reached. */
    fun interface Transport {
        fun send(method: String, url: String, headers: Map<String, String>, contentType: String?, body: String?): Response
    }

    /** Why a sign-in or a refresh didn't work, for what to tell the player and whether signing in again would help. */
    enum class Reason {
        /** The player closed or declined the sign-in. */
        CANCELLED,
        /** Microsoft no longer accepts the saved sign-in: the account has to be signed in again. */
        SIGN_IN_AGAIN,
        /** The Microsoft account has no Xbox profile, can't use Xbox Live where it lives, or is a child's without a family. */
        XBOX,
        /** The account doesn't own Minecraft: Java Edition (or never picked a name). */
        NO_MINECRAFT,
        /** Minecraft's sign-in answered "too many requests"; it works again in a minute or two. */
        RATE_LIMITED,
        /** A server couldn't be reached. */
        OFFLINE,
        OTHER,
    }

    class Failure(val reason: Reason, message: String) : Exception(message)

    /** A Microsoft sign-in: the token for Xbox Live, and the one that gets the next without signing in. */
    class MicrosoftTokens(val access: String, val refresh: String)

    /** A Minecraft account signed in: what the game needs to play as it. */
    class Profile(val uuid: UUID, val name: String, val accessToken: String, val expiresAt: Long, val skin: String, val slim: Boolean)

    private val random = SecureRandom()

    /** A random URL-safe string from [bytes] random bytes (a PKCE verifier, a state). */
    fun randomToken(bytes: Int = 32): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** The PKCE challenge for [verifier]: its SHA-256, URL-safe base64. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    /**
     * The page to open in the browser. Microsoft sends the browser to [redirect] with a code (or an error) and [state]
     * once the player has signed in; the account picker is always shown, so another account than the browser's own
     * can be added.
     */
    fun authorizeUrl(redirect: String, state: String, verifier: String): String = AUTHORIZE + "?" + form(
        "client_id" to CLIENT_ID,
        "response_type" to "code",
        "redirect_uri" to redirect,
        "scope" to SCOPE,
        "code_challenge" to challenge(verifier),
        "code_challenge_method" to "S256",
        "prompt" to "select_account",
        "state" to state,
    )

    /** Exchanges the [code] the browser came back with for the account's Microsoft tokens. */
    fun redeem(code: String, redirect: String, verifier: String, http: Transport = Web): MicrosoftTokens = try {
        tokens(http, form(
            "client_id" to CLIENT_ID,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirect,
            "code_verifier" to verifier,
            "scope" to SCOPE,
        ))
    } catch (f: Failure) {
        // A code Microsoft turns down isn't a saved sign-in that ran out: there is nothing to do but start over.
        if (f.reason != Reason.SIGN_IN_AGAIN) throw f
        throw Failure(Reason.OTHER, "Microsoft didn't accept that sign-in. Try it again")
    }

    /** New Microsoft tokens from a saved [refreshToken], without a sign-in. The refresh token changes: save the new one. */
    fun refresh(refreshToken: String, http: Transport = Web): MicrosoftTokens = tokens(http, form(
        "client_id" to CLIENT_ID,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "scope" to SCOPE,
    ))

    private fun tokens(http: Transport, body: String): MicrosoftTokens {
        val res = send(http, "POST", TOKEN, emptyMap(), "application/x-www-form-urlencoded", body, "Microsoft")
        val json = parse(res.body)
        if (res.status != 200) {
            val error = json?.string("error") ?: ""
            // The code or refresh token was used up, expired or revoked, or the account's sign-in needs a person again.
            if (error == "invalid_grant" || error == "interaction_required" || error == "consent_required")
                throw Failure(Reason.SIGN_IN_AGAIN, "Microsoft wants this account signed in again")
            throw Failure(Reason.OTHER, "Microsoft's sign-in failed" + detail(error, res.status))
        }
        val access = json?.string("access_token")
        val refresh = json?.string("refresh_token")
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) throw Failure(Reason.OTHER, "Microsoft's sign-in gave no token")
        return MicrosoftTokens(access, refresh)
    }

    /** From a Microsoft token to the Minecraft account: Xbox Live, XSTS, Minecraft's sign-in, then the profile. */
    fun minecraft(microsoftToken: String, http: Transport = Web, now: Long = System.currentTimeMillis()): Profile {
        val user = xbox(http, XBOX_USER, """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":${quote("d=$microsoftToken")}},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}""")
        val xsts = xbox(http, XBOX_XSTS, """{"Properties":{"SandboxId":"RETAIL","UserTokens":[${quote(user.token)}]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}""")
        val login = send(http, "POST", MINECRAFT_LOGIN, mapOf("Accept" to "application/json"), "application/json",
            """{"identityToken":${quote("XBL3.0 x=${xsts.hash};${xsts.token}")}}""", "Minecraft")
        if (login.status == 429) throw Failure(Reason.RATE_LIMITED, "Minecraft's sign-in is busy. Try again in a minute")
        val body = parse(login.body)
        val token = body?.string("access_token")
        if (login.status != 200 || token.isNullOrEmpty()) throw Failure(Reason.OTHER, "Minecraft's sign-in failed" + detail(body?.string("error") ?: "", login.status))
        val expires = now + (body.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 86400L) * 1000L
        return profile(token, expires, http)
    }

    /**
     * The Minecraft profile of the account [accessToken] is for. Also the way to tell whether a saved token still
     * works: a token Minecraft no longer accepts is a [Reason.SIGN_IN_AGAIN] failure.
     */
    fun profile(accessToken: String, expiresAt: Long, http: Transport = Web): Profile {
        val res = send(http, "GET", MINECRAFT_PROFILE, mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json"), null, null, "Minecraft")
        if (res.status == 401) throw Failure(Reason.SIGN_IN_AGAIN, "Minecraft no longer accepts this sign-in")
        if (res.status == 404) throw Failure(Reason.NO_MINECRAFT, "This account doesn't own Minecraft: Java Edition")
        if (res.status == 429) throw Failure(Reason.RATE_LIMITED, "Minecraft's services are busy. Try again in a minute")
        val json = parse(res.body)
        val id = json?.string("id")
        val name = json?.string("name")
        if (res.status != 200 || id.isNullOrEmpty() || name.isNullOrEmpty()) throw Failure(Reason.OTHER, "Couldn't read the Minecraft profile" + detail("", res.status))
        val uuid = uuid(id) ?: throw Failure(Reason.OTHER, "Couldn't read the Minecraft profile")
        val skin = json.get("skins")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { s -> s.isJsonObject }?.asJsonObject }
            ?.firstOrNull { it.string("state") == "ACTIVE" }
        return Profile(uuid, name, accessToken, expiresAt, skin?.string("url") ?: "", skin?.string("variant").equals("SLIM", ignoreCase = true))
    }

    private class XboxToken(val token: String, val hash: String)

    private fun xbox(http: Transport, url: String, body: String): XboxToken {
        val res = send(http, "POST", url, mapOf("Accept" to "application/json", "x-xbl-contract-version" to "1"), "application/json", body, "Xbox Live")
        val json = parse(res.body)
        if (res.status == 401) {
            // XSTS says why an account can't use Xbox Live.
            throw Failure(Reason.XBOX, when (json?.get("XErr")?.takeIf { it.isJsonPrimitive }?.asLong) {
                2148916233L -> "This Microsoft account has no Xbox profile yet. Sign in at xbox.com once, then try again"
                2148916235L -> "Xbox Live isn't available in this account's country"
                2148916236L, 2148916237L -> "This account needs adult verification on xbox.com first"
                2148916238L -> "This is a child account: an adult has to add it to a Microsoft family first"
                else -> "Xbox Live refused this account"
            })
        }
        val token = json?.string("Token")
        val hash = json?.get("DisplayClaims")?.takeIf { it.isJsonObject }?.asJsonObject?.get("xui")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.string("uhs")
        if (res.status != 200 || token.isNullOrEmpty() || hash.isNullOrEmpty()) throw Failure(Reason.OTHER, "Xbox Live's sign-in failed" + detail("", res.status))
        return XboxToken(token, hash)
    }

    private fun send(http: Transport, method: String, url: String, headers: Map<String, String>, contentType: String?, body: String?, who: String): Response =
        try {
            http.send(method, url, headers, contentType, body)
        } catch (e: IOException) {
            throw Failure(Reason.OFFLINE, "Couldn't reach $who. Check your connection")
        }

    /** A service's short error code when it gave one (nothing longer is repeated: only codes, never what it was sent), else the status. */
    private fun detail(error: String, status: Int) = if (CODE.matches(error)) " ($error)" else " (HTTP $status)"

    private val CODE = Regex("[A-Za-z0-9_.]{1,48}")

    private fun parse(body: String): JsonObject? = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** [text] as a JSON string. */
    private fun quote(text: String): String = com.google.gson.JsonPrimitive(text).toString()

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }

    /** A profile id as Minecraft's services write it (32 hex digits, or with dashes). */
    fun uuid(id: String): UUID? {
        val hex = id.replace("-", "")
        if (hex.length != 32) return null
        return try {
            UUID(java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(hex.substring(16), 16))
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** The real network. */
    object Web : Transport {
        private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

        override fun send(method: String, url: String, headers: Map<String, String>, contentType: String?, body: String?): Response {
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "Horizon")
            for ((k, v) in headers) req.header(k, v)
            if (contentType != null) req.header("Content-Type", contentType)
            req.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
            return try {
                val res = client.send(req.build(), HttpResponse.BodyHandlers.ofString())
                Response(res.statusCode(), res.body())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted")
            }
        }
    }
}
