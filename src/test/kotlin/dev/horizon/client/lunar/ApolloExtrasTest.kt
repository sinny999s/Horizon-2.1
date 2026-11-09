package dev.horizon.client.lunar

import com.google.protobuf.Any
import com.google.protobuf.Value
import com.lunarclient.apollo.configurable.v1.ConfigurableSettings
import com.lunarclient.apollo.packetenrichment.v1.PlayerAttackMessage
import com.lunarclient.apollo.packetenrichment.v1.PlayerChatCloseMessage
import com.lunarclient.apollo.packetenrichment.v1.PlayerChatOpenMessage
import com.lunarclient.apollo.packetenrichment.v1.PlayerInventoryOpenMessage
import com.lunarclient.apollo.packetenrichment.v1.PlayerUseItemMessage
import com.lunarclient.apollo.player.v1.UpdatePlayerWorldMessage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ApolloRules], packet enrichment's messages ([ApolloProto]) and the rich presence's Discord lines. */
class ApolloExtrasTest {
    private fun bool(b: Boolean) = Value.newBuilder().setBoolValue(b).build()
    private fun num(n: Double) = Value.newBuilder().setNumberValue(n).build()
    private fun module(id: String, enable: Boolean, vararg props: Pair<String, Value>) =
        ConfigurableSettings.newBuilder().setApolloModule(id).setEnable(enable).putAllProperties(props.toMap()).build()

    @Test
    fun `packet enrichment only while the module and the message are on`() {
        val r = ApolloRules()
        assertFalse(r.enrich("player-attack"))
        r.add(listOf(module("packet_enrichment", false, "player-attack.send-packet" to bool(true))))
        assertFalse(r.enrich("player-attack"))
        r.add(listOf(module("packet_enrichment", true, "player-attack.send-packet" to bool(true), "player-chat-open.send-packet" to bool(false))))
        assertTrue(r.enrich("player-attack"))
        assertFalse(r.enrich("player-chat-open"))
        // Apollo before 1.2.2 had only `enable`: everything.
        val old = ApolloRules()
        old.add(listOf(module("packet_enrichment", true)))
        assertTrue(old.enrich("player-use-item"))
    }

    @Test
    fun `combat and server rules`() {
        val r = ApolloRules()
        assertFalse(r.noMissPenalty(false))
        assertNull(r.maxChatLength())
        r.add(listOf(
            module("combat", true, "disable-miss-penalty" to bool(true), "disable-block-miss-penalty" to bool(false), "allow-dig-and-use" to bool(true)),
            module("server_rule", true, "override-max-chat-length" to bool(true), "max-chat-length" to num(100.0)),
        ))
        assertTrue(r.noMissPenalty(onBlock = false))
        assertFalse(r.noMissPenalty(onBlock = true))
        assertTrue(r.allowDigAndUse())
        assertEquals(100, r.maxChatLength())
        // A module turned off stops all of it; a null value forgets the option.
        r.add(listOf(module("combat", false)))
        assertFalse(r.noMissPenalty(false))
        assertFalse(r.allowDigAndUse())
        r.add(listOf(ConfigurableSettings.newBuilder().setApolloModule("server_rule").setEnable(true)
            .putProperties("override-max-chat-length", Value.newBuilder().setNullValueValue(0).build()).build()))
        assertNull(r.maxChatLength())
    }

    private val me = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val them = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val self = ApolloProto.Player(me, "world", 1.5, 64.0, -2.5, 90f, 10f, sneaking = false, sprinting = true,
        full = true, jumping = true, forward = 1f, strafe = -0.5f)
    private val other = ApolloProto.Player(them, "world", 3.0, 64.0, -2.0, 180f, 0f, sneaking = true, sprinting = false, full = false)

    @Test
    fun `attack message as Lunar sends it`() {
        val any = Any.parseFrom(ApolloProto.attack(1_700_000_000_123L, self, other, 4.25))
        assertEquals("type.googleapis.com/lunarclient.apollo.packetenrichment.v1.PlayerAttackMessage", any.typeUrl)
        val m = any.unpack(PlayerAttackMessage::class.java)
        assertEquals(1_700_000_000L, m.packetInfo.instantiationTime.seconds)
        assertEquals(123_000_000, m.packetInfo.instantiationTime.nanos)
        assertEquals(4.25, m.distance)
        val a = m.attackerInfo
        assertEquals(me.mostSignificantBits, a.playerUuid.high64)
        assertEquals(me.leastSignificantBits, a.playerUuid.low64)
        assertEquals("world", a.location.location.world)
        assertEquals(1.5, a.location.location.x)
        assertEquals(90f, a.location.yaw)
        assertTrue(a.sprinting)
        assertTrue(a.jumping)
        assertEquals(1f, a.forwardSpeed)
        assertEquals(-0.5f, a.strafeSpeed)
        val t = m.targetInfo
        assertEquals(them.leastSignificantBits, t.playerUuid.low64)
        assertTrue(t.sneaking)
        // The target's info has no input fields.
        assertFalse(t.jumping)
        assertEquals(0f, t.forwardSpeed)
    }

    @Test
    fun `chat, inventory and item use messages`() {
        assertTrue(Any.parseFrom(ApolloProto.chat(true, 0, self)).`is`(PlayerChatOpenMessage::class.java))
        assertTrue(Any.parseFrom(ApolloProto.chat(false, 0, self)).`is`(PlayerChatCloseMessage::class.java))
        assertTrue(Any.parseFrom(ApolloProto.inventory(true, 0, self)).`is`(PlayerInventoryOpenMessage::class.java))
        val use = Any.parseFrom(ApolloProto.useItem(5_000, self, mainHand = false)).unpack(PlayerUseItemMessage::class.java)
        assertFalse(use.mainHand)
        assertEquals(5L, use.packetInfo.instantiationTime.seconds)
        assertEquals("world", use.playerInfo.location.location.world)
    }

    @Test
    fun `the server names the world`() {
        assertEquals("lobby", ApolloProto.world(Any.pack(UpdatePlayerWorldMessage.newBuilder().setWorld("lobby").build()).toByteArray()))
        assertNull(ApolloProto.world(ApolloProto.chat(true, 0, self)))
    }

    @Test
    fun `server rules`() {
        val r = ApolloRules()
        assertNull(r.gamma())
        assertFalse(r.antiPortalTraps())
        r.add(listOf(module("server_rule", true, "override-brightness" to bool(true), "brightness" to num(1000.0))))
        assertEquals(10f, r.gamma())
        // anti-portal-traps is on unless the server turns it off.
        assertTrue(r.antiPortalTraps())
        assertFalse(r.competitiveGame())
        assertFalse(r.competitiveCommand("/hub"))
    }

    @Test
    fun `competitive commands as Lunar matches them`() {
        val r = ApolloRules()
        r.add(listOf(module("server_rule", true, "competitive-game" to bool(true))))
        assertTrue(r.competitiveCommand("/hub"))
        assertTrue(r.competitiveCommand("/HUB"))
        assertTrue(r.competitiveCommand("/server lobby"))
        assertFalse(r.competitiveCommand("/hubs"))
        assertFalse(r.competitiveCommand("/msg hub"))
        // The root is the whole text when its first space comes before the third character.
        val list = ConfigurableSettings.newBuilder().setApolloModule("server_rule").setEnable(true)
            .putProperties("competitive-commands", Value.newBuilder().setListValue(com.google.protobuf.ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue("/l"))
                .addValues(Value.newBuilder().setStringValue("/l x"))).build()).build()
        r.add(listOf(list))
        assertTrue(r.competitiveCommand("/l x"))
        assertFalse(r.competitiveCommand("/hub"))
    }

    @Test
    fun `crystal optimizer's damage test`() {
        assertTrue(ApolloRules.crystalHitDamages(null, null, 1.0, 0.0))
        // Weakness I: w = 4; a hand's 1 doesn't hurt, a diamond sword's attribute 7 (> w + 5? no) plus its modifier does.
        assertFalse(ApolloRules.crystalHitDamages(0, null, 1.0, 0.0))
        assertTrue(ApolloRules.crystalHitDamages(0, null, 10.0, 0.0))
        assertTrue(ApolloRules.crystalHitDamages(0, null, 1.0, 6.0))
        // Strength II (s = 6) makes up for Weakness I.
        assertTrue(ApolloRules.crystalHitDamages(0, 1, 1.0, 0.0))
        assertFalse(ApolloRules.crystalHitDamages(1, 0, 1.0, 0.0))
    }

    @Test
    fun `rich status for Lunar friends`() {
        val rp = ApolloFeatures.RichPresence("Bedwars", "Doubles", "", "", "Lighthouse", "", 1, 2)
        fun fields(b: ByteArray) = com.google.protobuf.UnknownFieldSet.parseFrom(b)
        val req = fields(LunarFriendWire.broadcastRichStatus("mc.hypixel.net", rp))
        assertEquals(5L, req.getField(2).varintList.single())
        val server = fields(fields(req.getField(1).lengthDelimitedList.single().toByteArray()).getField(1).lengthDelimitedList.single().toByteArray())
        assertEquals("mc.hypixel.net", server.getField(1).lengthDelimitedList.single().toStringUtf8())
        val status = fields(server.getField(2).lengthDelimitedList.single().toByteArray())
        assertEquals("Bedwars", status.getField(1).lengthDelimitedList.single().toStringUtf8())
        assertEquals("Lighthouse", status.getField(4).lengthDelimitedList.single().toStringUtf8())
        assertEquals(2L, status.getField(7).varintList.single())
        assertEquals(1L, status.getField(8).varintList.single())
        assertEquals("Doubles", status.getField(9).lengthDelimitedList.single().toStringUtf8())
        // The server's reset: an empty status.
        val reset = fields(fields(fields(LunarFriendWire.broadcastRichStatus("mc.hypixel.net", null)).getField(1).lengthDelimitedList.single().toByteArray())
            .getField(1).lengthDelimitedList.single().toByteArray())
        assertEquals(0, reset.getField(2).lengthDelimitedList.single().size())
        // A friend's location with a rich status, as Lunar's list shows it.
        val location = LunarWire.message { l ->
            l.writeBytes(1, com.google.protobuf.ByteString.copyFrom(LunarWire.message { ps ->
                ps.writeString(2, "Hypixel")
                ps.writeBytes(7, com.google.protobuf.ByteString.copyFrom(LunarWire.message { it.writeString(1, "Bedwars") }))
            }))
        }
        assertEquals("Playing Bedwars on Hypixel", LunarFriendWire.location(location))
    }

    @Test
    fun `rich presence on Discord as Lunar's launcher writes it`() {
        fun rp(game: String, variant: String = "", map: String = "", state: String = "", team: Int = 0, max: Int = 0) =
            ApolloFeatures.RichPresence(game, variant, "", state, map, "", team, max)
        assertEquals("Playing Bedwars on Hypixel" to "Doubles on Lighthouse", rp("Bedwars", "Doubles", "Lighthouse").discord("Hypixel", "26.3"))
        assertEquals("Spectating Bedwars on Hypixel" to "Solo (1 of 2)", rp("Bedwars", "Solo", state = "Spectating", team = 1, max = 2).discord("Hypixel", "26.3"))
        assertEquals("Playing Lobby on Hypixel" to "26.3", rp("Lobby").discord("Hypixel", "26.3"))
        assertNull(rp("").discord("Hypixel", "26.3"))
    }
}
