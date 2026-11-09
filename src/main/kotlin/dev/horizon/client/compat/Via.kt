package dev.horizon.client.compat

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.Connection
import java.lang.reflect.Method

/**
 * ViaFabricPlus, when it's installed next to the client: the Minecraft version
 * servers are joined as, for all servers and per server. Reached by reflection through its public API, so the client
 * neither needs nor bundles it (it's GPL; this only calls it). Versions are ViaVersion `ProtocolVersion`s, held as
 * [Any]. Without it, [present] is false and nothing else here is used.
 */
object Via {
    private val installed = FabricLoader.getInstance().isModLoaded("viafabricplus")
    private var ready = false
    private var broken = false
    private var translation: Any? = null
    private lateinit var target: Method
    private var connTarget: Method? = null
    private lateinit var setTarget: Method
    private lateinit var serverVersion: Method
    private lateinit var setServerVersion: Method
    private lateinit var reversed: Method
    private lateinit var name: Method
    private lateinit var protocol: Method
    private lateinit var known: Method
    private var olderOrEqual: Method? = null
    private var v1_8: Any? = null
    private var versionType: Method? = null

    /** "Auto detect": the version is found by pinging the server first. */
    var autoDetect: Any? = null
        private set

    /** ViaFabricPlus is installed and its API answered. */
    val present: Boolean get() = installed && load()

    @Synchronized
    private fun load(): Boolean {
        if (ready || broken) return ready
        try {
            val loader = Via::class.java.classLoader
            val api = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus", true, loader).getMethod("api").invoke(null)
            val apiType = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusAPI", true, loader)
            translation = apiType.getMethod("protocolTranslation").invoke(api)
            val pt = Class.forName("com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation", true, loader)
            val pv = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion", true, loader)
            autoDetect = pt.getField("AUTO_DETECT_VERSION").get(null)
            target = pt.getMethod("targetVersion")
            connTarget = runCatching { pt.getMethod("targetVersion", Connection::class.java) }.getOrNull()
            setTarget = pt.getMethod("setTargetVersion", pv)
            serverVersion = pt.getMethod("serverVersion", ServerData::class.java)
            setServerVersion = pt.getMethod("setServerVersion", ServerData::class.java, pv)
            reversed = pv.getMethod("getReversedProtocols")
            name = pv.getMethod("getName")
            protocol = pv.getMethod("getVersion")
            known = pv.getMethod("isKnown")
            olderOrEqual = runCatching { pv.getMethod("olderThanOrEqualTo", pv) }.getOrNull()
            v1_8 = runCatching { pv.getField("v1_8").get(null) }.getOrNull()
            // (Without it every version counts as a release.)
            versionType = runCatching { pv.getMethod("getVersionType") }.getOrNull()
            ready = translation != null
        } catch (_: ReflectiveOperationException) {
            broken = true
        } catch (_: RuntimeException) {
            broken = true
        } catch (_: LinkageError) {
            broken = true
        }
        return ready
    }

    private fun call(m: Method, on: Any?, vararg args: Any?): Any? = try {
        m.invoke(on, *args)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Every version it can join as, newest first. */
    fun versions(): List<Any> {
        if (!present) return emptyList()
        val all = call(reversed, null) as? List<*> ?: return emptyList()
        return all.filterNotNull().filter { it !== autoDetect && call(known, it) == true }
    }

    /** The version servers without their own are joined as. */
    fun target(): Any? = if (present) call(target, translation) else null

    fun setTarget(version: Any) {
        if (present) call(setTarget, translation, version)
    }

    /** The version this server is always joined as, or null for [target]. */
    fun server(data: ServerData): Any? = if (present) call(serverVersion, translation, data) else null

    /** Sets (or with null clears) this server's own version; save the server list after. */
    fun setServer(data: ServerData, version: Any?) {
        if (present) call(setServerVersion, translation, data, version)
    }

    fun name(version: Any?): String = when {
        version == null -> ""
        version === autoDetect -> "Auto detect"
        else -> call(name, version) as? String ?: version.toString()
    }

    /** The protocol number a server on this version answers a ping with. */
    fun protocol(version: Any): Int = call(protocol, version) as? Int ?: -1

    /** ViaVersion's kind of version (RELEASE, RELEASE_INITIAL, BETA_..., ALPHA_..., CLASSIC, SPECIAL). */
    fun type(version: Any): String = versionType?.let { call(it, version)?.toString() } ?: "RELEASE"

    /** The active connection's protocol version while online, else the chosen join target (null when Auto detect). */
    fun activeVersion(): Any? {
        if (!present) return null
        val conn = Minecraft.getInstance().connection?.connection
        val ct = connTarget
        if (conn != null && ct != null) {
            val v = call(ct, translation, conn)
            if (v != null && v !== autoDetect) return v
        }
        val t = target()
        return if (t !== autoDetect) t else null
    }

    /**
     * The version name [conn] is joined as ("1.8.x", "26.3"...), or null without ViaFabricPlus, or when it doesn't
     * know (then the game's own version is the one).
     */
    fun versionName(conn: Connection): String? {
        if (!present) return null
        val ct = connTarget ?: return null
        val v = call(ct, translation, conn) ?: return null
        return if (v === autoDetect) null else name(v)
    }

    /** Connected (or set to join) on 1.8.x or older, before 1.9's attack cooldown. */
    fun isLegacyCombat(): Boolean {
        if (!present) return false
        val v = activeVersion() ?: return false
        val ref = v1_8
        val cmp = olderOrEqual
        if (ref != null && cmp != null) return call(cmp, v, ref) == true
        val p = protocol(v)
        return p in 0..47 && type(v) != "SPECIAL"
    }
}
