package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import kotlinx.serialization.Serializable

/**
 * Not in the original: the Lunar Client version Lunar Spoof claims to be, after Moonlight's `MoonlightConfig`. Kept in
 * `horizon/lunar-spoof/version.json` with when it was looked up, and looked up again ([LunarJar]) once it's a day old.
 * The version is Lunar's own build data (`lunarBuildData.txt` in its `lunar.jar`), which is the same jar for every
 * Minecraft version Lunar runs on.
 */
object LunarVersion {
    @Serializable
    data class Cache(
        var lunarVersion: String? = null,
        var lunarGitCommit: String? = null,
        /** The build's branch ("master" for Lunar's releases). */
        var lunarGitBranch: String? = null,
        var fetchedAt: Long = 0,
    )

    private val file get() = Storage.dir("lunar-spoof").resolve("version.json")
    private var cache: Cache? = null

    @Volatile
    private var fetching = false

    /** Set when the last lookup failed (shown in chat once). */
    @Volatile
    var lastError: String? = null

    private fun cache(): Cache = synchronized(this) {
        cache ?: (Storage.read(file, Cache.serializer()) ?: Cache()).also { cache = it }
    }

    val version: String? get() = cache().lunarVersion
    val gitCommit: String? get() = cache().lunarGitCommit
    val gitBranch: String get() = cache().lunarGitBranch ?: "master"

    /** Lunar's brand for its client: "lunarclient:v<version>". */
    val brand: String? get() = version?.let(LunarProfile::brand)

    /** Unknown, or a day old. */
    val stale: Boolean get() = cache().let { it.lunarVersion == null || it.lunarGitCommit == null || System.currentTimeMillis() - it.fetchedAt > STALE_MS }

    /** Not before this (ms) after a failed lookup, so a dead API isn't asked every tick. */
    @Volatile
    private var retryAt = 0L

    /** Stale, not being looked up, and not just failed. */
    val due: Boolean get() = stale && !fetching && System.currentTimeMillis() >= retryAt

    /** Looks the version up on a background thread (unless already looking); [done] runs there afterwards. */
    fun fetchAsync(done: (LunarJar.Build?) -> Unit = {}) {
        if (fetching) return
        fetching = true
        retryAt = System.currentTimeMillis() + RETRY_MS
        Thread({
            val build = try {
                LunarJar.fetch().also { b ->
                    synchronized(this) {
                        val c = cache()
                        c.lunarVersion = b.version
                        c.lunarGitCommit = b.gitCommit
                        c.lunarGitBranch = b.gitBranch
                        c.fetchedAt = System.currentTimeMillis()
                        Storage.write(file, Cache.serializer(), c)
                    }
                    lastError = null
                    HorizonClient.LOG.info("Lunar Spoof: Lunar Client {} ({})", b.version, b.gitCommit.take(7))
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                HorizonClient.LOG.warn("Lunar Spoof: couldn't look up Lunar's version: {}", lastError)
                null
            } finally {
                fetching = false
            }
            done(build)
        }, "Horizon-LunarVersion").apply { isDaemon = true }.start()
    }

    private const val STALE_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 5 * 60 * 1000L
}
