package dev.horizon.client.lunar

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import com.lunarclient.apollo.common.v1.BlockLocation
import com.lunarclient.apollo.common.v1.Color
import com.lunarclient.apollo.common.v1.Component
import com.lunarclient.apollo.waypoint.v1.DisplayWaypointMessage
import com.lunarclient.apollo.waypoint.v1.RemoveWaypointMessage
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lunar's emotes, sprays, notifications, group chats and Apollo's server features on the wire. */
class LunarSocialTest {
    private fun proto(write: (CodedOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { b -> CodedOutputStream.newInstance(b).also(write).flush() }.toByteArray()

    @Test
    fun emoteLogin() {
        // owned_emote_ids packed (1), owned_emotes (7), has_all_emotes_flag (5).
        val login = proto { o ->
            o.writeBytes(1, ByteString.copyFrom(proto { p -> p.writeInt32NoTag(12); p.writeInt32NoTag(40) }))
            o.writeBytes(7, ByteString.copyFrom(proto { it.writeInt32(1, 77) }))
            o.writeBool(5, false)
        }
        val (owned, all) = LunarSocialWire.emoteLogin(login)
        assertEquals(setOf(12, 40, 77), owned)
        assertFalse(all)
        assertEquals("your account doesn't own it", LunarSocialWire.useEmoteStatus(2))
    }

    @Test
    fun sprays() {
        val u = UUID.randomUUID()
        val s = LunarSocialWire.Spray(u, 69, 10.5f, 64.005f, -3.5f, 2, 45f)
        assertEquals(s, LunarSocialWire.useSprayPush(LunarSocialWire.useSprayPushBytes(s)))
        val login = proto { o ->
            o.writeBytes(1, ByteString.copyFrom(proto { it.writeInt32(1, 68) }))
            o.writeInt32(3, 69)
            o.writeInt32(5, 3)
        }
        val (owned, all, max) = LunarSocialWire.sprayLogin(login)
        assertEquals(setOf(68, 69), owned)
        assertFalse(all)
        assertEquals(3, max)
        val types = LunarSocial.parseSprays("""[{"id":69,"name":"Lunar (Gold)","resource":"lunar:sprays/lunar_gold.webp","width":1,"height":1,"offsetX":null},
            {"id":70,"name":"Wide","resource":"lunar:sprays/wide.webp","width":2,"height":1}]""")
        assertEquals("assets/lunar/sprays/lunar_gold.webp", types[69]!!.path)
        assertEquals(2f, types[70]!!.width)
    }

    @Test
    fun notifications() {
        val n = LunarSocialWire.displayNotification(proto { o -> o.writeString(1, "Gift"); o.writeString(2, "You got a cloak") })
        assertEquals(LunarSocialWire.Note("Gift", "You got a cloak"), n)
        assertEquals("hi", LunarSocialWire.chatLine(proto { it.writeString(1, "hi") }))
        val note = proto { o -> o.writeString(2, "New friend: Bob"); o.writeEnum(3, 6) }
        val login = LunarSocialWire.notificationLogin(proto { o ->
            o.writeBytes(1, ByteString.copyFrom(note))
            o.writeBytes(1, ByteString.copyFrom(proto { r -> r.writeString(2, "old"); r.writeBytes(5, ByteString.copyFrom(proto { it.writeInt64(1, 5) })) }))
        })
        assertEquals(listOf(Triple("New friend: Bob", 6, false), Triple("old", 0, true)), login)
        assertEquals("New friend", LunarSocialWire.notificationKind(6))
    }

    @Test
    fun groupsStickersPins() {
        val g = UUID.randomUUID()
        val a = UUID.randomUUID()
        val conv = proto { c ->
            c.writeBytes(1, ByteString.copyFrom(proto { it.writeBytes(2, ByteString.copyFrom(LunarWire.uuid(g))) }))
            c.writeBytes(2, ByteString.copyFrom(proto { it.writeBytes(1, ByteString.copyFrom(LunarFriendWire.playerBytes(a, "Alice"))) }))
            c.writeEnum(3, 2)
            c.writeString(5, "Squad")
        }
        val groups = LunarFriendWire.groups(proto { o -> o.writeEnum(1, 1); o.writeBytes(2, ByteString.copyFrom(conv)) })
        assertEquals(listOf(LunarFriendWire.Group(g, "Squad", mapOf(a to "Alice"), null)), groups)
        // A direct conversation isn't a group.
        assertNull(LunarFriendWire.group(proto { c -> c.writeEnum(3, 1) }))
        // A message with an id, pinned, and a sticker.
        val id = UUID.randomUUID()
        val msg = proto { m ->
            m.writeBytes(1, ByteString.copyFrom(LunarWire.uuid(id)))
            m.writeBytes(3, ByteString.copyFrom(proto { it.writeBytes(1, ByteString.copyFrom(LunarFriendWire.playerBytes(a, "Alice"))) }))
            m.writeBytes(4, ByteString.copyFrom(proto { c -> c.writeBytes(2, ByteString.copyFrom(proto { s -> s.writeString(1, "gg"); s.writeString(2, "https://x.lunarclientcdn.com/gg.webp"); s.writeString(3, "GG") })) }))
            m.writeBool(6, true)
        }
        val parsed = LunarFriendWire.conversationMessage(msg)!!
        assertEquals(id, parsed.id)
        assertTrue(parsed.pinned)
        assertEquals("GG", parsed.sticker?.name)
        assertEquals("GG", parsed.text)
        // Pushes keyed by the group's id.
        val push = proto { o ->
            o.writeBytes(1, ByteString.copyFrom(proto { it.writeBytes(2, ByteString.copyFrom(LunarWire.uuid(g))) }))
            o.writeBytes(2, ByteString.copyFrom(msg))
        }
        assertEquals(g, LunarFriendWire.messagePush(push).first)
        // Sticker packs from ConversationService.Login (3).
        val stickers = LunarFriendWire.stickers(proto { o ->
            o.writeBytes(3, ByteString.copyFrom(proto { p -> p.writeString(2, "Pack"); p.writeBytes(4, ByteString.copyFrom(proto { s -> s.writeString(1, "wave"); s.writeString(3, "Wave") })) }))
        })
        assertEquals(listOf(LunarFriendWire.Sticker("wave", "", "Wave")), stickers)
        // Login's pinned friends (8).
        val b = UUID.randomUUID()
        assertEquals(setOf(b), LunarFriendWire.login(proto { it.writeBytes(8, ByteString.copyFrom(LunarWire.uuid(b))) }).pinned)
    }

    @Test
    fun stickerHosts() {
        assertTrue(LunarImages.allowed("https://textures.lunarclientcdn.com/file/abc"))
        assertTrue(LunarImages.allowed("https://lunarclient.com/x.png"))
        assertFalse(LunarImages.allowed("http://textures.lunarclientcdn.com/file/abc"))
        assertFalse(LunarImages.allowed("https://evil.example.com/lunarclientcdn.com"))
        assertFalse(LunarImages.allowed("https://notlunarclient.com/x"))
    }

    @Test
    fun apolloWaypoints() {
        ApolloFeatures.clear()
        val m = DisplayWaypointMessage.newBuilder().setName("Base").setLocation(BlockLocation.newBuilder().setX(10).setY(64).setZ(-5))
            .setColor(Color.newBuilder().setColor(0x00FF0000)).build()
        assertTrue(ApolloFeatures.handle(Any.pack(m)))
        val w = assertNotNull(ApolloFeatures.waypoints["Base"])
        assertEquals(10.5, w.pos.x)
        assertEquals(0xFFFF0000.toInt(), w.color)
        assertTrue(ApolloFeatures.handle(Any.pack(RemoveWaypointMessage.newBuilder().setName("Base").build())))
        assertTrue(ApolloFeatures.waypoints.isEmpty())
        assertEquals("Hello world", ApolloFeatures.text(Component.newBuilder().setContent("Hello ").addChildren(Component.newBuilder().setContent("world")).build()))
        assertEquals("Red team", ApolloFeatures.plain("""{"text":"Red ","extra":[{"text":"team"}]}"""))
    }
}
