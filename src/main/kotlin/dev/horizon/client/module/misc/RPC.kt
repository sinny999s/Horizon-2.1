package dev.horizon.client.module.misc

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.TickEvent
import dev.horizon.client.event.TitleScreenInitEvent
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.gui.UrlTextures
import dev.horizon.client.lunar.ApolloFeatures
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.MoveFix
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Overlay
import dev.horizon.client.render.Theme
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.properties.ObservableProperty
import kotlin.reflect.KProperty

/**
 * Discord rich presence over Discord's local IPC (its own client, not a library). The module has no handlers of its own:
 * an always-on driver checks its enabled state every tick and, every 5 s while on, sets the presence ("Playing on <ip>",
 * "public beta test", an image and button); turning it off closes the connection. On the very first title screen it
 * switches itself, assistant and move-fix on and asks "Enable Discord RPC?".
 */
class RPC : Module("RPC", Categories.MISC) {
    init {
        Driver.module = this
        EventBus.on<TickEvent>(Driver) { Driver.tick() }
        EventBus.on<TitleScreenInitEvent>(Driver) { Driver.titleScreen() }
        // Closes the connection on shutdown.
        Runtime.getRuntime().addShutdownHook(Thread { Presence.disconnect() })
    }

    /** Always on the bus, whatever the module state. */
    private object Driver {
        lateinit var module: RPC
        private val timer = MsTimer()
        private var titleSeen = false

        fun tick() {
            if (module.enabled) {
                if (!timer.passedAndReset(5000)) return
                Presence.appId = APP_ID
                val (details, state) = details()
                if (details.isNotEmpty()) Presence.details = details
                Presence.state = state
                Presence.large = "main" to "Horizon"
            } else if (Presence.connected) {
                Presence.disconnect()
            }
        }

        /**
         * The status lines: what an Apollo server says you're doing (its rich presence, through Lunar Spoof), else
         * "Playing on <ip>"; "Selecting server..." in the server list.
         */
        private fun details(): Pair<String, String?> {
            val mc = Minecraft.getInstance()
            val server = mc.connection?.serverData
            if (mc.connection != null && server != null) {
                val name = server.name.takeIf { it.isNotBlank() && it != Component.translatable("selectServer.defaultName").string } ?: server.ip
                ApolloFeatures.richPresence?.discord(name, SharedConstants.getCurrentVersion().name())?.let { return it }
                return "Playing on " + server.ip to null
            }
            return (if (mc.gui.screen() is JoinMultiplayerScreen) "Selecting server..." else "") to null
        }

        /** The first title screen ever (per `rpcAsked`) turns these on and asks. */
        fun titleScreen() {
            if (!titleSeen && !Storage.shared.rpcAsked) {
                module.enabledSetting.value = true
                Modules.get<MoveFix>().enabledSetting.value = true
                Minecraft.getInstance().gui.setScreen(ConfirmScreen(module))
                Storage.shared.rpcAsked = true
            }
            titleSeen = true
        }
    }

    private companion object {
        /** The Discord application. */
        const val APP_ID = 1238952344641732699L
    }
}

// ---- the presence and the IPC client ----

/**
 * What the presence shows, and the connection. Changing [appId] (re)connects; changing anything
 * else builds a new activity, sent by the update thread (every 5 s once Discord said READY) or at READY. Values are
 * only acted on when they change, so turning the module off and on again doesn't reconnect (on purpose).
 */
private object Presence {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        @OptIn(ExperimentalSerializationApi::class)
        namingStrategy = JsonNamingStrategy.SnakeCase
    }
    private val dirs = arrayOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP")
    private val startedAt = Instant.now().epochSecond
    val noUser = User("none", "none", "none")

    @Volatile
    var user = noUser

    @Volatile
    private var connection: IpcConnection? = null

    @Volatile
    private var ready = false

    @Volatile
    private var pending: Activity? = null

    var appId: Long? by Watched(null, true)
    var details: String? by Watched(null)
    var state: String? by Watched(null)
    var large: Pair<String, String>? by Watched(null)
    var small: Pair<String, String>? by Watched(null)
    var button1: Pair<String, String>? by Watched(null)
    var button2: Pair<String, String>? by Watched(null)

    val connected get() = connection != null

    private class Watched<T>(initial: T, private val isAppId: Boolean = false) : ObservableProperty<T>(initial) {
        override fun afterChange(property: KProperty<*>, oldValue: T, newValue: T) {
            if (oldValue == newValue) return
            if (isAppId) connect(newValue as Long) else pending = build()
        }
    }

    private fun build(): Activity {
        val buttons = ArrayList<Button>()
        button1?.let { buttons += Button(it.first, it.second) }
        button2?.let { buttons += Button(it.first, it.second) }
        val large = large
        val small = small
        val assets = if (large != null || small != null) Assets(large?.first, large?.second, small?.first, small?.second) else null
        return Activity(details, state, Timestamps(startedAt), assets, buttons)
    }

    private fun connect(appId: Long) {
        if (connection != null) disconnect()
        connection = open()
        val c = connection ?: return
        c.send(Opcode.HANDSHAKE, encode(Handshake.serializer(), Handshake(1, appId.toString())))
        thread(isDaemon = true, name = "Discord IPC - Update thread") {
            while (connected) {
                runCatching { if (ready) sendPending() }
                Thread.sleep(5000)
            }
        }
    }

    fun disconnect() {
        val c = connection ?: return
        c.close()
        connection = null
        ready = false
        pending = null
        user = noUser
    }

    private fun sendPending() {
        val c = connection
        val a = pending
        if (c != null && a != null) c.send(Opcode.FRAME, encode(Command.serializer(), Command(Cmd.SET_ACTIVITY, Args(pid(), a))))
        pending = null
    }

    /** Runs on the connection's reader thread. */
    fun handle(packet: IpcPacket) {
        when (packet.opcode) {
            Opcode.FRAME -> {
                if (packet.payload.evt == Evt.ERROR) {
                    println("Discord IPC error " + packet.payload.data.code + " with message: " + packet.payload.data.message)
                    return
                }
                if (packet.payload.cmd == Cmd.DISPATCH) {
                    ready = true
                    user = packet.payload.data.user!!
                    if (pending != null) sendPending()
                }
            }
            Opcode.CLOSE -> {
                println("Discord IPC error " + packet.payload.data.code + " with message: " + packet.payload.data.message)
                disconnect()
            }
            else -> {}
        }
    }

    fun decode(text: String): Payload = json.decodeFromString(Payload.serializer(), text)

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

    /** Little-endian opcode and length, then the JSON with a random nonce added. */
    fun frame(opcode: Opcode, obj: JsonObject): ByteBuffer {
        val map = obj.toMutableMap()
        map["nonce"] = JsonPrimitive(UUID.randomUUID().toString())
        val bytes = json.encodeToString(JsonObject.serializer(), JsonObject(map)).toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(bytes.size + 8)
        buf.putInt(Integer.reverseBytes(opcode.ordinal))
        buf.putInt(Integer.reverseBytes(bytes.size))
        buf.put(bytes)
        buf.flip()
        return buf
    }

    private fun pid(): Int = ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toInt()

    /** The first of `discord-ipc-0..9` that opens. */
    private fun open(): IpcConnection? {
        if (System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")) {
            for (i in 0 until 10) runCatching { return PipeConnection("\\\\?\\pipe\\discord-ipc-$i") }
            return null
        }
        val dir = dirs.firstNotNullOfOrNull { System.getenv(it) }
        for (i in 0 until 10) runCatching { return SocketConnection((dir ?: "/tmp") + "/discord-ipc-$i") }
        return null
    }
}

private enum class Opcode { HANDSHAKE, FRAME, CLOSE, PING, PONG }

@Serializable
private enum class Cmd { DISPATCH, SET_ACTIVITY }

@Serializable
private enum class Evt { ERROR, READY }

@Serializable
private class Handshake(val v: Int, val clientId: String)

@Serializable
private class Command(val cmd: Cmd, val args: Args)

@Serializable
private class Args(val pid: Int, val activity: Activity)

@Serializable
private class Activity(val details: String?, val state: String?, val timestamps: Timestamps, val assets: Assets?, val buttons: List<Button>?)

@Serializable
private class Timestamps(val start: Long, val end: Long? = null)

@Serializable
private class Assets(val largeImage: String?, val largeText: String?, val smallImage: String?, val smallText: String?)

@Serializable
private class Button(val label: String, val url: String)

@Serializable
private class Payload(val evt: Evt?, val cmd: Cmd?, val data: PayloadData)

@Serializable
private class PayloadData(val user: User?, val code: Int?, val message: String?)

/** A data class, so the screen can tell "no user yet" by equality. */
@Serializable
private data class User(val id: String = "none", val username: String = "none", val avatar: String = "none") {
    fun avatarUrl() = "https://cdn.discordapp.com/avatars/$id/$avatar.png?size=128"
}

private class IpcPacket(val opcode: Opcode?, val payload: Payload)

private abstract class IpcConnection {
    fun send(opcode: Opcode, obj: JsonObject) = write(Presence.frame(opcode, obj))

    protected abstract fun write(buf: ByteBuffer)

    abstract fun close()
}

/** A Windows named pipe, read by a polling thread. */
private class PipeConnection(name: String) : IpcConnection() {
    private val file = RandomAccessFile(name, "rw")

    init {
        thread(name = "Discord IPC - Pipe thread") { read() }
    }

    override fun write(buf: ByteBuffer) {
        runCatching { file.write(buf.array()) }.onFailure { Presence.disconnect() }
    }

    private fun read() {
        val header = ByteBuffer.allocate(4)
        while (true) {
            fill(header)
            val opcode = Opcode.entries[Integer.reverseBytes(header.getInt(0))]
            fill(header)
            val body = ByteBuffer.allocate(Integer.reverseBytes(header.getInt(0)))
            fill(body)
            Presence.handle(IpcPacket(opcode, Presence.decode(Charset.defaultCharset().decode(body.rewind()).toString())))
        }
    }

    private fun fill(buf: ByteBuffer) {
        buf.rewind()
        while (file.length() < buf.remaining()) {
            Thread.onSpinWait()
            Thread.sleep(100)
        }
        while (buf.hasRemaining()) file.channel.read(buf)
    }

    override fun close() {
        runCatching { file.close() }
    }
}

/** A Unix domain socket, read through a selector. */
private class SocketConnection(path: String) : IpcConnection() {
    private val selector = Selector.open()
    private val channel = SocketChannel.open(UnixDomainSocketAddress.of(path))
    private val header = ByteBuffer.allocate(4)
    private var opcode: Opcode? = null
    private var length: Int? = null
    private var body: ByteBuffer? = null
    private var opcodeRead = false

    init {
        channel.configureBlocking(false)
        channel.register(selector, SelectionKey.OP_READ)
        thread(name = "Discord IPC - Socket thread") {
            runCatching {
                while (selector.select() > 0) {
                    selector.selectedKeys().removeIf { key ->
                        if (!key.isReadable) return@removeIf false
                        val packet = read(channel) ?: return@removeIf false
                        Presence.handle(packet)
                        true
                    }
                }
            }
        }
    }

    override fun write(buf: ByteBuffer) {
        runCatching { channel.write(buf) }
    }

    /** One frame, read across as many wakeups as it takes. */
    private fun read(ch: ReadableByteChannel): IpcPacket? {
        if (!opcodeRead) {
            if (!header(ch)) return null
            opcode = Opcode.entries.getOrNull(Integer.reverseBytes(header.getInt(0)))
            opcodeRead = true
            header.clear()
        }
        if (length == null) {
            if (!header(ch)) return null
            val n = Integer.reverseBytes(header.getInt(0))
            length = n
            body = ByteBuffer.allocate(n)
            header.clear()
        }
        val b = body ?: return null
        ch.read(b)
        if (b.hasRemaining()) return null
        b.flip()
        val packet = IpcPacket(opcode, Presence.decode(Charsets.UTF_8.decode(b).toString()))
        opcode = null
        opcodeRead = false
        length = null
        body = null
        return packet
    }

    private fun header(ch: ReadableByteChannel): Boolean {
        ch.read(header)
        if (header.hasRemaining()) return false
        header.flip()
        return true
    }

    override fun close() {
        runCatching {
            channel.close()
            selector.close()
        }
    }
}

// ---- the first-start prompt ----

/** A 100×25 glass button, its tint mix easing up on hover (OUT_QUINT, 200 ms). */
private class ConfirmButton(private val label: String, private val action: () -> Unit) {
    private var x = 0f
    private var y = 0f
    private val w = 100f
    private val h = 25f
    private val color = Theme.blur
    private val hover = Animation(Easing.OUT_QUINT, 200)

    fun draw(x: Float, y: Float, mouseX: Float, mouseY: Float) {
        if (x != -1f && y != -1f) {
            this.x = x
            this.y = y
        }
        hover.update(if (hovered(mouseX, mouseY)) 1f else 0f)
        Canvas.glass(this.x, this.y, w, h, 3f, 2f, 0.25f + hover.value / 3f, 1f, color, -1)
        Fonts.bold12.drawCentered(label, this.x + w / 2f, this.y + h / 2f, -1)
    }

    fun click(mouseX: Float, mouseY: Float) {
        if (hovered(mouseX, mouseY)) action()
    }

    private fun hovered(mx: Float, my: Float) = mx <= x + w && x <= mx && my <= y + h && y <= my
}

/**
 * "Enable Discord RPC?", the Discord avatar (a placeholder until Discord answered) and a picture, with No/Yes buttons
 * that set the module and go back to the title screen.
 */
private class ConfirmScreen(private val module: RPC) : Screen(Component.nullToEmpty("horizon-discord-confirm")) {
    private val picture = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/exclude/discord_confirm.png")
    private val yes = ConfirmButton("Yes!") { choose(true) }
    private val no = ConfirmButton("No...") { choose(false) }

    private fun choose(on: Boolean) {
        module.enabledSetting.value = on
        Minecraft.getInstance().gui.setScreen(TitleScreen())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        Overlay.late { draw(mouseX.toFloat(), mouseY.toFloat()) }
    }

    private fun draw(mouseX: Float, mouseY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        Canvas.quad(0f, 0f, width.toFloat(), height.toFloat(), -1072689136)
        val font = Fonts.bold20
        val dotsHalf = font.width("...") / 2f
        font.draw("...", cx - dotsHalf, cy - 94f, java.awt.Color.GRAY.rgb)
        font.draw("...".dropLast(3 - (4 * (System.currentTimeMillis() % 1200.0 / 1200.0)).toInt()), cx - dotsHalf, cy - 94f, -1)
        font.drawCentered("Enable Discord RPC?", cx, cy - 10f, Colors.withAlpha(-1, 0.5f))
        val user = Presence.user
        val avatar = UrlTextures.get(if (user != Presence.noUser) user.avatarUrl() else "https://i.imgur.com/y5rOVPP.png")
        Canvas.texturedRect(avatar, cx - 85f, cy - 108f, 65f, 65f, 32.5f, 2f, 0f, 1f, -1, -1)
        val own = Minecraft.getInstance().textureManager.getTexture(picture).textureView
        Canvas.texturedRect(own, cx + 20f, cy - 108f, 65f, 65f, 32.5f, 2f, 0f, 1f, -1, -1)
        no.draw(cx - 110f, cy + 40f, mouseX, mouseY)
        yes.draw(cx + 10f, cy + 40f, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        yes.click(event.x().toFloat(), event.y().toFloat())
        no.click(event.x().toFloat(), event.y().toFloat())
        return super.mouseClicked(event, doubled)
    }
}
