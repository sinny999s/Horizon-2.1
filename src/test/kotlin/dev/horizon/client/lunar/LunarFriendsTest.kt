package dev.horizon.client.lunar

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Value
import com.lunarclient.apollo.configurable.v1.ConfigurableSettings
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lunar's friends and messages on the wire, and what Apollo settings block. */
class LunarFriendsTest {
    private fun proto(write: (CodedOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { b -> CodedOutputStream.newInstance(b).also(write).flush() }.toByteArray()

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()

    @Test
    fun loginLists() {
        val login = proto { o ->
            o.writeEnum(1, LunarFriendWire.ONLINE)
            o.writeBytes(11, ByteString.copyFrom(LunarFriendWire.friendBytes(alice, "Alice", true, "Hypixel", logo = 0x00FF0000, badge = 194)))
            o.writeBytes(2, ByteString.copyFrom(proto { f ->
                f.writeBytes(1, ByteString.copyFrom(LunarFriendWire.playerBytes(bob, "Bob")))
                f.writeBytes(2, ByteString.copyFrom(proto { it.writeInt64(1, 1_700_000_000); it.writeInt32(2, 500_000_000) }))
            }))
            o.writeBool(3, false)
            // A request carol sent you (inbound_friend_add_requests, 7).
            o.writeBytes(7, ByteString.copyFrom(proto { r -> r.writeBytes(1, ByteString.copyFrom(LunarFriendWire.playerBytes(carol, "Carol"))) }))
        }
        val l = LunarFriendWire.login(login)
        assertEquals(2, l.friends.size)
        val a = l.friends.first { it.uuid == alice }
        assertTrue(a.online)
        assertEquals("Hypixel", a.location)
        assertEquals(0xFFFF0000.toInt(), a.logo)
        assertEquals(194, a.badge)
        val b = l.friends.first { it.uuid == bob }
        assertFalse(b.online)
        assertEquals(1_700_000_000_500L, b.lastSeen)
        assertEquals(listOf(LunarFriendWire.Request(carol, "Carol", true)), l.requests)
        assertFalse(l.allowRequests)
    }

    @Test
    fun locations() {
        fun loc(field: Int, inner: ByteArray = ByteArray(0)) = LunarFriendWire.location(proto { it.writeBytes(field, ByteString.copyFrom(inner)) })
        assertEquals("Hypixel", loc(1, proto { it.writeString(2, "Hypixel"); it.writeString(3, "mc.hypixel.net") }))
        assertEquals("6b6t.org", loc(1, proto { it.writeString(3, "6b6t.org") }))
        assertEquals("A private server", loc(2))
        assertEquals("Singleplayer", loc(4))
        assertEquals("In menus", loc(6))
        assertEquals("Bob's world", loc(8, proto { it.writeBytes(1, ByteString.copyFrom(LunarFriendWire.playerBytes(bob, "Bob"))) }))
        assertNull(LunarFriendWire.location(ByteArray(0)))
    }

    @Test
    fun statusPushes() {
        val on = LunarFriendWire.statusPush(LunarFriendWire.statusPushBytes(LunarFriendWire.friendBytes(alice, "Alice", true, "Minemen"), true))!!
        assertTrue(on.online)
        assertEquals("Minemen", on.location)
        val off = LunarFriendWire.statusPush(LunarFriendWire.statusPushBytes(LunarFriendWire.friendBytes(alice, "Alice", false, null), false))!!
        assertFalse(off.online)
        assertEquals(alice, LunarFriendWire.uuidPush(LunarFriendWire.uuidRequest(alice)))
    }

    /** The Join button's address: a public server's primary_address; nothing for one without it, or offline. */
    @Test
    fun joinAddress() {
        val hypixel = LunarFriendWire.statusPush(LunarFriendWire.statusPushBytes(
            LunarFriendWire.friendBytes(alice, "Alice", true, "Hypixel", address = "mc.hypixel.net"), true))!!
        assertEquals("Hypixel", hypixel.location)
        assertEquals("mc.hypixel.net", hypixel.address)
        assertNull(LunarFriendWire.statusPush(LunarFriendWire.statusPushBytes(LunarFriendWire.friendBytes(alice, "Alice", true, "Minemen"), true))!!.address)
        assertNull(LunarFriendWire.statusPush(LunarFriendWire.statusPushBytes(LunarFriendWire.friendBytes(alice, "Alice", false, null), false))!!.address)
        // A private server (Location field 2) has no address to join.
        val private = LunarWire.message { it.writeBytes(2, ByteString.EMPTY) }
        assertNull(LunarFriendWire.serverAddress(private))
    }

    /** BroadcastLocationChangeRequest as Lunar Client sends it: InboundLocation's server (1) / single_player (2) / in_menus (4), and the trigger. */
    @Test
    fun broadcastLocation() {
        fun read(bytes: ByteArray): Pair<Pair<Int, String?>, Int> {
            var where = 0
            var ip: String? = null
            var trigger = 0
            val i = CodedInputStream.newInstance(bytes)
            while (!i.isAtEnd) {
                val tag = i.readTag()
                when (tag ushr 3) {
                    1 -> {
                        val loc = CodedInputStream.newInstance(i.readBytes().toByteArray())
                        val t = loc.readTag()
                        where = t ushr 3
                        val inner = loc.readBytes().toByteArray()
                        if (where == 1) ip = CodedInputStream.newInstance(inner).let { it.readTag(); it.readString() }
                    }
                    2 -> trigger = i.readEnum()
                    else -> i.skipField(tag)
                }
            }
            return (where to ip) to trigger
        }
        assertEquals((1 to "6b6t.org") to 1, read(LunarFriendWire.broadcastLocation("6b6t.org", false, LunarFriendWire.TRIGGER_JOIN)))
        assertEquals((2 to null) to 1, read(LunarFriendWire.broadcastLocation(null, true, LunarFriendWire.TRIGGER_JOIN)))
        assertEquals((4 to null) to 2, read(LunarFriendWire.broadcastLocation(null, false, LunarFriendWire.TRIGGER_DISCONNECT)))
    }

    @Test
    fun messages() {
        val (friend, msg, updated) = LunarFriendWire.messagePush(LunarFriendWire.messagePushBytes(alice, "Alice", "gg", 1_700_000_000_123L))
        assertEquals(alice, friend)
        assertEquals(LunarFriendWire.Message(alice, "Alice", "gg", 1_700_000_000_123L), msg)
        assertFalse(updated)
        // SendConversationMessageRequest: reference { friend_uuid }, contents { plain_text }.
        val send = LunarFriendWire.sendMessage(bob, "hi")
        val i = CodedInputStream.newInstance(send)
        assertEquals(1, i.readTag() ushr 3)
        assertEquals(bob, LunarFriendWire.reference(i.readBytes().toByteArray()))
        assertEquals(2, i.readTag() ushr 3)
        val contents = CodedInputStream.newInstance(i.readBytes().toByteArray())
        contents.readTag()
        assertEquals("hi", contents.readString())
        assertEquals(null, LunarFriendWire.sendMessageStatus(1))
        assertEquals("it's too long", LunarFriendWire.sendMessageStatus(4))
    }

    @Test
    fun apolloBlocks() {
        val s = ApolloBlocks.Summary()
        s.add(listOf(
            ConfigurableSettings.newBuilder().setApolloModule("mod_setting").setEnable(true)
                .putProperties("freelook.enabled", Value.newBuilder().setBoolValue(false).build())
                .putProperties("minimap.enabled", Value.newBuilder().setBoolValue(false).build())
                .putProperties("hitboxes.enabled", Value.newBuilder().setBoolValue(true).build())
                .putProperties("zoom.zoom-amount", Value.newBuilder().setNumberValue(4.0).build()).build(),
            ConfigurableSettings.newBuilder().setLunarClientMod("waypoints").setEnable(false).build(),
            // Another Apollo module (a server feature, not a Lunar mod setting): not a block.
            ConfigurableSettings.newBuilder().setApolloModule("nametag").setEnable(false).build(),
        ))
        assertEquals(setOf("freelook", "minimap", "waypoints"), s.blocked.toSet())
        assertEquals(mapOf("zoom.zoom-amount" to "4"), s.forced.toMap())
        // A later message lets Freelook back on.
        s.add(listOf(ConfigurableSettings.newBuilder().setApolloModule("mod_setting")
            .putProperties("freelook.enabled", Value.newBuilder().setBoolValue(true).build()).build()))
        assertFalse("freelook" in s.blocked)
        assertEquals("Player Hitbox", ApolloBlocks.modName("player_hitbox"))
        assertEquals("Zoom: zoom amount", ApolloBlocks.optionName("zoom.zoom-amount"))
    }
}
