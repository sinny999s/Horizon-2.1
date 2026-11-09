package dev.horizon.client.lunar

import com.google.gson.JsonParser
import com.google.protobuf.Any
import com.google.protobuf.InvalidProtocolBufferException
import com.lunarclient.apollo.common.v1.LunarClientVersion
import com.lunarclient.apollo.common.v1.MinecraftVersion
import com.lunarclient.apollo.configurable.v1.OverrideConfigurableSettingsMessage
import com.lunarclient.apollo.player.v1.EmbeddedCheckoutSupport
import com.lunarclient.apollo.player.v1.PlayerHandshakeMessage
import com.lunarclient.apollo.transfer.v1.PingRequest
import com.lunarclient.apollo.transfer.v1.PingResponse
import com.lunarclient.apollo.transfer.v1.TransferRequest
import com.lunarclient.apollo.transfer.v1.TransferResponse

/**
 * The bytes of Lunar Client's server protocol, Apollo, after Moonlight's `ApolloHandler` and
 * `ApolloMessageHandler`, corrected to what Lunar Client itself sends ([LunarProfile]). Game-free, so it's tested on its own; [LunarApollo] puts it on the wire.
 * Every message on [APOLLO] is a protobuf `Any` around an Apollo message.
 */
object ApolloProto {
    const val APOLLO = "lunar:apollo"
    const val APOLLO_JSON = "apollo:json"

    /**
     * Lunar's handshake, as Lunar Client 2.23.14 builds it: its id for the Minecraft version played ([versionId], e.g.
     * "v1_8", "v26_3"), its version (build branch, full commit, "v<version>") and embedded checkouts in a window. No
     * `mod_status`: Lunar doesn't send it (Moonlight sent a made-up one).
     */
    fun handshake(versionId: String, version: String, gitBranch: String, gitCommit: String): ByteArray {
        val msg = PlayerHandshakeMessage.newBuilder()
            .setMinecraftVersion(MinecraftVersion.newBuilder().setEnum(versionId))
            .setLunarClientVersion(LunarClientVersion.newBuilder().setGitBranch(gitBranch).setGitCommit(gitCommit).setSemver(LunarProfile.semver(version)))
            .setEmbeddedCheckoutSupport(EmbeddedCheckoutSupport.EMBEDDED_CHECKOUT_SUPPORT_WINDOW)
            .build()
        return Any.pack(msg).toByteArray()
    }

    /** A message from the server, said in a few words, and the answer to send back on [APOLLO] (null: none). */
    data class Incoming(val what: String, val reply: ByteArray?)

    /**
     * What Moonlight answers: a ping with a ping response (same request id), a transfer request with a refusal (the
     * client stays where it is), module settings only logged. Anything else is left alone.
     */
    fun handle(data: ByteArray): Incoming {
        val any = try {
            Any.parseFrom(data)
        } catch (e: InvalidProtocolBufferException) {
            return Incoming("unreadable (${e.message})", null)
        }
        return when {
            any.`is`(PingRequest::class.java) -> {
                val req = any.unpack(PingRequest::class.java)
                Incoming("ping", Any.pack(PingResponse.newBuilder().setRequestId(req.requestId).build()).toByteArray())
            }
            any.`is`(TransferRequest::class.java) -> {
                val req = any.unpack(TransferRequest::class.java)
                val res = TransferResponse.newBuilder().setRequestId(req.requestId).setStatus(TransferResponse.Status.STATUS_REJECTED).build()
                Incoming("transfer to ${req.serverIp} (refused)", Any.pack(res).toByteArray())
            }
            any.`is`(OverrideConfigurableSettingsMessage::class.java) ->
                Incoming("settings for ${any.unpack(OverrideConfigurableSettingsMessage::class.java).configurableSettingsCount} module(s)", null)
            else -> Incoming(any.typeUrl.substringAfterLast('/'), null)
        }
    }

    /** The mod settings in a settings override (or a single ConfigurableSettings), else null. */
    fun settings(data: ByteArray): List<com.lunarclient.apollo.configurable.v1.ConfigurableSettings>? = try {
        val any = Any.parseFrom(data)
        when {
            any.`is`(OverrideConfigurableSettingsMessage::class.java) -> any.unpack(OverrideConfigurableSettingsMessage::class.java).configurableSettingsList
            any.`is`(com.lunarclient.apollo.configurable.v1.ConfigurableSettings::class.java) ->
                listOf(any.unpack(com.lunarclient.apollo.configurable.v1.ConfigurableSettings::class.java))
            else -> null
        }
    } catch (e: InvalidProtocolBufferException) {
        null
    }

    /**
     * A player as packet enrichment describes them (PlayerInfo): uuid, where (world, position, yaw, pitch), sneaking and
     * sprinting; with [full] (yourself) also jumping and the movement input's forward and strafe speeds, as Lunar sends.
     */
    class Player(val uuid: java.util.UUID, val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float,
                 val sneaking: Boolean, val sprinting: Boolean, val full: Boolean = true, val jumping: Boolean = false,
                 val forward: Float = 0f, val strafe: Float = 0f)

    private fun info(p: Player): com.lunarclient.apollo.packetenrichment.v1.PlayerInfo {
        val b = com.lunarclient.apollo.packetenrichment.v1.PlayerInfo.newBuilder()
            .setPlayerUuid(com.lunarclient.apollo.common.v1.Uuid.newBuilder().setHigh64(p.uuid.mostSignificantBits).setLow64(p.uuid.leastSignificantBits))
            .setLocation(com.lunarclient.apollo.common.v1.PlayerLocation.newBuilder()
                .setLocation(com.lunarclient.apollo.common.v1.Location.newBuilder().setWorld(p.world).setX(p.x).setY(p.y).setZ(p.z))
                .setYaw(p.yaw).setPitch(p.pitch))
            .setSneaking(p.sneaking).setSprinting(p.sprinting)
        if (p.full) b.setJumping(p.jumping).setForwardSpeed(p.forward).setStrafeSpeed(p.strafe)
        return b.build()
    }

    /** PacketInfo: when the message was made ([atMs], epoch ms, as seconds and nanos). */
    private fun packet(atMs: Long) = com.lunarclient.apollo.packetenrichment.v1.PacketInfo.newBuilder()
        .setInstantiationTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(Math.floorDiv(atMs, 1000L)).setNanos((Math.floorMod(atMs, 1000L) * 1_000_000).toInt()))
        .build()

    /**
     * PlayerAttackMessage: you ([attacker], full) hitting [target] (uuid, place, sneaking, sprinting). [distance] is what
     * Lunar on 1.20.5+ puts there: the crosshair hit's squared distance to the target's feet, not the reach.
     */
    fun attack(atMs: Long, attacker: Player, target: Player, distance: Double): ByteArray = Any.pack(
        com.lunarclient.apollo.packetenrichment.v1.PlayerAttackMessage.newBuilder().setPacketInfo(packet(atMs))
            .setTargetInfo(info(target)).setAttackerInfo(info(attacker)).setDistance(distance).build()).toByteArray()

    /** PlayerChatOpenMessage / PlayerChatCloseMessage. */
    fun chat(open: Boolean, atMs: Long, player: Player): ByteArray = Any.pack(
        if (open) com.lunarclient.apollo.packetenrichment.v1.PlayerChatOpenMessage.newBuilder().setPlayerInfo(info(player)).setPacketInfo(packet(atMs)).build()
        else com.lunarclient.apollo.packetenrichment.v1.PlayerChatCloseMessage.newBuilder().setPlayerInfo(info(player)).setPacketInfo(packet(atMs)).build()
    ).toByteArray()

    /** PlayerInventoryOpenMessage / PlayerInventoryCloseMessage (your own inventory, not a container). */
    fun inventory(open: Boolean, atMs: Long, player: Player): ByteArray = Any.pack(
        if (open) com.lunarclient.apollo.packetenrichment.v1.PlayerInventoryOpenMessage.newBuilder().setPlayerInfo(info(player)).setPacketInfo(packet(atMs)).build()
        else com.lunarclient.apollo.packetenrichment.v1.PlayerInventoryCloseMessage.newBuilder().setPlayerInfo(info(player)).setPacketInfo(packet(atMs)).build()
    ).toByteArray()

    /** PlayerUseItemMessage: using the item in the main hand or the offhand. */
    fun useItem(atMs: Long, player: Player, mainHand: Boolean): ByteArray = Any.pack(
        com.lunarclient.apollo.packetenrichment.v1.PlayerUseItemMessage.newBuilder().setPacketInfo(packet(atMs)).setPlayerInfo(info(player))
            .setMainHand(mainHand).build()).toByteArray()

    /** The world name in an UpdatePlayerWorldMessage (packet enrichment's locations carry it), else null. */
    fun world(data: ByteArray): String? = try {
        val any = Any.parseFrom(data)
        if (any.`is`(com.lunarclient.apollo.player.v1.UpdatePlayerWorldMessage::class.java))
            any.unpack(com.lunarclient.apollo.player.v1.UpdatePlayerWorldMessage::class.java).world else null
    } catch (e: InvalidProtocolBufferException) {
        null
    }

    /** A message on [APOLLO_JSON]: only its type ("@type"), which Moonlight logs. */
    fun jsonType(data: ByteArray): String = try {
        val o = JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject
        if (o.has("@type")) o.get("@type").asString else "unknown"
    } catch (e: Exception) {
        "unreadable"
    }
}
