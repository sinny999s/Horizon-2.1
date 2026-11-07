package dev.horizon.client.lunar

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Horizon share's sign-in body ([HorizonShare.authBody]). A stand-in "Mojang" key signs a player certificate the way
 * Mojang does (vanilla's ProfilePublicKey.Data.signedPayload, SHA1withRSA). The body is written to
 * `build/share-auth-fixture.json` for `share-worker/test/cert-test.mjs`, which checks it with the service's own code.
 */
class HorizonShareTest {
    @Test
    fun authBody() {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val mojang = gen.generateKeyPair()
        val player = gen.generateKeyPair()
        val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        val expiresAt = System.currentTimeMillis() + 3600_000L
        val keyDer = player.public.encoded
        val payload = ByteBuffer.allocate(24 + keyDer.size).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
            .putLong(expiresAt).put(keyDer).array()
        val keySig = Signature.getInstance("SHA1withRSA").apply { initSign(mojang.private); update(payload) }.sign()
        val time = System.currentTimeMillis()

        val body = HorizonShare.authBody(uuid, expiresAt, keyDer, keySig, player.private, time)
        assertEquals("069a79f444e94726a5befca90e38aaf5", body.get("uuid").asString)
        val sig = Base64.getDecoder().decode(body.get("signature").asString)
        assertTrue(Signature.getInstance("SHA256withRSA").apply {
            initVerify(player.public)
            update("horizon-share auth 069a79f444e94726a5befca90e38aaf5 $time".toByteArray())
        }.verify(sig))

        body.addProperty("mojangKey", Base64.getEncoder().encodeToString(mojang.public.encoded))
        body.addProperty("now", time)
        Files.writeString(Path.of("build/share-auth-fixture.json"), body.toString())
    }
}
