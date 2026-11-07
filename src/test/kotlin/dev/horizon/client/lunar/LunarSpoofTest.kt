package dev.horizon.client.lunar

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.lunarclient.apollo.configurable.v1.OverrideConfigurableSettingsMessage
import com.lunarclient.apollo.player.v1.EmbeddedCheckoutSupport
import com.lunarclient.apollo.player.v1.PlayerHandshakeMessage
import com.lunarclient.apollo.transfer.v1.PingRequest
import com.lunarclient.apollo.transfer.v1.PingResponse
import com.lunarclient.apollo.transfer.v1.TransferRequest
import com.lunarclient.apollo.transfer.v1.TransferResponse
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lunar Spoof's game-free parts: what Lunar claims, Apollo messages, Lunar's websocket/auth messages, the version lookup. */
class LunarSpoofTest {
    private val player = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    // ---- what Lunar claims ----

    @Test
    fun versionIds() {
        // Minecraft's and ViaVersion's names to Lunar's ids.
        assertEquals("v26_3", LunarProfile.versionId("26.3"))
        assertEquals("v1_8", LunarProfile.versionId("1.8.x"))
        assertEquals("v1_8", LunarProfile.versionId("1.8.9"))
        assertEquals("v1_7", LunarProfile.versionId("1.7.6-1.7.10"))
        assertEquals("v1_12", LunarProfile.versionId("1.12.2"))
        assertEquals("v1_21_11", LunarProfile.versionId("1.21.11"))
        assertEquals("v1_21_0", LunarProfile.versionId("1.21"))
        assertEquals("v26_1_1", LunarProfile.versionId("26.1.1"))
        assertEquals("v26_1", LunarProfile.versionId("26.1"))
        // Versions Lunar doesn't run on: nothing claimed.
        assertNull(LunarProfile.versionId("1.16.4"))
        assertNull(LunarProfile.versionId("1.13.2"))
        assertNull(LunarProfile.versionId("26.4"))
        assertNull(LunarProfile.versionId("b1.7.3"))
        assertNull(LunarProfile.versionId("c0.30"))
    }

    @Test
    fun brandIsLunars() {
        assertEquals("lunarclient:v2.23.14-2645", LunarProfile.brand("2.23.14-2645"))
        assertEquals("lunarclient:v2.23.14-2645", LunarProfile.brand("v2.23.14-2645"))
    }

    @Test
    fun channelsAnswerTheServer() {
        // A server without Lunar's channels gets nothing.
        val vanilla = listOf("minecraft:brand", "hypixel:hello", "fabric:recipe_sync")
        assertEquals(emptyList(), LunarProfile.channelsFor(vanilla))
        assertFalse(LunarProfile.wantsHandshake(vanilla))
        // An Apollo server: the Lunar channels it has, in Lunar's order, and the handshake. Never transfer:channel.
        val apollo = listOf("transfer:channel", "apollo:json", "lunar:apollo", "bungeecord:main")
        assertEquals(listOf("lunar:apollo", "apollo:json"), LunarProfile.channelsFor(apollo))
        assertTrue(LunarProfile.wantsHandshake(apollo))
        // badlion:timers alone: registered back, but no Apollo handshake.
        assertEquals(listOf("badlion:timers"), LunarProfile.channelsFor(listOf("badlion:timers")))
        assertFalse(LunarProfile.wantsHandshake(listOf("badlion:timers")))
        assertTrue(LunarProfile.wantsHandshake(listOf("lunarclient:pm")))
    }

    // ---- Apollo ----

    @Test
    @Suppress("DEPRECATION")
    fun handshakeIsLunars() {
        for (id in listOf("v1_8", "v26_3")) {
            val bytes = ApolloProto.handshake(id, "2.23.14-2645", "master", "0123456789abcdef0123456789abcdef01234567")
            val any = Any.parseFrom(bytes)
            assertEquals("type.googleapis.com/lunarclient.apollo.player.v1.PlayerHandshakeMessage", any.typeUrl)
            val msg = any.unpack(PlayerHandshakeMessage::class.java)
            assertEquals(id, msg.minecraftVersion.enum)
            assertEquals("master", msg.lunarClientVersion.gitBranch)
            assertEquals("0123456789abcdef0123456789abcdef01234567", msg.lunarClientVersion.gitCommit)
            assertEquals("v2.23.14-2645", msg.lunarClientVersion.semver)
            assertEquals(EmbeddedCheckoutSupport.EMBEDDED_CHECKOUT_SUPPORT_WINDOW, msg.embeddedCheckoutSupport)
            // Lunar sends no mod_status.
            assertEquals(0, msg.modStatusCount)
        }
    }

    @Test
    fun pingIsAnswered() {
        val id = ByteString.copyFromUtf8(UUID.randomUUID().toString())
        val req = Any.pack(PingRequest.newBuilder().setRequestId(id).addServerIps("mc.example.net").build()).toByteArray()
        val res = ApolloProto.handle(req)
        assertEquals("ping", res.what)
        val reply = Any.parseFrom(assertNotNull(res.reply))
        assertTrue(reply.`is`(PingResponse::class.java))
        assertEquals(id, reply.unpack(PingResponse::class.java).requestId)
    }

    @Test
    fun transferIsRefused() {
        val id = ByteString.copyFromUtf8(UUID.randomUUID().toString())
        val req = Any.pack(TransferRequest.newBuilder().setRequestId(id).setServerIp("evil.example.net").build()).toByteArray()
        val res = ApolloProto.handle(req)
        assertTrue("evil.example.net" in res.what)
        val reply = Any.parseFrom(assertNotNull(res.reply)).unpack(TransferResponse::class.java)
        assertEquals(id, reply.requestId)
        assertEquals(TransferResponse.Status.STATUS_REJECTED, reply.status)
    }

    @Test
    fun othersAreNotAnswered() {
        val settings = Any.pack(OverrideConfigurableSettingsMessage.getDefaultInstance()).toByteArray()
        assertNull(ApolloProto.handle(settings).reply)
        assertNull(ApolloProto.handle(byteArrayOf(0x0a, 0x7f, 0x01)).reply) // truncated: unreadable
        assertEquals("lunarclient.apollo.common.v1.Unknown", ApolloProto.handle(Any.newBuilder().setTypeUrl("type.googleapis.com/lunarclient.apollo.common.v1.Unknown").build().toByteArray()).what)
        assertEquals("lunarclient.apollo.x.Y", ApolloProto.jsonType("""{"@type":"lunarclient.apollo.x.Y"}""".toByteArray()))
        assertEquals("unreadable", ApolloProto.jsonType("nope".toByteArray()))
    }

    // ---- Lunar's services ----

    /** Field number -> raw value bytes (length-delimited) or the varint/fixed value as a long. */
    private fun read(bytes: ByteArray): List<Pair<Int, kotlin.Any>> {
        val out = ArrayList<Pair<Int, kotlin.Any>>()
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            out += (tag ushr 3) to when (tag and 7) {
                0 -> input.readInt64()
                1 -> input.readFixed64()
                2 -> input.readBytes().toByteArray()
                else -> error("wire type ${tag and 7}")
            }
        }
        return out
    }

    @Test
    fun uuidsRoundTrip() {
        val u = UUID.randomUUID()
        assertEquals(listOf(1 to u.mostSignificantBits, 2 to u.leastSignificantBits), read(LunarWire.uuid(u)))
        assertEquals(u, LunarWire.readUuid(LunarWire.uuid(u)))
        val list = read(LunarWire.uuidList(listOf(u, player)))
        assertEquals(2, list.size)
        assertEquals(player, LunarWire.readUuid(list[1].second as ByteArray))
    }

    @Test
    fun helloIsMoonlights() {
        val hello = read(LunarWire.authServerbound(1, LunarWire.hello(player, "Notch")))
        assertEquals(1, hello.single().first)
        val inner = read(hello.single().second as ByteArray)
        assertEquals("assetServer", String(inner[1].second as ByteArray))
        val identity = read(inner[0].second as ByteArray)
        assertEquals(player, LunarWire.readUuid(identity[0].second as ByteArray))
        assertEquals("Notch", String(identity[1].second as ByteArray))
    }

    @Test
    fun authenticatorMessages() {
        val request = ByteArrayOutputStream().also { b ->
            val o = com.google.protobuf.CodedOutputStream.newInstance(b)
            o.writeBytes(1, ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            o.writeBytes(2, ByteString.copyFrom(byteArrayOf(9, 9)))
            o.flush()
        }.toByteArray()
        val wrapped = LunarWire.authServerbound(1, request) // same shape as the clientbound wrapper
        val (field, inner) = LunarWire.authClientbound(wrapped)
        assertEquals(1, field)
        val (key, random) = assertNotNull(LunarWire.encryptionRequest(inner))
        assertContentEquals(byteArrayOf(1, 2, 3), key)
        assertContentEquals(byteArrayOf(9, 9), random)
        assertNull(LunarWire.encryptionRequest(LunarWire.encryptionFailure("x")))
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLWJ5dGVz"
        val secret = ByteArray(16) { (it * 7 + 3).toByte() }
        // AuthSuccessMessage { string jwt = 1; bytes encrypted_jwt = 2 }: plain, as Moonlight knew it.
        assertEquals(jwt, LunarWire.authToken(proto { it.writeString(1, jwt) }, null))
        // Encrypted, as Lunar sends it now: AES-GCM under the secret key, a 12-byte IV in front (Lunar Client's own way).
        val iv = ByteArray(12) { (it + 40).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(secret, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        val encrypted = iv + cipher.doFinal(jwt.toByteArray())
        val success = proto { it.writeBytes(2, ByteString.copyFrom(encrypted)) }
        assertEquals(jwt, LunarWire.authToken(success, secret))
        // A wrong key or none: no token (GCM's tag check fails rather than giving garbage).
        assertNull(LunarWire.authToken(success, ByteArray(16)))
        assertNull(LunarWire.authToken(success, null))
        assertNull(LunarWire.authToken(proto { it.writeInt64(3, 99) }, secret))
        // The log's shape never shows the token.
        val shape = LunarWire.shape(LunarWire.authServerbound(2, success))
        assertEquals("2:{2:bytes(${encrypted.size})}", shape)
        // Minecraft's server hash: Notch's well-known test vectors use the same signed-hex digest.
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", LunarWire.serverId("Notch".toByteArray(), ByteArray(0)))
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", LunarWire.serverId("jeb_".toByteArray(), ByteArray(0)))
    }

    @Test
    fun gameHandshakeIsLunars() {
        val msg = read(LunarWire.gameHandshake(player, "Notch", "jwt!", "v26_3", "2.23.14-2645", "master", "abcdef0123456789", "win32", "x64"))
        assertEquals(listOf(1, 2, 5, 6, 7, 8), msg.map { it.first })
        val identity = read(msg[0].second as ByteArray)
        assertEquals(2L, identity[1].second) // TYPE_MICROSOFT
        assertEquals("jwt!", String(identity[2].second as ByteArray))
        assertEquals("win32", String(msg[2].second as ByteArray))
        val game = read(msg[5].second as ByteArray)
        assertEquals("v26_3", String(read(game[0].second as ByteArray)[0].second as ByteArray))
        assertEquals(listOf("master", "abcdef0123456789", "v2.23.14-2645"), read(game[1].second as ByteArray).map { String(it.second as ByteArray) })
    }

    private fun frame(id: String?, output: ByteArray): ByteArray {
        val rpc = ByteArrayOutputStream().also { b ->
            val o = com.google.protobuf.CodedOutputStream.newInstance(b)
            if (id != null) o.writeBytes(1, ByteString.copyFromUtf8(id))
            o.writeBytes(2, ByteString.copyFrom(output))
            o.flush()
        }.toByteArray()
        return LunarWire.authServerbound(1, rpc)
    }

    private fun proto(write: (com.google.protobuf.CodedOutputStream) -> Unit) =
        ByteArrayOutputStream().also { b -> com.google.protobuf.CodedOutputStream.newInstance(b).also(write).flush() }.toByteArray()

    @Test
    fun tabLogosAndLogin() {
        val other = UUID.randomUUID()
        val logos = proto { o ->
            o.writeBytes(1, ByteString.copyFrom(proto { l ->
                l.writeBytes(1, ByteString.copyFrom(LunarWire.uuid(player)))
                l.writeBytes(2, ByteString.copyFrom(proto { it.writeInt32(1, 0x00FF8800) })) // alpha 0: opaque
                l.writeBytes(3, ByteString.copyFrom(proto { it.writeInt32(1, 0x12345678) }))
                l.writeInt32(4, 7)
            }))
            o.writeBytes(1, ByteString.copyFrom(proto { l -> l.writeBytes(1, ByteString.copyFrom(LunarWire.uuid(other))) }))
        }
        val replies = LunarWire.replies(frame("5", logos))
        assertEquals("5", replies.single().first)
        val parsed = LunarWire.tabLogos(replies.single().second)
        // Lunar+ colour: its RGB, opaque.
        assertEquals(LunarWire.TabLogo(player, 0xFFFF8800.toInt(), 7, 0xFF345678.toInt()), parsed[0])
        assertEquals(LunarWire.TabLogo(other, -1, 0), parsed[1]) // no colour: white
        val login = proto { o -> o.writeBytes(1, ByteString.copyFrom(proto { it.writeInt32(1, 0x8000FF00.toInt()) })); o.writeBool(2, true) }
        assertEquals(0x8000FF00.toInt(), LunarWire.loginColor(login))
        assertNull(LunarWire.loginColor(proto { it.writeBool(2, true) }))
        assertEquals(0, LunarWire.loginPlus(login))
        assertEquals(0xFF00AAFF.toInt(), LunarWire.loginPlus(proto { o -> o.writeBool(2, true); o.writeBytes(3, ByteString.copyFrom(proto { it.writeInt32(1, 0x0000AAFF) })) }))
        // BadgeService.Login: owned badges (1), the one worn (2).
        assertEquals(42, LunarWire.loginBadge(proto { o -> o.writeBytes(1, ByteString.copyFrom(proto { it.writeInt32(1, 5) })); o.writeInt32(2, 42); o.writeBool(3, false) }))
        assertEquals(0, LunarWire.loginBadge(ByteArray(0)))
        val call = read(LunarWire.call("12", LunarWire.COSMETIC_SERVICE, "LoadTabLogos", byteArrayOf(1)))
        assertEquals(listOf("12", LunarWire.COSMETIC_SERVICE, "LoadTabLogos"), call.take(3).map { String(it.second as ByteArray) })
    }

    @Test
    fun osAndArchNames() {
        assertEquals("win32", LunarWire.os("Windows 11"))
        assertEquals("darwin", LunarWire.os("Mac OS X"))
        assertEquals("linux", LunarWire.os("Linux"))
        assertEquals("arm64", LunarWire.arch("aarch64"))
        assertEquals("x64", LunarWire.arch("amd64"))
    }

    // ---- version lookup ----

    private fun zip(entries: Map<String, String>, stored: Boolean = false): ByteArray = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z ->
            for ((name, text) in entries) {
                val data = text.toByteArray()
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = java.util.zip.CRC32().also { it.update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun ranges(bytes: ByteArray, log: MutableList<LongRange>? = null) = LunarJar.Ranges { s, e ->
        log?.add(s..e)
        bytes.copyOfRange(s.toInt(), minOf(e.toInt() + 1, bytes.size))
    }

    @Test
    fun buildDataIsFoundInTheJar() {
        val filler = (1..300).associate { "net/minecraft/C$it.class" to "x".repeat(it * 7) }
        val build = "lunarVersion=2.18.3-2451\nfullGitHash=0123456789abcdef0123456789abcdef01234567\nother=1\n"
        for (stored in listOf(false, true)) {
            val jar = zip(filler + ("lunarBuildData.txt" to build) + ("z.txt" to "after"), stored)
            val log = ArrayList<LongRange>()
            val found = LunarJar.find(jar.size.toLong(), ranges(jar, log))
            assertEquals(LunarJar.Build("2.18.3-2451", "0123456789abcdef0123456789abcdef01234567"), found)
            // Only small parts are read: the tail, the directory, a header and the entry.
            assertTrue(log.size <= 4)
        }
        assertNull(LunarJar.find(zip(filler).size.toLong(), ranges(zip(filler))))
        assertNull(LunarJar.find(zip(mapOf("lunarBuildData.txt" to "lunarVersion=1")).let { it.size.toLong() }, ranges(zip(mapOf("lunarBuildData.txt" to "lunarVersion=1")))))
        assertEquals(-1, LunarJar.eocd(ByteArray(100)))
    }

    @Test
    fun launchApi() {
        val body = com.google.gson.JsonParser.parseString(LunarJar.launchBody("win32", "x64", player, "10.0")).asJsonObject
        assertEquals("1.8.9", body.get("version").asString)
        assertEquals("OFFLINE", body.get("launch_type").asString)
        assertEquals(player.toString(), body.get("installation_id").asString)
        val answer = """{"launchTypeData":{"artifacts":[{"name":"natives.zip","url":"a"},{"name":"lunar.jar","url":"https://x/lunar.jar"}]}}"""
        assertEquals("https://x/lunar.jar", LunarJar.jarUrl(answer))
        assertNull(LunarJar.jarUrl("""{"success":false}"""))
    }

    /** The real lookup, against Lunar's API: run with LUNAR_LIVE=1 (it isn't part of a normal test run). */
    @Test
    fun liveLookup() {
        if (System.getenv("LUNAR_LIVE") != "1") return
        val b = LunarJar.fetch()
        println("LIVE: Lunar Client ${b.version} ${b.gitCommit}")
        assertTrue(b.version.isNotEmpty() && b.gitCommit.length >= 7)
    }
}
