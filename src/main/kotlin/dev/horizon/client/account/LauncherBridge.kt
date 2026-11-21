package dev.horizon.client.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * The accounts signed in to the Horizon Client launcher, for a game it started. The launcher keeps the sign-ins (in
 * Windows' account broker) and is the only one that can renew them, so the game asks it: the launcher gives a game it
 * starts an address on this computer and a key for this run of the launcher (`HORIZON_LAUNCHER_BRIDGE` and
 * `HORIZON_LAUNCHER_BRIDGE_KEY`), and answers with its accounts and, for one of them, a Minecraft token it has just
 * checked (renewing it quietly first when it has run out).
 *
 * Without those two (another launcher, or an older Horizon Client) there is nothing to ask. Everything here blocks.
 */
object LauncherBridge {
    class Account(val id: String, val uuid: UUID, val name: String, val skin: String, val signedOut: Boolean)

    class Token(val uuid: UUID, val name: String, val accessToken: String)

    private val base: String? = System.getenv("HORIZON_LAUNCHER_BRIDGE")?.trim()?.trimEnd('/')?.takeIf(::local)
    private val key: String? = System.getenv("HORIZON_LAUNCHER_BRIDGE_KEY")?.takeIf { it.isNotBlank() }

    /** Whether this game was started by a launcher that shares its accounts. */
    val available: Boolean get() = base != null && key != null

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build() }

    /** The key is only ever sent to this computer. */
    private fun local(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "http" && (u.host == "127.0.0.1" || u.host == "localhost" || u.host == "[::1]")
    } catch (_: Exception) {
        false
    }

    /** The launcher's Minecraft accounts, in its order. */
    fun accounts(): List<Account> {
        val json = call("GET", "", null, Duration.ofSeconds(5))
        val list = json.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val uuid = o.string("uuid")?.let(MicrosoftAuth::uuid) ?: return@mapNotNull null
            Account(o.string("id") ?: return@mapNotNull null, uuid, o.string("name") ?: return@mapNotNull null, o.string("skin") ?: "",
                o.get("signedOut")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
        }
    }

    /** A Minecraft token for the launcher's account [id]. It may sign the account in again first, which takes a few seconds. */
    fun token(id: String): Token {
        val json = call("POST", "/token", JsonObject().apply { addProperty("id", id) }.toString(), Duration.ofSeconds(60))
        val uuid = json.string("uuid")?.let(MicrosoftAuth::uuid)
        val name = json.string("name")
        val token = json.string("accessToken")
        if (uuid == null || name.isNullOrEmpty() || token.isNullOrEmpty()) throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, "The launcher gave no token")
        return Token(uuid, name, token)
    }

    private fun call(method: String, path: String, body: String?, timeout: Duration): JsonObject {
        val url = base ?: throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.OFFLINE, "The launcher isn't sharing its accounts")
        val req = HttpRequest.newBuilder(URI(url + path)).timeout(timeout).header("Authorization", "Bearer $key").header("Accept", "application/json")
        if (body != null) req.header("Content-Type", "application/json")
        req.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        val res = try {
            http.send(req.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.OFFLINE, "The launcher isn't running")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.CANCELLED, "Cancelled")
        }
        val json = try {
            JsonParser.parseString(res.body()).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
        if (res.statusCode() == 200 && json != null) return json
        // The launcher says what went wrong in "error", and in words in "message".
        val message = json?.string("message")
        throw when (json?.string("error")) {
            "sign_in_required" -> MicrosoftAuth.Failure(MicrosoftAuth.Reason.SIGN_IN_AGAIN, message ?: "Sign in to this account again in the launcher")
            "rate_limited" -> MicrosoftAuth.Failure(MicrosoftAuth.Reason.RATE_LIMITED, message ?: "Minecraft's sign-in is busy. Try again in a minute")
            "offline" -> MicrosoftAuth.Failure(MicrosoftAuth.Reason.OFFLINE, message ?: "The launcher couldn't reach Microsoft")
            else -> MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, message ?: "The launcher couldn't do that (HTTP ${res.statusCode()})")
        }
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
