package dev.horizon.client.lunar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import dev.horizon.client.HorizonClient
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerModelType
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Other players' Lunar emotes. Lunar pushes `UseEmotePush { player, emote id }` and
 * `StopEmotePush { player }` for the players it sends cosmetics for; the emote's animation is an action in Lunar's
 * emote armature files (`emotes/models/actions*.bobj`, "emote_<name>"), played on Lunar's skinned player mesh
 * (`emotes/models/entity/default.bobj`, `slim.bobj` for slim skins) wearing the player's skin, with the emote's props
 * (`props*.bobj`, shown from their `show_at`). While a player emotes, their vanilla model and layers are replaced by it
 * (`misc2/LunarEmoteMixin`); the name tag stays. An emote ends when Lunar says so, when a one-shot emote is over, or
 * when the player moves for emotes that stop on moving; your own also when you walk, jump or sneak. Emote sounds, music and the emotes' particle effects aren't
 * played; the emote's face "morph" isn't drawn.
 */
object LunarEmotes {
    private val mc: Minecraft get() = Minecraft.getInstance()
    const val USE_PUSH = "lunarclient.websocket.emote.v1.UseEmotePush"
    const val STOP_PUSH = "lunarclient.websocket.emote.v1.StopEmotePush"
    private const val BASE = "assets/lunar/emotes/"

    class Emote(val id: Int, val name: String, val duration: Int, val looping: Boolean, val stopOnMove: Boolean, val meshes: List<Pair<String, Float>>,
                val particle: String?, val morph: String? = null)

    /** One of an emote's timed effects (Lunar's "morphs", particles/configuration.json): a particle effect on a bone, from tick [start] for [length]. */
    class Morph(val scheme: String, val bone: String, val start: Int, val length: Int, val translate: FloatArray?)

    private class Playing(val emote: Emote, val start: Long, val x: Double, val z: Double)

    /** Everything loaded once from Lunar's CDN. */
    private class Data(
        val emotes: Map<Int, Emote>,
        val wide: Bobj.File,
        val slim: Bobj.File?,
        val actions: Map<String, Bobj.Action>,
        val props: Map<String, Bobj.Mesh>,
        val propTextures: Map<String, String>,
        val morphs: Map<String, List<Morph>> = emptyMap(),
    )

    @Volatile
    private var data: Data? = null

    @Volatile
    private var loading = false
    private val playing = ConcurrentHashMap<UUID, Playing>()

    /** Emotes are drawn: with Lunar's cosmetics and emotes on. */
    private val shown: Boolean get() = LunarCosmetics.shown && LunarSettings.emotes.value

    /** A push from Lunar: an emote starts or stops. */
    fun push(type: String, value: ByteArray) {
        val uuid = LunarWire.emotePlayer(value) ?: return
        when {
            type.endsWith(USE_PUSH) -> {
                val id = LunarWire.emoteId(value)
                ensure()
                val d = data
                val e = d?.emotes?.get(id)
                if (e == null) {
                    pendingUse[uuid] = id
                    return
                }
                start(uuid, e)
            }
            type.endsWith(STOP_PUSH) -> {
                playing.remove(uuid)
                pendingUse.remove(uuid)
            }
        }
    }

    /** Emotes asked for before the emote data was in, started once it is. */
    private val pendingUse = ConcurrentHashMap<UUID, Int>()

    private fun start(uuid: UUID, e: Emote) {
        // Lunar echoing an emote you just started yourself: already playing.
        playing[uuid]?.let { if (it.emote.id == e.id && System.nanoTime() - it.start < 3_000_000_000L && uuid == mc.player?.uuid) return }
        val p = mc.level?.getPlayerByUUID(uuid)
        playing[uuid] = Playing(e, System.nanoTime(), p?.x ?: 0.0, p?.z ?: 0.0)
    }

    /** For ShotBot (`lunaremote`): Lunar says [uuid] uses emote [id] (0: stops). */
    @JvmStatic
    fun testEmote(uuid: UUID, id: Int) {
        val frame = if (id == 0) LunarWire.pushFrame(STOP_PUSH, LunarWire.emotePush(uuid, 0))
        else LunarWire.pushFrame(USE_PUSH, LunarWire.emotePush(uuid, id))
        LunarNetwork.pushes(frame)
    }

    /** Lunar's emotes, by id order (empty until loaded; starts loading). */
    fun emotes(): List<Emote> {
        ensure()
        return data?.emotes?.values?.sortedBy { it.id } ?: emptyList()
    }

    /** Plays emote [id] on your own player (client-side: from the locker). */
    fun playSelf(id: Int) {
        val e = data?.emotes?.get(id) ?: return
        val me = mc.player ?: return
        start(me.uuid, e)
    }

    /** Whether [uuid] is in an emote now. */
    fun isPlaying(uuid: UUID) = playing.containsKey(uuid)

    fun stopSelf() {
        mc.player?.uuid?.let { playing.remove(it) }
    }

    fun clear() {
        playing.clear()
        pendingUse.clear()
    }

    /** Every tick: emotes that ended, or whose player moved, stop; ones waiting for the data start. */
    fun tick() {
        if (pendingUse.isNotEmpty()) data?.let { d ->
            val it = pendingUse.entries.iterator()
            while (it.hasNext()) {
                val (u, id) = it.next()
                it.remove()
                d.emotes[id]?.let { e -> start(u, e) }
            }
        }
        if (playing.isEmpty()) return
        val level = mc.level ?: return playing.clear()
        // Yours: walking, jumping or sneaking ends it (as in Lunar), for everyone and with its music.
        val me = mc.player
        val mine = me?.let { playing[it.uuid] }
        if (me != null && mine != null) {
            val i = me.input.keyPresses
            val pressed = mc.gui.screen() == null && (i.forward() || i.backward() || i.left() || i.right() || i.jump() || i.shift())
            val moved = Math.abs(me.x - mine.x) > 0.15 || Math.abs(me.z - mine.z) > 0.15
            if (pressed || (mine.emote.stopOnMove && moved)) LunarSocial.stopEmote()
        }
        val now = System.nanoTime()
        playing.entries.removeIf { (u, p) ->
            val player = level.getPlayerByUUID(u) ?: return@removeIf true
            val ticks = (now - p.start) / 50_000_000.0
            (!p.emote.looping && ticks > p.emote.duration) ||
                (p.emote.stopOnMove && (Math.abs(player.x - p.x) > 0.15 || Math.abs(player.z - p.z) > 0.15))
        }
    }

    /** Whether the player drawn as [state] is emoting (and the emote can be drawn). */
    @JvmStatic
    fun emoting(state: AvatarRenderState): Boolean {
        if (!shown || playing.isEmpty() || data == null) return false
        val p = mc.level?.getEntity(state.id) as? Player ?: return false
        return playing.containsKey(p.uuid)
    }

    private fun ensure() {
        if (data != null || loading) return
        if (LunarAssets.index == null) {
            LunarAssets.ensure()
            return
        }
        loading = true
        LunarAssets.pool.execute {
            try {
                data = load()
                HorizonClient.LOG.info("Lunar emotes: {} emotes, {} animations", data?.emotes?.size, data?.actions?.size)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar emotes: couldn't load Lunar's emotes: {}", e.toString())
            } finally {
                loading = false
            }
        }
    }

    private fun text(path: String) = String(LunarAssets.file(path), Charsets.UTF_8)

    private fun load(): Data {
        val json = JsonParser.parseString(text(BASE + "emotes.json")).asJsonObject
        val emotes = HashMap<Int, Emote>()
        for (el in json.getAsJsonArray("emotes")) {
            val o = el.asJsonObject
            val meshes = o.getAsJsonArray("meshes")?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                (mo.get("name")?.asString ?: return@mapNotNull null) to (mo.get("show_at")?.asFloat ?: 0f)
            } ?: emptyList()
            val id = o.get("id")?.asInt ?: continue
            emotes[id] = Emote(id, o.get("name").asString, o.get("duration")?.asInt ?: 100, o.get("looping")?.asBoolean == true,
                o.get("stopOnMove")?.asBoolean == true, meshes, o.get("particleEffect")?.asString, o.get("morph")?.asString)
        }
        val wide = Bobj.parse(text(BASE + "models/entity/default.bobj"))
        val slim = try {
            Bobj.parse(text(BASE + "models/entity/slim.bobj"))
        } catch (e: Exception) {
            null
        }
        val actions = HashMap<String, Bobj.Action>()
        val props = HashMap<String, Bobj.Mesh>()
        fun list(key: String) = json.getAsJsonArray(key)?.map { LunarCosmeticIndex.asset(it.asString) } ?: emptyList()
        for (path in list("actions")) try {
            actions += Bobj.parse(text(path), wide.bones).actions
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Lunar emotes: {} doesn't read: {}", path, e.toString())
        }
        for (path in list("props") + (BASE + "models/props.bobj")) try {
            for (m in Bobj.parse(text(path), wide.bones).meshes) props[m.name] = m
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Lunar emotes: {} doesn't read: {}", path, e.toString())
        }
        // Prop textures: emotes.json's meshes, and the player model's own (popcorn's is there).
        val textures = HashMap<String, String>()
        val model = try {
            JsonParser.parseString(text(BASE + "models/entity/default.json")).asJsonObject.getAsJsonObject("meshes")
        } catch (e: Exception) {
            null
        }
        for (meshes in listOfNotNull(model, json.getAsJsonObject("meshes"))) for ((name, el) in meshes.entrySet()) {
            val tex = (el as? JsonObject)?.get("texture")?.asString ?: continue
            if (tex.startsWith("lunar:")) textures[name] = LunarCosmeticIndex.asset(tex)
        }
        val morphs = try {
            parseMorphs(text("assets/lunar/particles/configuration.json"))
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Lunar emotes: no morphs ({})", e.toString())
            emptyMap()
        }
        return Data(emotes, wide, slim, actions, props, textures, morphs)
    }

    private val world = Array(64) { Matrix4f() }
    private val skin = Array(64) { Matrix4f() }

    /** Each emote's most telling tick ([telling]). */
    private val tellingTicks = HashMap<Int, Float>()

    /** How long emote [id]'s animation is, in ticks (0 until the emotes are in). */
    fun ticks(id: Int): Float {
        val d = data ?: return 0f
        val e = d.emotes[id] ?: return 0f
        return maxOf(1f, d.actions["emote_" + e.name]?.length ?: e.duration.toFloat())
    }

    /**
     * For the locker's emote pictures: the player mesh (the [slim] one for a slim skin) posed as emote [id] at [tick],
     * as quads ([GeoModel.QUAD_FLOATS] each, as [Bobj.skin] writes them: y up from the feet); null while the emotes
     * are still loading, or for an emote without an animation. [tick] null is the emote's most telling moment
     * ([telling]). On the render thread only (it poses with the arrays [submit] uses).
     */
    fun posed(id: Int, slim: Boolean, tick: Float?): FloatArray? {
        val d = data ?: return null
        val e = d.emotes[id] ?: return null
        val body = if (slim) d.slim ?: d.wide else d.wide
        val action = d.actions["emote_" + e.name] ?: return null
        if (body.bones.size > world.size) return null
        val mesh = body.meshes.firstOrNull { it.name == "body" } ?: return null
        val at = tick ?: tellingTicks.getOrPut(id) { telling(body, action) }
        Bobj.pose(body.bones, action, at, world, skin)
        val out = FloatArray(Bobj.size(mesh))
        val n = Bobj.skin(mesh, skin, out)
        return if (n == out.size) out else out.copyOf(n)
    }

    /**
     * The tick of [action], of [TELLING_SAMPLES] along it, at which the body is furthest from standing still: the
     * pose that says most about the emote (its first and last ticks are close to standing). Measured a little way
     * along every bone, so a bone turning counts as well as one moving.
     */
    private fun telling(body: Bobj.File, action: Bobj.Action): Float {
        val n = body.bones.size
        val v = Vector3f()
        val rest = FloatArray(n * 3)
        Bobj.pose(body.bones, null, 0f, world, skin)
        for (i in 0 until n) {
            world[i].transformPosition(v.set(0f, 0.25f, 0f))
            rest[i * 3] = v.x; rest[i * 3 + 1] = v.y; rest[i * 3 + 2] = v.z
        }
        val length = maxOf(1f, action.length)
        var best = length / 2f
        var bestD = -1f
        for (k in 1..TELLING_SAMPLES) {
            val at = length * k / (TELLING_SAMPLES + 1)
            Bobj.pose(body.bones, action, at, world, skin)
            var d = 0f
            for (i in 0 until n) {
                world[i].transformPosition(v.set(0f, 0.25f, 0f))
                val dx = v.x - rest[i * 3]; val dy = v.y - rest[i * 3 + 1]; val dz = v.z - rest[i * 3 + 2]
                d += dx * dx + dy * dy + dz * dz
            }
            if (d > bestD) {
                bestD = d
                best = at
            }
        }
        return best
    }

    private const val TELLING_SAMPLES = 11

    /**
     * Draws the emoting player as [state], instead of their model: [poseStack] is as vanilla has it for the model (body
     * turned, flipped, 1.501 down); [light] and [overlay] as for the model.
     */
    @JvmStatic
    fun submit(state: AvatarRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, overlay: Int) {
        val d = data ?: return
        val p = mc.level?.getEntity(state.id) as? Player ?: return
        val play = playing[p.uuid] ?: return
        val slimSkin = state.skin?.model() == PlayerModelType.SLIM
        val body = if (slimSkin) d.slim ?: d.wide else d.wide
        val action = d.actions["emote_" + play.emote.name]
        val ticks = ((System.nanoTime() - play.start) / 50_000_000.0).toFloat()
        val length = maxOf(1f, action?.length ?: play.emote.duration.toFloat())
        val frame = if (play.emote.looping) ticks % length else minOf(ticks, length)
        if (body.bones.size > world.size) return
        Bobj.pose(body.bones, action, frame, world, skin)
        poseStack.pushPose()
        // Back to y up from the feet (the body's turn and the player scale stay), as the emote meshes are made. The
        // meshes face +z and vanilla's model faces -z, so it is half a turn about x (y and z flip), not about z: that
        // one left the emoting player facing backwards.
        poseStack.translate(0f, 1.501f, 0f)
        poseStack.scale(1f, -1f, -1f)
        val skinTexture = state.skin?.body()?.texturePath()
        body.meshes.firstOrNull { it.name == "body" }?.let { m ->
            if (skinTexture != null) draw(m, poseStack, collector, RenderTypes.entityTranslucent(skinTexture), light, overlay)
        }
        for ((name, showAt) in play.emote.meshes) {
            // show_at is in seconds (popcorn's 1.0 shows a second in; bubbles' 0.0 at once).
            if (ticks / 20f < showAt) continue
            val m = d.props[name] ?: continue
            val tex = d.propTextures[name]?.let { LunarCosmetics.texture(it) } ?: continue
            draw(m, poseStack, collector, RenderTypes.entityTranslucent(tex), light, overlay)
        }
        poseStack.popPose()
        play.emote.particle?.let { effect -> particles(p, state, body, effect) }
        play.emote.morph?.let { key -> morphs(p, state, body, d.morphs[key] ?: return@let, frame) }
    }

    /** The emote's timed particle effects, each on its bone while its window lasts (Lunar's morphs). */
    private fun morphs(p: Player, state: AvatarRenderState, body: Bobj.File, list: List<Morph>, tick: Float) {
        for ((i, m) in list.withIndex()) {
            if (tick < m.start || tick >= m.start + m.length) continue
            val key = "morph$i"
            LunarParticles.keep(p.uuid, key, m.scheme)
            val name = m.bone.removeSuffix(".end")
            val bone = body.bones.indexOfFirst { it.name == name }
            val local = Vector3f(0f, 1.2f, 0f)
            if (bone >= 0) world[bone].getTranslation(local)
            m.translate?.let { local.add(it[0], it[1], it[2]) }
            // The mesh faces +z: the body's turn alone takes it to the world (see submit).
            local.mul(0.9375f).rotateY(Math.toRadians((-state.bodyRot).toDouble()).toFloat())
            LunarParticles.place(p.uuid, key, net.minecraft.world.phys.Vec3(state.x + local.x, state.y + local.y, state.z + local.z))
        }
    }

    /** particles/configuration.json: each morph key's particle effects (block ones are left out). Game-free. */
    fun parseMorphs(json: String): Map<String, List<Morph>> {
        val out = HashMap<String, List<Morph>>()
        val root = JsonParser.parseString(json) as? JsonObject ?: return out
        for ((key, v) in root.entrySet()) {
            val list = (v as? JsonObject)?.getAsJsonArray("morphs") ?: continue
            out[key] = list.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val scheme = Regex("Scheme:\"([^\"]+)\"").find(o.get("morph")?.asString ?: "")?.groupValues?.get(1) ?: return@mapNotNull null
                val t = o.getAsJsonArray("translate")?.let { a -> FloatArray(3) { i -> a[i].asFloat } }
                Morph(scheme, o.get("bone")?.asString ?: "body", o.get("start")?.asInt ?: 0, o.get("length")?.asInt ?: 20, t)
            }
        }
        return out
    }

    /** Keeps the emote's particle effect going from its bone (the effect names it), or the chest. */
    private fun particles(p: Player, state: AvatarRenderState, body: Bobj.File, effect: String) {
        LunarParticles.keep(p.uuid, "emote", effect)
        val bone = LunarParticles.bone(effect)?.let { b -> body.bones.indexOfFirst { it.name == b } } ?: -1
        val local = Vector3f(0f, 1.2f, 0f)
        if (bone >= 0) world[bone].getTranslation(local)
        // The body's turn (the mesh faces +z, see submit) and the player scale.
        local.mul(0.9375f).rotateY(Math.toRadians((-state.bodyRot).toDouble()).toFloat())
        LunarParticles.place(p.uuid, "emote", net.minecraft.world.phys.Vec3(state.x + local.x, state.y + local.y, state.z + local.z))
    }

    private fun draw(m: Bobj.Mesh, poseStack: PoseStack, collector: SubmitNodeCollector,
                     type: net.minecraft.client.renderer.rendertype.RenderType, light: Int, overlay: Int) {
        val out = FloatArray(Bobj.size(m))
        val n = Bobj.skin(m, skin, out)
        collector.submitCustomGeometry(poseStack, type) { pose, consumer ->
            var i = 0
            while (i < n) {
                val nx = out[i + 20]
                val ny = out[i + 21]
                val nz = out[i + 22]
                for (v in 0 until 4) {
                    val o = i + v * 5
                    consumer.addVertex(pose, out[o], out[o + 1], out[o + 2]).setColor(-1).setUv(out[o + 3], out[o + 4])
                        .setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz)
                }
                i += GeoModel.QUAD_FLOATS
            }
        }
    }
}
