package dev.horizon.client.lunar

import com.google.gson.JsonParser
import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Not in the original: Lunar's cosmetic files, from Lunar's public texture CDN as Lunar Client gets them. Lunar's
 * launcher API names the current asset index (`textures.jitIndexUrl`, every file's path and sha1) and the CDN's base
 * URL; a file is `baseUrl + sha1`. No sign-in: anyone can fetch these. Each file is kept under Storage's
 * `lunar-cosmetics` folder by its sha1 (checked on download), so a cosmetic is fetched once. Network work runs on
 * [pool], never the game thread.
 */
object LunarAssets {
    private const val DEFAULT_BASE = "https://textures.lunarclientcdn.com/file/"
    private const val COSMETICS_INDEX = "assets/lunar/cosmetics/index"
    private const val COSMETICS_JSON = "assets/lunar/cosmetics.json"
    private const val RETRY_MS = 60_000L

    /** Two threads, daemon: fetching and decoding cosmetics. */
    val pool: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "Horizon-LunarAssets").apply { isDaemon = true } }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    @Volatile
    private var base = DEFAULT_BASE

    /** Asset path -> sha1, once the index is in. */
    @Volatile
    var assets: Map<String, String>? = null
        private set

    /** Cosmetic id -> its line in the cosmetics index, once it's in. */
    @Volatile
    var index: Map<Int, LunarCosmeticIndex.Entry>? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var loading = false
    private var retryAt = 0L

    private val dir get() = Storage.dir("lunar-cosmetics")

    /** Starts loading the indexes unless they're in, loading, or failed less than a minute ago. */
    fun ensure() {
        if (index != null || loading || System.currentTimeMillis() < retryAt) return
        loading = true
        pool.execute {
            try {
                val list = LunarCosmeticIndex.parseAssets(String(assetIndex(), Charsets.UTF_8))
                assets = list
                // cosmetics.json is the list Lunar Client loads (its CosmeticManager reads nothing else). The CSV index is
                // a leftover that stopped in January 2026: read only when cosmetics.json can't be.
                val parsed = try {
                    val sha = list[COSMETICS_JSON] ?: error("no cosmetics.json in Lunar's asset index")
                    LunarCosmeticIndex.parseCosmetics(String(bySha(sha), Charsets.UTF_8)).also { check(it.isNotEmpty()) { "cosmetics.json is empty" } }
                } catch (e: Exception) {
                    val sha = list[COSMETICS_INDEX] ?: throw e
                    HorizonClient.LOG.warn("Lunar cosmetics: {}, so the older index", e.toString())
                    LunarCosmeticIndex.parseIndex(String(bySha(sha), Charsets.UTF_8))
                }
                index = parsed
                HorizonClient.LOG.info("Lunar cosmetics: {} cosmetics, {} files in Lunar's index", index?.size, list.size)
                lastError = null
            } catch (e: Exception) {
                lastError = e.toString()
                retryAt = System.currentTimeMillis() + RETRY_MS
                HorizonClient.LOG.warn("Lunar cosmetics: couldn't load Lunar's index: {}", e.toString())
            } finally {
                loading = false
            }
        }
    }

    /**
     * The asset index named by Lunar's launcher API, kept by its sha1. When the API can't be reached, the newest one
     * kept from before.
     */
    private fun assetIndex(): ByteArray {
        try {
            val res = http.send(HttpRequest.newBuilder(URI(LunarJar.LAUNCH_API)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", LunarJar.USER_AGENT).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(LunarJar.launchBody(LunarWire.os(), LunarWire.arch(), UUID.randomUUID(),
                    System.getProperty("os.version", "10.0")))).build(), HttpResponse.BodyHandlers.ofString())
            check(res.statusCode() == 200) { "launch API returned ${res.statusCode()}" }
            val tex = JsonParser.parseString(res.body()).asJsonObject.getAsJsonObject("textures") ?: error("no textures in the launch API's answer")
            tex.get("baseUrl")?.asString?.let { base = it }
            val url = tex.get("jitIndexUrl")?.asString ?: tex.get("indexUrl")?.asString ?: error("no texture index in the launch API's answer")
            val sha = (tex.get("jitIndexSha1") ?: tex.get("indexSha1"))?.asString
            val file = dir.resolve("index-${sha ?: "latest"}.txt")
            if (Files.exists(file)) return Files.readAllBytes(file)
            val bytes = get(url)
            if (sha != null) check(sha1(bytes) == sha) { "the asset index doesn't match its sha1" }
            Files.write(file, bytes)
            return bytes
        } catch (e: Exception) {
            val kept = Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("index-") }.max(compareBy { Files.getLastModifiedTime(it) }).orElse(null) }
                ?: throw e
            HorizonClient.LOG.warn("Lunar cosmetics: using the asset index kept from before ({})", e.toString())
            return Files.readAllBytes(kept)
        }
    }

    /** The file at [path] in Lunar's asset index (blocking: call it on [pool]). Throws when it isn't listed or fails. */
    fun file(path: String): ByteArray {
        val sha = assets?.let { a -> a[path] ?: a[renamed(path)] } ?: error("$path isn't in Lunar's asset index")
        return bySha(sha)
    }

    /** Whether [path] is in the asset index. */
    fun has(path: String) = assets?.let { it.containsKey(path) || it.containsKey(renamed(path)) } == true

    /** Newer cloak files carry a `_cloak` suffix the cosmetics index doesn't have yet (`x.webp` → `x_cloak.webp`). */
    private fun renamed(path: String): String {
        val dot = path.indexOf(".webp")
        return if (dot < 0 || "/cloaks/" !in path) path else path.substring(0, dot) + "_cloak" + path.substring(dot)
    }

    private fun bySha(sha: String): ByteArray {
        val file = dir.resolve(sha)
        if (Files.exists(file)) return Files.readAllBytes(file)
        val bytes = get(base + sha)
        check(sha1(bytes) == sha) { "a download from Lunar's CDN doesn't match its sha1" }
        Files.write(file, bytes)
        return bytes
    }

    /** The CDN refuses requests without a client's user agent (403). */
    private fun get(url: String): ByteArray {
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", LunarJar.USER_AGENT).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
        check(res.statusCode() == 200) { "$url returned ${res.statusCode()}" }
        return res.body()
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
