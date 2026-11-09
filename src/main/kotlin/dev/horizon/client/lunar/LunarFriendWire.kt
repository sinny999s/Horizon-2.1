package dev.horizon.client.lunar

import com.google.protobuf.ByteString
import dev.horizon.client.lunar.LunarWire.fields
import dev.horizon.client.lunar.LunarWire.message
import java.util.UUID

/**
 * Lunar's friends and messages on its game websocket, read and written by hand like [LunarWire]
 * (schemas from lunar.jar: `lunarclient.websocket.friend.v1`, `conversation.v1`, `common.v1`). Game-free.
 */
object LunarFriendWire {
    const val FRIEND_SERVICE = "lunarclient.websocket.friend.v1.FriendService"
    const val CONVERSATION_SERVICE = "lunarclient.websocket.conversation.v1.ConversationService"

    const val STATUS_PUSH = "lunarclient.websocket.friend.v1.FriendStatusPush"
    const val REMOVED_YOU_PUSH = "lunarclient.websocket.friend.v1.FriendRemovedYouPush"
    const val REQUEST_RECEIVED_PUSH = "lunarclient.websocket.friend.v1.FriendRequestReceivedPush"
    const val REQUEST_ACCEPTED_PUSH = "lunarclient.websocket.friend.v1.FriendRequestAcceptedPush"
    const val REQUEST_DENIED_PUSH = "lunarclient.websocket.friend.v1.FriendRequestDeniedPush"
    const val REQUEST_CANCELED_PUSH = "lunarclient.websocket.friend.v1.FriendRequestCanceledPush"
    const val MESSAGE_PUSH = "lunarclient.websocket.conversation.v1.ConversationMessagePush"
    const val CONVERSATION_ADDED_PUSH = "lunarclient.websocket.conversation.v1.ConversationAddedPush"
    const val CONVERSATION_REMOVED_PUSH = "lunarclient.websocket.conversation.v1.ConversationRemovedPush"
    const val ADD_PINNED_PUSH = "lunarclient.websocket.conversation.v1.ConversationAddPinnedMessagePush"
    const val REMOVE_PINNED_PUSH = "lunarclient.websocket.conversation.v1.ConversationRemovePinnedMessagePush"
    const val REMOVE_MESSAGE_PUSH = "lunarclient.websocket.conversation.v1.ConversationRemoveMessagePush"

    /** Pushes after which the group list is asked for again (members, names). */
    val GROUP_CHANGE_PUSHES = listOf("ConversationAddParticipantsPush", "ConversationRemoveParticipantPush", "ConversationUpdateNamePush",
        "ConversationOwnerUpdatePush", "RefreshConversationsPush")

    /** OnlineFriendStatus. */
    const val ONLINE = 1
    const val AWAY = 2
    const val BUSY = 3
    const val INVISIBLE = 4

    /** BroadcastLocationChangeRequest.Trigger. */
    const val TRIGGER_JOIN = 1
    const val TRIGGER_DISCONNECT = 2
    const val TRIGGER_SERVER_RICH_STATUS = 5

    /**
     * A friend: online ([status] > 0) with where they are ([location], null when Lunar doesn't say) and their version,
     * or offline, last seen at [lastSeen] (epoch ms, 0 unknown). Their logo colour, Lunar+ colour and badge as by names.
     * [address] is the server to join them on, when they're on a public server Lunar knows (its primary address).
     */
    data class Friend(
        val uuid: UUID, val name: String, val status: Int = 0, val location: String? = null, val version: String? = null,
        val lastSeen: Long = 0, val logo: Int = -1, val plus: Int = 0, val badge: Int = 0, val justOnline: Boolean = false,
        val address: String? = null,
    ) {
        val online get() = status > 0
    }

    /** A friend request: who, and whether they sent it to you ([inbound]) or you to them. */
    data class Request(val uuid: UUID, val name: String, val inbound: Boolean)

    /** A message in a conversation: who sent it, its text, when (epoch ms), its id, whether it's pinned, its sticker if any. */
    data class Message(val sender: UUID?, val senderName: String, val text: String, val at: Long, val id: UUID? = null,
                       val pinned: Boolean = false, val sticker: Sticker? = null, val system: Boolean = false)

    /** A Lunar sticker: its id, picture URL and name. */
    data class Sticker(val id: String, val url: String, val name: String)

    /** A group chat: its id, name, members (uuid to name) and owner. */
    data class Group(val id: UUID, val name: String, val members: Map<UUID, String>, val owner: UUID?)

    /** FriendService.Login's answer. */
    class Login(val status: Int, val friends: List<Friend>, val requests: List<Request>, val allowRequests: Boolean,
                val pinned: Set<UUID> = emptySet())

    // ---- reading ----

    /** UuidAndUsername: (uuid, name). */
    fun player(bytes: ByteArray): Pair<UUID?, String> {
        var u: UUID? = null
        var name = ""
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> u = LunarWire.readUuid(i.readBytes().toByteArray())
                2 -> name = i.readString()
                else -> i.skipField(t)
            }
        }
        return u to name
    }

    /** google.protobuf.Timestamp as epoch ms. */
    fun timestamp(bytes: ByteArray): Long {
        var s = 0L
        var n = 0
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> s = i.readInt64()
                2 -> n = i.readInt32()
                else -> i.skipField(t)
            }
        }
        return s * 1000 + n / 1_000_000
    }

    private fun color(bytes: ByteArray) = LunarWire.opaque(LunarWire.packedColor(bytes))
    private fun plus(bytes: ByteArray) = LunarWire.plusColor(LunarWire.packedColor(bytes))

    /** OnlineFriend. */
    fun onlineFriend(bytes: ByteArray): Friend? {
        var p: Pair<UUID?, String> = null to ""
        var status = ONLINE
        var location: String? = null
        var address: String? = null
        var version: String? = null
        var logo = -1
        var plusColor = 0
        var badge = 0
        var just = false
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> p = player(i.readBytes().toByteArray())
                2 -> status = i.readEnum()
                3 -> {
                    val b = i.readBytes().toByteArray()
                    location = location(b)
                    address = serverAddress(b)
                }
                4 -> version = version(i.readBytes().toByteArray())
                5 -> just = i.readBool()
                7 -> logo = color(i.readBytes().toByteArray())
                9 -> plusColor = plus(i.readBytes().toByteArray())
                15 -> badge = i.readInt32()
                else -> i.skipField(t)
            }
        }
        val u = p.first ?: return null
        return Friend(u, p.second, if (status <= 0) ONLINE else status, location, version, 0, logo, plusColor, badge, just, address)
    }

    /** OfflineFriend. */
    fun offlineFriend(bytes: ByteArray): Friend? {
        var p: Pair<UUID?, String> = null to ""
        var seen = 0L
        var logo = -1
        var plusColor = 0
        var badge = 0
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> p = player(i.readBytes().toByteArray())
                2 -> seen = timestamp(i.readBytes().toByteArray())
                3 -> logo = color(i.readBytes().toByteArray())
                4 -> plusColor = plus(i.readBytes().toByteArray())
                10 -> badge = i.readInt32()
                else -> i.skipField(t)
            }
        }
        val u = p.first ?: return null
        return Friend(u, p.second, 0, null, null, seen, logo, plusColor, badge)
    }

    /** MinecraftVersion: its id ("1.8.9"), else its enum name. */
    fun version(bytes: ByteArray): String? {
        var id: String? = null
        var enum: String? = null
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> enum = i.readString()
                3 -> id = i.readString()
                else -> i.skipField(t)
            }
        }
        return id?.takeIf { it.isNotEmpty() } ?: enum?.takeIf { it.isNotEmpty() }?.removePrefix("v")?.replace('_', '.')
    }

    /** A Location's public server address (PublicServer's primary_address), the one Lunar's Join button connects to. */
    fun serverAddress(bytes: ByteArray): String? {
        var out: String? = null
        fields(bytes) { f, i, t ->
            if (f == 1) {
                fields(i.readBytes().toByteArray()) { g, j, u -> if (g == 3) out = j.readString().trim().ifEmpty { null } else j.skipField(u) }
            } else i.skipField(t)
        }
        return out
    }

    /** Location, said as Lunar's friends list says it ("Hypixel", "Singleplayer", "In menus"...). */
    fun location(bytes: ByteArray): String? {
        var out: String? = null
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> {
                    // PublicServer: name 2, primary_address 3, rich_status 7 (its game_name 1, as Lunar's list shows it).
                    var name: String? = null
                    var address: String? = null
                    var game: String? = null
                    fields(i.readBytes().toByteArray()) { g, j, u ->
                        when (g) {
                            2 -> name = j.readString()
                            3 -> address = j.readString()
                            7 -> fields(j.readBytes().toByteArray()) { h, k, v -> if (h == 1) game = k.readString() else k.skipField(v) }
                            else -> j.skipField(u)
                        }
                    }
                    val server = name?.takeIf { it.isNotEmpty() } ?: address ?: "A server"
                    out = if (game.isNullOrBlank()) server else "Playing $game on $server"
                }
                2 -> { i.skipField(t); out = "A private server" }
                3 -> { i.skipField(t); out = "A local server" }
                4 -> { i.skipField(t); out = "Singleplayer" }
                5 -> { i.skipField(t); out = "Realms" }
                6 -> { i.skipField(t); out = "In menus" }
                7 -> { i.skipField(t); out = "In the launcher" }
                8 -> {
                    var host = ""
                    fields(i.readBytes().toByteArray()) { g, j, u -> if (g == 1) host = player(j.readBytes().toByteArray()).second else j.skipField(u) }
                    out = if (host.isEmpty()) "A hosted world" else "$host's world"
                }
                9 -> { i.skipField(t); out = "Watching a replay" }
                10 -> { i.skipField(t); out = "In game" }
                11 -> { i.skipField(t); out = "In a rewind" }
                else -> i.skipField(t)
            }
        }
        return out
    }

    /** FriendRequest (Login's 6 and 7): the other player. */
    private fun friendRequest(bytes: ByteArray): Pair<UUID?, String> {
        var p: Pair<UUID?, String> = null to ""
        fields(bytes) { f, i, t -> if (f == 1) p = player(i.readBytes().toByteArray()) else i.skipField(t) }
        return p
    }

    fun login(bytes: ByteArray): Login {
        var status = ONLINE
        val friends = ArrayList<Friend>()
        val requests = LinkedHashMap<UUID, Request>()
        var allow = true
        val pinned = HashSet<UUID>()
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> status = i.readEnum()
                8 -> pinned += LunarWire.readUuid(i.readBytes().toByteArray())
                11 -> onlineFriend(i.readBytes().toByteArray())?.let { friends += it }
                2 -> offlineFriend(i.readBytes().toByteArray())?.let { friends += it }
                3 -> allow = i.readBool()
                4, 5 -> {
                    val (u, n) = player(i.readBytes().toByteArray())
                    if (u != null) requests.putIfAbsent(u, Request(u, n, f == 5))
                }
                6, 7 -> {
                    val (u, n) = friendRequest(i.readBytes().toByteArray())
                    if (u != null) requests[u] = Request(u, n, f == 7)
                }
                else -> i.skipField(t)
            }
        }
        return Login(status, friends, requests.values.toList(), allow, pinned)
    }

    /** FriendStatusPush: the friend as they are now (online or offline). */
    fun statusPush(bytes: ByteArray): Friend? {
        var out: Friend? = null
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> out = onlineFriend(i.readBytes().toByteArray())
                2 -> out = offlineFriend(i.readBytes().toByteArray())
                else -> i.skipField(t)
            }
        }
        return out
    }

    /** A push whose field 1 is a Uuid (FriendRemovedYou, FriendRequestDenied, FriendRequestCanceled). */
    fun uuidPush(bytes: ByteArray): UUID? {
        var u: UUID? = null
        fields(bytes) { f, i, t -> if (f == 1) u = LunarWire.readUuid(i.readBytes().toByteArray()) else i.skipField(t) }
        return u
    }

    /** FriendRequestReceivedPush: the sender. */
    fun requestReceived(bytes: ByteArray): Request? {
        val (u, n) = friendRequest(bytes)
        return u?.let { Request(it, n, true) }
    }

    /** FriendRequestAcceptedPush: the new friend (uuid, name). */
    fun requestAccepted(bytes: ByteArray): Pair<UUID?, String> {
        var u: UUID? = null
        var name = ""
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> u = LunarWire.readUuid(i.readBytes().toByteArray())
                2 -> player(i.readBytes().toByteArray()).let { (pu, pn) -> if (u == null) u = pu; name = pn }
                else -> i.skipField(t)
            }
        }
        return u to name
    }

    /** A response's status enum (field 1); 0 when absent. */
    fun status(bytes: ByteArray): Int {
        var s = 0
        fields(bytes) { f, i, t -> if (f == 1) s = i.readEnum() else i.skipField(t) }
        return s
    }

    /** SendFriendRequestResponse's status, said. */
    fun sendRequestStatus(status: Int) = when (status) {
        1 -> null
        2 -> "that isn't a valid name"
        3 -> "no Lunar player has that name"
        4 -> "they don't take friend requests"
        5 -> "that's you"
        6 -> "you're already friends"
        7 -> "they already sent you a request: accept it"
        8 -> "you already sent them one"
        else -> "Lunar said no ($status)"
    }

    /** AcceptFriendRequestResponse's status, said. */
    fun acceptStatus(status: Int) = when (status) {
        1 -> null
        2 -> "your friends list is full"
        3 -> "their friends list is full"
        4 -> "you're already friends"
        5 -> "that request is gone"
        else -> "Lunar said no ($status)"
    }

    /** ConversationMessage. */
    fun conversationMessage(bytes: ByteArray): Message? {
        var sender: UUID? = null
        var name = ""
        var text: String? = null
        var at = 0L
        var id: UUID? = null
        var pinned = false
        var sticker: Sticker? = null
        var system = false
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> id = LunarWire.readUuid(i.readBytes().toByteArray())
                5 -> system = i.readEnum() == 2
                6 -> pinned = i.readBool()
                2 -> at = timestamp(i.readBytes().toByteArray())
                3 -> fields(i.readBytes().toByteArray()) { g, j, u ->
                    if (g == 1) player(j.readBytes().toByteArray()).let { sender = it.first; name = it.second } else j.skipField(u)
                }
                4 -> fields(i.readBytes().toByteArray()) { g, j, u ->
                    when (g) {
                        1 -> text = j.readString()
                        2 -> { sticker = sticker(j.readBytes().toByteArray()); if (text == null) text = sticker?.name?.ifEmpty { null } ?: "sticker" }
                        5 -> { j.skipField(u); if (text == null) { text = "(group update)"; system = true } }
                        3 -> { j.skipField(u); if (text == null) text = "[image]" }
                        else -> j.skipField(u)
                    }
                }
                else -> i.skipField(t)
            }
        }
        val s = text ?: return null
        return Message(sender, name, s, at, id, pinned, sticker, system)
    }

    /** LunarSticker. */
    fun sticker(bytes: ByteArray): Sticker {
        var id = ""
        var url = ""
        var name = ""
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> id = i.readString()
                2 -> url = i.readString()
                3 -> name = i.readString()
                else -> i.skipField(t)
            }
        }
        return Sticker(id, url, name)
    }

    /** ConversationReference: (the friend for a direct conversation, the group's id for a group chat). */
    fun referenceBoth(bytes: ByteArray): Pair<UUID?, UUID?> {
        var friend: UUID? = null
        var group: UUID? = null
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> friend = LunarWire.readUuid(i.readBytes().toByteArray())
                2 -> group = LunarWire.readUuid(i.readBytes().toByteArray())
                else -> i.skipField(t)
            }
        }
        return friend to group
    }

    /** The key a conversation is kept by: the friend's uuid, else the group's. */
    fun referenceKey(bytes: ByteArray): UUID? = referenceBoth(bytes).let { it.first ?: it.second }

    /** Conversation: a group chat (null for a direct one). */
    fun group(bytes: ByteArray): Group? {
        var id: UUID? = null
        var name = ""
        var type = 0
        var owner: UUID? = null
        val members = LinkedHashMap<UUID, String>()
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> id = referenceBoth(i.readBytes().toByteArray()).second
                2 -> fields(i.readBytes().toByteArray()) { g, j, u ->
                    if (g == 1) player(j.readBytes().toByteArray()).let { (pu, pn) -> if (pu != null) members[pu] = pn } else j.skipField(u)
                }
                3 -> type = i.readEnum()
                5 -> name = i.readString()
                7 -> owner = LunarWire.readUuid(i.readBytes().toByteArray())
                else -> i.skipField(t)
            }
        }
        val g = id ?: return null
        if (type == 1) return null
        return Group(g, name.ifEmpty { members.values.take(3).joinToString(", ") }, members, owner)
    }

    /** The group chats in a GetConversationsResponse (field 2) or CreateConversationResponse (field 2), or a ConversationAddedPush (field 1). */
    fun groups(bytes: ByteArray, field: Int = 2): List<Group> {
        val out = ArrayList<Group>()
        fields(bytes) { f, i, t -> if (f == field) group(i.readBytes().toByteArray())?.let { out += it } else i.skipField(t) }
        return out
    }

    /** ConversationService.Login: the stickers of its sticker packs (field 3). */
    fun stickers(bytes: ByteArray): List<Sticker> {
        val out = ArrayList<Sticker>()
        fields(bytes) { f, i, t ->
            if (f == 3) fields(i.readBytes().toByteArray()) { g, j, u -> if (g == 4) out += sticker(j.readBytes().toByteArray()) else j.skipField(u) }
            else i.skipField(t)
        }
        return out
    }

    /** A conversation push: its reference (field 1) and the uuid in field [idField] (a message id, or a sender). */
    fun refAndId(bytes: ByteArray, idField: Int): Pair<UUID?, UUID?> {
        var ref: UUID? = null
        var id: UUID? = null
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> ref = referenceKey(i.readBytes().toByteArray())
                idField -> id = LunarWire.readUuid(i.readBytes().toByteArray())
                else -> i.skipField(t)
            }
        }
        return ref to id
    }

    /** ConversationReference: the friend for a direct conversation (null for a group). */
    fun reference(bytes: ByteArray): UUID? {
        var u: UUID? = null
        fields(bytes) { f, i, t -> if (f == 1) u = LunarWire.readUuid(i.readBytes().toByteArray()) else i.skipField(t) }
        return u
    }

    /** ConversationMessagePush: (the friend's conversation or null for a group, the message, whether it's an edit). */
    fun messagePush(bytes: ByteArray): Triple<UUID?, Message?, Boolean> {
        var friend: UUID? = null
        var msg: Message? = null
        var updated = false
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> friend = referenceKey(i.readBytes().toByteArray())
                2 -> msg = conversationMessage(i.readBytes().toByteArray())
                3 -> updated = i.readBool()
                else -> i.skipField(t)
            }
        }
        return Triple(friend, msg, updated)
    }

    /** LoadConversationResponse: its messages, oldest first. */
    fun loadedMessages(bytes: ByteArray): List<Message> {
        val out = ArrayList<Message>()
        fields(bytes) { f, i, t -> if (f == 1) conversationMessage(i.readBytes().toByteArray())?.let { out += it } else i.skipField(t) }
        return out.sortedBy { it.at }
    }

    // ---- writing ----

    private fun uuidField(u: UUID) = ByteString.copyFrom(LunarWire.uuid(u))

    /** Requests whose field 1 is a player's Uuid (RemoveFriend, CancelFriendRequest, AcceptFriendRequest, DenyFriendRequest). */
    fun uuidRequest(u: UUID): ByteArray = message { it.writeBytes(1, uuidField(u)) }

    fun sendFriendRequest(name: String): ByteArray = message { it.writeString(1, name) }

    /**
     * BroadcastLocationChangeRequest: InboundLocation is the server's address ([server]), singleplayer, or in menus
     * (neither), and why ([trigger]).
     */
    fun broadcastLocation(server: String?, singleplayer: Boolean, trigger: Int): ByteArray = message { out ->
        val location = message { l ->
            when {
                server != null -> l.writeBytes(1, ByteString.copyFrom(message { it.writeString(1, server) }))
                singleplayer -> l.writeBytes(2, ByteString.EMPTY)
                else -> l.writeBytes(4, ByteString.EMPTY)
            }
        }
        out.writeBytes(1, ByteString.copyFrom(location))
        out.writeEnum(2, trigger)
    }

    /**
     * BroadcastLocationChangeRequest for an Apollo server's rich presence, as Lunar sends it: InboundServer (its
     * [server] address 1, rich_status 2) with trigger SERVER_RICH_STATUS. The status (common.v1.ServerRichStatus) is
     * game_name 1, game_state 2, player_state 3, map_name 4, sub_server 5, team sizes 6 and 7, source 8 (APOLLO, 1),
     * game_variant_name 9; [rich] null is the server's reset, an empty status.
     */
    fun broadcastRichStatus(server: String, rich: ApolloFeatures.RichPresence?): ByteArray = message { out ->
        val status = message { s ->
            if (rich != null) {
                if (rich.game.isNotEmpty()) s.writeString(1, rich.game)
                if (rich.gameState.isNotEmpty()) s.writeString(2, rich.gameState)
                if (rich.playerState.isNotEmpty()) s.writeString(3, rich.playerState)
                if (rich.map.isNotEmpty()) s.writeString(4, rich.map)
                if (rich.subServer.isNotEmpty()) s.writeString(5, rich.subServer)
                if (rich.teamSize != 0) s.writeInt32(6, rich.teamSize)
                if (rich.teamMax != 0) s.writeInt32(7, rich.teamMax)
                s.writeEnum(8, 1)
                if (rich.variant.isNotEmpty()) s.writeString(9, rich.variant)
            }
        }
        val location = message { l ->
            l.writeBytes(1, ByteString.copyFrom(message { it.writeString(1, server); it.writeBytes(2, ByteString.copyFrom(status)) }))
        }
        out.writeBytes(1, ByteString.copyFrom(location))
        out.writeEnum(2, TRIGGER_SERVER_RICH_STATUS)
    }

    /** ConversationReference for [friend]'s direct conversation. */
    private fun friendReference(friend: UUID) = reference(friend, false)

    /** ConversationReference: a friend's direct conversation, or a group chat ([group]). */
    fun reference(key: UUID, group: Boolean): ByteString = ByteString.copyFrom(message { it.writeBytes(if (group) 2 else 1, uuidField(key)) })

    fun sendMessage(key: UUID, text: String, group: Boolean = false): ByteArray = message { out ->
        out.writeBytes(1, reference(key, group))
        out.writeBytes(2, ByteString.copyFrom(message { it.writeString(1, text) }))
    }

    /** SendConversationMessage with a sticker. */
    fun sendSticker(key: UUID, group: Boolean, s: Sticker): ByteArray = message { out ->
        out.writeBytes(1, reference(key, group))
        out.writeBytes(2, ByteString.copyFrom(message { c ->
            c.writeBytes(2, ByteString.copyFrom(message { st -> st.writeString(1, s.id); st.writeString(2, s.url); st.writeString(3, s.name) }))
        }))
    }

    fun loadConversation(key: UUID, group: Boolean = false): ByteArray = message { it.writeBytes(1, reference(key, group)) }

    /** LoadPinnedMessages / AddPinnedMessage / RemovePinnedMessage: the conversation, and the message ([id]). */
    fun pinRequest(key: UUID, group: Boolean, id: UUID?): ByteArray = message { out ->
        out.writeBytes(1, reference(key, group))
        if (id != null) out.writeBytes(2, uuidField(id))
    }

    /** LoadPinnedMessagesResponse: its messages (field 2). */
    fun pinnedMessages(bytes: ByteArray): List<Message> {
        val out = ArrayList<Message>()
        fields(bytes) { f, i, t -> if (f == 2) conversationMessage(i.readBytes().toByteArray())?.let { out += it } else i.skipField(t) }
        return out
    }

    /** CreateConversationRequest for a group chat with [members]. */
    fun createGroup(members: Collection<UUID>): ByteArray = message { out ->
        out.writeEnum(1, 2)
        for (u in members) out.writeBytes(2, uuidField(u))
    }

    /** GetConversationsRequest: the latest [limit]. */
    fun getConversations(limit: Int): ByteArray = message { it.writeInt32(1, limit) }

    /** RemoveConversationParticipant / AddConversationParticipants (the latter repeated, one here). */
    fun participantRequest(group: UUID, target: UUID, name: String): ByteArray = message { out ->
        out.writeBytes(1, reference(group, true))
        out.writeBytes(2, ByteString.copyFrom(playerBytes(target, name)))
    }

    fun renameGroup(group: UUID, name: String): ByteArray = message { out ->
        out.writeBytes(1, reference(group, true))
        out.writeString(2, name)
    }

    /** CreateConversationResponse's status, said. */
    fun createStatus(status: Int) = when (status) {
        1 -> null
        2 -> "you have too many group chats"
        3 -> "pick friends for it"
        4 -> "that group already exists"
        else -> "Lunar said no ($status)"
    }

    // ---- your status, pinned friends, requests ----

    fun statusRequest(status: Int): ByteArray = message { it.writeEnum(1, status) }

    fun toggleRequests(allow: Boolean): ByteArray = message { it.writeBool(1, allow) }

    /** SendConversationMessageResponse's status, said. */
    fun sendMessageStatus(status: Int) = when (status) {
        1 -> null
        2 -> "Lunar doesn't know that conversation"
        3 -> "Lunar blocked it as inappropriate"
        4 -> "it's too long"
        else -> "Lunar said no ($status)"
    }

    // ---- for tests and ShotBot ----

    fun playerBytes(u: UUID, name: String): ByteArray = message { o -> o.writeBytes(1, uuidField(u)); o.writeString(2, name) }

    /** An OnlineFriend on a public server named [server] at [address] (or offline when [server] is null and [online] false). */
    fun friendBytes(u: UUID, name: String, online: Boolean, server: String?, logo: Int = 0, badge: Int = 0, address: String? = null): ByteArray = message { o ->
        o.writeBytes(1, ByteString.copyFrom(playerBytes(u, name)))
        if (online) {
            o.writeEnum(2, ONLINE)
            if (server != null) o.writeBytes(3, ByteString.copyFrom(message { l ->
                l.writeBytes(1, ByteString.copyFrom(message { ps -> ps.writeString(2, server); if (address != null) ps.writeString(3, address) }))
            }))
            if (logo != 0) o.writeBytes(7, ByteString.copyFrom(message { it.writeInt32(1, logo) }))
            if (badge != 0) o.writeInt32(15, badge)
        } else {
            if (logo != 0) o.writeBytes(3, ByteString.copyFrom(message { it.writeInt32(1, logo) }))
            if (badge != 0) o.writeInt32(10, badge)
        }
    }

    /** A FriendStatusPush for [friendBytes]. */
    fun statusPushBytes(friend: ByteArray, online: Boolean): ByteArray = message { it.writeBytes(if (online) 1 else 2, ByteString.copyFrom(friend)) }

    /** A ConversationMessagePush from [from] in their direct conversation. */
    fun messagePushBytes(from: UUID, name: String, text: String, at: Long): ByteArray = message { o ->
        o.writeBytes(1, friendReference(from))
        o.writeBytes(2, ByteString.copyFrom(message { m ->
            m.writeBytes(2, ByteString.copyFrom(message { ts -> ts.writeInt64(1, at / 1000); ts.writeInt32(2, ((at % 1000) * 1_000_000).toInt()) }))
            m.writeBytes(3, ByteString.copyFrom(message { it.writeBytes(1, ByteString.copyFrom(playerBytes(from, name))) }))
            m.writeBytes(4, ByteString.copyFrom(message { it.writeString(1, text) }))
        }))
    }
}
