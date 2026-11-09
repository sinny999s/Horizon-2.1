package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.module.misc.LunarSpoof
import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.impl.networking.RegistrationPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Lunar Spoof's Apollo side, after Moonlight's `ApolloHandler` and its custom payload mixin, but
 * doing what Lunar Client itself does: when a server (not singleplayer) registers channels, it registers back those of
 * Lunar's the server has ([LunarProfile.channelsFor], in a `minecraft:register` of their own), and sends the handshake
 * on `lunar:apollo` if the server has an Apollo channel ([LunarProfile.wantsHandshake]), claiming the Minecraft version
 * the server is joined as. A server that registers none of them gets nothing. Afterwards it answers what the server
 * sends on `lunar:apollo` ([ApolloProto.handle]). What the server's settings override asks of Lunar's mods is kept
 * ([blocks]) and said in chat: the Lunar mods it turns off (Horizon has none of them, so nothing changes here).
 *
 * The two Apollo channels are custom payload types in both directions, carrying raw bytes ([Raw]). They have no
 * receivers, so Fabric doesn't list them in its own channel registration (a server sees them only from the module, while
 * it's on); incoming ones are read from [AfterReceivePacketEvent] instead.
 */
object LunarApollo {
    /** A custom payload as bytes, on one of the Apollo channels. */
    class Raw(private val type: CustomPacketPayload.Type<Raw>, val bytes: ByteArray) : CustomPacketPayload {
        override fun type() = type

        /** The channel and the bytes in hex (ShotBot's packet log prints this). */
        override fun toString() = "${type.id()} ${bytes.joinToString("") { "%02x".format(it) }}"
    }

    private val types = HashMap<String, CustomPacketPayload.Type<Raw>>()

    /** What this server's Apollo settings ask of Lunar's mods (since joining). */
    @Volatile
    var blocks = ApolloBlocks.Summary()
        private set
    private val mc: Minecraft get() = Minecraft.getInstance()

    fun init() {
        for (channel in listOf(ApolloProto.APOLLO, ApolloProto.APOLLO_JSON)) {
            val type = CustomPacketPayload.Type<Raw>(Identifier.parse(channel))
            val codec: StreamCodec<ByteBuf, Raw> = CustomPacketPayload.codec({ value, buf -> buf.writeBytes(value.bytes) },
                { buf -> Raw(type, ByteArray(buf.readableBytes()).also { buf.readBytes(it) }) })
            try {
                PayloadTypeRegistry.serverboundPlay().register(type, codec)
                PayloadTypeRegistry.clientboundPlay().register(type, codec)
            } catch (e: IllegalArgumentException) {
                HorizonClient.LOG.warn("Lunar Spoof: {} is already registered by another mod", channel)
            }
            types[channel] = type
        }
        ClientPlayConnectionEvents.JOIN.register { handler, _, _ -> joined(handler) }
        // The server's channels, as Fabric learns them (all of them so far, not just the new ones, as Lunar keeps them).
        ServerboundPlayChannelEvents.REGISTER.register { handler, _, client, _ -> client.execute { serverRegistered(handler) } }
        EventBus.on<AfterReceivePacketEvent>(this) { e ->
            val raw = (e.packet as? ClientboundCustomPayloadPacket)?.payload() as? Raw ?: return@on
            received(raw)
        }
    }

    /** Says what's claimed on this server (with Notify on). */
    private fun joined(handler: ClientPacketListener) {
        blocks = ApolloBlocks.Summary()
        ApolloFeatures.clear()
        val module = LunarSpoof.instance ?: return
        if (!module.enabled || !module.notify.value || handler.connection.isMemoryConnection) return
        val versionId = LunarSpoof.versionId(handler.connection)
        val version = LunarVersion.version
        moduleMessage(module.name.trim(), when {
            versionId == null -> "not claiming Lunar here: Lunar doesn't run on this Minecraft version"
            version == null -> "Lunar's version isn't known yet: not claiming Lunar until it is"
            else -> "claiming Lunar Client ${LunarProfile.semver(version)} for ${LunarProfile.versionName(versionId)}" +
                if (module.brand.value && LunarSpoof.fabricBrand(versionId)) " with Fabric" else ""
        })
    }

    private fun serverRegistered(handler: ClientPacketListener) {
        val module = LunarSpoof.instance ?: return
        if (!module.enabled || !module.apollo.value || handler.connection.isMemoryConnection) return
        val versionId = LunarSpoof.versionId(handler.connection) ?: return
        val server = ClientPlayNetworking.getSendable().map(Identifier::toString)
        val ours = LunarProfile.channelsFor(server)
        if (ours.isNotEmpty()) handler.send(ServerboundCustomPayloadPacket(RegistrationPayload(RegistrationPayload.REGISTER, ours.map(Identifier::parse))))
        if (!LunarProfile.wantsHandshake(server)) return
        val version = LunarVersion.version
        val commit = LunarVersion.gitCommit
        if (version == null || commit == null) {
            HorizonClient.LOG.warn("Lunar Spoof: Lunar's version isn't known yet, so no Apollo handshake")
            moduleMessage(module.name.trim(), "Lunar's version isn't known yet: registered Lunar's channels but sent no handshake")
            return
        }
        val bytes = ApolloProto.handshake(versionId, version, LunarVersion.gitBranch, commit)
        send(ApolloProto.APOLLO, bytes)
        HorizonClient.LOG.info("Lunar Spoof: the server registered {}; registered {} back and sent the Apollo handshake (Lunar Client {}, {}, {} bytes)",
            server.filter { it in LunarProfile.LUNAR_CHANNELS }, ours, version, versionId, bytes.size)
        if (module.notify.value) moduleMessage(module.name.trim(), "Apollo server: claiming Lunar Client ${LunarProfile.semver(version)} ($versionId)")
    }

    private fun received(raw: Raw) {
        val module = LunarSpoof.instance ?: return
        if (!module.enabled || !module.apollo.value) return
        when (raw.type().id().toString()) {
            ApolloProto.APOLLO -> {
                val incoming = ApolloProto.handle(raw.bytes)
                HorizonClient.LOG.info("Lunar Spoof: Apollo {}{}", incoming.what, if (incoming.reply != null) ", answered" else "")
                incoming.reply?.let { send(ApolloProto.APOLLO, it) }
                ApolloProto.settings(raw.bytes)?.let { settings(it) }
                // What the server shows Lunar players (waypoints, team view, holograms...).
                try {
                    ApolloFeatures.handle(com.google.protobuf.Any.parseFrom(raw.bytes))
                } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                }
            }
            ApolloProto.APOLLO_JSON -> HorizonClient.LOG.info("Lunar Spoof: Apollo JSON {}", ApolloProto.jsonType(raw.bytes))
        }
    }

    /** A settings override: kept, and the newly blocked Lunar mods said in chat. */
    fun settings(list: List<com.lunarclient.apollo.configurable.v1.ConfigurableSettings>) {
        val before = blocks.blocked.toSet()
        blocks.add(list)
        val added = blocks.blocked - before
        HorizonClient.LOG.info("Lunar Spoof: Apollo settings: blocks {}, sets {}", blocks.blocked, blocks.forced)
        val module = LunarSpoof.instance ?: return
        if (added.isEmpty() || !module.notify.value) return
        mc.execute {
            moduleMessage(module.name.trim(), "this server turns off Lunar's " + added.joinToString(", ") { ApolloBlocks.modName(it) } +
                " (Lunar mods; Horizon's modules aren't affected). Lunar Spoof's apollo-settings lists everything it asks")
        }
    }

    /** Says in chat everything this server's Apollo settings ask (Lunar Spoof's `apollo-settings`). */
    fun tellBlocks() {
        val module = LunarSpoof.instance ?: return
        val name = module.name.trim()
        val b = blocks
        if (b.isEmpty && b.allowed.isEmpty() && b.server.isEmpty()) {
            moduleMessage(name, "this server hasn't sent Apollo mod settings")
            return
        }
        moduleMessage(name, if (b.blocked.isEmpty()) "Lunar mods turned off: none" else "Lunar mods turned off: " + b.blocked.joinToString(", ") { ApolloBlocks.modName(it) })
        if (b.forced.isNotEmpty()) moduleMessage(name, "set by the server: " + b.forced.entries.joinToString(", ") { "${ApolloBlocks.optionName(it.key)} = ${it.value}" })
        if (b.server.isNotEmpty()) moduleMessage(name, "server rules: " + b.server.entries.joinToString(", ") { "${ApolloBlocks.optionName(it.key)} = ${it.value}" })
    }

    /** For ShotBot and tests: as if the server sent this settings override. */
    @JvmStatic
    fun testSettings(bytes: ByteArray) {
        ApolloProto.settings(bytes)?.let { settings(it) }
    }

    private fun send(channel: String, bytes: ByteArray) {
        mc.connection?.send(ServerboundCustomPayloadPacket(Raw(types.getValue(channel), bytes)))
    }
}
