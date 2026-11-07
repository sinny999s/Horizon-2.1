package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.render.Textures
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Not in the original: pictures Lunar names by URL (stickers in Lunar chats), fetched once on [LunarAssets.pool] and
 * kept as textures. Only https URLs on Lunar's own hosts are fetched.
 */
object LunarImages {
    private val HOSTS = listOf("lunarclient.com", "lunarclientcdn.com", "lunarclientprod.com")

    private object Loading
    private object Failed

    private val images = ConcurrentHashMap<String, Any>()
    private val decoded = ConcurrentLinkedQueue<Pair<String, BufferedImage>>()
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    /** Whether [url] may be fetched: https on one of Lunar's hosts. Game-free. */
    fun allowed(url: String): Boolean {
        val u = try { URI(url) } catch (e: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** [url]'s picture once it's in (starts fetching it); null while loading or when it can't be had. Game/render thread. */
    fun get(url: String): Identifier? {
        while (true) {
            val (u, img) = decoded.poll() ?: break
            val id = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/lunar/url/" + Integer.toHexString(u.hashCode()))
            Minecraft.getInstance().textureManager.register(id, Textures.upload("lunar $u", img))
            images[u] = id
        }
        when (val v = images[url]) {
            is Identifier -> return v
            null -> {}
            else -> return null
        }
        if (!allowed(url)) {
            images[url] = Failed
            return null
        }
        images[url] = Loading
        LunarAssets.pool.execute {
            try {
                val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", LunarJar.USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                check(res.statusCode() == 200) { "returned ${res.statusCode()}" }
                val bytes = res.body()
                val img = if (bytes.size > 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP") WebP.decode(bytes)
                else javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: error("not a picture")
                decoded += url to img
            } catch (e: Exception) {
                images[url] = Failed
                HorizonClient.LOG.warn("Lunar: couldn't load {}: {}", url, e.toString())
            }
        }
        return null
    }
}
