package dev.horizon.client.lunar

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lunar cosmetics: the wire messages, the index files, WebP and the Bedrock geometry (samples from Lunar's CDN). */
class LunarCosmeticsTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/lunar/$name")!!.use { it.readAllBytes() }

    @Test
    fun pushRoundTrip() {
        val uuid = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        val frame = LunarWire.pushFrame(LunarWire.COSMETICS_PUSH, LunarWire.playerCosmeticsPush(uuid, listOf(8993, 9003)))
        val pushes = LunarWire.pushes(frame)
        assertEquals(1, pushes.size)
        assertEquals("type.googleapis.com/${LunarWire.COSMETICS_PUSH}", pushes[0].first)
        val p = LunarWire.playerCosmetics(pushes[0].second)!!
        assertEquals(uuid, p.uuid)
        assertEquals(listOf(8993, 9003), p.defaults.map { it.id })
        // An RPC reply (rpc_response, field 1) isn't a push, and the reply still reads.
        val reply = com.google.protobuf.ByteString.copyFrom(LunarWire.call("7", "s", "m", byteArrayOf(1, 2)))
        val frameOut = java.io.ByteArrayOutputStream()
        com.google.protobuf.CodedOutputStream.newInstance(frameOut).apply { writeBytes(1, com.google.protobuf.ByteString.copyFrom(rpc("7", byteArrayOf(1, 2)))); flush() }
        assertTrue(LunarWire.pushes(frameOut.toByteArray()).isEmpty())
        assertEquals("7", LunarWire.replies(frameOut.toByteArray()).single().first)
        assertTrue(reply.size() > 0)
    }

    private fun rpc(id: String, output: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        com.google.protobuf.CodedOutputStream.newInstance(out).apply {
            writeBytes(1, com.google.protobuf.ByteString.copyFromUtf8(id))
            writeByteArray(2, output)
            flush()
        }
        return out.toByteArray()
    }

    @Test
    fun subscribeResponse() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val pushA = LunarWire.playerCosmeticsPush(a, listOf(1))
        val pushB = LunarWire.playerCosmeticsPush(b, listOf(2, 3))
        val out = java.io.ByteArrayOutputStream()
        val cos = com.google.protobuf.CodedOutputStream.newInstance(out)
        cos.writeByteArray(1, pushA)
        cos.writeByteArray(1, pushB)
        cos.writeByteArray(2, byteArrayOf(8, 1)) // a radio push, skipped
        cos.flush()
        val list = LunarWire.subscribeResponse(out.toByteArray())
        assertEquals(listOf(a, b), list.map { it.uuid })
        assertEquals(listOf(2, 3), list[1].defaults.map { it.id })
    }

    @Test
    fun index() {
        val idx = LunarCosmeticIndex.parseIndex(String(res("index.csv")) + "\n7,,,Name, with comma,,true,cloak,NONE,cosmetics/cloaks/x.webp,false,A,B\n")
        val shades = idx[9003]!!
        assertEquals("BSOD Shades", shades.name)
        assertEquals("glasses", shades.type)
        assertTrue(shades.gek)
        assertEquals("assets/lunar/cosmetics/models/gek/hats/bsod_shades/bsod_shades.gek.json", shades.assetPath)
        val odd = idx[7]!!
        assertEquals("Name, with comma", odd.name)
        assertTrue(odd.isCloak)
        assertEquals(mapOf("assets/lunar/a.webp" to "0123456789012345678901234567890123456789"),
            LunarCosmeticIndex.parseAssets("assets/lunar/a.webp 0123456789012345678901234567890123456789 12 34\nbad line\n"))
        val gek = LunarCosmeticIndex.parseGek("""{"type":"hat","attached_bone":"HEAD","model":"lunar:cosmetics/m.geo.json","texture":"lunar:cosmetics/m.webp","is_translucent":true}""")
        assertEquals("assets/lunar/cosmetics/m.geo.json", gek.model)
        assertTrue(gek.translucent)
        assertEquals(LunarCosmeticIndex.Animation(6, 176, 136), LunarCosmeticIndex.parseMcmeta("""{ "animation": {"frametime": 6, "width": 176, "height": 136 } }"""))
    }

    @Test
    fun webp() {
        val sky = WebP.decode(res("sky.webp"))
        assertEquals(176, sky.width)
        assertEquals(0, sky.height % 136)
        val chip = WebP.decode(res("chip.webp"))
        assertTrue(chip.width > 0 && chip.height > 0)
        println("sky ${sky.width}x${sky.height}, chip ${chip.width}x${chip.height}")
    }

    /**
     * A tall animated cloak (Lunar+ Cloak (White & Pink), 704×6528, lossless): TwelveMonkeys 3.12 threw "Coordinate out of
     * bounds!" on its backward references, so ~30 of Lunar's animated cloaks never loaded. Checked against libwebp (PIL):
     * the CRC32 of every pixel as A,R,G,B bytes, fully transparent pixels counted as 0.
     */
    @Test
    fun tallAnimatedCloak() {
        val img = WebP.decode(res("lunarpluswhitepink.webp"))
        assertEquals(704, img.width)
        assertEquals(6528, img.height)
        val crc = java.util.zip.CRC32()
        val px = ByteArray(4)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val v = if (argb ushr 24 == 0) 0 else argb
            for (i in 0 until 4) px[i] = (v ushr (24 - 8 * i)).toByte()
            crc.update(px)
        }
        assertEquals(0xD4BFCEB8L, crc.value)
    }

    @Test
    fun geometry() {
        // A hat: everything on the head, around the head's top (J y from 24 − 30 = −6 up to 24 − 25 = −1).
        val visor = GeoModel.parse(String(res("2026_visor.geo.json")), "HEAD").rest()
        assertEquals(setOf(GeoModel.Limb.HEAD), visor.parts.keys)
        val q = visor.parts[GeoModel.Limb.HEAD]!!
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in q.indices step GeoModel.QUAD_FLOATS) for (v in 0 until 4) {
            val y = q[i + v * 5 + 1]
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        assertTrue(minY > -8f && maxY < 0.5f, "visor y $minY..$maxY")
        // A suit with generic bone names: its top-level bones spread over the body, arms and legs.
        val armor = GeoModel.parse(String(res("2026_armor.geo.json")), "NONE").rest()
        assertEquals(setOf(GeoModel.Limb.BODY, GeoModel.Limb.RIGHT_ARM, GeoModel.Limb.LEFT_ARM, GeoModel.Limb.RIGHT_LEG, GeoModel.Limb.LEFT_LEG), armor.parts.keys)
        // The right arm's pieces are on the right (J x < 0), like the player's right arm.
        val right = armor.parts[GeoModel.Limb.RIGHT_ARM]!!
        val xs = (right.indices step GeoModel.QUAD_FLOATS).flatMap { i -> (0 until 4).map { right[i + it * 5] } }
        assertTrue(xs.average() < -4.0, "right arm x ${xs.average()}")
        val chip = GeoModel.parse(String(res("chip.geo.json")), "SHOULDER").rest()
        assertNotNull(chip.parts[GeoModel.Limb.BODY])
        println("visor ${visor.quadCount} quads, armor ${armor.quadCount}, chip ${chip.quadCount}")
    }

    @Test
    fun molang() {
        val scope = Molang.Scope(functions = Molang.parseFunctions(String(res("functions.molang"))))
        scope.query = { if (it == "life_time") 2.0 else 0.0 }
        fun run(src: String) = Molang.compile(src).run(scope)
        assertEquals(7.0, run("1 + 2 * 3"))
        assertEquals(1.0, run("math.sin(90)"), 1e-9)
        assertEquals(5.0, run("v.a = 5; return v.a;"))
        assertEquals(5.0, run("v.a"))
        assertEquals(3.0, run("v.never ?? 3"))
        assertEquals(0.0, run("v.z = 0; return v.z ?? 3;"))
        assertEquals(2.0, run("q.life_time"))
        assertEquals(4.0, run("t.x = 1; if (t.x > 0) { t.x = 4; } else { t.x = 9; } return t.x;"))
        assertEquals(10.0, run("t.n = 0; loop(10, { t.n = t.n + 1; }); return t.n;"))
        assertEquals(1.0, run("(1 > 0) ? { v.b = 1; } : 0; return v.b;"))
        // Lunar's own functions: lunar.sigmoid(0, r, c) is 0, lunar.sinw(frq, amp) = sin(life_time * frq) * amp.
        assertEquals(0.0, run("lunar.sigmoid(0, 5, 2)"), 1e-9)
        assertEquals(Math.sin(Math.toRadians(90.0)) * 3, run("lunar.sinw(45, 3)"), 1e-9)
        assertEquals(1.0, run("'abc' == 'abc'"))
        // Lunar's cosmetic options: a missing one falls through ??, a set one doesn't.
        assertEquals(5.0, run("option.size ?? 5"))
        scope.options = mapOf("size" to 7.0)
        assertEquals(7.0, run("option.size ?? 5"))
        assertEquals(3.0, run("unknown.thing ?? 3"))
        // Lunar's per-frame constants run without trouble and set c. values.
        Molang.compile(String(res("constants.molang"))).run(scope)
        assertTrue(scope.context.containsKey("normalized_ground_speed"))
    }

    @Test
    fun legacyWingsFlap() {
        val model = GeoModel.parse(String(res("legacy_dragon_wings.geo.json")), "SHOULDER")
        val anims = GeoAnimation.parse(String(res("legacy_dragon_wings.anim.json")))
        val a = anims["animation.dragon_wings.flap"]!!
        assertEquals(GeoAnimation.Loop.LOOP, a.loop)
        val scope = Molang.Scope()
        val pose = GeoModel.Pose(model.bones.size)
        fun at(t: Double): Float {
            pose.reset()
            scope.query = { if (it == "anim_time") t else 0.0 }
            GeoAnimation.sample(a, t, model, pose, scope)
            return pose.rot[model.index["right_wing"]!! * 3 + 2]
        }
        // The wing's z rotation follows (sin(t·180) + 0.125)·0.8 rad: it flaps.
        assertEquals(((Math.sin(Math.toRadians(0.5 * 180)) + 0.125) * 0.8 * 57.29577951308232).toFloat(), at(0.5), 1e-3f)
        assertTrue(Math.abs(at(0.5) - at(1.5)) > 50f)
        val anim = CosmeticAnimator(model, anims, CosmeticAnimator.controllers(com.google.gson.JsonParser.parseString(
            """{"state_machine":{"name":"single","anim":"animation.dragon_wings.flap"}}""").asJsonObject, anims), null, emptyMap(), null)
        val inst = anim.Instance(0.0)
        val a1 = inst.update(0.5) { 0.0 }.parts.values.first().copyOf()
        val a2 = inst.update(1.5) { 0.0 }.parts.values.first().copyOf()
        assertTrue(!a1.contentEquals(a2), "the baked wings move")
    }

    @Test
    fun ease() {
        for (e in listOf("linear", "easeInOutQuad", "easeOutBack", "easeInElastic", "easeOutBounce", "easeInOutExpo", "easeInOutCirc")) {
            assertEquals(0.0, GeoAnimation.ease(e, 0.0), 1e-9, e)
            assertEquals(1.0, GeoAnimation.ease(e, 1.0), 1e-9, e)
        }
        assertEquals(0.0, GeoAnimation.ease("step", 0.7))
    }

    @Test
    fun legacyObj() {
        val obj = LegacyModel.parseObj(String(res("beaniehat.obj")))
        assertTrue(obj.quads.size / GeoModel.QUAD_FLOATS >= 60)
        val defs = LegacyModel.parseDefinitions("""{"beaniehat":{"name":"beaniehat","bodyPart":"HEAD","showWithArmor":true,"transformations":[
            {"transformType":"translate","values":{"gui":{"x":0,"y":9,"z":0},"player":{"x":0,"y":1.45,"z":0},"helmet":{"x":0,"y":0.1,"z":0}}}]}}""")
        val d = defs["beaniehat"]!!
        assertTrue(d.head)
        val m = LegacyModel.placement(d, false, 0f, LegacyModel.Wearing.NONE)
        assertEquals(1.45f, m.m31(), 1e-5f)
        val withHelmet = LegacyModel.placement(d, false, 0f, LegacyModel.Wearing(helmet = true))
        assertEquals(1.55f, withHelmet.m31(), 1e-5f)
    }

    @Test
    fun conditionsRoundTrip() {
        val u = UUID.randomUUID()
        val bytes = LunarWire.playerCosmeticsPush(u, listOf(1), listOf(
            LunarWire.Condition(LunarWire.Condition.IN_LIQUID, 1) to listOf(2),
            LunarWire.Condition(LunarWire.Condition.SERVER, 0, "hypixel.net") to listOf(3),
            LunarWire.Condition(LunarWire.Condition.ON_FIRE) to listOf(4),
        ))
        val p = LunarWire.playerCosmetics(bytes)!!
        assertEquals(listOf(1), p.defaults.map { it.id })
        assertEquals(listOf(LunarWire.Condition(6, 1), LunarWire.Condition(9, 0, "hypixel.net"), LunarWire.Condition(8)), p.conditional.map { it.first })
        assertEquals(listOf(listOf(2), listOf(3), listOf(4)), p.conditional.map { c -> c.second.map { it.id } })
    }

    @Test
    fun emoteSkinning() {
        val body = Bobj.parse(String(res("default_simple.bobj")))
        assertTrue(body.bones.size > 10, "bones ${body.bones.size}")
        val mesh = body.meshes.first { it.name == "body" }
        val actions = Bobj.parse(String(res("actions-9.bobj")), body.bones).actions
        val pocoyo = actions["emote_pocoyo"]!!
        assertTrue(pocoyo.length > 10f)
        val world = Array(body.bones.size) { org.joml.Matrix4f() }
        val skin = Array(body.bones.size) { org.joml.Matrix4f() }
        val out = FloatArray(Bobj.size(mesh))
        // At rest the skinned mesh is the mesh as written.
        Bobj.pose(body.bones, null, 0f, world, skin)
        Bobj.skin(mesh, skin, out)
        val v0 = mesh.corners[0]
        assertEquals(mesh.positions[v0 * 3 + 1], out[1], 1e-4f)
        // Playing the emote moves it, staying around the player (within 3 blocks).
        val moved = FloatArray(out.size)
        Bobj.pose(body.bones, pocoyo, pocoyo.length / 2, world, skin)
        Bobj.skin(mesh, skin, moved)
        assertTrue(!moved.contentEquals(out))
        for (i in moved.indices step GeoModel.QUAD_FLOATS) for (v in 0 until 4) {
            val o = i + v * 5
            assertTrue(Math.abs(moved[o]) < 3 && moved[o + 1] > -1.5f && moved[o + 1] < 4f && Math.abs(moved[o + 2]) < 3, "vertex ${moved[o]} ${moved[o + 1]} ${moved[o + 2]}")
        }
    }

    @Test
    fun bezierCurve() {
        // A straight-line Bézier is linear.
        assertEquals(5f, Bobj.bezier(0f, 0f, 3.333f, 3.333f, 6.667f, 6.667f, 10f, 10f, 5f), 1e-2f)
        // Flat handles ease in and out.
        val mid = Bobj.bezier(0f, 0f, 5f, 0f, 5f, 10f, 10f, 10f, 2f)
        assertTrue(mid > 0f && mid < 2f, "$mid")
    }

    @Test
    fun emotePushes() {
        val u = UUID.randomUUID()
        val use = LunarWire.emotePush(u, 68)
        assertEquals(u, LunarWire.emotePlayer(use))
        assertEquals(68, LunarWire.emoteId(use))
        val frame = LunarWire.pushFrame(LunarEmotes.USE_PUSH, use)
        assertEquals("type.googleapis.com/${LunarEmotes.USE_PUSH}", LunarWire.pushes(frame).single().first)
    }

    @Test
    fun particles() {
        val aura = Snowstorm.parse(String(res("sparkleaura.particle.json")))
        val e = Snowstorm.Emitter(aura, java.util.Random(3))
        e.x = 10.0; e.y = 64.0; e.z = 10.0
        repeat(60) { e.update(1.0 / 20) { _, _, _ -> false } }
        assertTrue(e.particles.isNotEmpty())
        // The aura's box: particles around the emitter (within its half size plus their travel).
        for (p in e.particles) assertTrue(Math.abs(p.x - 10.0) < 3 && p.y > 62 && p.y < 67 && Math.abs(p.z - 10.0) < 3, "${p.x} ${p.y} ${p.z}")
        val rain = Snowstorm.parse(String(res("make_it_rain.particle.json")))
        assertEquals("low_left_arm.end", rain.bone)
        e.stop()
        repeat(200) { e.update(1.0 / 20) { _, _, _ -> false } }
        assertTrue(e.done)
    }

    /** A raw protobuf message, for building Lunar's outfits as its server sends them. */
    private fun msg(write: (com.google.protobuf.CodedOutputStream) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val c = com.google.protobuf.CodedOutputStream.newInstance(out)
        write(c)
        c.flush()
        return out.toByteArray()
    }

    @Test
    fun outfits() {
        val outfitId = UUID.randomUUID()
        // An equipped cosmetic with options Lunar set (cloth_cloak, show_over_helmet) that must survive.
        val cloak = msg { it.writeInt32(1, 100); it.writeBool(3, true); it.writeBool(5, true) }
        val hat = msg { it.writeInt32(1, 200) }
        val outfit = msg {
            it.writeByteArray(1, LunarWire.uuid(outfitId))
            it.writeString(2, "Main")
            it.writeByteArray(3, cloak)
            it.writeByteArray(3, hat)
            it.writeBool(4, true)
            it.writeString(7, "skinhash")
            it.writeInt32(8, 12)
        }
        val conditional = msg { it.writeByteArray(2, LunarWire.uuid(UUID.randomUUID())) }
        val tree = msg { it.writeByteArray(1, conditional); it.writeByteArray(2, LunarWire.uuid(outfitId)) }
        val login = msg {
            it.writeByteArray(6, msg { o -> o.writeInt32(1, 100) })
            it.writeByteArray(6, msg { o -> o.writeInt32(1, 300) })
            it.writeByteArray(10, outfit)
            it.writeByteArray(11, tree)
        }
        val (owned, all) = LunarWire.loginOwned(login)
        assertEquals(setOf(100, 300), owned)
        assertTrue(!all)
        val (list, t) = LunarWire.loginOutfits(login)
        val o = list.single()
        assertEquals(outfitId, o.id)
        assertEquals("Main", o.name)
        assertEquals(listOf(100, 200), o.cosmetics.map { it.first })
        assertEquals(outfitId, LunarWire.treeDefault(t!!))

        // Putting on 300 (a hat) replaces the hat 200 and keeps the cloak with its options.
        val types = mapOf(100 to "cloak", 200 to "hat", 300 to "hat", 400 to "pet")
        val planned = LunarAccount.plan(o.cosmetics, { types[it] }, 300, true)
        val rebuilt = LunarWire.outfit(LunarWire.withCosmetics(o, planned))
        assertEquals(listOf(100, 300), rebuilt.cosmetics.map { it.first })
        assertTrue(rebuilt.cosmetics.first().second.contentEquals(cloak), "the cloak's options stay")
        // Everything else in the outfit is as Lunar sent it.
        assertEquals(outfitId, rebuilt.id)
        assertEquals("Main", rebuilt.name)
        val rest = LunarWire.withCosmetics(o, emptyList())
        assertTrue(rest.size > 0 && LunarWire.outfit(rest).cosmetics.isEmpty())
        // Taking the cloak off leaves the hat.
        assertEquals(listOf(200), LunarAccount.plan(o.cosmetics, { types[it] }, 100, false).map { LunarWire.outfit(LunarWire.withCosmetics(o, listOf(it))).cosmetics.single().first })
        // A new default in the tree keeps its conditional outfits.
        val newId = UUID.randomUUID()
        val t2 = LunarWire.treeWithDefault(t, newId)
        assertEquals(newId, LunarWire.treeDefault(t2))
        var conditionals = 0
        com.google.protobuf.CodedInputStream.newInstance(t2).let { inp ->
            while (!inp.isAtEnd) { val tag = inp.readTag(); if (tag ushr 3 == 1) conditionals++; inp.skipField(tag) }
        }
        assertEquals(1, conditionals)
        // The request and reply shapes.
        val req = LunarWire.updateOutfitRequest(rebuilt.raw)
        assertTrue(req.size > rebuilt.raw.size)
        val reply = msg { it.writeEnum(1, 1); it.writeByteArray(2, rebuilt.raw) }
        val (status, saved) = LunarWire.outfitResponse(reply)
        assertEquals(1, status)
        assertEquals(listOf(100, 300), saved!!.cosmetics.map { it.first })
    }

    @Test
    fun logoAndBadgeInPushes() {
        val u = UUID.randomUUID()
        val p = LunarWire.playerCosmetics(LunarWire.playerCosmeticsPush(u, listOf(3), logo = 0x00112233, plus = 0x00FF00FF, badge = 194))!!
        assertEquals(0xFF112233.toInt(), p.logo) // alpha 0: opaque, as Moonlight reads it
        assertEquals(0xFFFF00FF.toInt(), p.plus)
        assertEquals(194, p.badge)
        assertEquals(listOf(3), p.defaults.map { it.id })
        val none = LunarWire.playerCosmetics(LunarWire.playerCosmeticsPush(u, emptyList()))!!
        assertEquals(null, none.logo)
        assertEquals(0, none.plus)
        assertEquals(0, none.badge)
    }

    @Test
    fun badgeIndex() {
        val json = """[{"id":322,"name":"Twitchcon","description":"","resource":"lunar:badges/lunar_twitchcon.webp","animated":false},
            {"id":1,"name":"Trophy","resource":"lunar:badges/trophy.webp","animated":true},{"name":"no id","resource":"lunar:x.webp"}]"""
        val b = LunarBadges.parse(json)
        assertEquals(2, b.size)
        assertEquals(LunarBadges.Badge(322, "Twitchcon", "assets/lunar/badges/lunar_twitchcon.webp", false), b[322])
        assertEquals(true, b[1]!!.animated)
        assertEquals("assets/lunar/badges/plus.webp", LunarBadges.path("lunar:badges/plus.webp"))
    }
}
