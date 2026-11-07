package dev.horizon.client.lunar

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Value
import com.lunarclient.apollo.common.v1.Component
import com.lunarclient.apollo.common.v1.EntityId
import com.lunarclient.apollo.common.v1.Uuid
import com.lunarclient.apollo.configurable.v1.ConfigurableSettings
import com.lunarclient.apollo.entity.v1.FlipEntityMessage
import com.lunarclient.apollo.limb.v1.ArmorPiece
import com.lunarclient.apollo.limb.v1.HideArmorPiecesMessage
import com.lunarclient.apollo.nametag.v1.OverrideNametagMessage
import com.lunarclient.apollo.nickhider.v1.OverrideNickHiderMessage
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Badges, Lunar+ colours, outfits and their tree, the emote wheel, jams, morphs, companions, and Apollo's other modules. */
class LunarProfileTest {
    private fun proto(write: (CodedOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { b -> CodedOutputStream.newInstance(b).also(write).flush() }.toByteArray()

    @Test
    fun badgesAndPlusColors() {
        val login = proto { o ->
            o.writeBytes(1, ByteString.copyFrom(proto { it.writeInt32(1, 194) }))
            o.writeBytes(1, ByteString.copyFrom(proto { it.writeInt32(1, 1) }))
            o.writeInt32(2, 194)
        }
        assertEquals(listOf(194, 1) to false, LunarWire.badgeLogin(login))
        assertEquals(194, LunarWire.loginBadge(login))
        val cos = proto { o ->
            o.writeBytes(4, ByteString.copyFrom(proto { it.writeInt32(1, 0xFF55FF) }))
            o.writeBytes(4, ByteString.copyFrom(proto { it.writeInt32(1, 0x55FFFF) }))
        }
        assertEquals(listOf(0xFFFF55FF.toInt(), 0xFF55FFFF.toInt()), LunarWire.loginPlusColors(cos))
    }

    @Test
    fun outfitTree() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val night = LunarWire.Condition(LunarWire.Condition.TIME, 2)
        val server = LunarWire.Condition(LunarWire.Condition.SERVER, 0, "6b6t.org")
        val t = LunarWire.Tree(a, listOf(server to b, night to b))
        assertEquals(t, LunarWire.tree(LunarWire.treeBytes(t)))
        assertEquals(a, LunarWire.treeDefault(LunarWire.treeBytes(t)))
        assertEquals("on 6b6t.org", LunarProfileEdits.conditionText(server))
        assertEquals("at night", LunarProfileEdits.conditionText(night))
        // Renaming keeps every other field (the cosmetics).
        val o = LunarWire.outfit(proto { p ->
            p.writeBytes(1, ByteString.copyFrom(LunarWire.uuid(a)))
            p.writeString(2, "Main")
            p.writeBytes(3, ByteString.copyFrom(LunarWire.equippedCosmetic(42)))
        })
        val renamed = LunarWire.outfit(LunarWire.withName(o, "PvP"))
        assertEquals("PvP", renamed.name)
        assertEquals(listOf(42), renamed.cosmetics.map { it.first })
        assertEquals(a, renamed.id)
    }

    @Test
    fun emoteWheelAndJams() {
        val login = proto { o ->
            o.writeBytes(8, ByteString.copyFrom(proto { e -> e.writeInt32(1, 12); e.writeInt32(2, 0); e.writeInt32(3, 7) }))
            o.writeBytes(8, ByteString.copyFrom(proto { e -> e.writeInt32(1, 40); e.writeInt32(2, 3) }))
        }
        assertEquals(mapOf(0 to 12, 3 to 40), LunarSocialWire.emoteWheel(login))
        assertEquals(mapOf(12 to 7), LunarSocialWire.wheelJams(login))
        // Saving the wheel writes it both ways Lunar reads it.
        assertEquals(mapOf(0 to 12, 3 to 40), LunarSocialWire.emoteWheel(proto { o ->
            val w = LunarSocialWire.updateWheel(mapOf(3 to 40, 0 to 12))
            com.google.protobuf.CodedInputStream.newInstance(w).let { i ->
                while (!i.isAtEnd) {
                    val tag = i.readTag()
                    if (tag ushr 3 == 2) o.writeBytes(8, i.readBytes()) else i.skipField(tag)
                }
            }
        }))
        val push = proto { o -> o.writeBytes(1, ByteString.copyFrom(LunarWire.uuid(UUID.randomUUID()))); o.writeInt32(2, 12); o.writeString(4, "https://x.example/a.mp3") }
        assertEquals("https://x.example/a.mp3", LunarJams.soundtrack(push))
        assertEquals(null, LunarJams.soundtrack(proto { it.writeString(4, "http://insecure/a.mp3") }))
    }

    @Test
    fun morphsAndCompanions() {
        val morphs = LunarEmotes.parseMorphs("""{"avatar_air":{"title":"Air","morphs":[
            {"morph":"{Scheme:\"avatar_air1\",Name:\"snowstorm\"}","bone":"anchor","start":30,"length":15,"translate":[0.0,0.5,0.25]},
            {"morph":"{Meta:0b,Block:\"minecraft:grass\",Name:\"block\"}","bone":"misc_bone_1","start":0,"length":58}]}}""")
        val air = morphs["avatar_air"]!!
        assertEquals(1, air.size)
        assertEquals("avatar_air1", air[0].scheme)
        assertEquals(30, air[0].start)
        assertEquals(0.5f, air[0].translate!![1])
        val companions = LunarCosmeticIndex.parseCompanions("""[{"id":9816,"name":"Slasher Dog","resource":"lunar:cosmetics/models/gek/companions/ghost_dog/textures/slasher_dog.gek.json","category":"companion"},
            {"id":9520,"name":"Shield","resource":"lunar:x.gek.json","category":"shields"}]""")
        assertEquals(setOf(9816), companions.keys)
        assertEquals("companion", companions[9816]!!.type)
        assertEquals("assets/lunar/cosmetics/models/gek/companions/ghost_dog/textures/slasher_dog.gek.json", companions[9816]!!.assetPath)
    }

    @Test
    fun twoArmModels() {
        val g = LunarCosmeticIndex.parseGek("""{"type":"suits","model":{"lunar:cosmetics/models/gek/suits/flame_hands/flame_hands.geo.json":"!q.is_slim_model",
            "lunar:cosmetics/models/gek/suits/flame_hands/flame_hands_slim.geo.json":"q.is_slim_model"},"texture":"lunar:x.webp"}""")
        assertEquals("assets/lunar/cosmetics/models/gek/suits/flame_hands/flame_hands.geo.json", g.model)
        assertEquals("assets/lunar/cosmetics/models/gek/suits/flame_hands/flame_hands_slim.geo.json", g.slimModel)
        val plain = LunarCosmeticIndex.parseGek("""{"type":"hat","model":"lunar:a.geo.json"}""")
        assertEquals("assets/lunar/a.geo.json", plain.model)
        assertEquals(null, plain.slimModel)
    }

    @Test
    fun apolloOtherModules() {
        ApolloFeatures.clear()
        val u = UUID.randomUUID()
        val au = Uuid.newBuilder().setHigh64(u.mostSignificantBits).setLow64(u.leastSignificantBits).build()
        assertTrue(ApolloFeatures.handle(Any.pack(OverrideNametagMessage.newBuilder().setPlayerUuid(au)
            .addLines(Component.newBuilder().setContent("[RED]")).addLines(Component.newBuilder().setContent("Bob")).build())))
        assertEquals(listOf("[RED]", "Bob"), ApolloFeatures.nametags[u])
        assertTrue(ApolloFeatures.handle(Any.pack(HideArmorPiecesMessage.newBuilder().setPlayerUuid(au).addArmorPieces(ArmorPiece.ARMOR_PIECE_HELMET).build())))
        assertEquals(setOf(1), ApolloFeatures.hiddenArmor[u])
        assertTrue(ApolloFeatures.handle(Any.pack(FlipEntityMessage.newBuilder().addEntityIds(EntityId.newBuilder().setEntityId(77)).build())))
        assertTrue(77 in ApolloFeatures.flipped)
        assertTrue(ApolloFeatures.handle(Any.pack(OverrideNickHiderMessage.newBuilder().setNick("Hidden").build())))
        assertEquals("Hidden", ApolloFeatures.nick)
        ApolloFeatures.clear()
        assertFalse(77 in ApolloFeatures.flipped)
        // Apollo's own modules' settings are reported as server rules, not Lunar mod blocks.
        val s = ApolloBlocks.Summary()
        s.add(listOf(ConfigurableSettings.newBuilder().setApolloModule("server_rule").setEnable(true)
            .putProperties("competitive-game", Value.newBuilder().setBoolValue(true).build()).build()))
        assertEquals(mapOf("server_rule.competitive-game" to "on"), s.server.toMap())
        assertTrue(s.blocked.isEmpty())
    }
}
