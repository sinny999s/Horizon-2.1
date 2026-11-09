package dev.horizon.client

import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import net.minecraft.client.multiplayer.resolver.AddressCheck
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.resources.Identifier
import java.net.IDN
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Joining 6b6t and the other big anarchy servers Mojang's blocklist keeps out, as
 * the 6b6t team's AnarchyMod does (MIT licence, github.com/6b6t/AnarchyMod). Their domains pass the blocklist check, the
 * list is kept up to date from 6b6t.org, and on joining one of them the client sends the same empty `anarchymod:join`
 * message AnarchyMod does (6b6t counts players with the mod by it).
 */
object AnarchyServers {
    private val DEFAULT = setOf(
        "*.6b6t.org", "*.10b10t.org", "*.6b6t.cc", "*.6b6t.me", "*.7b7t.me", "*.8b8t.org", "*.8b8t.xyz",
        "*.alacity.net", "*.anarchypvp.pw", "*.l2x9.org", "*.simpleanarchy.org",
    )
    private const val LIST_URL = "https://www.6b6t.org/api/anarchy-mod.json"
    private val JOIN = Identifier.fromNamespaceAndPath("anarchymod", "join")

    @Volatile
    private var domains: Set<String> = DEFAULT
    private val fetched = AtomicBoolean()

    /** Fetch the current list once (in the background; the built-in one is used until then, or if it fails). */
    fun init() {
        if (!fetched.compareAndSet(false, true)) return
        try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val req = HttpRequest.newBuilder(URI.create(LIST_URL)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").GET().build()
            client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { res ->
                val body = res.body()
                if (res.statusCode() != 200 || body == null || body.length > 1_048_576) return@thenAccept
                val list = JsonParser.parseString(body).asJsonObject.getAsJsonArray("domains") ?: return@thenAccept
                val next = HashSet(DEFAULT)
                for ((n, e) in list.withIndex()) {
                    if (n > 4096) break
                    if (!e.isJsonPrimitive) continue
                    pattern(e.asString)?.let { next += it }
                }
                domains = next.toSet()
            }.exceptionally { null }
        } catch (_: RuntimeException) {
            // The built-in list still works.
        }
    }

    /** An address (host, host:port or [ipv6]:port) on one of the anarchy domains. */
    @JvmStatic
    fun contains(address: String?): Boolean {
        val host = host(address) ?: return false
        return domains.any { entry ->
            if (entry.startsWith("*.")) {
                val base = entry.substring(2)
                host == base || host.endsWith(".$base")
            } else {
                host == entry
            }
        }
    }

    /** Mojang's blocklist check, letting the anarchy domains through. */
    @JvmStatic
    fun unblock(blocklist: AddressCheck): AddressCheck = object : AddressCheck {
        override fun isAllowed(address: ResolvedServerAddress): Boolean = contains(address.hostName) || blocklist.isAllowed(address)

        override fun isAllowed(address: ServerAddress): Boolean = contains(address.host) || blocklist.isAllowed(address)
    }

    /** The empty message AnarchyMod sends on joining one of these servers. */
    @JvmStatic
    fun joinMessage(): ServerboundCustomPayloadPacket {
        val payload = DiscardedPayload.codec<FriendlyByteBuf>(JOIN, 0).decode(FriendlyByteBuf(Unpooled.EMPTY_BUFFER))
        return ServerboundCustomPayloadPacket(payload)
    }

    private fun pattern(entry: String?): String? {
        val s = entry?.trim() ?: return null
        val wild = s.startsWith("*.")
        val host = host(if (wild) s.substring(2) else s) ?: return null
        return (if (wild) "*." else "") + host
    }

    /** The lower-case ASCII host name: no port, brackets or trailing dot. */
    private fun host(address: String?): String? {
        var h = address?.trim() ?: return null
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end < 0) return null
            h = h.substring(1, end)
        } else if (h.indexOf(':') == h.lastIndexOf(':') && h.indexOf(':') >= 0) {
            h = h.substring(0, h.indexOf(':'))
        }
        h = h.trimEnd('.')
        if (h.isEmpty()) return null
        if (h.indexOf(':') >= 0) return h.lowercase(Locale.ROOT)
        return try {
            val ascii = IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            if (ascii.isEmpty() || ascii.length > 253) null else ascii
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
