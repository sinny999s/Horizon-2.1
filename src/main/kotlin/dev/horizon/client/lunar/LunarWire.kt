package dev.horizon.client.lunar

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/**
 * Not in the original: the messages of Lunar's own services (the authenticator and the game websocket), hand-encoded
 * protobuf as Moonlight's `AuthClient` and `WSClient` write and read them (Lunar publishes no schema for these). Field
 * numbers are Moonlight's. Game-free: [LunarNetwork] does the connecting.
 */
object LunarWire {
    const val INITIATOR = "assetServer"
    const val COSMETIC_SERVICE = "lunarclient.websocket.cosmetic.v2.CosmeticService"
    const val HEARTBEAT_SERVICE = "lunarclient.websocket.heartbeat.v1.HeartbeatService"
    const val SUBSCRIPTION_SERVICE = "lunarclient.websocket.subscription.v1.SubscriptionService"
    const val BADGE_SERVICE = "lunarclient.websocket.badge.v1.BadgeService"
    const val COSMETICS_PUSH = "lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2"

    /**
     * A Lunar tab logo: whose, its colour (ARGB, alpha 0 read as opaque), the Lunar+ colour (opaque ARGB; 0 for a
     * player without Lunar+) and badge (0: none).
     */
    data class TabLogo(val uuid: UUID, val argb: Int, val badge: Int, val plus: Int = 0)

    // ---- authenticator ----

    /** A serverbound auth message: [inner] as field [field] (1 hello, 2 encryption response, 3 encryption failure). */
    fun authServerbound(field: Int, inner: ByteArray): ByteArray = message { it.writeBytes(field, ByteString.copyFrom(inner)) }

    /** Hello: who's asking (UUID and name), and the [INITIATOR]. */
    fun hello(uuid: UUID, name: String): ByteArray {
        val identity = message {
            it.writeBytes(1, ByteString.copyFrom(uuid(uuid)))
            it.writeString(2, name)
        }
        return message {
            it.writeBytes(1, ByteString.copyFrom(identity))
            it.writeString(2, INITIATOR)
        }
    }

    fun encryptionResponse(secretKey: ByteArray, verifyToken: ByteArray): ByteArray = message {
        it.writeBytes(1, ByteString.copyFrom(secretKey))
        it.writeBytes(2, ByteString.copyFrom(verifyToken))
    }

    fun encryptionFailure(reason: String): ByteArray = message { it.writeString(1, reason) }

    /** A clientbound auth message: which (1 encryption request, 2 success) and what it holds. */
    fun authClientbound(bytes: ByteArray): Pair<Int, ByteArray> {
        val input = CodedInputStream.newInstance(bytes)
        val tag = input.readTag()
        return (tag ushr 3) to input.readBytes().toByteArray()
    }

    /** The authenticator's encryption request: its public key (X.509) and the bytes to send back encrypted. */
    fun encryptionRequest(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        var key: ByteArray? = null
        var random: ByteArray? = null
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> key = input.readBytes().toByteArray()
                2 -> random = input.readBytes().toByteArray()
                else -> input.skipField(tag)
            }
        }
        val k = key ?: return null
        val r = random ?: return null
        return k to r
    }

    /**
     * The token in the authenticator's success message, `AuthSuccessMessage { string jwt = 1; bytes encrypted_jwt = 2 }`
     * (from Lunar Client's own schema, in its lunar.jar). Moonlight read `jwt`; Lunar now sends `encrypted_jwt` instead:
     * AES-GCM under the [secret] key sent in the encryption response, the first 12 bytes its IV, as Lunar Client itself
     * decrypts it. Null when there's neither (or it doesn't decrypt).
     */
    fun authToken(success: ByteArray, secret: ByteArray?): String? {
        var jwt: String? = null
        var encrypted: ByteArray? = null
        fields(success) { field, input, tag ->
            when (field) {
                1 -> jwt = input.readString()
                2 -> encrypted = input.readBytes().toByteArray()
                else -> input.skipField(tag)
            }
        }
        jwt?.takeIf { it.isNotEmpty() }?.let { return it }
        val data = encrypted ?: return null
        if (secret == null || data.size <= 12) return null
        return try {
            decryptToken(data, secret)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    /** Lunar Client's token decryption: AES/GCM/NoPadding, 128-bit tag, IV = the first 12 bytes. */
    fun decryptToken(data: ByteArray, secret: ByteArray): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(secret, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }

    /**
     * A message's shape for the log, without its content: field numbers, wire types and lengths, nested messages
     * expanded (so a token never lands in the log). E.g. `2:{2:bytes(314)}`.
     */
    fun shape(bytes: ByteArray, depth: Int = 0): String {
        val parts = ArrayList<String>()
        try {
            fields(bytes) { field, input, tag ->
                parts += when (tag and 7) {
                    0 -> "$field:${input.readInt64()}"
                    1 -> { input.readFixed64(); "$field:fixed64" }
                    5 -> { input.readFixed32(); "$field:fixed32" }
                    2 -> {
                        val v = input.readBytes().toByteArray()
                        if (depth < 4 && v.isNotEmpty() && isMessage(v)) "$field:{${shape(v, depth + 1)}}" else "$field:bytes(${v.size})"
                    }
                    else -> { input.skipField(tag); "$field:?" }
                }
            }
        } catch (e: Exception) {
            parts += "unreadable"
        }
        return parts.joinToString(" ")
    }

    /** Parses as protobuf fields, and isn't plain printable text (which can happen to parse too). */
    private fun isMessage(bytes: ByteArray) = try {
        fields(bytes) { _, input, tag -> input.skipField(tag) }
        !bytes.all { it in 0x20..0x7e }
    } catch (e: Exception) {
        false
    }

    /** The server id Mojang's session server is told about, as for a Minecraft server: SHA-1(secret, public key) in signed hex. */
    fun serverId(secretKey: ByteArray, publicKey: ByteArray): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(secretKey)
        sha1.update(publicKey)
        return BigInteger(sha1.digest()).toString(16)
    }

    // ---- game websocket ----

    /**
     * The first message on the game websocket: identity (with the token), version, OS, arch, language and game. The game
     * part is as Lunar Client fills it: its id for the Minecraft version ([versionId], lowercase, e.g. "v26_3") and its
     * version (build branch, full commit, "v<version>"). Moonlight sent "V1_8", "dev", a short commit and no "v".
     */
    fun gameHandshake(uuid: UUID, name: String, jwt: String, versionId: String, version: String, gitBranch: String, gitCommit: String,
                      os: String, arch: String): ByteArray {
        val player = message {
            it.writeBytes(1, ByteString.copyFrom(uuid(uuid)))
            it.writeString(2, name)
        }
        val identity = message {
            it.writeBytes(1, ByteString.copyFrom(player))
            // TYPE_MICROSOFT is 2 in Lunar's schema (MinecraftIdentity.Type); Moonlight sent 1, which is TYPE_MOJANG.
            it.writeInt32(2, 2)
            it.writeString(3, jwt)
        }
        val versionMsg = message { it.writeString(1, version) }
        val lang = message {
            it.writeString(1, "eng")
            it.writeString(2, "en_US")
        }
        val mcVersion = message { it.writeString(1, versionId) }
        val lcVersion = message {
            it.writeString(1, gitBranch)
            it.writeString(2, gitCommit)
            it.writeString(3, LunarProfile.semver(version))
        }
        val game = message {
            it.writeBytes(1, ByteString.copyFrom(mcVersion))
            it.writeBytes(2, ByteString.copyFrom(lcVersion))
        }
        return message {
            it.writeBytes(1, ByteString.copyFrom(identity))
            it.writeBytes(2, ByteString.copyFrom(versionMsg))
            it.writeString(5, os)
            it.writeString(6, arch)
            it.writeBytes(7, ByteString.copyFrom(lang))
            it.writeBytes(8, ByteString.copyFrom(game))
        }
    }

    /** A call to one of Lunar's services: request id, service, method, input. */
    fun call(requestId: String, service: String, method: String, input: ByteArray): ByteArray = message {
        it.writeBytes(1, ByteString.copyFromUtf8(requestId))
        it.writeString(2, service)
        it.writeString(3, method)
        it.writeBytes(4, ByteString.copyFrom(input))
    }

    /** LoadTabLogos' input: the UUIDs to ask about. */
    fun uuidList(uuids: Collection<UUID>): ByteArray = message { out -> for (u in uuids) out.writeBytes(1, ByteString.copyFrom(uuid(u))) }

    /** The replies in a clientbound websocket frame: (request id or null, output). */
    fun replies(bytes: ByteArray): List<Pair<String?, ByteArray>> {
        val out = ArrayList<Pair<String?, ByteArray>>()
        fields(bytes) { field, input, tag ->
            if (field != 1) {
                input.skipField(tag)
                return@fields
            }
            var id: String? = null
            var output: ByteArray? = null
            fields(input.readBytes().toByteArray()) { f, rpc, t ->
                when (f) {
                    1 -> id = rpc.readBytes().toStringUtf8()
                    2 -> output = rpc.readBytes().toByteArray()
                    else -> rpc.skipField(t)
                }
            }
            output?.let { out += id to it }
        }
        return out
    }

    // ---- cosmetics (schemas from Lunar Client's own lunar.jar: subscription/v1/service.proto, cosmetic/v2/push.proto,
    // cosmetic/v2/common.proto) ----

    /** A cosmetic a player wears: its id in the cosmetics index, and [hatHeight] (EquippedCosmetic.hat_height_offset). */
    data class Equipped(val id: Int, val hatHeight: Float = 0f)

    /**
     * When a conditional set applies: one EquipCondition (a oneof), its [kind] the field number set and [value] that
     * condition's own field 1 (an enum number, or [text] for a string). Kinds, from cosmetic/v2/common.proto:
     * 1 dimension (text: dimension id), 2 weather (1 rain, 2 snow, 3 thunderstorm, 4 clear), 3 biome temperature (1 hot,
     * 2 cold), 4 passenger of entity (text: entity type id), 5 movement state (1 swimming, 2 creative flying, 3 elytra
     * flying), 6 in liquid (1 water, 2 lava), 7 radio playing, 8 on fire, 9 server (text: server address), 10 time
     * (1 day, 2 night). 0: none set (never applies).
     */
    data class Condition(val kind: Int = 0, val value: Int = 0, val text: String? = null) {
        companion object {
            const val DIMENSION = 1
            const val WEATHER = 2
            const val BIOME_TEMPERATURE = 3
            const val PASSENGER = 4
            const val MOVEMENT = 5
            const val IN_LIQUID = 6
            const val RADIO = 7
            const val ON_FIRE = 8
            const val SERVER = 9
            const val TIME = 10
        }
    }

    /**
     * What a player wears (PlayerCosmeticsPushV2): [defaults] (default_cosmetics), and [conditional] sets
     * (conditional_cosmetics) that replace them while their condition holds.
     */
    data class PlayerCosmetics(val uuid: UUID, val defaults: List<Equipped>, val conditional: List<Pair<Condition, List<Equipped>>> = emptyList(),
                               val logo: Int? = null, val plus: Int = 0, val badge: Int = 0)

    /** SubscribeV2's and Unsubscribe's input: the players to watch (target_uuids, field 1). Same shape as [uuidList]. */
    fun subscribe(uuids: Collection<UUID>): ByteArray = uuidList(uuids)

    /** SubscribeV2Response: cosmetic_pushes (field 1); its radio pushes (field 2) are skipped. */
    fun subscribeResponse(bytes: ByteArray): List<PlayerCosmetics> {
        val out = ArrayList<PlayerCosmetics>()
        fields(bytes) { field, input, tag ->
            if (field == 1) playerCosmetics(input.readBytes().toByteArray())?.let { out += it } else input.skipField(tag)
        }
        return out
    }

    /** A PlayerCosmeticsPushV2, or null without a player UUID. */
    fun playerCosmetics(bytes: ByteArray): PlayerCosmetics? {
        var uuid: UUID? = null
        val defaults = ArrayList<Equipped>()
        val conditional = ArrayList<Pair<Condition, List<Equipped>>>()
        var logo: Int? = null
        var plus = 0
        var badge = 0
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> uuid = readUuid(input.readBytes().toByteArray())
                2 -> logo = opaque(packedColor(input.readBytes().toByteArray()))
                4 -> plus = plusColor(packedColor(input.readBytes().toByteArray()))
                7 -> badge = input.readInt32()
                6 -> equipped(input.readBytes().toByteArray())?.let { defaults += it }
                5 -> {
                    var cond = Condition()
                    val set = ArrayList<Equipped>()
                    fields(input.readBytes().toByteArray()) { f, c, t ->
                        when (f) {
                            1 -> cond = condition(c.readBytes().toByteArray())
                            2 -> equipped(c.readBytes().toByteArray())?.let { set += it }
                            else -> c.skipField(t)
                        }
                    }
                    conditional += cond to set
                }
                else -> input.skipField(tag)
            }
        }
        return uuid?.let { PlayerCosmetics(it, defaults, conditional, logo, plus, badge) }
    }

    private fun equipped(bytes: ByteArray): Equipped? {
        var id: Int? = null
        var hat = 0f
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> id = input.readInt32()
                9 -> hat = input.readFloat()
                else -> input.skipField(tag)
            }
        }
        return id?.let { Equipped(it, hat) }
    }

    /** EquipCondition: whichever condition is set, with its field 1 (string for dimension, passenger and server). */
    private fun condition(bytes: ByteArray): Condition {
        var c = Condition()
        fields(bytes) { field, input, tag ->
            if (field !in 1..10) {
                input.skipField(tag)
                return@fields
            }
            var value = 0
            var text: String? = null
            fields(input.readBytes().toByteArray()) { f, s, t ->
                if (f == 1 && (t and 7) == 2) text = s.readString() else if (f == 1) value = s.readInt32() else s.skipField(t)
            }
            c = Condition(field, value, text)
        }
        return c
    }

    /** A PlayerCosmeticsPushV2 wearing [ids], plus [conditional] sets (for tests and ShotBot; Lunar's server sends these). */
    fun playerCosmeticsPush(uuid: UUID, ids: List<Int>, conditional: List<Pair<Condition, List<Int>>> = emptyList(),
                            logo: Int? = null, plus: Int = 0, badge: Int = 0): ByteArray = message { out ->
        out.writeBytes(1, ByteString.copyFrom(uuid(uuid)))
        logo?.let { c -> out.writeBytes(2, ByteString.copyFrom(message { it.writeInt32(1, c) })) }
        if (plus != 0) out.writeBytes(4, ByteString.copyFrom(message { it.writeInt32(1, plus) }))
        if (badge != 0) out.writeInt32(7, badge)
        for ((cond, set) in conditional) {
            val inner = message { m ->
                if (cond.text != null) m.writeString(1, cond.text) else if (cond.value != 0) m.writeInt32(1, cond.value)
            }
            out.writeBytes(5, ByteString.copyFrom(message { cc ->
                cc.writeBytes(1, ByteString.copyFrom(message { it.writeBytes(cond.kind, ByteString.copyFrom(inner)) }))
                for (id in set) cc.writeBytes(2, ByteString.copyFrom(message { it.writeInt32(1, id) }))
            }))
        }
        for (id in ids) out.writeBytes(6, ByteString.copyFrom(message { it.writeInt32(1, id) }))
    }

    /** UseEmotePush / StopEmotePush (emote/v1/push.proto): the player (field 1). */
    fun emotePlayer(bytes: ByteArray): UUID? {
        var u: UUID? = null
        fields(bytes) { field, input, tag -> if (field == 1) u = readUuid(input.readBytes().toByteArray()) else input.skipField(tag) }
        return u
    }

    /** UseEmotePush's emote_id (field 2). */
    fun emoteId(bytes: ByteArray): Int {
        var id = 0
        fields(bytes) { field, input, tag -> if (field == 2) id = input.readInt32() else input.skipField(tag) }
        return id
    }

    /** A UseEmotePush (or, with [id] 0, a StopEmotePush) for [uuid] (tests and ShotBot). */
    fun emotePush(uuid: UUID, id: Int): ByteArray = message { out ->
        out.writeBytes(1, ByteString.copyFrom(uuid(uuid)))
        if (id != 0) out.writeInt32(2, id)
    }

    /** The push notifications in a clientbound frame (push_notification, field 2, a google.protobuf.Any): (type URL, value). */
    fun pushes(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        fields(bytes) { field, input, tag ->
            if (field != 2) {
                input.skipField(tag)
                return@fields
            }
            var type = ""
            var value = ByteArray(0)
            try {
                fields(input.readBytes().toByteArray()) { f, any, t ->
                    when (f) {
                        1 -> type = any.readString()
                        2 -> value = any.readBytes().toByteArray()
                        else -> any.skipField(t)
                    }
                }
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                return@fields
            }
            out += type to value
        }
        return out
    }

    /** A clientbound frame holding one push of [type] (a full type name), as Lunar's server sends it. */
    fun pushFrame(type: String, value: ByteArray): ByteArray = message { out ->
        out.writeBytes(2, ByteString.copyFrom(message {
            it.writeString(1, "type.googleapis.com/$type")
            it.writeBytes(2, ByteString.copyFrom(value))
        }))
    }

    /** LoadTabLogosResponse: field 1, repeated TabLogo (1 UUID, 2 colour, 3 Lunar+ colour, 4 badge). */
    fun tabLogos(bytes: ByteArray): List<TabLogo> {
        val out = ArrayList<TabLogo>()
        fields(bytes) { field, input, tag ->
            if (field != 1) {
                input.skipField(tag)
                return@fields
            }
            var uuid: UUID? = null
            var argb = -1
            var badge = 0
            var plus = 0
            fields(input.readBytes().toByteArray()) { f, logo, t ->
                when (f) {
                    1 -> uuid = readUuid(logo.readBytes().toByteArray())
                    2 -> argb = opaque(packedColor(logo.readBytes().toByteArray()))
                    3 -> plus = plusColor(packedColor(logo.readBytes().toByteArray()))
                    4 -> badge = logo.readInt32()
                    else -> logo.skipField(t)
                }
            }
            uuid?.let { out += TabLogo(it, argb, badge, plus) }
        }
        return out
    }

    /** Your own logo colour from Login's response (field 1, a colour); null when the account has no Lunar cosmetics. */
    fun loginColor(bytes: ByteArray): Int? {
        var argb: Int? = null
        fields(bytes) { field, input, tag ->
            if (field != 1) {
                input.skipField(tag)
                return@fields
            }
            fields(input.readBytes().toByteArray()) { f, c, t -> if (f == 1) argb = opaque(c.readInt32()) else c.skipField(t) }
        }
        return argb
    }

    /** Your Lunar+ colour from Login's response (plus_color, field 3); 0 without Lunar+. */
    fun loginPlus(bytes: ByteArray): Int {
        var plus = 0
        fields(bytes) { field, input, tag -> if (field == 3) plus = plusColor(packedColor(input.readBytes().toByteArray())) else input.skipField(tag) }
        return plus
    }

    /** BadgeService.Login's answer: the badge you wear (equipped_badge_id, field 2), 0 for none. */
    fun loginBadge(bytes: ByteArray): Int {
        var badge = 0
        fields(bytes) { field, input, tag -> if (field == 2) badge = input.readInt32() else input.skipField(tag) }
        return badge
    }

    /**
     * The cosmetics the account owns, from CosmeticService.Login's response: owned_cosmetics (field 6, OwnedCosmetic
     * { int32 cosmetic_id = 1 }) and has_all_cosmetics_flag (field 7).
     */
    fun loginOwned(bytes: ByteArray): Pair<Set<Int>, Boolean> {
        val owned = HashSet<Int>()
        var all = false
        fields(bytes) { field, input, tag ->
            when (field) {
                6 -> fields(input.readBytes().toByteArray()) { f, c, t -> if (f == 1) owned += c.readInt32() else c.skipField(t) }
                7 -> all = input.readBool()
                else -> input.skipField(tag)
            }
        }
        return owned to all
    }

    // ---- outfits (cosmetic/v2/common.proto: Outfit { Uuid id = 1; name = 2; repeated EquippedCosmetic cosmetics = 3;
    // favorite = 4; created_at = 5; updated_at = 6; skin_hash = 7; badge_id = 8 }, OutfitTree { repeated
    // ConditionalOutfit conditional_outfits = 1; Uuid default_outfit_id = 2 }) ----

    /** An outfit as Lunar sent it ([raw]), its id and its cosmetics (each id with its EquippedCosmetic bytes). */
    class Outfit(val raw: ByteArray, val id: UUID?, val name: String, val cosmetics: List<Pair<Int, ByteArray>>)

    fun outfit(bytes: ByteArray): Outfit {
        var id: UUID? = null
        var name = ""
        val cosmetics = ArrayList<Pair<Int, ByteArray>>()
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> id = readUuid(input.readBytes().toByteArray())
                2 -> name = input.readString()
                3 -> {
                    val c = input.readBytes().toByteArray()
                    var cid: Int? = null
                    fields(c) { f, e, t -> if (f == 1) cid = e.readInt32() else e.skipField(t) }
                    cid?.let { cosmetics += it to c }
                }
                else -> input.skipField(tag)
            }
        }
        return Outfit(bytes, id, name, cosmetics)
    }

    /** LoginResponse's outfits (field 10) and outfit tree (field 11, raw; null when it has none). */
    fun loginOutfits(bytes: ByteArray): Pair<List<Outfit>, ByteArray?> {
        val outfits = ArrayList<Outfit>()
        var tree: ByteArray? = null
        fields(bytes) { field, input, tag ->
            when (field) {
                10 -> outfits += outfit(input.readBytes().toByteArray())
                11 -> tree = input.readBytes().toByteArray()
                else -> input.skipField(tag)
            }
        }
        return outfits to tree
    }

    /** An OutfitTree's default_outfit_id (field 2). */
    fun treeDefault(tree: ByteArray): UUID? {
        var id: UUID? = null
        fields(tree) { field, input, tag -> if (field == 2) id = readUuid(input.readBytes().toByteArray()) else input.skipField(tag) }
        return id
    }

    /** [o] with its cosmetics replaced by [cosmetics] (EquippedCosmetic bytes); every other field kept as it was. */
    fun withCosmetics(o: Outfit, cosmetics: List<ByteArray>): ByteArray = message { out ->
        copyFields(o.raw, setOf(3), out)
        for (c in cosmetics) out.writeBytes(3, ByteString.copyFrom(c))
    }

    /** An EquippedCosmetic wearing [id] with Lunar's defaults (as Lunar's locker adds one). */
    fun equippedCosmetic(id: Int): ByteArray = message { it.writeInt32(1, id) }

    /** [tree] with its default outfit set to [id] (its conditional outfits kept); a new tree when there's none. */
    fun treeWithDefault(tree: ByteArray?, id: UUID): ByteArray = message { out ->
        if (tree != null) copyFields(tree, setOf(2), out)
        out.writeBytes(2, ByteString.copyFrom(uuid(id)))
    }

    /** [o] renamed to [name]; every other field kept. */
    fun withName(o: Outfit, name: String): ByteArray = message { out ->
        copyFields(o.raw, setOf(2), out)
        out.writeString(2, name)
    }

    /** An OutfitTree: its default outfit and its conditional ones (the first whose condition holds is worn). */
    data class Tree(val default: UUID?, val rules: List<Pair<Condition, UUID>>)

    fun tree(bytes: ByteArray?): Tree {
        if (bytes == null) return Tree(null, emptyList())
        var def: UUID? = null
        val rules = ArrayList<Pair<Condition, UUID>>()
        fields(bytes) { field, input, tag ->
            when (field) {
                2 -> def = readUuid(input.readBytes().toByteArray())
                1 -> {
                    var cond = Condition()
                    var id: UUID? = null
                    fields(input.readBytes().toByteArray()) { f, c, t ->
                        when (f) {
                            1 -> cond = condition(c.readBytes().toByteArray())
                            2 -> id = readUuid(c.readBytes().toByteArray())
                            else -> c.skipField(t)
                        }
                    }
                    id?.let { rules += cond to it }
                }
                else -> input.skipField(tag)
            }
        }
        return Tree(def, rules)
    }

    /** EquipCondition for [c]: its kind's field, holding its value (field 1, a string for text kinds). */
    fun conditionBytes(c: Condition): ByteArray = message { out ->
        val inner = message { m -> if (c.text != null) m.writeString(1, c.text) else if (c.value != 0) m.writeEnum(1, c.value) }
        out.writeBytes(c.kind, ByteString.copyFrom(inner))
    }

    fun treeBytes(t: Tree): ByteArray = message { out ->
        for ((cond, id) in t.rules) out.writeBytes(1, ByteString.copyFrom(message { r ->
            r.writeBytes(1, ByteString.copyFrom(conditionBytes(cond)))
            r.writeBytes(2, ByteString.copyFrom(uuid(id)))
        }))
        t.default?.let { out.writeBytes(2, ByteString.copyFrom(uuid(it))) }
    }

    fun deleteOutfitRequest(id: UUID): ByteArray = message { it.writeBytes(1, ByteString.copyFrom(uuid(id))) }

    /** CosmeticService.Login's available_lunar_plus_colors (field 4), opaque ARGB. */
    fun loginPlusColors(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        fields(bytes) { field, input, tag -> if (field == 4) out += plusColor(packedColor(input.readBytes().toByteArray())) else input.skipField(tag) }
        return out.filter { it != 0 }
    }

    /** UpdateLunarPlusColorRequest: plus_color (field 3), RGB. */
    fun updatePlusColor(argb: Int): ByteArray = message { o -> o.writeBytes(3, ByteString.copyFrom(message { it.writeInt32(1, argb and 0xFFFFFF) })) }

    /** BadgeService.Login: the badges you own (owned_badges 1), and whether you have them all (3). */
    fun badgeLogin(bytes: ByteArray): Pair<List<Int>, Boolean> {
        val owned = ArrayList<Int>()
        var all = false
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> fields(input.readBytes().toByteArray()) { f, b, t -> if (f == 1) owned += b.readInt32() else b.skipField(t) }
                3 -> all = input.readBool()
                else -> input.skipField(tag)
            }
        }
        return owned to all
    }

    fun equipBadge(id: Int): ByteArray = message { it.writeInt32(1, id) }

    fun updateOutfitRequest(outfit: ByteArray): ByteArray = message { it.writeBytes(1, ByteString.copyFrom(outfit)) }
    fun createOutfitRequest(name: String): ByteArray = message { it.writeString(1, name) }
    fun selectOutfitRequest(tree: ByteArray): ByteArray = message { it.writeBytes(1, ByteString.copyFrom(tree)) }

    /** Create/UpdateOutfitResponse: status (1, STATUS_OK = 1) and the outfit as saved (2), or SelectOutfitResponse's status. */
    fun outfitResponse(bytes: ByteArray): Pair<Int, Outfit?> {
        var status = 0
        var o: Outfit? = null
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> status = input.readEnum()
                2 -> o = outfit(input.readBytes().toByteArray())
                else -> input.skipField(tag)
            }
        }
        return status to o
    }

    /** Copies [bytes]' fields to [out] as they are, except those in [skip]. */
    private fun copyFields(bytes: ByteArray, skip: Set<Int>, out: CodedOutputStream) {
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if ((tag ushr 3) in skip) {
                input.skipField(tag)
                continue
            }
            input.skipField(tag, out)
        }
    }

    fun uuid(u: UUID): ByteArray = message {
        it.writeFixed64(1, u.mostSignificantBits)
        it.writeFixed64(2, u.leastSignificantBits)
    }

    fun readUuid(bytes: ByteArray): UUID {
        var high = 0L
        var low = 0L
        fields(bytes) { field, input, tag ->
            when (field) {
                1 -> high = input.readFixed64()
                2 -> low = input.readFixed64()
                else -> input.skipField(tag)
            }
        }
        return UUID(high, low)
    }

    /** Lunar's names for the OS and CPU, as its launcher sends them. */
    fun os(name: String = System.getProperty("os.name", "")): String {
        val n = name.lowercase()
        return when {
            "mac" in n || "darwin" in n -> "darwin"
            "win" in n -> "win32"
            else -> "linux"
        }
    }

    fun arch(name: String = System.getProperty("os.arch", "")): String {
        val n = name.lowercase()
        return if ("aarch64" in n || "arm64" in n) "arm64" else "x64"
    }

    internal fun packedColor(bytes: ByteArray): Int {
        var packed = 0
        var found = false
        fields(bytes) { field, input, tag -> if (field == 1 && !found) { packed = input.readInt32(); found = true } else input.skipField(tag) }
        return packed
    }

    /** A Lunar+ colour as Lunar reads it: 0 is none, else its RGB, opaque. */
    internal fun plusColor(packed: Int) = if (packed == 0) 0 else packed or (0xFF shl 24)

    /** Moonlight reads an alpha of 0 as fully opaque. */
    internal fun opaque(packed: Int) = if (packed ushr 24 == 0) packed or (0xFF shl 24) else packed

    internal inline fun message(write: (CodedOutputStream) -> Unit): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = CodedOutputStream.newInstance(buf)
        write(out)
        out.flush()
        return buf.toByteArray()
    }

    internal inline fun fields(bytes: ByteArray, each: (field: Int, input: CodedInputStream, tag: Int) -> Unit) {
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            each(tag ushr 3, input, tag)
        }
    }
}
