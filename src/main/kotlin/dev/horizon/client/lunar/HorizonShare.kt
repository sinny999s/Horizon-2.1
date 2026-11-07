package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.horizon.client.HorizonClient
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PrivateKey
import java.security.Signature
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Not in the original: Horizon users see each other's Lunar locker picks, client-side cosmetics included, through
 * Horizon's own small service (`share-worker/`, a Cloudflare Worker at [LunarSettings.shareUrl]). Lunar players and
 * everyone else see nothing of it.
 * - Signing in: the game's chat-signing key for your account (Mojang signs its public half, the "player certificate")
 *   signs a fresh challenge; the service checks Mojang's signature and the challenge's, so only you can set your picks.
 *   Nothing secret leaves the game (the certificate is what every server you chat on sees). It hands back a token for a
 *   day. (Asking Mojang's hasJoined from the service doesn't work: Mojang answers 403 to Cloudflare Workers.)
 * - Yours: with `share-picks` on, your locker picks go up when they change (and every few hours); off, they're taken down.
 * - Theirs: players around you on a server are looked up (64 at a time, each again after five minutes); a Horizon
 *   user's picks are drawn on them instead of what Lunar says they wear ([LunarCosmetics]).
 * All network work runs on its own thread.
 */
object HorizonShare {
    /** Where the deployed `share-worker` is (set once it's deployed; `share-url` in lunar.hzn overrides it). */
    const val DEFAULT_URL = "https://horizon-share.devynh869.workers.dev"

    private const val RECHECK_MS = 5 * 60_000L
    private const val REUPLOAD_MS = 4 * 3600_000L
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "Horizon-Share").apply { isDaemon = true } }
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    /** Horizon users' shared picks (empty: not a Horizon user, or nothing shared), and when each was looked up. */
    private val shared = ConcurrentHashMap<UUID, List<Int>>()
    private val checked = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenUntil = 0L

    @Volatile
    private var tokenAccount: UUID? = null

    @Volatile
    private var uploaded: List<Int>? = null
    private var uploadedAt = 0L

    @Volatile
    private var busy = false
    private var failedUntil = 0L
    private var tick = 0

    private val url get() = LunarSettings.shareUrl.value.trim().trimEnd('/')

    /** A Horizon user's shared picks for [uuid], or null. */
    fun picks(uuid: UUID): List<Int>? = shared[uuid]?.takeIf { it.isNotEmpty() }

    fun clear() {
        shared.clear()
        checked.clear()
    }

    /** Every tick (game thread). */
    fun tick() {
        if (url.isEmpty() || busy || System.currentTimeMillis() < failedUntil) return
        if (++tick % 20 != 0) return
        val level = mc.level ?: return
        val conn = mc.connection ?: return
        val local = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
        // Only on servers (and against a local test service).
        if (conn.connection.isMemoryConnection && !local) return
        val me = mc.user.profileId
        if (tokenAccount != null && tokenAccount != me) {
            token = null
            uploaded = null
        }
        // Yours, when they changed (or now and then, so they don't expire).
        val mine = if (LunarSettings.sharePicks.value) LunarLocker.equippedIds() else emptyList()
        val now = System.currentTimeMillis()
        if (mine != uploaded || (mine.isNotEmpty() && now - uploadedAt > REUPLOAD_MS)) {
            run { upload(mine) }
            return
        }
        // Theirs.
        if (!LunarSettings.cosmetics.value) return
        val ask = level.players().asSequence().map { it.uuid }.filter { it != me && now - (checked[it] ?: 0) > RECHECK_MS }.take(64).toList()
        if (ask.isNotEmpty()) {
            for (u in ask) checked[u] = now
            run { lookup(ask) }
        }
        // Forget players gone.
        if (tick % 1200 == 0) {
            val here = level.players().map { it.uuid }.toHashSet()
            shared.keys.removeIf { it !in here }
            checked.keys.removeIf { it !in here }
        }
    }

    private fun run(job: () -> Unit) {
        busy = true
        pool.execute {
            try {
                job()
            } catch (e: Exception) {
                failedUntil = System.currentTimeMillis() + 60_000
                HorizonClient.LOG.warn("Horizon share: {}", e.toString())
            } finally {
                busy = false
            }
        }
    }

    private fun post(path: String, body: JsonObject): JsonObject {
        val res = http.send(HttpRequest.newBuilder(URI(url + path)).timeout(Duration.ofSeconds(15)).header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
        val out = try {
            JsonParser.parseString(res.body()).asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }
        if (res.statusCode() == 401) token = null
        check(res.statusCode() == 200) { "$path returned ${res.statusCode()}: ${out.get("error")?.asString ?: res.body().take(80)}" }
        return out
    }

    /** A token for your account (signing in with Mojang when there's none or it's old). Pool thread. */
    private fun token(): String {
        val t = token
        if (t != null && System.currentTimeMillis() < tokenUntil) return t
        val user = mc.user
        val pair = try {
            mc.profileKeyPairManager.prepareKeyPair().get(15, TimeUnit.SECONDS).orElse(null)
        } catch (e: Exception) {
            null
        }
        val body = if (pair != null) {
            val cert = pair.publicKey().data()
            authBody(user.profileId, cert.expiresAt().toEpochMilli(), cert.key().encoded, cert.keySignature(), pair.privateKey(),
                System.currentTimeMillis())
        } else {
            // An offline (dev) account has no Mojang key; a local test service may take it by uuid.
            if (!url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) error("no Mojang chat key for this account")
            JsonObject().apply { addProperty("uuid", user.profileId.toString()) }
        }
        val out = post("/auth", body)
        val nt = out.get("token")?.asString ?: error("no token")
        token = nt
        tokenUntil = System.currentTimeMillis() + 20 * 3600_000L
        tokenAccount = user.profileId
        return nt
    }

    /**
     * /auth's body: the account's player certificate ([keyDer], Mojang's [keySignature] of it, [expiresAt]) and the
     * challenge `horizon-share auth <uuid> <time>` signed with [privateKey] (SHA256withRSA, as the game signs chat).
     * Game-free (share-worker/src/cert.js checks it).
     */
    fun authBody(uuid: UUID, expiresAt: Long, keyDer: ByteArray, keySignature: ByteArray, privateKey: PrivateKey, time: Long): JsonObject {
        val plainUuid = uuid.toString().replace("-", "")
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update("horizon-share auth $plainUuid $time".toByteArray(Charsets.UTF_8))
        }.sign()
        val b64 = Base64.getEncoder()
        return JsonObject().apply {
            addProperty("uuid", plainUuid)
            addProperty("expiresAt", expiresAt)
            addProperty("key", b64.encodeToString(keyDer))
            addProperty("keySignature", b64.encodeToString(keySignature))
            addProperty("time", time)
            addProperty("signature", b64.encodeToString(sig))
        }
    }

    private fun upload(ids: List<Int>) {
        val body = JsonObject()
        body.addProperty("token", token())
        body.add("picks", JsonArray().also { a -> ids.forEach { a.add(it) } })
        post("/picks", body)
        uploaded = ids
        uploadedAt = System.currentTimeMillis()
        HorizonClient.LOG.info("Horizon share: {} picks shared", ids.size)
    }

    private fun lookup(uuids: List<UUID>) {
        val body = JsonObject()
        body.addProperty("token", token())
        body.add("uuids", JsonArray().also { a -> uuids.forEach { a.add(it.toString().replace("-", "")) } })
        val got = post("/lookup", body).getAsJsonObject("picks") ?: JsonObject()
        for (u in uuids) {
            val list = got.getAsJsonArray(u.toString().replace("-", ""))?.mapNotNull { it.asInt.takeIf { n -> n > 0 } } ?: emptyList()
            shared[u] = list
        }
        val found = uuids.count { shared[it]?.isNotEmpty() == true }
        if (found > 0) HorizonClient.LOG.info("Horizon share: {} Horizon users' picks", found)
    }
}
