package dev.horizon.client.lunar

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.HorizonClient
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.ClientAsset
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerModelPart
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.level.biome.Biome
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Not in the original: other players' Lunar Client cosmetics, drawn without Lunar. [LunarNetwork] has Lunar's websocket
 * send what each player in the world wears (cosmetic ids, [received]); the cosmetics themselves come from Lunar's public
 * CDN ([LunarAssets]) and are drawn here, as Lunar Client draws them:
 * - cloaks, as the player's cape (vanilla's cape layer, and Horizon's wavey capes), animated ones frame by frame; as
 *   with any cape, only while the player shows their cape (skin customisation) and wears no elytra;
 * - GeckoLib cosmetics (hats, glasses, masks, suits, backpacks, wings, pets, auras, shields...) as models on the body
 *   parts their bones belong to ([GeoModel]), animated by their Molang animations and state machines
 *   ([CosmeticAnimator]) with each wearer's own movement (walking, swimming, flying, crouching, looking around...);
 *   Lunar's classic dragon wings are its shared `legacy_dragon_wings` model with the cosmetic's texture;
 * - Lunar's older `.obj` cosmetics (hats, bandannas, masks, glasses, ties, belts, backpacks), placed as Lunar places
 *   them ([LegacyModel]).
 * Outfits that change with a condition (server, dimension, weather, biome temperature, riding, swimming, flying, elytra,
 * water or lava, fire, day or night) switch as the wearer's situation does. Emotes and particles: [LunarEmotes],
 * [LunarParticles].
 */
object LunarCosmetics {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** What each player wears, as Lunar last said (any thread). */
    private val players = ConcurrentHashMap<UUID, LunarWire.PlayerCosmetics>()

    /** Lunar should be asked about cosmetics: Lunar's services and cosmetics on ([LunarSettings]). */
    val wanted: Boolean get() = LunarSettings.network.value && LunarSettings.cosmetics.value

    /** Cosmetics are drawn (whatever Lunar has sent, or ShotBot's test pushes). */
    val shown: Boolean get() = LunarSettings.cosmetics.value

    fun init() {
        LunarParticles.init()
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            try {
                drawCompanions(ctx)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar companions: {}", e.toString())
            }
        }
        LivingEntityRenderLayerRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
            if (renderer is AvatarRenderer<*>) {
                @Suppress("UNCHECKED_CAST")
                helper.register(Layer(renderer as RenderLayerParent<AvatarRenderState, PlayerModel>))
            }
        }
    }

    fun received(list: List<LunarWire.PlayerCosmetics>) {
        for (p in list) {
            players[p.uuid] = p
            LunarNetwork.pushed(p)
        }
        if (list.isNotEmpty()) HorizonClient.LOG.debug("Lunar cosmetics: {} players' cosmetics in", list.size)
    }

    fun forget(uuid: UUID) {
        players.remove(uuid)
        instances.remove(uuid)
    }

    fun clear() {
        players.clear()
        instances.clear()
    }

    /** For ShotBot (`lunarcosmetic`): [uuid] wears [ids], fed through the same push handling as Lunar's own. */
    @JvmStatic
    fun testPush(uuid: UUID, ids: IntArray) = testPush(uuid, ids, emptyList())

    /**
     * For ShotBot (`lunarcosmetic <name> <spec>`): [spec] is default ids, then any number of `when <kind> <value> <ids>`
     * (kind and value as in [LunarWire.Condition]; a text value for dimension, passenger and server).
     */
    @JvmStatic
    fun testPushSpec(uuid: UUID, spec: String) {
        val t = spec.trim().split(' ', '\t').filter { it.isNotEmpty() }
        val defaults = ArrayList<Int>()
        val conditional = ArrayList<Pair<LunarWire.Condition, List<Int>>>()
        var i = 0
        while (i < t.size && t[i] != "when") defaults += t[i++].toInt()
        while (i < t.size && t[i] == "when") {
            val kind = t[i + 1].toInt()
            val v = t[i + 2]
            i += 3
            val ids = ArrayList<Int>()
            while (i < t.size && t[i] != "when") ids += t[i++].toInt()
            val cond = v.toIntOrNull()?.let { LunarWire.Condition(kind, it) } ?: LunarWire.Condition(kind, 0, v)
            conditional += cond to ids
        }
        testPush(uuid, defaults.toIntArray(), conditional)
    }

    fun testPush(uuid: UUID, ids: IntArray, conditional: List<Pair<LunarWire.Condition, List<Int>>>) =
        LunarNetwork.pushes(LunarWire.pushFrame(LunarWire.COSMETICS_PUSH, LunarWire.playerCosmeticsPush(uuid, ids.toList(), conditional)))

    /** The cosmetics [player] wears now: the first conditional set whose condition holds for them, else the defaults. */
    private fun wearing(player: Player): List<LunarWire.Equipped>? {
        // Yourself: what you chose in the locker (drawn by this client only), else what Lunar says your account wears.
        if (player.uuid == mc.player?.uuid) LunarLocker.wearing()?.let { return it }
        // Another Horizon user: what they picked in their locker (shared through Horizon's service).
        HorizonShare.picks(player.uuid)?.let { ids -> return ids.map { LunarWire.Equipped(it) } }
        val p = players[player.uuid] ?: return null
        for ((c, set) in p.conditional) if (holds(c, player)) return set
        return p.defaults
    }

    /** Whether an outfit condition holds for [p] (cosmetic/v2/common.proto's EquipCondition). */
    private fun holds(c: LunarWire.Condition, p: Player): Boolean {
        val level = p.level()
        val pos = p.blockPosition()
        return when (c.kind) {
            LunarWire.Condition.DIMENSION -> {
                val dim = level.dimension().identifier().toString()
                val want = c.text ?: return false
                dim == want || dim.endsWith(":$want")
            }
            LunarWire.Condition.WEATHER -> {
                val raining = level.isRainingAt(pos.above())
                val snowing = level.isRaining && level.getBiome(pos).value().getPrecipitationAt(pos, level.seaLevel) == Biome.Precipitation.SNOW
                when (c.value) {
                    1 -> raining
                    2 -> snowing
                    3 -> level.isThundering && raining
                    4 -> !level.isRaining
                    else -> false
                }
            }
            LunarWire.Condition.BIOME_TEMPERATURE -> {
                val t = level.getBiome(pos).value().baseTemperature
                when (c.value) {
                    1 -> t >= 1.0f
                    2 -> t < 0.2f
                    else -> false
                }
            }
            LunarWire.Condition.PASSENGER -> {
                val v = p.vehicle ?: return false
                val id = BuiltInRegistries.ENTITY_TYPE.getKey(v.type).toString()
                val want = c.text ?: return true
                id == want || id.endsWith(":$want")
            }
            LunarWire.Condition.MOVEMENT -> when (c.value) {
                1 -> p.isSwimming
                2 -> p.abilities.flying
                3 -> p.isFallFlying
                else -> false
            }
            LunarWire.Condition.IN_LIQUID -> when (c.value) {
                1 -> p.isInWater
                2 -> p.isInLava
                else -> false
            }
            LunarWire.Condition.RADIO -> false
            LunarWire.Condition.ON_FIRE -> p.isOnFire
            LunarWire.Condition.SERVER -> {
                val host = mc.currentServer?.ip?.substringBefore(':')?.lowercase() ?: return false
                val want = c.text?.lowercase() ?: return false
                host == want || host.endsWith(".$want")
            }
            LunarWire.Condition.TIME -> when (c.value) {
                1 -> level.isBrightOutside
                2 -> level.isDarkOutside
                else -> false
            }
            else -> false
        }
    }

    // ---- assets ----

    private sealed interface Asset
    private object Loading : Asset
    private object Unsupported : Asset
    private class Cloak(val texture: Frames, val asset: ClientAsset.ResourceTexture) : Asset

    /** A cloak's texture once it's in (the locker's thumbnails); starts fetching it. Game thread. */
    fun cloakTexture(id: Int): Identifier? = (asset(id) as? Cloak)?.texture?.let { it.usedAt = System.currentTimeMillis(); it.id }

    /**
     * A model cosmetic's quads at rest for the locker's thumbnails: every part in one space (y down, [GeoModel.QUAD_FLOATS]
     * each) and its texture. Null while it loads or when it isn't a model; starts fetching it. Game thread.
     */
    class Thumb(val texture: Identifier, val quads: FloatArray, /** Back faces can be skipped (an opaque model's closed cubes). */ val cull: Boolean)

    private val thumbs = HashMap<Int, Thumb>()

    fun thumb(id: Int): Thumb? {
        val a = asset(id)
        val frames = when (a) {
            is Model -> a.texture
            is Legacy -> a.texture
            else -> return null
        }
        frames.usedAt = System.currentTimeMillis()
        return thumbs.getOrPut(id) {
            val quads = when (a) {
                is Model -> {
                    val mesh = a.animator.Instance(0.0).update(0.0) { 0.0 }
                    val out = FloatArray(mesh.parts.values.sumOf { it.size })
                    var at = 0
                    for (q in mesh.parts.values) {
                        q.copyInto(out, at)
                        at += q.size
                    }
                    out
                }
                is Legacy -> {
                    // Placed as on a head (or body) in blocks; only the shape matters here.
                    val m = LegacyModel.placement(a.definition, a.headwear, 0f, LegacyModel.Wearing(helmet = false, chestplate = false, skinLayer = true))
                    val out = a.obj.quads.copyOf()
                    val v = org.joml.Vector3f()
                    var i = 0
                    while (i < out.size) {
                        for (k in 0 until 4) {
                            val o = i + k * 5
                            m.transformPosition(v.set(out[o], out[o + 1], out[o + 2]))
                            out[o] = v.x; out[o + 1] = v.y; out[o + 2] = v.z
                        }
                        m.transformDirection(v.set(out[i + 20], out[i + 21], out[i + 22])).normalize()
                        out[i + 20] = v.x; out[i + 21] = v.y; out[i + 22] = v.z
                        i += GeoModel.QUAD_FLOATS
                    }
                    out
                }
                else -> FloatArray(0)
            }
            Thumb(frames.id, quads, a is Model && !a.translucent)
        }
    }
    private class Model(val animator: CosmeticAnimator, val texture: Frames, val translucent: Boolean, val companion: Boolean = false,
                        /** For slim-armed wearers, when the cosmetic has its own slim model. */
                        val slim: CosmeticAnimator? = null) : Asset
    private class Legacy(val obj: LegacyModel.Obj, val definition: LegacyModel.Definition, val headwear: Boolean, val texture: Frames) : Asset

    /** Decoded off the game thread, waiting to be uploaded. The texture is [canvasW]×[canvasH], frames at its top left. */
    private class Decoded(val id: Int, val frames: List<BufferedImage>, val frametime: Int, val canvasW: Int, val canvasH: Int, val make: (Frames) -> Asset)

    /** A texture and its frames' pixels (ABGR, one frame for a still one, [width] wide), shown by [tick]. */
    private class Frames(val id: Identifier, val texture: DynamicTexture, val frames: List<IntArray>, val width: Int, val frametime: Int) {
        var shown = 0
        var usedAt = 0L
    }

    private val assets = ConcurrentHashMap<Int, Asset>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()
    private val animated = ArrayList<Frames>()

    /** Lunar's shared Molang (constants.molang and functions.molang) and legacy definitions, loaded once (on the pool). */
    private class Shared(val constants: Molang.Node?, val functions: Map<String, Molang.Function>, val hats: Map<String, LegacyModel.Definition>,
                         val bodywear: Map<String, LegacyModel.Definition>)

    @Volatile
    private var shared: Shared? = null

    private fun shared(): Shared = shared ?: synchronized(this) {
        shared ?: run {
            fun text(path: String) = try {
                String(LunarAssets.file(path), Charsets.UTF_8)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar cosmetics: couldn't load {}: {}", path, e.toString())
                null
            }
            Shared(
                text("assets/lunar/cosmetics/constants.molang")?.let { Molang.compile(it) },
                text("assets/lunar/cosmetics/functions.molang")?.let { Molang.parseFunctions(it) } ?: emptyMap(),
                text("assets/lunar/cosmetics/indexes/hats.json")?.let { LegacyModel.parseDefinitions(it) } ?: emptyMap(),
                text("assets/lunar/cosmetics/indexes/bodywear.json")?.let { LegacyModel.parseDefinitions(it) } ?: emptyMap(),
            ).also { shared = it }
        }
    }

    /** Cosmetic [id] once it's ready to draw; starts fetching it the first time. Game thread. */
    private fun asset(id: Int): Asset? {
        while (true) upload(decoded.poll() ?: break)
        assets[id]?.let { return it }
        val index = LunarAssets.index
        if (index == null) {
            LunarAssets.ensure()
            return null
        }
        val entry = index[id]
        if (entry == null) {
            assets[id] = Unsupported
            return Unsupported
        }
        assets[id] = Loading
        LunarAssets.pool.execute {
            try {
                val d = decode(entry)
                if (d == null) {
                    assets[id] = Unsupported
                    HorizonClient.LOG.info("Lunar cosmetics: {} ({} {}) isn't drawn", entry.name, entry.type, id)
                } else decoded += d
            } catch (e: Exception) {
                assets[id] = Unsupported
                HorizonClient.LOG.warn("Lunar cosmetics: couldn't load {} ({}): {}", entry.name, id, e.toString())
            }
        }
        return Loading
    }

    /** Fetches and decodes a cosmetic (on [LunarAssets.pool]); null for kinds that aren't drawn. */
    private fun decode(entry: LunarCosmeticIndex.Entry): Decoded? {
        if (entry.isCloak) {
            val (frames, frametime) = frames(entry.assetPath)
            val (w, h) = capeCanvas(frames[0].width, frames[0].height)
            return Decoded(entry.id, frames, frametime, w, h) { f -> Cloak(f, ClientAsset.ResourceTexture(f.id, f.id)) }
        }
        if (entry.gek) return gek(entry, LunarAssets.file(entry.assetPath), null)
        // Lunar's classic dragon wings: one shared GeckoLib model, the cosmetic's picture as its texture.
        if (entry.type == "dragon_wings" && entry.path.endsWith(".webp"))
            return gek(entry, LunarAssets.file(LEGACY_WINGS), entry.assetPath)
        return legacy(entry)
    }

    /** A GeckoLib cosmetic from its `.gek.json` [bytes]; [texture] replaces its texture (legacy dragon wings). */
    private fun gek(entry: LunarCosmeticIndex.Entry, bytes: ByteArray, texture: String?): Decoded? {
        val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        val gek = LunarCosmeticIndex.parseGek(json.toString())
        // Held-item replacements and companions (their own entities) aren't body cosmetics.
        if (gek.type in setOf("sword", "shovel", "pickaxe", "axe", "hoe", "hand", "items")) return null
        val companion = gek.type == "companion" || entry.type == "companion"
        val modelPath = gek.model ?: return null
        val texPath = texture ?: gek.texture ?: return null
        val model = GeoModel.parse(String(LunarAssets.file(modelPath), Charsets.UTF_8), gek.attachedBone)
        if (model.bones.none { it.quads.isNotEmpty() }) return null
        val animations = gek.animation?.takeIf { LunarAssets.has(it) }?.let {
            try {
                GeoAnimation.parse(String(LunarAssets.file(it), Charsets.UTF_8))
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar cosmetics: {}'s animations don't read: {}", entry.name, e.toString())
                null
            }
        } ?: emptyMap()
        val s = shared()
        val controllers = if (companion) companionControllers(animations) else CosmeticAnimator.controllers(json, animations)
        val animator = CosmeticAnimator(model, animations, controllers, s.constants, s.functions,
            CosmeticAnimator.transformations(json), CosmeticAnimator.options(json))
        val slimAnimator = gek.slimModel?.takeIf { it != modelPath && LunarAssets.has(it) }?.let { path ->
            val slimModel = GeoModel.parse(String(LunarAssets.file(path), Charsets.UTF_8), gek.attachedBone)
            CosmeticAnimator(slimModel, animations, controllers, s.constants, s.functions, CosmeticAnimator.transformations(json), CosmeticAnimator.options(json))
        }
        val (frames, frametime) = frames(texPath)
        val translucent = gek.translucent || entry.type == "dragon_wings" || gek.type == "pet" || entry.type == "auras"
        return Decoded(entry.id, frames, frametime, frames[0].width, frames[0].height) { f -> Model(animator, f, translucent, companion, slimAnimator) }
    }

    /** A companion's animations by how it moves (swimming, run, walk, idle: the ones it has), as Lunar's states pick them. */
    private fun companionControllers(animations: Map<String, GeoAnimation.Animation>): List<CosmeticAnimator.Controller> {
        val states = listOf("swimming" to "query.is_in_water", "run" to "query.horizon_run", "walk" to "query.horizon_walk", "idle" to "1")
            .filter { it.first in animations }
            .map { (anim, cond) -> CosmeticAnimator.State(anim, Molang.constOrCompile(cond), 4.0) }
        if (states.isEmpty()) return CosmeticAnimator.controllers(com.google.gson.JsonObject(), animations)
        return listOf(CosmeticAnimator.Controller(states, 4.0))
    }

    // ---- companions ----

    /**
     * A companion following its owner, simulated here as Lunar does it per viewer: it walks to about two blocks behind
     * them (runs when far), stays on the ground (or floats, for flying ones), teleports back past 30 blocks, faces where
     * it goes or its owner. Positions are kept for interpolation.
     */
    private class Follower(var x: Double, var y: Double, var z: Double) {
        var px = x
        var py = y
        var pz = z
        var yaw = 0f
        var pyaw = 0f
        var speed = 0.0
    }

    private val followers = ConcurrentHashMap<UUID, Follower>()

    /** The companion [p] wears, if any. */
    private fun companionOf(p: Player): Pair<Int, Model>? {
        val wear = wearing(p) ?: return null
        for (e in wear) {
            val a = asset(e.id) as? Model ?: continue
            if (a.companion) return e.id to a
        }
        return null
    }

    private fun tickCompanions() {
        val level = mc.level ?: run { followers.clear(); return }
        if (!shown || !LunarSettings.models.value) {
            followers.clear()
            return
        }
        val alive = HashSet<UUID>()
        for (p in level.players()) {
            if (companionOf(p) == null) continue
            alive += p.uuid
            val f = followers.getOrPut(p.uuid) { Follower(p.x + 1.5, p.y, p.z + 1.5) }
            f.px = f.x; f.py = f.y; f.pz = f.z; f.pyaw = f.yaw
            // Where it wants to be: behind its owner, a little to the side.
            val yawR = Math.toRadians(p.yBodyRot.toDouble())
            val tx = p.x + Math.sin(yawR) * 1.8 + Math.cos(yawR) * 0.8
            val tz = p.z - Math.cos(yawR) * 1.8 + Math.sin(yawR) * 0.8
            val dx = tx - f.x
            val dz = tz - f.z
            val dist = Math.sqrt(dx * dx + dz * dz)
            val owner = Math.sqrt((p.x - f.x).let { it * it } + (p.z - f.z).let { it * it })
            if (owner > 30 || Math.abs(p.y - f.y) > 12) {
                f.x = tx; f.y = p.y; f.z = tz
                f.px = f.x; f.py = f.y; f.pz = f.z
                f.speed = 0.0
                continue
            }
            val step = if (owner > 10) 0.42 else 0.2
            if (dist > 0.6) {
                val s = Math.min(step, dist)
                f.x += dx / dist * s
                f.z += dz / dist * s
                f.speed = s * 20
                f.yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            } else {
                f.speed = 0.0
                f.yaw = Math.toDegrees(Math.atan2(-(p.x - f.x), p.z - f.z)).toFloat()
            }
            f.y = ground(level, f.x, p.y, f.z)
        }
        followers.keys.removeIf { it !in alive }
    }

    /** The ground under (x, z) near height [near]: the top of the first solid block within a few blocks. */
    private fun ground(level: net.minecraft.world.level.Level, x: Double, near: Double, z: Double): Double {
        val base = Math.floor(near).toInt() + 1
        for (dy in 0..6) {
            val pos = net.minecraft.core.BlockPos.containing(x, (base - dy).toDouble(), z)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            if (!shape.isEmpty) return pos.y + shape.max(net.minecraft.core.Direction.Axis.Y)
        }
        return near
    }

    private fun drawCompanions(ctx: net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext) {
        if (followers.isEmpty()) return
        val level = mc.level ?: return
        val cam = ctx.levelState().cameraRenderState.pos ?: return
        val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
        val now = System.nanoTime() / 1e9
        for ((uuid, f) in followers) {
            val p = level.getPlayerByUUID(uuid) ?: continue
            val (id, a) = companionOf(p) ?: continue
            val x = f.px + (f.x - f.px) * partial
            val y = f.py + (f.y - f.py) * partial
            val z = f.pz + (f.z - f.pz) * partial
            var dyaw = f.yaw - f.pyaw
            while (dyaw > 180) dyaw -= 360
            while (dyaw < -180) dyaw += 360
            val yaw = f.pyaw + dyaw * partial.toFloat()
            val inWater = level.getFluidState(net.minecraft.core.BlockPos.containing(x, y + 0.2, z)).`is`(FluidTags.WATER)
            val q: (String) -> Double = { n ->
                when (n) {
                    "horizon_run" -> if (f.speed > 6) 1.0 else 0.0
                    "horizon_walk" -> if (f.speed > 0.5) 1.0 else 0.0
                    "is_in_water" -> if (inWater) 1.0 else 0.0
                    "ground_speed", "modified_move_speed" -> f.speed
                    "is_on_ground" -> 1.0
                    "is_moving" -> if (f.speed > 0.5) 1.0 else 0.0
                    "owner_distance" -> Math.sqrt((p.x - x) * (p.x - x) + (p.z - z) * (p.z - z))
                    else -> 0.0
                }
            }
            val mesh = instance(uuid, id, a, now).update(now, q)
            a.texture.usedAt = System.currentTimeMillis()
            // As vanilla places a mob's model: turned to its yaw, flipped, from the feet; quads in model pixels.
            val m = org.joml.Matrix4f().translate((x - cam.x).toFloat(), (y - cam.y).toFloat(), (z - cam.z).toFloat())
                .rotateY(Math.toRadians(180.0 - yaw).toFloat()).scale(-1f, -1f, 1f).translate(0f, -1.501f, 0f).scale(1 / 16f)
            val light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, net.minecraft.core.BlockPos.containing(x, y + 0.5, z))
            val type = if (a.translucent) RenderTypes.entityTranslucent(a.texture.id) else RenderTypes.entityCutout(a.texture.id)
            val all = mesh.parts.values.toList()
            ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), type) { pose, consumer ->
                val v = org.joml.Vector3f()
                val n = org.joml.Vector3f()
                for (quads in all) {
                    var i = 0
                    while (i < quads.size) {
                        m.transformDirection(n.set(quads[i + 20], quads[i + 21], quads[i + 22])).normalize()
                        for (k in 0 until 4) {
                            val o = i + k * 5
                            m.transformPosition(v.set(quads[o], quads[o + 1], quads[o + 2]))
                            consumer.addVertex(pose, v.x, v.y, v.z).setColor(-1).setUv(quads[o + 3], quads[o + 4])
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, n.x, n.y, n.z)
                        }
                        i += GeoModel.QUAD_FLOATS
                    }
                }
            }
        }
    }

    /** One of Lunar's older `.obj` cosmetics, by its legacy model name and definition. */
    private fun legacy(entry: LunarCosmeticIndex.Entry): Decoded? {
        val name = entry.legacyModel.lowercase().takeIf { it.isNotEmpty() && it != "none" } ?: return null
        val s = shared()
        val (definition, folder) = s.hats[name]?.let { it to "hats" } ?: s.bodywear[name]?.let { it to "bodywear" } ?: return null
        val objPath = "assets/lunar/cosmetics/models/$folder/${definition.name}/${definition.name}.obj"
        if (!LunarAssets.has(objPath)) return null
        val obj = LegacyModel.parseObj(String(LunarAssets.file(objPath), Charsets.UTF_8))
        if (obj.quads.isEmpty()) return null
        val (frames, frametime) = frames(entry.assetPath)
        val headwear = entry.type in setOf("hat", "bandanna", "mask", "glasses")
        return Decoded(entry.id, frames, frametime, frames[0].width, frames[0].height) { f -> Legacy(obj, definition, headwear, f) }
    }

    /** A texture's frames: its `.mcmeta` animation's grid of frames (row by row), else the whole picture. */
    private fun frames(path: String): Pair<List<BufferedImage>, Int> {
        val img = WebP.decode(LunarAssets.file(path))
        val meta = "$path.mcmeta"
        val anim = if (LunarAssets.has(meta)) LunarCosmeticIndex.parseMcmeta(String(LunarAssets.file(meta), Charsets.UTF_8)) else null
        if (anim == null || anim.width > img.width || anim.height > img.height) return listOf(img) to 1
        // Some animations give the frame size in model pixels for a bigger picture: scale to the picture's width.
        val scale = if (img.width % anim.width == 0) 1 else img.width / anim.width
        val fw = anim.width * maxOf(1, scale)
        val fh = anim.height * maxOf(1, scale)
        val cols = maxOf(1, img.width / fw)
        val rows = maxOf(1, img.height / fh)
        val out = ArrayList<BufferedImage>()
        for (r in 0 until rows) for (c in 0 until cols) out += img.getSubimage(c * fw, r * fh, fw, fh)
        return out to anim.frametime
    }

    /**
     * The cape texture's size for a Lunar cloak picture [w]×[h]. Lunar's cloaks are the 22×17 cape region alone (8× for
     * 176×136); vanilla's cape model reads that region of a 64×32 texture, so the picture goes top-left on a texture
     * that much bigger. A picture already 2:1 is used as it is.
     */
    private fun capeCanvas(w: Int, h: Int): Pair<Int, Int> =
        if (w == h * 2) w to h else Math.round(w * 64f / 22f) to Math.round(h * 32f / 17f)

    /** Plain textures by asset path (emote props, particles): the texture once uploaded, [Loading] while fetched. */
    private val plain = ConcurrentHashMap<String, Any>()
    private val plainDecoded = ConcurrentLinkedQueue<Pair<String, BufferedImage>>()

    /** The texture at [path] in Lunar's asset index (a WebP or PNG), once it's in; starts fetching it. Game thread. */
    fun texture(path: String): Identifier? {
        while (true) {
            val (p, img) = plainDecoded.poll() ?: break
            val id = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/lunar/" + p.lowercase().replace(Regex("[^a-z0-9/._-]"), "_"))
            mc.textureManager.register(id, dev.horizon.client.render.Textures.upload("lunar $p", img))
            plain[p] = id
        }
        when (val v = plain[path]) {
            is Identifier -> return v
            null -> {}
            else -> return null
        }
        if (LunarAssets.index == null) {
            LunarAssets.ensure()
            return null
        }
        plain[path] = Loading
        LunarAssets.pool.execute {
            try {
                val bytes = LunarAssets.file(path)
                val img = if (path.endsWith(".webp")) WebP.decode(bytes) else javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                plainDecoded += path to img
            } catch (e: Exception) {
                plain[path] = Unsupported
                HorizonClient.LOG.warn("Lunar cosmetics: couldn't load {}: {}", path, e.toString())
            }
        }
        return null
    }

    /** Uploads a decoded cosmetic's texture (game thread). */
    private fun upload(d: Decoded) {
        val first = d.frames[0]
        val w = first.width
        val h = first.height
        val pixels = d.frames.map { f ->
            IntArray(w * h).also { px ->
                for (y in 0 until h) for (x in 0 until w) {
                    val argb = if (x < f.width && y < f.height) f.getRGB(x, y) else 0
                    px[y * w + x] = (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)
                }
            }
        }
        val native = NativeImage(d.canvasW, d.canvasH, false)
        native.fillRect(0, 0, d.canvasW, d.canvasH, 0)
        val id = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/lunar/${d.id}.png")
        val tex = DynamicTexture({ "lunar cosmetic ${d.id}" }, native)
        val frames = Frames(id, tex, pixels, w, d.frametime)
        write(frames, 0)
        mc.textureManager.register(id, tex)
        if (pixels.size > 1) animated += frames
        assets[d.id] = d.make(frames)
    }

    private fun write(f: Frames, frame: Int) {
        val px = f.frames[frame]
        val native = f.texture.pixels ?: return
        val h = px.size / f.width
        for (y in 0 until h) for (x in 0 until f.width) native.setPixelABGR(x, y, px[y * f.width + x])
        f.texture.upload()
        f.shown = frame
    }

    /** Every tick (game thread): animated textures in use move to their current frame; gone wearers are forgotten. */
    fun tick() {
        tickCompanions()
        if (instances.isNotEmpty()) {
            val level = mc.level
            if (level == null) instances.clear()
            else instances.keys.removeIf { u -> level.getPlayerByUUID(u) == null }
        }
        if (animated.isEmpty()) return
        val now = System.currentTimeMillis()
        val ticks = now / 50
        for (f in animated) {
            if (now - f.usedAt > 2000) continue
            val frame = ((ticks / f.frametime) % f.frames.size).toInt()
            if (frame != f.shown) write(f, frame)
        }
    }

    // ---- per wearer ----

    /** Each wearer's animation state per cosmetic. */
    private val instances = ConcurrentHashMap<UUID, HashMap<Int, CosmeticAnimator.Instance>>()

    private fun instance(uuid: UUID, id: Int, a: Model, now: Double, slim: Boolean = false): CosmeticAnimator.Instance {
        val map = instances.getOrPut(uuid) { HashMap() }
        val s = if (slim) a.slim else null
        return if (s != null) map.getOrPut(-id) { s.Instance(now) } else map.getOrPut(id) { a.animator.Instance(now) }
    }

    /**
     * The `q.` values Lunar's cosmetics read, for [p] posed as [model]: movement in blocks per second (local ones
     * relative to where the body faces), states, head and limb rotations in degrees; `lunar.check_for_*` biome checks.
     * Anything else is 0 (`is_in_gui`, `is_first_person`, `owner_distance`...).
     */
    private class Queries(val p: Player, val model: PlayerModel) : (String) -> Double {
        private val vx = (p.x - p.xo) * 20
        private val vy = (p.y - p.yo) * 20
        private val vz = (p.z - p.zo) * 20
        private val ground = Math.sqrt(vx * vx + vz * vz)
        private val yaw = Math.toRadians(p.yBodyRot.toDouble())
        // Forward is (−sin yaw, cos yaw) in Minecraft.
        private val localZ = -vx * Math.sin(yaw) + vz * Math.cos(yaw)
        private val localX = vx * Math.cos(yaw) + vz * Math.sin(yaw)

        private fun b(v: Boolean) = if (v) 1.0 else 0.0
        private fun deg(r: Float) = Math.toDegrees(r.toDouble())

        override fun invoke(n: String): Double = when (n) {
            "ground_speed", "ground_speed_smoothed", "instant_ground_speed", "modified_move_speed" -> ground
            "y_velocity", "instant_y_velocity" -> vy
            "x_velocity" -> vx
            "z_velocity" -> vz
            "local_x_velocity" -> localX
            "local_y_velocity" -> vy
            "local_z_velocity" -> localZ
            "absolute_speed" -> Math.sqrt(vx * vx + vy * vy + vz * vz)
            "is_on_ground" -> b(p.onGround())
            "is_moving" -> b(ground > 0.25)
            "is_swimming" -> b(p.isSwimming)
            "is_flying" -> b(p.abilities.flying)
            "is_elytra_flying", "is_gliding", "is_elytra" -> b(p.isFallFlying)
            "is_in_water" -> b(p.isInWater)
            "is_under_water" -> b(p.isUnderWater)
            "is_in_lava" -> b(p.isInLava)
            "is_crouching", "is_sneaking" -> b(p.isCrouching)
            "is_sprinting", "is_running" -> b(p.isSprinting)
            "is_on_fire" -> b(p.isOnFire)
            "is_blocking" -> b(p.isBlocking)
            "is_sleeping" -> b(p.isSleeping)
            "is_riding" -> b(p.isPassenger)
            "is_raining" -> b(p.level().isRainingAt(p.blockPosition().above()))
            "is_in_combat" -> b(p.hurtTime > 0)
            "last_damaged" -> p.hurtTime.toDouble()
            "head_x_rotation" -> p.xRot.toDouble()
            "head_y_rotation" -> net.minecraft.util.Mth.wrapDegrees(p.yHeadRot - p.yBodyRot).toDouble()
            "body_y_rotation" -> p.yBodyRot.toDouble()
            "entity_y_rotation", "entity_y_rotation_target" -> p.yRot.toDouble()
            "distance_from_surface" -> if (p.isInWater) maxOf(0.0, p.getFluidHeight(FluidTags.WATER)) else 0.0
            "surface_blocked" -> 0.0
            "is_slim_model", "is_slim_skin" -> b(p is net.minecraft.client.player.AbstractClientPlayer && p.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM)
            "head_slot_equipped" -> b(!p.getItemBySlot(EquipmentSlot.HEAD).isEmpty)
            "body_slot_equipped" -> b(!p.getItemBySlot(EquipmentSlot.CHEST).isEmpty)
            "right_arm_x_rotation" -> deg(model.rightArm.xRot)
            "right_arm_y_rotation" -> deg(model.rightArm.yRot)
            "right_arm_z_rotation" -> deg(model.rightArm.zRot)
            "left_arm_x_rotation" -> deg(model.leftArm.xRot)
            "left_arm_y_rotation" -> deg(model.leftArm.yRot)
            "left_arm_z_rotation" -> deg(model.leftArm.zRot)
            "right_leg_x_rotation" -> deg(model.rightLeg.xRot)
            "left_leg_x_rotation" -> deg(model.leftLeg.xRot)
            "body_x_rotation" -> deg(model.body.xRot)
            "lunar.check_for_nether" -> b(p.level().dimension() == net.minecraft.world.level.Level.NETHER)
            "lunar.check_for_the_end" -> b(p.level().dimension() == net.minecraft.world.level.Level.END)
            "lunar.check_for_ocean" -> b(biome().contains("ocean"))
            "lunar.check_for_desert" -> b(biome().contains("desert") || biome().contains("badlands"))
            "lunar.get_light_level" -> p.level().getMaxLocalRawBrightness(p.blockPosition()).toDouble()
            else -> 0.0
        }

        private fun biome() = p.level().getBiome(p.blockPosition()).unwrapKey().map { it.identifier().path }.orElse("")
    }

    private fun wearingNow(p: Player): LegacyModel.Wearing = LegacyModel.Wearing(
        helmet = !p.getItemBySlot(EquipmentSlot.HEAD).isEmpty,
        chestplate = !p.getItemBySlot(EquipmentSlot.CHEST).isEmpty,
        skinLayer = p.isModelPartShown(PlayerModelPart.HAT),
    )

    /**
     * The particle effects [p]'s cosmetics emit: (cosmetic id, effect name, where: 0 feet for auras, 1 the back for
     * backpacks, 2 above the head for headwear).
     */
    fun particleEffects(p: Player): List<Triple<Int, String, Int>> {
        if (!shown) return emptyList()
        val index = LunarAssets.index ?: return emptyList()
        val out = ArrayList<Triple<Int, String, Int>>(0)
        for (e in wearing(p) ?: return emptyList()) {
            val entry = index[e.id] ?: continue
            if (entry.particle.isEmpty()) continue
            val where = when (entry.type) {
                "backpack", "bodywear" -> 1
                "hat", "glasses", "mask", "bandanna" -> 2
                else -> 0
            }
            out += Triple(e.id, entry.particle, where)
        }
        return out
    }

    // ---- drawing ----

    /** [player]'s skin with their Lunar cloak as the cape (Lunar Spoof's SkinEvent handler); [skin] when they have none. */
    fun skin(player: Player, skin: PlayerSkin): PlayerSkin {
        if (!shown || !LunarSettings.cloaks.value) return skin
        val cloak = cloak(player) ?: return skin
        if (skin.cape() == cloak.asset) return skin
        return PlayerSkin(skin.body(), cloak.asset, skin.elytra(), skin.model(), skin.secure())
    }

    private fun cloak(player: Player): Cloak? {
        for (e in wearing(player) ?: return null) {
            val c = asset(e.id) as? Cloak ?: continue
            c.texture.usedAt = System.currentTimeMillis()
            return c
        }
        return null
    }

    /** Draws a player's model cosmetics, after vanilla's layers. */
    class Layer(parent: RenderLayerParent<AvatarRenderState, PlayerModel>) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {
        override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, yRot: Float, xRot: Float) {
            if (!shown || !LunarSettings.models.value || state.isInvisible) return
            val player = mc.level?.getEntity(state.id) as? Player ?: return
            val wear = wearing(player) ?: return
            val model = parentModel
            var posed = false
            var queries: Queries? = null
            val now = System.nanoTime() / 1e9
            for (e in wear) {
                val a = asset(e.id)
                if (a !is Model && a !is Legacy) continue
                // Companions walk on their own (drawCompanions).
                if (a is Model && a.companion) continue
                if (!posed) {
                    model.setupAnim(state)
                    posed = true
                }
                when (a) {
                    is Model -> {
                        val q = queries ?: Queries(player, model).also { queries = it }
                        val slimSkin = state.skin?.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM
                        val mesh = instance(player.uuid, e.id, a, now, slimSkin).update(now, q)
                        a.texture.usedAt = System.currentTimeMillis()
                        val type = if (a.translucent) RenderTypes.entityTranslucent(a.texture.id) else RenderTypes.entityCutout(a.texture.id)
                        for ((limb, quads) in mesh.parts) {
                            val part = part(model, limb)
                            if (!part.visible) continue
                            poseStack.pushPose()
                            model.root().translateAndRotate(poseStack)
                            part.translateAndRotate(poseStack)
                            // Quads are in model pixels from the model's origin; the part's pose is about its rest pivot.
                            poseStack.scale(1 / 16f, 1 / 16f, 1 / 16f)
                            val rest = part.initialPose
                            poseStack.translate(-rest.x(), -rest.y(), -rest.z())
                            val copy = quads.copyOf()
                            collector.submitCustomGeometry(poseStack, type) { pose, consumer -> emit(pose, consumer, copy, light) }
                            poseStack.popPose()
                        }
                    }
                    is Legacy -> {
                        val w = wearingNow(player)
                        val part = if (a.definition.head) model.head else model.body
                        // As in Lunar: hidden under armour on its part unless it shows with armour.
                        if (!a.definition.showWithArmor && (if (a.definition.head) w.helmet else w.chestplate)) continue
                        if (!part.visible) continue
                        a.texture.usedAt = System.currentTimeMillis()
                        poseStack.pushPose()
                        model.root().translateAndRotate(poseStack)
                        part.translateAndRotate(poseStack)
                        poseStack.mulPose(LegacyModel.placement(a.definition, a.headwear, e.hatHeight, w))
                        val quads = a.obj.quads
                        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(a.texture.id)) { pose, consumer -> emit(pose, consumer, quads, light) }
                        poseStack.popPose()
                    }
                    else -> {}
                }
            }
        }

        private fun part(model: PlayerModel, limb: GeoModel.Limb): ModelPart = when (limb) {
            GeoModel.Limb.HEAD -> model.head
            GeoModel.Limb.BODY -> model.body
            GeoModel.Limb.RIGHT_ARM -> model.rightArm
            GeoModel.Limb.LEFT_ARM -> model.leftArm
            GeoModel.Limb.RIGHT_LEG -> model.rightLeg
            GeoModel.Limb.LEFT_LEG -> model.leftLeg
        }

        private fun emit(pose: PoseStack.Pose, consumer: VertexConsumer, q: FloatArray, light: Int) {
            var i = 0
            while (i < q.size) {
                val nx = q[i + 20]
                val ny = q[i + 21]
                val nz = q[i + 22]
                for (v in 0 until 4) {
                    val o = i + v * 5
                    consumer.addVertex(pose, q[o], q[o + 1], q[o + 2]).setColor(-1).setUv(q[o + 3], q[o + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz)
                }
                i += GeoModel.QUAD_FLOATS
            }
        }
    }

    private const val LEGACY_WINGS = "assets/lunar/cosmetics/models/gek/wings/legacy_dragon_wings/legacy_dragon_wings.gek.json"
}
