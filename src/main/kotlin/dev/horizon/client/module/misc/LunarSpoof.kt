package dev.horizon.client.module.misc

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.compat.Via
import dev.horizon.client.lunar.LunarProfile
import dev.horizon.client.lunar.LunarVersion
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.SharedConstants
import net.minecraft.network.Connection

/**
 * Makes the client pass for Lunar Client on servers, after Moonlight (afterlike's Forge 1.8.9 mod),
 * for servers that treat Lunar players differently (Hypixel lets Lunar through where it's picky about other clients;
 * Apollo servers enable Lunar's features). Off singleplayer only, and corrected to what Lunar Client itself sends
 * ([dev.horizon.client.lunar.LunarProfile]). It claims Lunar for the Minecraft version the server is joined as
 * (ViaFabricPlus's, else 26.3), for every version Lunar's launcher offers: Lunar 1.8 through ViaFabricPlus on 1.8.9,
 * Lunar 1.21.11, 26.1.2 (ViaFabricPlus's 26.1-26.1.2) or 26.2 through it, Lunar 26.3 natively, and so on down to 1.16.5.
 * On a version Lunar doesn't run on, nothing is claimed. Each part is its own setting:
 * - [brand]: the client brand sent on login is "lunarclient:v<version>" instead of "fabric" (`misc2/LunarBrandMixin`),
 *   or with [fabricModule] "lunarclient:v<version>,fabric", as Lunar running its Fabric module sends on 1.16.5 and later.
 * - [apollo]: as Lunar does, once the server registers Lunar's channels, the same ones are registered back and Lunar's
 *   Apollo handshake is sent; the server's pings are answered and its transfer requests refused
 *   ([dev.horizon.client.lunar.LunarApollo]). A server that registers none gets nothing. The Lunar mods its settings turn
 *   off are said in chat; the ClickGUI's Lunar menu lists everything it asks.
 * Only what the server sees is here. Lunar's own services (logos and badges, friends, cosmetics, the locker) are built
 * in and set from the ClickGUI's Lunar menu ([dev.horizon.client.lunar.LunarSettings]); they work with this module off.
 * The Lunar version claimed is looked up from Lunar's launcher API and kept for a day ([LunarVersion]); it's the same
 * build on every Minecraft version.
 */
class LunarSpoof : Module("lunar-spoof", Categories.MISC, listOf("lunar", "moonlight", "lunar-client")) {
    /** The login brand is Lunar's. Applies from the next login. */
    val brand = bool("brand", true)

    /**
     * The brand is Lunar's with its Fabric module, the launcher's default on 1.16.5 and later (Fabric API's channels,
     * which this client has too, are what a server sees from that Lunar): ",fabric" is added there. Off: Lunar without
     * Fabric. 1.8 is always plain Lunar. Applies from the next login.
     */
    val fabricModule = bool("fabric-module", false) { brand.value }

    /** Lunar's channels and Apollo handshake, and its answers. Applies from the next join. */
    val apollo = bool("apollo", true)

    /** A chat line on joining (the version claimed) and when an Apollo server turns Lunar mods off. */
    val notify = bool("notify", true)

    init {
        instance = this
        action("refresh-version") {
            LunarVersion.fetchAsync { b ->
                moduleMessage(name.trim(), if (b != null) "Lunar Client ${b.version} (${b.gitCommit.take(7)})" else "couldn't look it up: ${LunarVersion.lastError}")
            }
        }
    }

    override fun onEnable() {
        if (LunarVersion.version == null) {
            moduleMessage(name.trim(), "looking up Lunar's current version")
            LunarVersion.fetchAsync { b ->
                moduleMessage(name.trim(), if (b != null) "will claim Lunar Client ${b.version} from the next login"
                    else "couldn't look up Lunar's version (${LunarVersion.lastError}); nothing is claimed until it's known")
            }
        }
    }

    companion object {
        var instance: LunarSpoof? = null
            private set

        /**
         * Lunar's id for the Minecraft version [conn] is joined as ("v1_8", "v26_1_2", "v26_3"...): ViaFabricPlus's version
         * for it, else the game's own. Null when Lunar doesn't run on that version.
         */
        @JvmStatic
        fun versionId(conn: Connection): String? =
            LunarProfile.versionId(Via.versionName(conn) ?: SharedConstants.getCurrentVersion().name())

        /** Whether the brand claimed for Lunar's [versionId] is its Fabric module's. */
        fun fabricBrand(versionId: String): Boolean {
            val m = instance ?: return false
            return m.fabricModule.value && versionId in LunarProfile.FABRIC_MODULE
        }

        /**
         * The brand to log in with: Lunar's, or null to keep the real one (off, singleplayer, a version Lunar doesn't run
         * on, or no Lunar version known yet).
         */
        @JvmStatic
        fun brandFor(conn: Connection): String? {
            val m = instance ?: return null
            if (!m.enabled || !m.brand.value || conn.isMemoryConnection) return null
            val id = versionId(conn) ?: return null
            val version = LunarVersion.version ?: return null
            return LunarProfile.brand(version, fabricBrand(id))
        }
    }
}
