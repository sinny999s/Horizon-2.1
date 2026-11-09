package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.module.misc.LunarSpoof
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.ssl.SslHandler
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.ScheduledFuture
import net.minecraft.client.Minecraft
import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLContext

/**
 * Lunar Spoof's link to Lunar's own services, after Moonlight's `AuthClient`, `WSClient`,
 * `SubscriptionManager` and `PeerRegistry`. It signs in to Lunar's authenticator the way a Lunar Client does, by joining
 * a Mojang session with your own account (so your token goes only to Mojang, never to Lunar), keeps Lunar's game
 * websocket open with the token it gets, asks it which players in the tab list use Lunar (LoadTabLogos), and keeps
 * their logo colours, Lunar+ colours and badges ([peers]) for Lunar's logo and badge in the tab list
 * (`misc2/LunarTabMixin`) and by their names ([LunarNameTag]). Your own logo shows when your account has Lunar cosmetics;
 * your badge is the one Lunar's BadgeService says you wear. With Lunar Spoof's cosmetics on, it also has Lunar send what the players in the world
 * wear ([syncCosmetics]), for [LunarCosmetics] to draw. Reconnects with a growing wait (up to 30 s) until turned off.
 */
object LunarNetwork {
    private const val AUTH_HOST = "authenticator.lunarclientprod.com"
    private const val GAME_HOST = "websocket.lunarclientprod.com"

    /**
     * What Lunar shows by a Lunar player's name: their logo colour (ARGB), Lunar+ colour (ARGB; 0 without Lunar+, drawn
     * as a small plus on the logo) and badge (0: none, else an id in [LunarBadges]).
     */
    data class Peer(val logo: Int, val plus: Int = 0, val badge: Int = 0)

    /** Players known to use Lunar, by UUID. */
    private val peers = ConcurrentHashMap<UUID, Peer>()

    /** Tab list players already asked about (main thread only). */
    private val asked = HashSet<UUID>()

    /** Players whose cosmetics Lunar is sending (SubscriptionService), main thread only. */
    private val subscribed = HashSet<UUID>()

    @Volatile
    private var socket: GameSocket? = null
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** [uuid]'s Lunar logo, Lunar+ colour and badge, or null for none (not a Lunar player, or Lunar Network is off). */
    @JvmStatic
    fun peer(uuid: UUID): Peer? {
        if (!LunarSettings.network.value) return null
        return peers[uuid]
    }

    /** The colour of [uuid]'s Lunar logo, or null for none (not a Lunar player, or Lunar Network is off). */
    @JvmStatic
    fun iconColor(uuid: UUID): Int? = peer(uuid)?.logo

    /** For tests (ShotBot's `lunarpeer`): [uuid] counts as a Lunar player, with Lunar+ colour [plus] (0: none) and [badge]. */
    @JvmStatic
    @JvmOverloads
    fun addPeer(uuid: UUID, argb: Int, plus: Int = 0, badge: Int = 0) {
        peers[uuid] = Peer(argb, plus, badge)
    }

    /** What a cosmetics push says of the player's logo, Lunar+ colour and badge (any thread). */
    fun pushed(p: LunarWire.PlayerCosmetics) {
        val logo = p.logo ?: peers[p.uuid]?.logo ?: return
        peers[p.uuid] = Peer(logo, p.plus, p.badge)
    }

    /**
     * Every tick while Lunar Spoof is on (main thread). [want]: the Lunar Network setting. Connects once you're on a
     * server (not singleplayer), as Moonlight does on its first server connection, then stays connected across servers;
     * reconnects when the account changes.
     */
    fun tick(want: Boolean) {
        val s = socket
        if (!want) {
            if (s != null) stop()
            return
        }
        val account = mc.user.profileId
        if (s != null && s.account != account) stop()
        if (socket == null) {
            val conn = mc.connection ?: return
            if (conn.connection.isMemoryConnection) return
            // Claims the version of the server it's started on (Lunar runs one version a session); none on a version
            // Lunar doesn't run on.
            val versionId = LunarSpoof.versionId(conn.connection) ?: return
            socket = GameSocket(account, mc.user.name, versionId).also { it.start() }
        }
        socket?.let {
            if (it.ready) {
                syncTab(it)
                syncCosmetics(it)
            }
            LunarFriends.tick(it.ready)
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        asked.clear()
        subscribed.clear()
        peers.clear()
        LunarCosmetics.clear()
        LunarEmotes.clear()
        LunarAccount.clear()
        LunarFriends.clear()
        LunarSocial.clear()
        LunarProfileEdits.clear()
        LunarJams.stopAll()
        HorizonShare.clear()
    }

    /** Where the connection is: 0 off, 1 waiting for a server to join (Lunar connects on the first one), 2 connecting, 3 connected. */
    val state: Int
        get() {
            if (!LunarSettings.network.value) return 0
            val s = socket ?: return 1
            return if (s.ready) 3 else 2
        }

    /** Calls a Lunar service method; [reply] gets its answer (on the socket's thread). False when not connected. */
    fun request(service: String, method: String, input: ByteArray, reply: (ByteArray) -> Unit): Boolean =
        socket?.takeIf { it.ready }?.request(service, method, input, reply) ?: false

    /**
     * Has Lunar send the cosmetics of the players in the world (yours included), as Lunar Client does with
     * SubscriptionService.SubscribeV2: the answer holds what each wears now, and Lunar pushes changes afterwards.
     * Players who leave are unsubscribed and forgotten.
     */
    private fun syncCosmetics(s: GameSocket) {
        if (s.resubscribe) {
            s.resubscribe = false
            subscribed.clear()
        }
        if (!LunarCosmetics.wanted) {
            if (subscribed.isNotEmpty()) {
                s.call(LunarWire.SUBSCRIPTION_SERVICE, "Unsubscribe", LunarWire.subscribe(subscribed))
                subscribed.clear()
                LunarCosmetics.clear()
            }
            return
        }
        val level = mc.level ?: return
        val current = HashSet<UUID>()
        for (p in level.players()) current += p.uuid
        val add = current.filter { it !in subscribed }
        if (add.isNotEmpty()) {
            subscribed += add
            // Lunar Client asks in small batches.
            for (batch in add.chunked(50)) s.subscribe(batch)
        }
        val left = subscribed.filter { it !in current }
        if (left.isNotEmpty()) {
            subscribed -= left.toSet()
            s.call(LunarWire.SUBSCRIPTION_SERVICE, "Unsubscribe", LunarWire.subscribe(left))
            for (u in left) LunarCosmetics.forget(u)
        }
    }

    /** A frame's push notifications: players' cosmetics as they change them, and their emotes. Public for ShotBot (`lunarcosmetic`). */
    @JvmStatic
    fun pushes(frame: ByteArray) {
        for ((type, value) in LunarWire.pushes(frame)) {
            if (type.endsWith(LunarWire.COSMETICS_PUSH)) LunarWire.playerCosmetics(value)?.let { LunarCosmetics.received(listOf(it)) }
            else if (type.endsWith(LunarEmotes.USE_PUSH) || type.endsWith(LunarEmotes.STOP_PUSH)) {
                LunarEmotes.push(type, value)
                LunarJams.push(type, value)
            }
            else if (!LunarSocial.push(type, value)) LunarFriends.push(type, value)
        }
    }

    /** Asks about players new to the tab list, and forgets ones that left it (Moonlight's `syncWithTabList`). */
    private fun syncTab(s: GameSocket) {
        if (s.reset) {
            s.reset = false
            asked.clear()
        }
        val conn = mc.connection ?: return
        val self = mc.player?.uuid ?: return
        val current = HashSet<UUID>()
        for (info in conn.onlinePlayers) if (info.profile.id != self) current += info.profile.id
        val ask = current.filter { it !in asked }
        if (ask.isNotEmpty()) {
            asked += ask
            s.loadTabLogos(ask)
        }
        val left = asked.filter { it !in current }
        if (left.isNotEmpty()) {
            asked -= left.toSet()
            for (u in left) peers.remove(u)
        }
    }

    // ---- sockets ----

    private fun group(): EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

    /** A TLS websocket to wss://[host]/game with [headers]; [handler] gets the handshaker. */
    private fun open(group: EventLoopGroup, host: String, headers: Map<String, String>, handler: (WebSocketClientHandshaker) -> Socket): Channel {
        val h = DefaultHttpHeaders()
        for ((k, v) in headers) h.add(k, v)
        val hs = WebSocketClientHandshakerFactory.newHandshaker(URI("wss://$host/game"), WebSocketVersion.V13, null, false, h)
        return Bootstrap().group(group).channel(NioSocketChannel::class.java).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val engine = SSLContext.getDefault().createSSLEngine(host, 443)
                    engine.useClientMode = true
                    engine.sslParameters = engine.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    val ssl = SslHandler(engine)
                    ssl.setHandshakeTimeoutMillis(10_000)
                    // The websocket upgrade goes out once TLS is up (as Moonlight does).
                    ssl.handshakeFuture().addListener(FutureListener<Channel> { f -> if (f.isSuccess) hs.handshake(ch) else ch.close() })
                    ch.pipeline().addLast(ssl, HttpClientCodec(), HttpObjectAggregator(65536), handler(hs))
                }
            }).connect(host, 443).sync().channel()
    }

    /** A websocket's handler: [opened] once upgraded, then [binary] for each binary frame. Pings are answered. */
    private abstract class Socket(private val hs: WebSocketClientHandshaker) : SimpleChannelInboundHandler<Any>() {
        abstract fun opened(ctx: ChannelHandlerContext)
        abstract fun binary(ctx: ChannelHandlerContext, bytes: ByteArray)

        override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
            if (!hs.isHandshakeComplete) {
                hs.finishHandshake(ctx.channel(), msg as FullHttpResponse)
                opened(ctx)
                return
            }
            when (msg) {
                is BinaryWebSocketFrame -> binary(ctx, ByteArray(msg.content().readableBytes()).also { msg.content().readBytes(it) })
                is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(msg.content().retain()))
                is CloseWebSocketFrame -> {
                    HorizonClient.LOG.info("Lunar Spoof: Lunar closed the socket: {} {}", msg.statusCode(), msg.reasonText())
                    ctx.channel().close()
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            HorizonClient.LOG.warn("Lunar Spoof: socket error: {}", cause.message)
            ctx.close()
        }
    }

    private fun send(ch: Channel, bytes: ByteArray) = ch.writeAndFlush(BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)))

    // ---- authenticator ----

    /**
     * A Lunar token for the current account. One try per (re)connect, unlike Moonlight's three tries 2 s apart: each try
     * joins a Mojang session, and quick retries ran into Mojang's rate limit and then Lunar's (HTTP 429).
     */
    private fun signIn(): String = signInOnce()

    private fun signInOnce(): String {
        val user = mc.user
        val token = CompletableFuture<String>()
        val group = group()
        try {
            val ch = open(group, AUTH_HOST, mapOf("Accept" to "application/x-protobuf", "X-Initiator" to LunarWire.INITIATOR)) { hs ->
                object : Socket(hs) {
                    override fun opened(ctx: ChannelHandlerContext) {
                        send(ctx.channel(), LunarWire.authServerbound(1, LunarWire.hello(user.profileId, user.name)))
                    }

                    /** The shapes of the answers that held no token, for the log if no token comes. */
                    private val answers = ArrayList<String>()

                    /** The AES key sent in the encryption response: the token comes back encrypted with it. */
                    private var secretKey: ByteArray? = null

                    override fun binary(ctx: ChannelHandlerContext, bytes: ByteArray) {
                        val (field, inner) = LunarWire.authClientbound(bytes)
                        val jwt = when (field) {
                            1 -> {
                                encrypt(ctx, inner)
                                return
                            }
                            2 -> LunarWire.authToken(inner, secretKey)
                            else -> null
                        }
                        if (jwt != null) token.complete(jwt) else answers += LunarWire.shape(bytes)
                    }

                    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
                        if (msg is CloseWebSocketFrame) HorizonClient.LOG.info("Lunar Spoof: authenticator closed: {} {}", msg.statusCode(), msg.reasonText())
                        super.channelRead0(ctx, msg)
                    }

                    /** As for a Minecraft server: a secret key, the session joined with its server id, both encrypted back. */
                    private fun encrypt(ctx: ChannelHandlerContext, inner: ByteArray) {
                        val (publicKey, verify) = LunarWire.encryptionRequest(inner) ?: run {
                            val reason = "EncryptionRequestMessage missing required fields"
                            send(ctx.channel(), LunarWire.authServerbound(3, LunarWire.encryptionFailure(reason))).addListener(ChannelFutureListener.CLOSE)
                            token.completeExceptionally(IllegalStateException(reason))
                            return
                        }
                        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
                        val secret = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
                        secretKey = secret.encoded
                        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
                        val encryptedSecret = rsa.doFinal(secret.encoded)
                        val encryptedVerify = rsa.doFinal(verify)
                        mc.services().sessionService().joinServer(user.profileId, user.accessToken, LunarWire.serverId(secret.encoded, publicKey))
                        send(ctx.channel(), LunarWire.authServerbound(2, LunarWire.encryptionResponse(encryptedSecret, encryptedVerify)))
                    }

                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (token.isDone) return
                        if (answers.isNotEmpty())
                            HorizonClient.LOG.warn("Lunar Spoof: the authenticator's answers held no token: {}", answers.joinToString(" | "))
                        token.completeExceptionally(IllegalStateException(if (answers.isEmpty()) "closed before signing in" else "no token in the authenticator's answer"))
                    }

                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        token.completeExceptionally(cause)
                        ctx.close()
                    }
                }
            }
            try {
                return token.get(15, TimeUnit.SECONDS).also { HorizonClient.LOG.info("Lunar Spoof: signed in to Lunar as {}", user.name) }
            } finally {
                ch.close().sync()
            }
        } finally {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS)
        }
    }

    // ---- game websocket ----

    /** Lunar's game websocket for one account, reconnecting until [close]d (Moonlight's `WSClient`). */
    private class GameSocket(val account: UUID, val name: String, val versionId: String) {
        @Volatile
        var ready = false

        /** Set on every (re)connect: the tab list is asked about again. */
        @Volatile
        var reset = false

        /** Set on every (re)connect: cosmetics are subscribed to again. */
        @Volatile
        var resubscribe = false

        @Volatile
        private var stopped = false

        @Volatile
        private var channel: Channel? = null

        @Volatile
        private var heartbeat: ScheduledFuture<*>? = null
        private val requests = AtomicInteger(1)
        private val logins = ConcurrentHashMap.newKeySet<String>()
        private val subscribes = ConcurrentHashMap.newKeySet<String>()

        /** Calls waiting for their reply, by request id (any thread). */
        val pending = ConcurrentHashMap<String, (ByteArray) -> Unit>()
        private var told = false

        /** The badge you wear (BadgeService.Login), for your own [Peer]. */
        @Volatile
        private var ownBadge = 0
        private var thread: Thread? = null

        fun start() {
            thread = Thread({
                // Each try joins a Mojang session: wait 30 s, doubling to 10 min (5 min after a rate limit).
                var backoff = 30_000L
                while (!stopped) {
                    val group = group()
                    try {
                        val jwt = signIn()
                        val version = LunarVersion.version ?: error("Lunar's version isn't known yet")
                        val commit = LunarVersion.gitCommit ?: error("Lunar's version isn't known yet")
                        if (stopped) break
                        val ch = open(group, GAME_HOST, mapOf("Accept" to "application/x-protobuf")) { hs -> handler(hs, jwt, version, commit) }
                        channel = ch
                        if (stopped) ch.close()
                        ch.closeFuture().sync()
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        HorizonClient.LOG.warn("Lunar Spoof: couldn't connect to Lunar: {}", e.message)
                        val msg = e.message ?: ""
                        if ("429" in msg || "RateLimiter" in msg) backoff = maxOf(backoff, 300_000L)
                        if (!told) {
                            told = true
                            mc.execute { LunarSettings.tell("couldn't connect to Lunar (${e.message}); retrying") }
                        }
                    } finally {
                        ready = false
                        heartbeat?.cancel(false)
                        heartbeat = null
                        channel = null
                        group.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                    }
                    if (stopped) break
                    try {
                        Thread.sleep(backoff)
                    } catch (e: InterruptedException) {
                        break
                    }
                    backoff = minOf(backoff * 2, 600_000L)
                }
            }, "Horizon-LunarNetwork").apply { isDaemon = true; start() }
        }

        private fun handler(hs: WebSocketClientHandshaker, jwt: String, version: String, commit: String) = object : Socket(hs) {
            override fun opened(ctx: ChannelHandlerContext) {
                channel = ctx.channel()
                val hello = LunarWire.gameHandshake(account, name, jwt, versionId, version, LunarVersion.gitBranch, commit, LunarWire.os(), LunarWire.arch())
                send(ctx.channel(), hello).addListener(ChannelFutureListener { f ->
                    if (!f.isSuccess) {
                        ctx.close()
                        return@ChannelFutureListener
                    }
                    ready = true
                    reset = true
                    resubscribe = true
                    HorizonClient.LOG.info("Lunar Spoof: connected to Lunar")
                    if (!told) {
                        told = true
                        if (LunarSettings.notify.value) mc.execute { LunarSettings.tell("connected to Lunar") }
                    }
                    heartbeat = ctx.channel().eventLoop().scheduleAtFixedRate({
                        if (ready) call(LunarWire.HEARTBEAT_SERVICE, "GameHeartbeat", ByteArray(0))
                    }, 60, 60, TimeUnit.SECONDS)
                    // Announces you, so other Lunar players see your logo; the answer is your own logo colour.
                    logins += call(LunarWire.COSMETIC_SERVICE, "Login", ByteArray(0))
                    // Your friends list and messages.
                    LunarFriends.connected()
                    LunarSocial.connected()
                    // The badge you wear, as Lunar Client asks for it.
                    request(LunarWire.BADGE_SERVICE, "Login", ByteArray(0)) { out ->
                        val badge = LunarWire.loginBadge(out)
                        LunarProfileEdits.badgeLogin(out)
                        ownBadge = badge
                        peers[account]?.let { peers[account] = it.copy(badge = badge) }
                    }
                })
            }

            override fun binary(ctx: ChannelHandlerContext, bytes: ByteArray) {
                try {
                    pushes(bytes)
                    for ((id, output) in LunarWire.replies(bytes)) {
                        val waiting = id?.let { pending.remove(it) }
                        if (waiting != null) {
                            waiting(output)
                        } else if (id != null && subscribes.remove(id)) {
                            LunarCosmetics.received(LunarWire.subscribeResponse(output))
                        } else if (id != null && logins.remove(id)) {
                            val color = LunarWire.loginColor(output)
                            val (owned, all) = LunarWire.loginOwned(output)
                            LunarLocker.owned(owned, all)
                            val (outfits, tree) = LunarWire.loginOutfits(output)
                            LunarAccount.login(outfits, tree)
                            LunarProfileEdits.cosmeticLogin(output)
                            if (color == null) HorizonClient.LOG.info("Lunar Spoof: this account has no Lunar cosmetics (no logo of your own)")
                            else peers[account] = Peer(color, LunarWire.loginPlus(output), ownBadge)
                        } else {
                            for (logo in LunarWire.tabLogos(output)) peers[logo.uuid] = Peer(logo.argb, logo.plus, logo.badge)
                        }
                    }
                } catch (e: Exception) {
                    HorizonClient.LOG.warn("Lunar Spoof: unreadable message from Lunar: {}", e.message)
                }
            }
        }

        /** Calls [method] of [service]; returns the request id. */
        fun call(service: String, method: String, input: ByteArray): String {
            val id = requests.getAndIncrement().toString()
            channel?.let { send(it, LunarWire.call(id, service, method, input)) }
            return id
        }

        /** Calls [method] of [service]; [reply] gets the answer (on the socket's thread). False when not connected. */
        fun request(service: String, method: String, input: ByteArray, reply: (ByteArray) -> Unit): Boolean {
            if (!ready || channel == null) return false
            val id = requests.getAndIncrement().toString()
            pending[id] = reply
            channel?.let { send(it, LunarWire.call(id, service, method, input)) }
            return true
        }

        fun subscribe(uuids: Collection<UUID>) {
            if (!ready || uuids.isEmpty()) return
            subscribes += call(LunarWire.SUBSCRIPTION_SERVICE, "SubscribeV2", LunarWire.subscribe(uuids))
        }

        fun loadTabLogos(uuids: Collection<UUID>) {
            if (!ready || uuids.isEmpty()) return
            call(LunarWire.COSMETIC_SERVICE, "LoadTabLogos", LunarWire.uuidList(uuids))
        }

        fun close() {
            stopped = true
            ready = false
            channel?.close()
            thread?.interrupt()
        }
    }
}
