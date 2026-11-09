package dev.horizon.client.lunar

/**
 * What a real Lunar Client says about itself, read from Lunar Client 2.23.14-2645's own code
 * (`lunar.jar`, `modern` and `genesis` in `~/.lunarclient/offline/multiver`, 2026-11-05) and Lunar's launcher API
 * (2026-11-08), where it differs from Moonlight:
 * - the brand is "lunarclient:v<version>" (Moonlight left out the "v"). On 1.16.5 and later Lunar's `modern` jar
 *   overwrites `ClientBrandRetriever.getClientModName` and adds ",fabric" when Lunar runs with its Fabric module (the
 *   launcher's default there, with Fabric API in its base modpack): "lunarclient:v<version>,fabric" ([brand]);
 * - the Minecraft version is Lunar's lowercase id for the version actually played ([VERSIONS]), in the Apollo handshake
 *   and the websocket handshake alike (Moonlight always sent 1.8, and "V1_8" to the websocket);
 * - its version message is the build's branch, full commit and "v<version>" (Moonlight's websocket sent "dev", a short
 *   commit and no "v");
 * - channels are only an answer to the server's: Lunar registers those of [LUNAR_CHANNELS] the server registered, and
 *   sends the Apollo handshake only to a server that registered one of [HANDSHAKE_CHANNELS] (Moonlight registered and
 *   shook hands on every server, plus `transfer:channel`, which Lunar doesn't use).
 * The launcher hands every Minecraft version the same `lunar.jar`, so the Lunar version, channels and handshake are the
 * same on all of them; only the version id (and on 1.16.5+, the Fabric module) differ. Game-free, so it's tested on its own.
 */
object LunarProfile {
    /** Every Minecraft version Lunar Client runs on, by its id (its version enum, in order). */
    val VERSIONS = listOf(
        "v1_7", "v1_8", "v1_9", "v1_10", "v1_11", "v1_12", "v1_16_1", "v1_16_5", "v1_17_0", "v1_17_1", "v1_18_1", "v1_18_2",
        "v1_19_0", "v1_19_2", "v1_19_3", "v1_19_4", "v1_20_0", "v1_20_1", "v1_20_2", "v1_20_3", "v1_20_4", "v1_20_5", "v1_20_6",
        "v1_21_0", "v1_21_1", "v1_21_2", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_7", "v1_21_8", "v1_21_9", "v1_21_10",
        "v1_21_11", "v26_1", "v26_1_1", "v26_1_2", "v26_2", "v26_3",
    )

    /**
     * The versions Lunar's launcher offers to play (`/launcher/metadata/versions/lunar`, 2026-11-08): 1.7.10, 1.8.9, 1.12.2,
     * 1.16.5, 1.17.1, 1.18.1 and on (no 1.19.1 or 1.21.2). Only these are claimed: the rest of [VERSIONS] (1.9 to 1.11,
     * 1.16.1, 1.17, 1.21.2) are in Lunar's code but can't be launched, so no real Lunar player is on them.
     */
    val OFFERED = listOf(
        "v1_7", "v1_8", "v1_12", "v1_16_5", "v1_17_1", "v1_18_1", "v1_18_2", "v1_19_0", "v1_19_2", "v1_19_3", "v1_19_4",
        "v1_20_0", "v1_20_1", "v1_20_2", "v1_20_3", "v1_20_4", "v1_20_5", "v1_20_6", "v1_21_0", "v1_21_1", "v1_21_3", "v1_21_4",
        "v1_21_5", "v1_21_6", "v1_21_7", "v1_21_8", "v1_21_9", "v1_21_10", "v1_21_11", "v26_1", "v26_1_1", "v26_1_2", "v26_2", "v26_3",
    )

    /**
     * Those the launcher offers with Lunar's Fabric module, its default for them: all but 1.7, 1.8 and 1.12 (Lunar or
     * Forge), 1.18.1 and 1.19 (Lunar only).
     */
    val FABRIC_MODULE = OFFERED - setOf("v1_7", "v1_8", "v1_12", "v1_18_1", "v1_19_0")

    /** Lunar's channels, in its order. */
    val LUNAR_CHANNELS = listOf("lunar:apollo", "apollo:json", "lunarclient:pm", "badlion:timers")

    /** A server that registers one of these gets Lunar's Apollo handshake. */
    val HANDSHAKE_CHANNELS = setOf("lunar:apollo", "apollo:json", "lunarclient:pm")

    private val NUMBER = Regex("\\d+")

    /**
     * Lunar's id for a Minecraft version name, as Minecraft or ViaVersion writes it ("26.3", "1.21.11", "1.8.x",
     * "26.1-26.1.2"), or null when Lunar's launcher doesn't offer that version ([OFFERED]; then nothing is claimed).
     * Lunar's 1.7 to 1.12 cover their whole minor version ("v1_8" is any 1.8.x); later ones are exact, with "_0" for a
     * first 1.x release. A ViaVersion range is one protocol, so any of its versions fits: the newest one the launcher
     * offers is claimed ("26.1-26.1.2" is 26.1.2, "1.21.2-1.21.3" is 1.21.3).
     */
    fun versionId(name: String): String? {
        // Releases only: ViaVersion's betas, alphas and classic ("b1.7.3", "a1.2.6", "c0.30") aren't Lunar's.
        if (name.firstOrNull()?.isDigit() != true) return null
        val ends = name.split('-').map { end -> NUMBER.findAll(end).map { it.value.toInt() }.take(3).toList() }
        val first = ends.first()
        val last = ends.last()
        if (first.size < 2 || last.size < 2) return null
        val (major, minor) = last
        val newest = last.getOrElse(2) { 0 }
        val oldest = if (first[0] == major && first[1] == minor) minOf(first.getOrElse(2) { 0 }, newest) else newest
        val candidates = buildList {
            for (patch in newest downTo oldest) {
                add("v${major}_${minor}_$patch")
                // 26.x's first releases have no "_0" ("v26_1").
                if (patch == 0) add("v${major}_$minor")
            }
            add("v${major}_$minor")
        }
        return candidates.firstOrNull { it in OFFERED }
    }

    /** A version id as people write the version: "v1_21_0" is "1.21", "v26_1_2" is "26.1.2", "v1_8" is "1.8". */
    fun versionName(id: String): String = id.removePrefix("v").removeSuffix("_0").replace('_', '.')

    /** Lunar's version as it writes it everywhere: "v2.23.14-2645". */
    fun semver(version: String) = if (version.startsWith("v")) version else "v$version"

    /**
     * Lunar's client brand: "lunarclient:v2.23.14-2645", and with [fabric] (Lunar running its Fabric module)
     * "lunarclient:v2.23.14-2645,fabric".
     */
    fun brand(version: String, fabric: Boolean = false) = "lunarclient:${semver(version)}" + if (fabric) ",fabric" else ""

    /** The channels Lunar registers in answer to a server that has registered [server] (empty: none). */
    fun channelsFor(server: Collection<String>): List<String> = LUNAR_CHANNELS.filter { it in server }

    /** Whether a server that has registered [server] gets the Apollo handshake. */
    fun wantsHandshake(server: Collection<String>) = server.any { it in HANDSHAKE_CHANNELS }
}
