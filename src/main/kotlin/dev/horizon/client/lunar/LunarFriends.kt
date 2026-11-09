package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.lunar.LunarFriendWire.Friend
import dev.horizon.client.lunar.LunarFriendWire.Group
import dev.horizon.client.lunar.LunarFriendWire.Message
import dev.horizon.client.lunar.LunarFriendWire.Request
import dev.horizon.client.lunar.LunarFriendWire.Sticker
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Your Lunar friends list and chats, as Lunar Client keeps them, over Horizon's Lunar connection
 * ([LunarNetwork]). On connecting it signs in to Lunar's FriendService (friends online with where they are or offline,
 * requests, pinned friends, your status) and ConversationService (messages, group chats, sticker packs); afterwards
 * Lunar's pushes keep it current. You can set your status (online, away, busy, invisible), pin friends to the top, turn
 * friend requests off, message friends and group chats (text or stickers), make, rename and leave group chats, and pin
 * messages. With `share-server` on, where you are goes to your friends the way Lunar Client sends it
 * (BroadcastLocationChange). Shown in [dev.horizon.client.gui.modern.LunarFriendsScreen]; messages and friends coming
 * online also go to chat (`friend-messages`).
 *
 * Conversations are kept by one uuid: the friend's for a direct one, the group's for a group chat ([groups]).
 */
object LunarFriends {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val friendMap = ConcurrentHashMap<UUID, Friend>()
    private val requestMap = ConcurrentHashMap<UUID, Request>()
    private val groupMap = ConcurrentHashMap<UUID, Group>()
    private val conversations = ConcurrentHashMap<UUID, MutableList<Message>>()
    private val pins = ConcurrentHashMap<UUID, List<Message>>()
    private val pinnedFriends: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Conversations with messages you haven't opened. */
    val unread: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile
    var signedIn = false
        private set

    @Volatile
    var allowRequests = true
        private set

    /** Your status as Lunar shows it to friends (OnlineFriendStatus). */
    @Volatile
    var status = LunarFriendWire.ONLINE
        private set

    /** The stickers of Lunar's sticker packs (from ConversationService.Login). */
    @Volatile
    var stickers: List<Sticker> = emptyList()
        private set

    /** Where Lunar was last told you are: the server's address, "singleplayer" or "menus"; null for not yet. */
    private var sentLocation: String? = null

    /** Friends: pinned first, then online, then by name. */
    val friends: List<Friend>
        get() = friendMap.values.sortedWith(compareByDescending<Friend> { it.uuid in pinnedFriends }.thenByDescending { it.online }
            .thenBy { it.name.lowercase() })

    val requests: List<Request> get() = requestMap.values.sortedBy { it.name.lowercase() }

    val groups: List<Group> get() = groupMap.values.sortedBy { it.name.lowercase() }

    fun isPinned(friend: UUID) = friend in pinnedFriends
    fun isGroup(key: UUID) = groupMap.containsKey(key)
    fun group(key: UUID) = groupMap[key]
    fun friend(uuid: UUID) = friendMap[uuid]

    fun conversation(key: UUID): List<Message> = conversations[key]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /** [key]'s pinned messages (once loaded). */
    fun pinned(key: UUID): List<Message> = pins[key] ?: emptyList()

    private fun list(key: UUID): MutableList<Message> = conversations.getOrPut(key) { java.util.Collections.synchronizedList(ArrayList()) }

    private val enabled get() = LunarSettings.network.value && LunarSettings.friends.value

    // ---- from LunarNetwork ----

    /** Lunar's websocket is ready: signs in to the friend and conversation services (socket thread). */
    fun connected() {
        if (!enabled) return
        sentLocation = null
        LunarNetwork.request(LunarFriendWire.FRIEND_SERVICE, "Login", ByteArray(0)) { out -> login(LunarFriendWire.login(out)) }
        LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "Login", ByteArray(0)) { out -> stickers = LunarFriendWire.stickers(out) }
        refreshGroups()
    }

    fun login(l: LunarFriendWire.Login) {
        friendMap.clear()
        for (f in l.friends) friendMap[f.uuid] = f
        requestMap.clear()
        for (r in l.requests) requestMap[r.uuid] = r
        pinnedFriends.clear()
        pinnedFriends += l.pinned
        allowRequests = l.allowRequests
        status = if (l.status <= 0) LunarFriendWire.ONLINE else l.status
        signedIn = true
        HorizonClient.LOG.info("Lunar friends: {} friends ({} online), {} requests", l.friends.size, l.friends.count { it.online }, l.requests.size)
        for (f in l.friends) if (f.online) LunarNetwork.addPeer(f.uuid, f.logo, f.plus, f.badge)
    }

    /** Asks Lunar for your group chats (names, members). */
    private fun refreshGroups() {
        LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "GetConversations", LunarFriendWire.getConversations(50)) { out ->
            val list = LunarFriendWire.groups(out)
            groupMap.clear()
            for (g in list) groupMap[g.id] = g
        }
    }

    fun clear() {
        friendMap.clear()
        requestMap.clear()
        groupMap.clear()
        conversations.clear()
        pins.clear()
        pinnedFriends.clear()
        unread.clear()
        stickers = emptyList()
        signedIn = false
        sentLocation = null
    }

    /** A push from Lunar; true when it was one of the friend or conversation pushes (any thread). */
    fun push(type: String, value: ByteArray): Boolean {
        when {
            type.endsWith(LunarFriendWire.STATUS_PUSH) -> LunarFriendWire.statusPush(value)?.let { status(it) }
            type.endsWith(LunarFriendWire.REMOVED_YOU_PUSH) -> LunarFriendWire.uuidPush(value)?.let { u ->
                friendMap.remove(u)?.let { tell("${it.name} removed you as a friend") }
            }
            type.endsWith(LunarFriendWire.REQUEST_RECEIVED_PUSH) -> LunarFriendWire.requestReceived(value)?.let { r ->
                requestMap[r.uuid] = r
                tell("${r.name} sent you a friend request")
            }
            type.endsWith(LunarFriendWire.REQUEST_ACCEPTED_PUSH) -> LunarFriendWire.requestAccepted(value).let { (u, name) ->
                if (u == null) return true
                val n = name.ifEmpty { requestMap[u]?.name ?: "?" }
                requestMap.remove(u)
                friendMap.putIfAbsent(u, Friend(u, n))
                tell("$n accepted your friend request")
            }
            type.endsWith(LunarFriendWire.REQUEST_DENIED_PUSH) || type.endsWith(LunarFriendWire.REQUEST_CANCELED_PUSH) ->
                LunarFriendWire.uuidPush(value)?.let { requestMap.remove(it) }
            type.endsWith(LunarFriendWire.MESSAGE_PUSH) -> {
                val (key, msg, updated) = LunarFriendWire.messagePush(value)
                if (key != null && msg != null) {
                    if (updated) replace(key, msg) else received(key, msg)
                }
            }
            type.endsWith(LunarFriendWire.CONVERSATION_ADDED_PUSH) -> LunarFriendWire.groups(value, 1).forEach { g ->
                groupMap[g.id] = g
                tell("you were added to the group chat ${g.name}")
            }
            type.endsWith(LunarFriendWire.CONVERSATION_REMOVED_PUSH) -> LunarFriendWire.refAndId(value, 99).first?.let { k ->
                groupMap.remove(k)?.let { tell("you're no longer in ${it.name}") }
                conversations.remove(k)
            }
            type.endsWith(LunarFriendWire.ADD_PINNED_PUSH) || type.endsWith(LunarFriendWire.REMOVE_PINNED_PUSH) -> {
                val (k, id) = LunarFriendWire.refAndId(value, 3)
                if (k != null && id != null) {
                    val on = type.endsWith(LunarFriendWire.ADD_PINNED_PUSH)
                    conversations[k]?.let { l -> synchronized(l) { l.replaceAll { if (it.id == id) it.copy(pinned = on) else it } } }
                    loadPins(k)
                }
            }
            type.endsWith(LunarFriendWire.REMOVE_MESSAGE_PUSH) -> {
                val (k, id) = LunarFriendWire.refAndId(value, 2)
                if (k != null && id != null) conversations[k]?.let { l -> synchronized(l) { l.removeIf { it.id == id } } }
            }
            LunarFriendWire.GROUP_CHANGE_PUSHES.any { type.endsWith(it) } -> refreshGroups()
            else -> return false
        }
        return true
    }

    private fun status(f: Friend) {
        val was = friendMap[f.uuid]
        // Offline pushes and partial ones keep what's known (the name, colours).
        val now = f.copy(name = f.name.ifEmpty { was?.name ?: "?" }, logo = if (f.logo == -1 && was != null) was.logo else f.logo)
        friendMap[f.uuid] = now
        if (now.online) LunarNetwork.addPeer(now.uuid, now.logo, now.plus, now.badge)
        if (now.online && (was == null || !was.online || f.justOnline)) {
            if (LunarSettings.notify.value) tell("${now.name} is online" + (now.location?.let { " ($it)" } ?: ""))
        }
    }

    private fun replace(key: UUID, msg: Message) {
        val l = conversations[key] ?: return
        synchronized(l) { l.replaceAll { if (it.id != null && it.id == msg.id) msg else it } }
    }

    private fun received(key: UUID, msg: Message) {
        val l = list(key)
        synchronized(l) { if (msg.id == null || l.none { it.id == msg.id }) l.add(msg) }
        val self = mc.user.profileId
        if (msg.sender == self || msg.system) return
        unread += key
        val name = msg.senderName.ifEmpty { friendMap[key]?.name ?: "?" }
        if (LunarSettings.friendMessages.value) {
            val where = groupMap[key]?.let { "${ChatFormatting.DARK_AQUA}[${it.name}] " } ?: ""
            mc.execute { LunarSettings.tell("$where${ChatFormatting.AQUA}$name${ChatFormatting.GRAY}: ${ChatFormatting.WHITE}${msg.text}") }
        }
    }

    // ---- friends (game thread) ----

    fun sendRequest(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (!LunarNetwork.request(LunarFriendWire.FRIEND_SERVICE, "SendFriendRequest", LunarFriendWire.sendFriendRequest(n)) { out ->
                val why = LunarFriendWire.sendRequestStatus(LunarFriendWire.status(out))
                if (why == null) {
                    // SendFriendRequestResponse: target (2) is who it went to.
                    var target: Pair<UUID?, String> = null to n
                    LunarWire.fields(out) { f, i, t -> if (f == 2) target = LunarFriendWire.player(i.readBytes().toByteArray()) else i.skipField(t) }
                    target.first?.let { requestMap[it] = Request(it, target.second.ifEmpty { n }, false) }
                    tell("sent $n a friend request")
                } else tell("couldn't add $n: $why")
            }) notConnected()
    }

    fun accept(r: Request) = call("AcceptFriendRequest", r.uuid) { out ->
        val why = LunarFriendWire.acceptStatus(LunarFriendWire.status(out))
        requestMap.remove(r.uuid)
        if (why == null) {
            // AcceptFriendRequestResponse: offline_friend (2).
            var f: Friend? = null
            LunarWire.fields(out) { g, i, t -> if (g == 2) f = LunarFriendWire.offlineFriend(i.readBytes().toByteArray()) else i.skipField(t) }
            friendMap.putIfAbsent(r.uuid, f ?: Friend(r.uuid, r.name))
            tell("${r.name} is now your friend")
        } else tell("couldn't accept ${r.name}: $why")
    }

    fun deny(r: Request) = call("DenyFriendRequest", r.uuid) { requestMap.remove(r.uuid) }

    fun cancel(r: Request) = call("CancelFriendRequest", r.uuid) { requestMap.remove(r.uuid) }

    fun remove(f: Friend) = call("RemoveFriend", f.uuid) {
        friendMap.remove(f.uuid)
        conversations.remove(f.uuid)
        pinnedFriends.remove(f.uuid)
        tell("removed ${f.name} from your friends")
    }

    /** Pins [f] to the top of your list, or unpins them (AddFriendPin / RemoveFriendPin). */
    fun togglePin(f: Friend) {
        val on = f.uuid !in pinnedFriends
        call(if (on) "AddFriendPin" else "RemoveFriendPin", f.uuid) { out ->
            val s = LunarFriendWire.status(out)
            if (s == 1) {
                if (on) pinnedFriends += f.uuid else pinnedFriends -= f.uuid
            } else tell(if (on && s == 5) "you've pinned as many friends as Lunar allows" else "Lunar didn't change the pin ($s)")
        }
    }

    /** Your status as friends see it (BroadcastStatusChange). */
    fun setStatus(s: Int) {
        if (!LunarNetwork.request(LunarFriendWire.FRIEND_SERVICE, "BroadcastStatusChange", LunarFriendWire.statusRequest(s)) { }) {
            notConnected()
            return
        }
        status = s
    }

    /** Lets players send you friend requests, or not (ToggleFriendRequests). */
    fun setAllowRequests(allow: Boolean) {
        if (!LunarNetwork.request(LunarFriendWire.FRIEND_SERVICE, "ToggleFriendRequests", LunarFriendWire.toggleRequests(allow)) { }) {
            notConnected()
            return
        }
        allowRequests = allow
    }

    private fun call(method: String, u: UUID, done: (ByteArray) -> Unit) {
        if (!LunarNetwork.request(LunarFriendWire.FRIEND_SERVICE, method, LunarFriendWire.uuidRequest(u), done)) notConnected()
    }

    // ---- chats (game thread) ----

    /** Sends [text] to [key] (a friend or a group chat); it shows once Lunar echoes it back, as in Lunar Client. */
    fun send(key: UUID, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        sendBytes(LunarFriendWire.sendMessage(key, t, isGroup(key)))
    }

    fun sendSticker(key: UUID, s: Sticker) = sendBytes(LunarFriendWire.sendSticker(key, isGroup(key), s))

    private fun sendBytes(bytes: ByteArray) {
        val sent = LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "SendConversationMessage", bytes) { out ->
            LunarFriendWire.sendMessageStatus(LunarFriendWire.status(out))?.let { tell("message not sent: $it") }
        }
        if (!sent) notConnected()
    }

    /** Loads [key]'s history and pinned messages (when the screen opens it). */
    fun load(key: UUID) {
        unread -= key
        val group = isGroup(key)
        LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "LoadConversation", LunarFriendWire.loadConversation(key, group)) { out ->
            val loaded = LunarFriendWire.loadedMessages(out)
            val list = list(key)
            synchronized(list) {
                val seen = list.map { it.id ?: (it.at to it.text) }.toHashSet()
                val merged = (loaded.filter { (it.id ?: (it.at to it.text)) !in seen } + list).sortedBy { it.at }
                list.clear()
                list.addAll(merged)
            }
        }
        loadPins(key)
    }

    fun loadPins(key: UUID) {
        LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "LoadPinnedMessages", LunarFriendWire.pinRequest(key, isGroup(key), null)) { out ->
            pins[key] = LunarFriendWire.pinnedMessages(out)
        }
    }

    /** Pins or unpins message [m] in [key] (AddPinnedMessage / RemovePinnedMessage). */
    fun togglePinned(key: UUID, m: Message) {
        val id = m.id ?: return
        val method = if (m.pinned || pinned(key).any { it.id == id }) "RemovePinnedMessage" else "AddPinnedMessage"
        if (!LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, method, LunarFriendWire.pinRequest(key, isGroup(key), id)) { out ->
                if (LunarFriendWire.status(out) == 1) loadPins(key) else tell("Lunar didn't change the pin")
            }) notConnected()
    }

    /** Makes a group chat with [members] (CreateConversation). */
    fun createGroup(members: Collection<UUID>, opened: (UUID) -> Unit = {}) {
        if (members.isEmpty()) return
        if (!LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "CreateConversation", LunarFriendWire.createGroup(members)) { out ->
                val why = LunarFriendWire.createStatus(LunarFriendWire.status(out))
                val g = LunarFriendWire.groups(out).firstOrNull()
                if (why != null || g == null) tell("couldn't make the group: ${why ?: "Lunar sent no group"}")
                else {
                    groupMap[g.id] = g
                    tell("made the group chat ${g.name}")
                    mc.execute { opened(g.id) }
                }
            }) notConnected()
    }

    /** Leaves group chat [g] (removes yourself). */
    fun leaveGroup(g: Group) {
        val me = mc.user
        if (!LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "RemoveConversationParticipant",
                LunarFriendWire.participantRequest(g.id, me.profileId, me.name)) { out ->
                if (LunarFriendWire.status(out) == 1) {
                    groupMap.remove(g.id)
                    conversations.remove(g.id)
                    tell("left ${g.name}")
                } else tell("couldn't leave ${g.name}")
            }) notConnected()
    }

    fun renameGroup(g: Group, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (!LunarNetwork.request(LunarFriendWire.CONVERSATION_SERVICE, "UpdateConversationName", LunarFriendWire.renameGroup(g.id, n)) { out ->
                if (LunarFriendWire.status(out) == 1) groupMap[g.id] = g.copy(name = n) else tell("couldn't rename ${g.name}")
            }) notConnected()
    }

    // ---- where you are ----

    /** Every tick (game thread): tells Lunar where you are when it changes, with `share-server` on. */
    fun tick(ready: Boolean) {
        if (!ready || !enabled || !LunarSettings.shareServer.value) return
        val conn = mc.connection
        val here = when {
            conn == null -> "menus"
            conn.connection.isMemoryConnection -> "singleplayer"
            else -> mc.currentServer?.ip ?: "menus"
        }
        if (here == sentLocation) return
        val trigger = if (here == "menus") LunarFriendWire.TRIGGER_DISCONNECT else LunarFriendWire.TRIGGER_JOIN
        val bytes = LunarFriendWire.broadcastLocation(here.takeUnless { it == "menus" || it == "singleplayer" }, here == "singleplayer", trigger)
        if (LunarNetwork.request(LunarFriendWire.FRIEND_SERVICE, "BroadcastLocationChange", bytes) { }) {
            sentLocation = here
            HorizonClient.LOG.info("Lunar friends: told Lunar you're {}", if (here == "menus" || here == "singleplayer") "in $here" else "on $here")
        }
    }

    private fun notConnected() = tell("not connected to Lunar (turn on Lunar in the ClickGUI's Lunar menu and join a server)")

    private fun tell(text: String) {
        HorizonClient.LOG.info("Lunar friends: {}", text)
        mc.execute { LunarSettings.tell(text) }
    }

    // ---- ShotBot ----

    /**
     * For ShotBot (`lunarfriends <spec>`), fed through the same parsing as Lunar's own messages; nothing is sent:
     * `login` (a list as FriendService.Login gives it, Alice pinned, a group chat "Squad", two stickers), `msg <name> <text>`
     * (a message from that friend), `group <text>` (a message in Squad from Alice), `pin` (pins Squad's first message),
     * `online <name> <server>` / `offline <name>` (status pushes), `open [name]` (the screen, on their conversation),
     * `send <text>` (writes in the open conversation).
     */
    @JvmStatic
    fun testSpec(spec: String) {
        val a = spec.trim().split(' ').filter { it.isNotEmpty() }
        fun uuidOf(name: String) = friendMap.values.firstOrNull { it.name.equals(name, true) }?.uuid
            ?: UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())
        val squad = UUID.nameUUIDFromBytes("group:Squad".toByteArray())
        when (a[0]) {
            "login" -> {
                val now = System.currentTimeMillis()
                val bytes = LunarWire.message { o ->
                    fun friend(field: Int, b: ByteArray) = o.writeBytes(field, com.google.protobuf.ByteString.copyFrom(b))
                    friend(11, LunarFriendWire.friendBytes(uuidOf("Alice"), "Alice", true, "Hypixel", logo = 0x00FF55FF, badge = 194))
                    friend(11, LunarFriendWire.friendBytes(uuidOf("Dave"), "Dave", true, "6b6t.org", logo = 0x0055FFFF, badge = 1))
                    friend(2, LunarWire.message { f ->
                        f.writeBytes(1, com.google.protobuf.ByteString.copyFrom(LunarFriendWire.playerBytes(uuidOf("Bob"), "Bob")))
                        f.writeBytes(2, com.google.protobuf.ByteString.copyFrom(LunarWire.message { it.writeInt64(1, (now - 3 * 3600_000L) / 1000) }))
                    })
                    friend(7, LunarWire.message { it.writeBytes(1, com.google.protobuf.ByteString.copyFrom(LunarFriendWire.playerBytes(uuidOf("Carol"), "Carol"))) })
                    friend(8, LunarWire.uuid(uuidOf("Bob")))
                }
                login(LunarFriendWire.login(bytes))
                groupMap[squad] = Group(squad, "Squad", linkedMapOf(uuidOf("Alice") to "Alice", uuidOf("Dave") to "Dave", mc.user.profileId to mc.user.name), uuidOf("Alice"))
                stickers = listOf(Sticker("gg", "https://textures.lunarclientcdn.com/file/missing", "gg"), Sticker("wave", "", "wave"))
            }
            "msg" -> push(LunarFriendWire.MESSAGE_PUSH, LunarFriendWire.messagePushBytes(uuidOf(a[1]), a[1], a.drop(2).joinToString(" "), System.currentTimeMillis()))
            "group" -> received(squad, Message(uuidOf("Alice"), "Alice", a.drop(1).joinToString(" "), System.currentTimeMillis(), UUID.randomUUID()))
            "pin" -> {
                val first = conversation(squad).firstOrNull() ?: return
                conversations[squad]?.let { l -> synchronized(l) { l.replaceAll { if (it === first) it.copy(pinned = true) else it } } }
                pins[squad] = listOf(first.copy(pinned = true))
            }
            "online" -> push(LunarFriendWire.STATUS_PUSH, LunarFriendWire.statusPushBytes(LunarFriendWire.friendBytes(uuidOf(a[1]), a[1], true, a.getOrNull(2)), true))
            "offline" -> push(LunarFriendWire.STATUS_PUSH, LunarFriendWire.statusPushBytes(LunarFriendWire.friendBytes(uuidOf(a[1]), a[1], false, null), false))
            "open" -> {
                dev.horizon.client.gui.modern.LunarFriendsScreen.open()
                a.getOrNull(1)?.let { dev.horizon.client.gui.modern.LunarFriendsScreen.select(it) }
            }
            "send" -> dev.horizon.client.gui.modern.LunarFriendsScreen.testSend(a.drop(1).joinToString(" "))
            else -> throw IllegalArgumentException("lunarfriends $spec")
        }
    }
}
