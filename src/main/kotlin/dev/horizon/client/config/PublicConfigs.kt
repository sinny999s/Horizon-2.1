package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import dev.horizon.client.lunar.HorizonShare
import dev.horizon.client.module.Config
import dev.horizon.client.module.Modules
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Public configs: Horizon users publish their module configs, and browse, load, star and report each other's
 * ([dev.horizon.client.gui.modern.PublicConfigsScreen]). They live on the Horizon service the locker sharing uses
 * (`share-worker/`, its `configs.js`), and you sign in the same way ([HorizonShare.signIn]: your Mojang-signed chat
 * key; nothing secret leaves the game). Browsing needs no sign-in.
 *
 * What goes up and what comes down is cleaned by [PublicConfigData]: settings only, nothing personal. Loading one
 * makes it a config of your own (named after it) and switches to it; your keybinds and personal settings stay yours.
 * Network work runs on its own thread, and every result is handed back on the game thread.
 */
object PublicConfigs {
    @Serializable
    data class Author(val uuid: String = "", val name: String = "")

    @Serializable
    data class Meta(
        val id: String,
        val name: String,
        val description: String = "",
        val tags: List<String> = emptyList(),
        val author: Author = Author(),
        val downloads: Int = 0,
        val likes: Int = 0,
        val enabled: List<String> = emptyList(),
        val settings: Int = 0,
        val size: Int = 0,
        val client: String = "",
        val mc: String = "",
        val created: Long = 0,
        val updated: Long = 0,
        val liked: Boolean = false,
        val mine: Boolean = false,
        /** Only for admins: reports, whether it's hidden, and whether its author is banned. */
        val reports: Int? = null,
        val hidden: Boolean? = null,
        val authorBanned: Boolean? = null,
    )

    @Serializable
    private class Page(val configs: List<Meta> = emptyList(), val more: Boolean = false, val admin: Boolean = false)

    @Serializable
    class Data(val modules: List<HolderEntry> = emptyList())

    @Serializable
    private class Got(val config: Meta, val data: Data = Data())

    @Serializable
    private class Published(val config: Meta)

    @Serializable
    private class Liked(val likes: Int = 0, val liked: Boolean = false)

    /** The service's tags, in the order the browser shows them. */
    val TAGS = listOf("bedwars", "hypixel", "anarchy", "pvp", "crystal", "legit", "blatant", "movement", "render", "utility")
    const val MAX_TAGS = 4

    enum class Sort(val key: String, val label: String) {
        POPULAR("popular", "Popular"),
        TOP("top", "Most starred"),
        RECENT("recent", "Recent"),
    }

    private val mc get() = Minecraft.getInstance()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "Horizon-Configs").apply { isDaemon = true } }
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    // ---- the browser's list (game thread) ----

    var query = ""
    var tag: String? = null
    var sort = Sort.POPULAR
    var mine = false

    /** Only the reported and hidden ones, and banned authors' (admins). */
    var reported = false
    val configs = ArrayList<Meta>()

    /** You moderate public configs (the service says so with each list). */
    var admin = false
        private set
    var more = false
        private set
    var loading = false
        private set
    var error: String? = null
        private set
    private var page = 0
    private var generation = 0

    /** The list again from its first page, as [query], [tag], [sort] and [mine] say. */
    fun refresh() {
        page = 0
        fetch(true)
    }

    /** The next page of the list, when there is one. */
    fun loadMore() {
        if (!more || loading) return
        page++
        fetch(false)
    }

    private fun fetch(reset: Boolean) {
        val gen = ++generation
        loading = true
        error = null
        val mine = mine
        val reported = reported && admin
        val body = buildJsonObject {
            put("q", query.trim())
            tag?.let { put("tag", it) }
            put("sort", sort.key)
            put("page", page)
            put("mine", mine)
            if (reported) put("reported", true)
        }
        val sign = if (mine || reported) Sign.ALWAYS else Sign.IF_SIGNED_IN
        async({ json.decodeFromJsonElement<Page>(post("/configs/list", body, sign)) }) { r ->
            if (gen != generation) return@async
            loading = false
            r.onSuccess { p ->
                admin = p.admin
                if (reset) configs.clear()
                val have = configs.mapTo(HashSet()) { it.id }
                configs += p.configs.filter { it.id !in have }
                more = p.more
            }.onFailure {
                if (reset) configs.clear()
                more = false
                error = message(it)
            }
        }
    }

    /** [meta] replaced in the list (after a star, say). */
    private fun replace(meta: Meta) {
        val i = configs.indexOfFirst { it.id == meta.id }
        if (i >= 0) configs[i] = meta
    }

    // ---- one config ----

    /** [meta]'s settings (this counts as a download), cleaned. */
    fun fetchData(meta: Meta, done: (Result<Pair<Meta, List<HolderEntry>>>) -> Unit) {
        val body = buildJsonObject { put("id", meta.id) }
        async({
            val got = json.decodeFromJsonElement<Got>(post("/configs/get", body, Sign.IF_SIGNED_IN))
            got.config to got.data.modules
        }) { r ->
            r.onSuccess { (m, entries) -> replace(m); done(Result.success(m to PublicConfigData.clean(entries, Modules.all))) }
                .onFailure { done(Result.failure(it)) }
        }
    }

    /**
     * Loads [meta] ([entries] from [fetchData]) as a config of your own and switches to it; the id it got. Game thread.
     * Loading the same public config again replaces the copy it made before.
     */
    fun adopt(meta: Meta, entries: List<HolderEntry>): String {
        Config.snapshot(Config.currentName)
        val origins = origins()
        val taken = Config.list.mapTo(HashSet()) { it.id }
        val id = PublicConfigData.localId(meta.name, meta.id, taken) { origins[it] == meta.id }
        val holders = PublicConfigData.adopt(entries, Modules.all)
        Config.list.removeIf { it.id == id }
        Config.list += NamedConfig(id, holders)
        origins[id] = meta.id
        saveOrigins(origins)
        Configs.load(id, ConfigType.MODULES)
        Config.writeAll()
        return id
    }

    /** How many of your live settings [entries] would change. */
    fun changes(entries: List<HolderEntry>): Int = PublicConfigData.changes(entries, Modules.all)

    // ---- yours ----

    /** Publishes your current config's live settings as [name] (updating yours of that name). */
    fun publish(name: String, description: String, tags: List<String>, done: (Result<Meta>) -> Unit) {
        Config.snapshot(Config.currentName)
        val data = Data(PublicConfigData.live(Modules.all))
        val version = FabricLoader.getInstance().getModContainer(HorizonClient.ID).map { it.metadata.version.friendlyString }.orElse("")
        val body = buildJsonObject {
            put("name", name.trim())
            put("description", description.trim())
            put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
            put("data", json.encodeToJsonElement(data))
            put("author", mc.user.name)
            put("client", version)
            put("mc", SharedConstants.getCurrentVersion().name())
        }
        async({ json.decodeFromJsonElement<Published>(post("/configs/publish", body, Sign.ALWAYS)).config }) { r ->
            r.onSuccess { m ->
                configs.removeIf { it.id == m.id }
                configs.add(0, m)
            }
            done(r)
        }
    }

    /** Stars [meta] (or takes your star back). */
    fun star(meta: Meta, on: Boolean, done: (Result<Meta>) -> Unit) {
        val body = buildJsonObject {
            put("id", meta.id)
            put("like", on)
        }
        async({ json.decodeFromJsonElement<Liked>(post("/configs/like", body, Sign.ALWAYS)) }) { r ->
            r.onSuccess { l ->
                val m = meta.copy(likes = l.likes, liked = l.liked)
                replace(m)
                done(Result.success(m))
            }.onFailure { done(Result.failure(it)) }
        }
    }

    fun report(meta: Meta, reason: String, done: (Result<Unit>) -> Unit) {
        val body = buildJsonObject {
            put("id", meta.id)
            put("reason", reason)
        }
        async({ post("/configs/report", body, Sign.ALWAYS); Unit }, done)
    }

    /** Takes [meta] down: yours, or anyone's for an admin (a copy someone loaded stays theirs). */
    fun delete(meta: Meta, done: (Result<Unit>) -> Unit) {
        val body = buildJsonObject { put("id", meta.id) }
        async({ post("/configs/delete", body, Sign.ALWAYS); Unit }) { r ->
            r.onSuccess { configs.removeIf { it.id == meta.id } }
            done(r)
        }
    }

    // ---- moderating (admins) ----

    /** Hides [meta] from everyone but its author and admins, or shows it again. */
    fun hide(meta: Meta, hidden: Boolean, done: (Result<Meta>) -> Unit) {
        val body = buildJsonObject {
            put("id", meta.id)
            put("hidden", hidden)
        }
        async({ post("/configs/hide", body, Sign.ALWAYS); Unit }) { r ->
            r.onSuccess {
                val m = meta.copy(hidden = hidden)
                replace(m)
                done(Result.success(m))
            }.onFailure { done(Result.failure(it)) }
        }
    }

    /** Bans [meta]'s author (they can't publish, and their configs are hidden), or lifts the ban. */
    fun ban(meta: Meta, banned: Boolean, done: (Result<Meta>) -> Unit) {
        val body = buildJsonObject {
            put("uuid", meta.author.uuid)
            put("banned", banned)
        }
        async({ post("/configs/ban", body, Sign.ALWAYS); Unit }) { r ->
            r.onSuccess {
                for (i in configs.indices) if (configs[i].author.uuid == meta.author.uuid) configs[i] = configs[i].copy(authorBanned = banned)
                done(Result.success(meta.copy(authorBanned = banned)))
            }.onFailure { done(Result.failure(it)) }
        }
    }

    // ---- plumbing ----

    private enum class Sign { NEVER, IF_SIGNED_IN, ALWAYS }

    private class Refused(message: String) : Exception(message)

    /** Runs [job] on the network thread and [done] with its result on the game thread. */
    private fun <T> async(job: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(job)
            r.exceptionOrNull()?.let { if (it !is Refused) HorizonClient.LOG.warn("Public configs: {}", it.toString()) }
            mc.execute { done(r) }
        }
    }

    /** What to tell the player about [t]. */
    fun message(t: Throwable): String = when {
        t is Refused -> t.message ?: "refused"
        t.message?.contains("chat key") == true -> "Sign-in needs a Microsoft account (an offline one can only browse)"
        t is java.net.ConnectException || t is java.net.http.HttpTimeoutException -> "Can't reach the config service"
        else -> "Something went wrong (see the log)"
    }

    private fun post(path: String, body: JsonObject, sign: Sign): JsonElement {
        val url = HorizonShare.serviceUrl
        if (url.isEmpty()) throw Refused("The config service is turned off (share-url in lunar.hzn)")
        val token = when (sign) {
            Sign.ALWAYS -> HorizonShare.signIn()
            Sign.IF_SIGNED_IN -> HorizonShare.currentToken()
            Sign.NEVER -> null
        }
        val full = if (token == null) body else JsonObject(body + ("token" to JsonPrimitive(token)))
        val res = http.send(HttpRequest.newBuilder(URI(url + path)).timeout(Duration.ofSeconds(20)).header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(full.toString())).build(), HttpResponse.BodyHandlers.ofString())
        val out = try {
            json.parseToJsonElement(res.body())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        if (res.statusCode() != 200) {
            val err = (out as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
            throw Refused(err?.replaceFirstChar { it.uppercase() } ?: "The config service answered ${res.statusCode()}")
        }
        return out
    }

    /** Which of your configs came from which public one (your config's id to the public id), so a reload replaces it. */
    private fun origins(): MutableMap<String, String> =
        Storage.read(Storage.root.resolve("public-configs.hzn"), MapSerializer(String.serializer(), String.serializer()))?.toMutableMap() ?: HashMap()

    private fun saveOrigins(m: Map<String, String>) {
        val live = Config.list.mapTo(HashSet()) { it.id }
        Storage.write(Storage.root.resolve("public-configs.hzn"), MapSerializer(String.serializer(), String.serializer()), m.filterKeys { it in live })
    }
}
