package dev.horizon.client.lunar

/**
 * Not in the original: what a real Lunar Client says about itself, read from Lunar Client 2.23.14-2645's own code
 * (`lunar.jar` and `genesis` in `~/.lunarclient/offline/multiver`, 2026-11-05), where it differs from Moonlight:
 * - the brand is "lunarclient:v<version>" (Moonlight left out the "v");
 * - the Minecraft version is Lunar's lowercase id for the version actually played ([VERSIONS]), in the Apollo handshake
 *   and the websocket handshake alike (Moonlight always sent 1.8, and "V1_8" to the websocket);
 * - its version message is the build's branch, full commit and "v<version>" (Moonlight's websocket sent "dev", a short
 *   commit and no "v");
 * - channels are only an answer to the server's: Lunar registers those of [LUNAR_CHANNELS] the server registered, and
 *   sends the Apollo handshake only to a server that registered one of [HANDSHAKE_CHANNELS] (Moonlight registered and
 *   shook hands on every server, plus `transfer:channel`, which Lunar doesn't use).
 * Game-free, so it's tested on its own.
 */
object LunarProfile {
    /** Every Minecraft version Lunar Client runs on, by its id (its version enum, in order). */
    val VERSIONS = listOf(
        "v1_7", "v1_8", "v1_9", "v1_10", "v1_11", "v1_12", "v1_16_1", "v1_16_5", "v1_17_0", "v1_17_1", "v1_18_1", "v1_18_2",
        "v1_19_0", "v1_19_2", "v1_19_3", "v1_19_4", "v1_20_0", "v1_20_1", "v1_20_2", "v1_20_3", "v1_20_4", "v1_20_5", "v1_20_6",
        "v1_21_0", "v1_21_1", "v1_21_2", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_7", "v1_21_8", "v1_21_9", "v1_21_10",
        "v1_21_11", "v26_1", "v26_1_1", "v26_1_2", "v26_2", "v26_3",
    )

    /** Lunar's channels, in its order. */
    val LUNAR_CHANNELS = listOf("lunar:apollo", "apollo:json", "lunarclient:pm", "badlion:timers")

    /** A server that registers one of these gets Lunar's Apollo handshake. */
    val HANDSHAKE_CHANNELS = setOf("lunar:apollo", "apollo:json", "lunarclient:pm")

    /**
     * Lunar's id for a Minecraft version name, as Minecraft or ViaVersion writes it ("26.3", "1.21.11", "1.8.x",
     * "1.7.6-1.7.10"), or null when Lunar doesn't run on that version (then nothing is claimed). Lunar's 1.7 to 1.12
     * cover their whole minor version ("v1_8" is any 1.8.x); later ones are exact, with "_0" for a first release.
     */
    fun versionId(name: String): String? {
        // Releases only: ViaVersion's betas, alphas and classic ("b1.7.3", "a1.2.6", "c0.30") aren't Lunar's.
        if (name.firstOrNull()?.isDigit() != true) return null
        val parts = Regex("\\d+").findAll(name.substringBefore('-')).map { it.value }.take(3).toList()
        if (parts.size < 2) return null
        val (major, minor) = parts
        val patch = parts.getOrNull(2)
        val candidates = buildList {
            if (patch != null) add("v${major}_${minor}_$patch") else add("v${major}_${minor}_0")
            add("v${major}_$minor")
        }
        return candidates.firstOrNull { it in VERSIONS }
    }

    /** Lunar's version as it writes it everywhere: "v2.23.14-2645". */
    fun semver(version: String) = if (version.startsWith("v")) version else "v$version"

    /** Lunar's client brand: "lunarclient:v2.23.14-2645". */
    fun brand(version: String) = "lunarclient:${semver(version)}"

    /** The channels Lunar registers in answer to a server that has registered [server] (empty: none). */
    fun channelsFor(server: Collection<String>): List<String> = LUNAR_CHANNELS.filter { it in server }

    /** Whether a server that has registered [server] gets the Apollo handshake. */
    fun wantsHandshake(server: Collection<String>) = server.any { it in HANDSHAKE_CHANNELS }
}
