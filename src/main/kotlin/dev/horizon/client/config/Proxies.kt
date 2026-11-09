package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import io.netty.channel.ChannelHandler
import io.netty.handler.proxy.Socks4ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/** A proxy; [ping] is -1 when never pinged or failed, -2 while pinging. */
@Serializable
data class Proxy(
    var id: String,
    var ip: String,
    var port: Int,
    var user: String,
    var password: String,
    var socks4: Boolean,
    @Transient var ping: Int = -1,
)

@Serializable
private data class ProxiesFile(val proxies: List<Proxy>)

/**
 * Proxies, saved in `proxy.hzn`. The selected one (not saved) is put first in the pipeline of every
 * connection to a server. Pings measure the TCP connect to the proxy.
 */
object Proxies {
    val list = ArrayList<Proxy>()

    /** The proxy for the next connections; deleting it doesn't clear it (on purpose). */
    var selected: Proxy? = null

    private val file: Path get() = Storage.root.resolve("proxy.hzn")
    private var ticks = 0

    fun load() {
        list.clear()
        try {
            if (Files.exists(file) && Files.size(file) > 0) list.addAll(Storage.json.decodeFromString(ProxiesFile.serializer(), Files.readString(file)).proxies)
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Proxies are not loaded from file for some reasons.")
        }
    }

    fun save() = Storage.write(file, ProxiesFile.serializer(), ProxiesFile(list.toList()))

    /**
     * Every proxy to -2, then one thread each. Proxy i waits 2000·i ms before connecting, and the
     * time is taken before that wait, so it reports about 2000·i ms too much (on purpose).
     */
    fun pingAll() {
        for (p in list) p.ping = -2
        for ((i, p) in list.toList().withIndex()) {
            Thread {
                val start = System.currentTimeMillis()
                Thread.sleep(2000L * i)
                p.ping = try {
                    Socket().use { it.connect(InetSocketAddress(p.ip, p.port), 8000) }
                    (System.currentTimeMillis() - start).toInt()
                } catch (e: Exception) {
                    -1
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** Pings one proxy at once (the new Windows screen's Test), without [pingAll]'s 2 s steps. */
    fun ping(p: Proxy) {
        p.ping = -2
        Thread {
            val start = System.currentTimeMillis()
            p.ping = try {
                Socket().use { it.connect(InetSocketAddress(p.ip, p.port), 8000) }
                (System.currentTimeMillis() - start).toInt()
            } catch (e: Exception) {
                -1
            }
        }.apply { isDaemon = true }.start()
    }

    /** Every player tick: pings every 30 s while the Windows screen is open (the count runs while it's closed). */
    fun tick() {
        ticks++
        val screen = Minecraft.getInstance().gui.screen()
        if (ticks >= 600 && screen === dev.horizon.client.gui.modern.ModernWindows) {
            ticks = 0
            pingAll()
        }
    }

    /** The SOCKS handler for a new server connection, or null. */
    fun handler(): ChannelHandler? {
        val p = selected ?: return null
        val address = InetSocketAddress(p.ip, p.port)
        return if (p.socks4) Socks4ProxyHandler(address, p.user) else Socks5ProxyHandler(address, p.user, p.password)
    }

    /** A dropped `.txt` ("host:port:user:pass" per line) or `.hzn` proxy list; false when it couldn't be read. */
    fun import(f: Path): Boolean {
        val name = f.fileName.toString()
        try {
            if (name.contains(".txt")) {
                Files.newBufferedReader(f).use { r ->
                    while (r.ready()) {
                        val parts = r.readLine().split(":").dropLastWhile { it.isEmpty() }
                        list += Proxy("proxy_" + (Random.nextFloat() * 10000).toInt(), parts[0], parts[1].toIntOrNull() ?: 80, parts[2], parts[3], false)
                    }
                }
            }
            if (name.contains(".hzn")) list.addAll(Storage.json.decodeFromString(ProxiesFile.serializer(), Files.readString(f)).proxies)
            return true
        } catch (e: Exception) {
            Minecraft.getInstance().player?.sendSystemMessage(Component.translatable("gui.proxy.import-error"))
            return false
        }
    }
}
