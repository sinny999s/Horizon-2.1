package dev.horizon.client.lunar

import com.google.protobuf.ByteString
import dev.horizon.client.lunar.LunarWire.fields
import dev.horizon.client.lunar.LunarWire.message
import java.util.UUID

/**
 * Not in the original: Lunar's emote, spray and notification services on its game websocket (schemas from lunar.jar:
 * `lunarclient.websocket.emote.v1`, `spray.v1`, `notification.v1`), read and written by hand like [LunarWire]. Game-free.
 */
object LunarSocialWire {
    const val EMOTE_SERVICE = "lunarclient.websocket.emote.v1.EmoteService"
    const val SPRAY_SERVICE = "lunarclient.websocket.spray.v1.SprayService"
    const val NOTIFICATION_SERVICE = "lunarclient.websocket.notification.v1.NotificationService"

    const val USE_SPRAY_PUSH = "lunarclient.websocket.spray.v1.UseSprayPush"
    const val REMOVE_SPRAY_PUSH = "lunarclient.websocket.spray.v1.RemoveSprayPush"
    const val DISPLAY_NOTIFICATION_PUSH = "lunarclient.websocket.notification.v1.DisplayNotificationPush"
    const val DISPLAY_CHAT_PUSH = "lunarclient.websocket.notification.v1.DisplayChatMessagePush"
    const val NEW_NOTIFICATION_PUSH = "lunarclient.websocket.notification.v1.NewNotificationPush"
    const val REFRESH_EMOTES_PUSH = "lunarclient.websocket.emote.v1.RefreshEmotesPush"
    const val REFRESH_SPRAYS_PUSH = "lunarclient.websocket.spray.v1.RefreshSpraysPush"

    /** A repeated int32 field's values here: packed (one length-delimited run) or one plain value. */
    private fun ints(i: com.google.protobuf.CodedInputStream, tag: Int): List<Int> {
        if (tag and 7 != 2) return listOf(i.readInt32())
        val p = com.google.protobuf.CodedInputStream.newInstance(i.readBytes().toByteArray())
        val out = ArrayList<Int>()
        while (!p.isAtEnd) out += p.readInt32()
        return out
    }

    // ---- emotes ----

    /** EmoteService.Login: the emotes your account owns (owned_emote_ids 1, owned_emotes 7) and whether it has them all (5). */
    fun emoteLogin(bytes: ByteArray): Pair<Set<Int>, Boolean> {
        val owned = HashSet<Int>()
        var all = false
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> owned += ints(i, t)
                7 -> fields(i.readBytes().toByteArray()) { g, j, u -> if (g == 1) owned += j.readInt32() else j.skipField(u) }
                5 -> all = i.readBool()
                else -> i.skipField(t)
            }
        }
        return owned to all
    }

    /** EmoteService.Login: your emote wheel, slot to emote (equipped_emotes 8; else equipped_emote_ids 2 in slot order). */
    fun emoteWheel(bytes: ByteArray): Map<Int, Int> {
        val slots = LinkedHashMap<Int, Int>()
        val plain = ArrayList<Int>()
        fields(bytes) { f, i, t ->
            when (f) {
                8 -> {
                    var id = 0
                    var slot = -1
                    fields(i.readBytes().toByteArray()) { g, j, u ->
                        when (g) {
                            1 -> id = j.readInt32()
                            2 -> slot = j.readInt32()
                            else -> j.skipField(u)
                        }
                    }
                    if (id != 0 && slot >= 0) slots[slot] = id
                }
                2 -> plain += ints(i, t)
                else -> i.skipField(t)
            }
        }
        if (slots.isEmpty()) plain.forEachIndexed { s, id -> if (id != 0) slots[s] = id }
        return slots
    }

    /** UpdateEquippedEmotesRequest for [wheel] (slot to emote): equipped_emote_ids (1, in slot order) and equipped_emotes (2). */
    fun updateWheel(wheel: Map<Int, Int>): ByteArray = message { out ->
        val sorted = wheel.entries.sortedBy { it.key }
        for ((_, id) in sorted) out.writeInt32(1, id)
        for ((slot, id) in sorted) out.writeBytes(2, ByteString.copyFrom(message { e -> e.writeInt32(1, id); e.writeInt32(2, slot) }))
    }

    /** UseEmoteRequest: the emote (1), and its music: soundtrack URL (3) and jam (4). */
    fun useEmote(id: Int, url: String? = null, jam: Int = 0): ByteArray = message { o ->
        o.writeInt32(1, id)
        if (url != null) o.writeString(3, url)
        if (jam != 0) o.writeInt32(4, jam)
    }

    /** EmoteService.Login: jams attached to your wheel's emotes (equipped_emotes 8: emote 1, attached_jam_id 3), emote to jam. */
    fun wheelJams(bytes: ByteArray): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        fields(bytes) { f, i, t ->
            if (f == 8) {
                var id = 0
                var jam = 0
                fields(i.readBytes().toByteArray()) { g, j, u ->
                    when (g) {
                        1 -> id = j.readInt32()
                        3 -> jam = j.readInt32()
                        else -> j.skipField(u)
                    }
                }
                if (id != 0 && jam != 0) out[id] = jam
            } else i.skipField(t)
        }
        return out
    }

    /** UseEmoteResponse's status, said. */
    fun useEmoteStatus(status: Int) = when (status) {
        1 -> null
        2 -> "your account doesn't own it"
        3 -> "Lunar refused its music"
        4 -> "too many emotes at once (wait a moment)"
        else -> "Lunar said no ($status)"
    }

    // ---- sprays ----

    /** A spray on a block face: who placed it, which, where ([x], [y], [z] as Lunar sends them), facing (Direction), turn. */
    data class Spray(val player: UUID?, val id: Int, val x: Float, val y: Float, val z: Float, val facing: Int, val rotation: Float)

    private fun vector(bytes: ByteArray): FloatArray {
        val v = FloatArray(3)
        fields(bytes) { f, i, t -> if (f in 1..3) v[f - 1] = i.readFloat() else i.skipField(t) }
        return v
    }

    private fun vectorBytes(x: Float, y: Float, z: Float) = ByteString.copyFrom(message { o -> o.writeFloat(1, x); o.writeFloat(2, y); o.writeFloat(3, z) })

    fun useSprayPush(bytes: ByteArray): Spray? {
        var player: UUID? = null
        var id = 0
        var pos = FloatArray(3)
        var facing = 0
        var rotation = 0f
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> player = LunarWire.readUuid(i.readBytes().toByteArray())
                2 -> id = i.readInt32()
                3 -> pos = vector(i.readBytes().toByteArray())
                4 -> facing = i.readEnum()
                5 -> rotation = i.readFloat()
                else -> i.skipField(t)
            }
        }
        if (id == 0) return null
        return Spray(player, id, pos[0], pos[1], pos[2], facing, rotation)
    }

    /** RemoveSprayPush: (player, position). */
    fun removeSprayPush(bytes: ByteArray): Pair<UUID?, FloatArray> {
        var player: UUID? = null
        var pos = FloatArray(3)
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> player = LunarWire.readUuid(i.readBytes().toByteArray())
                2 -> pos = vector(i.readBytes().toByteArray())
                else -> i.skipField(t)
            }
        }
        return player to pos
    }

    /** SprayService.Login: owned spray ids (owned_sprays 1), whether it has them all (4), how many can be up at once (5). */
    fun sprayLogin(bytes: ByteArray): Triple<Set<Int>, Boolean, Int> {
        val owned = LinkedHashSet<Int>()
        var all = false
        var max = 0
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> fields(i.readBytes().toByteArray()) { g, j, u -> if (g == 1) owned += j.readInt32() else j.skipField(u) }
                3 -> owned += i.readInt32()
                4 -> all = i.readBool()
                5 -> max = i.readInt32()
                6 -> owned += ints(i, t)
                else -> i.skipField(t)
            }
        }
        owned.remove(0)
        return Triple(owned, all, max)
    }

    fun useSpray(s: Spray): ByteArray = message { o ->
        o.writeInt32(1, s.id)
        o.writeBytes(2, vectorBytes(s.x, s.y, s.z))
        o.writeEnum(3, s.facing)
        o.writeFloat(4, s.rotation)
    }

    fun removeSpray(x: Float, y: Float, z: Float): ByteArray = message { it.writeBytes(1, vectorBytes(x, y, z)) }

    /** For tests and ShotBot: a UseSprayPush. */
    fun useSprayPushBytes(s: Spray): ByteArray = message { o ->
        s.player?.let { o.writeBytes(1, ByteString.copyFrom(LunarWire.uuid(it))) }
        o.writeInt32(2, s.id)
        o.writeBytes(3, vectorBytes(s.x, s.y, s.z))
        o.writeEnum(4, s.facing)
        o.writeFloat(5, s.rotation)
    }

    // ---- notifications ----

    /** A notification to show: its title and text. */
    data class Note(val title: String, val text: String)

    /** DisplayNotificationPush: title (1), message (2). */
    fun displayNotification(bytes: ByteArray): Note {
        var title = ""
        var text = ""
        fields(bytes) { f, i, t ->
            when (f) {
                1 -> title = i.readString()
                2 -> text = i.readString()
                else -> i.skipField(t)
            }
        }
        return Note(title, text)
    }

    /** DisplayChatMessagePush: its line (field 1). */
    fun chatLine(bytes: ByteArray): String {
        var s = ""
        fields(bytes) { f, i, t -> if (f == 1) s = i.readString() else i.skipField(t) }
        return s
    }

    /** Notification: (title, type, read). */
    fun notification(bytes: ByteArray): Triple<String, Int, Boolean> {
        var title = ""
        var type = 0
        var read = false
        fields(bytes) { f, i, t ->
            when (f) {
                2 -> title = i.readString()
                3 -> type = i.readEnum()
                5 -> { i.skipField(t); read = true }
                else -> i.skipField(t)
            }
        }
        return Triple(title, type, read)
    }

    /** NewNotificationPush: its notification (field 1). */
    fun newNotification(bytes: ByteArray): Triple<String, Int, Boolean>? {
        var n: Triple<String, Int, Boolean>? = null
        fields(bytes) { f, i, t -> if (f == 1) n = notification(i.readBytes().toByteArray()) else i.skipField(t) }
        return n
    }

    /** NotificationService.Login: its notifications (field 1). */
    fun notificationLogin(bytes: ByteArray): List<Triple<String, Int, Boolean>> {
        val out = ArrayList<Triple<String, Int, Boolean>>()
        fields(bytes) { f, i, t -> if (f == 1) out += notification(i.readBytes().toByteArray()) else i.skipField(t) }
        return out
    }

    /** Notification.NotificationType as a heading. */
    fun notificationKind(type: Int) = when (type) {
        1 -> "Announcement"
        2 -> "Friend request"
        3 -> "Purchase"
        4 -> "Gift"
        6 -> "New friend"
        7 -> "Promotion"
        else -> "Lunar"
    }
}
