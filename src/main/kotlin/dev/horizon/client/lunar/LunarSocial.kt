package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.horizon.client.HorizonClient
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.lunar.LunarSocialWire.Spray
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lunar's emotes, sprays and notifications over Horizon's Lunar connection ([LunarNetwork]).
 * - Emotes: Lunar's EmoteService says which emotes your account owns. Playing an owned one (the locker, or the
 *   `emote-key` bind for the last one played) also asks Lunar to play it (UseEmote), so other Lunar players see it, as
 *   in Lunar Client; others stay on your screen only.
 * - Sprays: other Lunar players' sprays (UseSprayPush) are drawn on the block faces they were put on, as Lunar draws
 *   them (pictures from Lunar's CDN, `assets/lunar/sprays.json`). The `spray-key` bind puts your chosen spray on the face
 *   you're looking at (UseSpray), for sprays your account owns.
 * - Notifications: Lunar's own notifications and messages come up as Horizon toasts (or chat lines).
 */
object LunarSocial {
    private val mc: Minecraft get() = Minecraft.getInstance()

    // ---- emotes ----

    @Volatile
    private var ownedEmotes: Set<Int> = emptySet()

    @Volatile
    private var allEmotes = false

    @Volatile
    var emotesKnown = false
        private set

    /** The emote last played (the `emote-key` bind plays it again). */
    @Volatile
    var lastEmote = 0

    fun ownsEmote(id: Int) = allEmotes || id in ownedEmotes

    /** Plays emote [id] on your player, and for other Lunar players too when your account owns it. */
    fun playEmote(id: Int) {
        lastEmote = id
        LunarEmotes.playSelf(id)
        if (!emotesKnown || !ownsEmote(id)) return
        // With a jam on its wheel slot: its music too, for you and (its URL sent along) for others.
        val jam = LunarProfileEdits.jams[id]
        if (jam != null && LunarSettings.emoteMusic.value) {
            LunarJams.playOwn(jam) { url -> use(id, url, jam) }
            return
        }
        use(id, null, 0)
    }

    private fun use(id: Int, url: String?, jam: Int) {
        LunarNetwork.request(LunarSocialWire.EMOTE_SERVICE, "UseEmote", LunarSocialWire.useEmote(id, url, jam)) { out ->
            LunarSocialWire.useEmoteStatus(LunarFriendWire.status(out))?.let { tell("emote only shown here: $it") }
        }
    }

    fun stopEmote() {
        LunarEmotes.stopSelf()
        LunarJams.stop(mc.user.profileId)
        if (emotesKnown) LunarNetwork.request(LunarSocialWire.EMOTE_SERVICE, "StopEmote", ByteArray(0)) { }
    }

    // ---- sprays ----

    /** A spray from sprays.json: its id, name, picture's asset path, and size in blocks. */
    data class SprayType(val id: Int, val name: String, val path: String, val width: Float, val height: Float)

    @Volatile
    private var sprayTypes: Map<Int, SprayType>? = null

    @Volatile
    private var loadingTypes = false

    @Volatile
    private var ownedSprays: List<Int> = emptyList()

    @Volatile
    private var allSprays = false

    /** Sprays up in this world, oldest first. */
    private val sprays = CopyOnWriteArrayList<Spray>()
    private var sprayLevel: Any? = null

    /** The spray the `spray-key` bind puts up. */
    @Volatile
    var chosenSpray = 0

    fun sprayType(id: Int): SprayType? {
        sprayTypes?.let { return it[id] }
        loadSprayTypes()
        return null
    }

    /** The sprays you can put up (owned; every one with all of them). */
    fun ownedSprays(): List<SprayType> {
        val types = sprayTypes ?: run { loadSprayTypes(); return emptyList() }
        return if (allSprays) types.values.sortedBy { it.id } else ownedSprays.mapNotNull { types[it] }
    }

    /** sprays.json. Game-free. */
    fun parseSprays(json: String): Map<Int, SprayType> {
        val out = HashMap<Int, SprayType>()
        for (el in JsonParser.parseString(json) as? JsonArray ?: return out) {
            val o = el.asJsonObject
            val id = o.get("id")?.asInt ?: continue
            val res = o.get("resource")?.asString ?: continue
            fun num(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asFloat ?: 1f
            out[id] = SprayType(id, o.get("name")?.asString ?: "#$id", LunarBadges.path(res), num("width"), num("height"))
        }
        return out
    }

    private fun loadSprayTypes() {
        if (sprayTypes != null || loadingTypes) return
        if (LunarAssets.assets == null) {
            LunarAssets.ensure()
            return
        }
        loadingTypes = true
        LunarAssets.pool.execute {
            try {
                sprayTypes = parseSprays(String(LunarAssets.file("assets/lunar/sprays.json"), Charsets.UTF_8))
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar sprays: couldn't load sprays.json: {}", e.toString())
                sprayTypes = emptyMap()
            } finally {
                loadingTypes = false
            }
        }
    }

    private fun added(s: Spray, max: Int) {
        // One spray per spot; a player's oldest go once they have more than Lunar allows.
        sprays.removeIf { same(it, s.x, s.y, s.z) }
        sprays += s
        if (max > 0) {
            val mine = sprays.filter { it.player == s.player }
            if (mine.size > max) sprays.removeAll(mine.take(mine.size - max).toSet())
        }
    }

    private fun same(s: Spray, x: Float, y: Float, z: Float) = Math.abs(s.x - x) < 0.01f && Math.abs(s.y - y) < 0.01f && Math.abs(s.z - z) < 0.01f

    /**
     * Where Lunar Client puts a spray for a look at [hit]: on the face, centred on the point looked at, turned by your
     * head's yaw on floors and ceilings (Lunar's `UseSprayRequest`).
     */
    fun placement(hit: BlockHitResult, yaw: Float, id: Int): Spray {
        val d = hit.direction
        val o = d.unitVec3i
        val negative = d.axisDirection == Direction.AxisDirection.NEGATIVE
        val l = hit.location
        fun axis(v: Double, off: Int) = if (off != 0) (v + (if (negative) off else 0) + off * 0.005).toFloat() else (v - 0.5).toFloat()
        val rotation = when (d) {
            Direction.UP -> yaw
            Direction.DOWN -> -yaw
            else -> 0f
        }
        return Spray(mc.user.profileId, id, axis(l.x, o.x), axis(l.y, o.y), axis(l.z, o.z), d.get3DDataValue() + 1, rotation)
    }

    /** Puts your chosen spray on the face you're looking at (the `spray-key` bind). */
    fun spray() {
        val hit = mc.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) {
            tell("look at a block to spray on it")
            return
        }
        val owned = ownedSprays()
        val id = chosenSpray.takeIf { c -> owned.any { it.id == c } } ?: owned.firstOrNull()?.id
        if (id == null) {
            tell(if (sprayTypes == null) "Lunar's sprays are still loading" else "your Lunar account has no sprays")
            return
        }
        val s = placement(hit, mc.player?.yHeadRot ?: 0f, id)
        val sent = LunarNetwork.request(LunarSocialWire.SPRAY_SERVICE, "UseSpray", LunarSocialWire.useSpray(s)) { out ->
            if (LunarFriendWire.status(out) == 1) mc.execute { added(s, maxSprays) } else tell("Lunar didn't put the spray up (you don't own it)")
        }
        if (!sent) tell("not connected to Lunar")
    }

    @Volatile
    private var maxSprays = 0

    // ---- from LunarNetwork ----

    /** Lunar's websocket is ready: signs in to the emote, spray and notification services (socket thread). */
    fun connected() {
        if (!LunarSettings.network.value) return
        LunarNetwork.request(LunarSocialWire.EMOTE_SERVICE, "Login", ByteArray(0)) { out ->
            val (owned, all) = LunarSocialWire.emoteLogin(out)
            LunarProfileEdits.emoteLogin(out)
            ownedEmotes = owned
            allEmotes = all
            emotesKnown = true
        }
        LunarNetwork.request(LunarSocialWire.SPRAY_SERVICE, "Login", ByteArray(0)) { out ->
            val (owned, all, max) = LunarSocialWire.sprayLogin(out)
            ownedSprays = owned.toList()
            allSprays = all
            maxSprays = max
        }
        LunarNetwork.request(LunarSocialWire.NOTIFICATION_SERVICE, "Login", ByteArray(0)) { out ->
            val unread = LunarSocialWire.notificationLogin(out).filter { !it.third }
            if (unread.isNotEmpty() && LunarSettings.notifications.value) {
                toast("Lunar", if (unread.size == 1) unread[0].first else "${unread.size} unread notifications")
            }
        }
    }

    fun clear() {
        ownedEmotes = emptySet()
        allEmotes = false
        emotesKnown = false
        ownedSprays = emptyList()
        allSprays = false
        sprays.clear()
    }

    /** A push from Lunar; true when it was an emote-account, spray or notification push (any thread). */
    fun push(type: String, value: ByteArray): Boolean {
        when {
            type.endsWith(LunarSocialWire.USE_SPRAY_PUSH) -> LunarSocialWire.useSprayPush(value)?.let { s ->
                var max = 0
                LunarWire.fields(value) { f, i, t -> if (f == 6) max = i.readInt32() else i.skipField(t) }
                mc.execute { added(s, max) }
            }
            type.endsWith(LunarSocialWire.REMOVE_SPRAY_PUSH) -> {
                val (_, p) = LunarSocialWire.removeSprayPush(value)
                sprays.removeIf { same(it, p[0], p[1], p[2]) }
            }
            type.endsWith(LunarSocialWire.DISPLAY_NOTIFICATION_PUSH) -> LunarSocialWire.displayNotification(value).let { n ->
                if (LunarSettings.notifications.value) toast(n.title.ifEmpty { "Lunar" }, n.text)
            }
            type.endsWith(LunarSocialWire.DISPLAY_CHAT_PUSH) -> LunarSocialWire.chatLine(value).let { line ->
                if (LunarSettings.notifications.value && line.isNotBlank()) mc.execute { LunarSettings.tell(line) }
            }
            type.endsWith(LunarSocialWire.NEW_NOTIFICATION_PUSH) -> LunarSocialWire.newNotification(value)?.let { (title, kind, _) ->
                if (LunarSettings.notifications.value) toast(LunarSocialWire.notificationKind(kind), title)
            }
            type.endsWith(LunarSocialWire.REFRESH_EMOTES_PUSH) || type.endsWith(LunarSocialWire.REFRESH_SPRAYS_PUSH) -> connected()
            else -> return false
        }
        return true
    }

    private fun toast(title: String, text: String) {
        HorizonClient.LOG.info("Lunar notification: {}: {}", title, text)
        mc.execute { Notifications.post("$title: ", text, 5f, NotificationType.INFO) }
    }

    private fun tell(text: String) = mc.execute { LunarSettings.tell(text) }

    // ---- drawing sprays ----

    fun init() {
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            val level = mc.level ?: return@register
            if (level !== sprayLevel) {
                sprayLevel = level
                sprays.clear()
            }
            if (sprays.isEmpty() || !LunarSettings.sprays.value) return@register
            val cam = ctx.levelState().cameraRenderState.pos ?: return@register
            for ((age, s) in sprays.reversed().withIndex()) {
                val type = sprayType(s.id) ?: continue
                val tex = LunarCosmetics.texture(type.path) ?: continue
                val d = Direction.from3DDataValue((s.facing - 1).coerceIn(0, 5))
                val o = d.unitVec3i
                // Lunar's: the centre half a block behind the face, newer sprays a hair in front of older ones.
                val k = 1.0 - age * 0.001
                val cx = s.x - k * o.x + 0.5
                val cy = s.y - k * o.y + 0.5
                val cz = s.z - k * o.z + 0.5
                // Gone with the block it was on.
                if (level.getBlockState(BlockPos.containing(cx, cy, cz)).isAir) continue
                val m = Matrix4f().translate((cx - cam.x).toFloat(), (cy - cam.y).toFloat(), (cz - cam.z).toFloat())
                if (d.axis.isHorizontal) {
                    m.rotateY(Math.toRadians(180.0 - d.toYRot()).toFloat())
                    m.rotateZ(Math.toRadians(180.0 + s.rotation).toFloat())
                } else {
                    m.rotateX(Math.toRadians(-90.0 * d.axisDirection.step).toFloat())
                    m.rotateY(Math.PI.toFloat())
                    m.rotateZ(Math.toRadians(s.rotation.toDouble()).toFloat())
                }
                m.translate(-0.5f, -0.5f, -0.5f)
                val light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(cx + o.x, cy + o.y, cz + o.z))
                val w = type.width
                val h = type.height
                val corners = arrayOf(floatArrayOf(0f, h, 0f, 1f), floatArrayOf(w, h, 1f, 1f), floatArrayOf(w, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 0f))
                val pts = corners.map { c -> Vector3f(c[0], c[1], 0f).mulPosition(m) to c }
                ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.entityTranslucent(tex)) { pose, consumer ->
                    for ((p, c) in pts) consumer.addVertex(pose, p.x, p.y, p.z).setColor(-1).setUv(c[2], c[3])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, o.x.toFloat(), o.y.toFloat(), o.z.toFloat())
                }
            }
        }
    }

    // ---- ShotBot ----

    /**
     * For ShotBot (`lunarsocial <spec>`), through the same parsing as Lunar's pushes; nothing is sent:
     * `spray <id> <x> <y> <z> <facing 1-6> [rotation]` (a UseSprayPush), `aimspray <id>` (where Lunar would put spray
     * [id] for your look, shown as a push), `note <title> | <text>` (DisplayNotificationPush), `chat <text>`,
     * `emotes <ids...>` (as if your account owned those).
     */
    @JvmStatic
    fun testSpec(spec: String) {
        val a = spec.trim().split(' ').filter { it.isNotEmpty() }
        when (a[0]) {
            "spray" -> {
                val s = Spray(UUID.randomUUID(), a[1].toInt(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat(), a[5].toInt(), a.getOrNull(6)?.toFloat() ?: 0f)
                push(LunarSocialWire.USE_SPRAY_PUSH, LunarSocialWire.useSprayPushBytes(s))
            }
            "aimspray" -> {
                val hit = mc.hitResult as? BlockHitResult ?: return
                val s = placement(hit, mc.player?.yHeadRot ?: 0f, a[1].toInt())
                push(LunarSocialWire.USE_SPRAY_PUSH, LunarSocialWire.useSprayPushBytes(s))
            }
            "note" -> {
                val rest = a.drop(1).joinToString(" ")
                val bytes = LunarWire.message { o -> o.writeString(1, rest.substringBefore('|').trim()); o.writeString(2, rest.substringAfter('|', "").trim()) }
                push(LunarSocialWire.DISPLAY_NOTIFICATION_PUSH, bytes)
            }
            "chat" -> push(LunarSocialWire.DISPLAY_CHAT_PUSH, LunarWire.message { it.writeString(1, a.drop(1).joinToString(" ")) })
            "emotes" -> {
                ownedEmotes = a.drop(1).map { it.toInt() }.toSet()
                emotesKnown = true
            }
            else -> throw IllegalArgumentException("lunarsocial $spec")
        }
    }

    /** Sprays up now (for tests). */
    fun spraysUp(): List<Spray> = sprays.toList()
}
