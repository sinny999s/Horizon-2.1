package dev.horizon.client.lunar

import com.google.gson.JsonParser
import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Not in the original: the particles of Lunar cosmetics and emotes ([Snowstorm] effects from Lunar's CDN,
 * `particles/schemes/<name>.particle.json`): cosmetics whose index line names an effect (auras, jetpacks, a few hats)
 * emit it while worn, from the feet (auras), the back (backpacks) or above the head (hats); an emote with an effect
 * emits it from its bone while it plays. About half of Lunar's effects draw from Minecraft 1.8's old particle sheet,
 * which 26.3 doesn't have: it's read from a 1.8.9 client jar already on this computer (the official launcher's), else
 * Mojang's own download of it, and kept with the cosmetics.
 */
object LunarParticles {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private const val LEGACY_SHEET = "textures/particle/particles"

    /** An emitter and where its local space is: [model] for the space of the cosmetic's model (pixels, y down). */
    private class Source(val emitter: Snowstorm.Emitter, var seen: Long, val model: Boolean)

    /** Emitters by wearer and source ("cosmetic:<id>", "emote"). Game thread. */
    private val emitters = HashMap<UUID, HashMap<String, Source>>()
    private val schemes = ConcurrentHashMap<String, Any>()
    private object Loading
    private object Missing

    @Volatile
    private var legacySheet: Identifier? = null

    @Volatile
    private var legacyLoading = false
    private var lastFrame = 0L

    private val shown: Boolean
        get() = LunarCosmetics.shown && LunarSettings.particles.value

    fun init() {
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            try {
                frame(ctx)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar particles: {}", e.toString())
            }
        }
    }

    /** The effect [name] once loaded (null while loading or missing). */
    private fun scheme(name: String): Snowstorm.Scheme? {
        when (val s = schemes[name]) {
            is Snowstorm.Scheme -> return s
            null -> {}
            else -> return null
        }
        if (LunarAssets.index == null) {
            LunarAssets.ensure()
            return null
        }
        schemes[name] = Loading
        LunarAssets.pool.execute {
            try {
                schemes[name] = Snowstorm.parse(String(LunarAssets.file("assets/lunar/particles/schemes/$name.particle.json"), Charsets.UTF_8))
            } catch (e: Exception) {
                schemes[name] = Missing
                HorizonClient.LOG.info("Lunar particles: no effect {} ({})", name, e.toString())
            }
        }
        return null
    }

    /** The bone an effect says it comes from (emotes), once the effect is loaded. */
    fun bone(name: String): String? = (schemes[name] as? Snowstorm.Scheme)?.bone

    /**
     * Keeps [uuid]'s emitter for [key] running effect [name] this frame. [model]: its local space is the cosmetic
     * model's (Lunar's jetpack and crown effects are written in model pixels, y down), not world blocks.
     */
    fun keep(uuid: UUID, key: String, name: String, model: Boolean = false) {
        if (!shown) return
        val s = scheme(name) ?: return
        val map = emitters.getOrPut(uuid) { HashMap() }
        val cur = map[key]
        if (cur != null && cur.emitter.scheme === s && !cur.emitter.done) {
            cur.seen = System.nanoTime()
            return
        }
        // A one-shot effect that's over starts again while its source lasts.
        map[key] = Source(Snowstorm.Emitter(s), System.nanoTime(), model)
    }

    private val positions = HashMap<String, Vec3>()

    /** Where [uuid]'s emitter for [key] is (world), set by its source every frame before drawing. */
    fun place(uuid: UUID, key: String, pos: Vec3) {
        positions["$uuid/$key"] = pos
    }

    fun clear() {
        emitters.clear()
        positions.clear()
    }

    private fun frame(ctx: net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.0 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.1)
        lastFrame = now
        val level = mc.level ?: return clear()
        if (!shown) {
            if (emitters.isNotEmpty()) clear()
            return
        }
        // Cosmetics' effects.
        val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        for (p in level.players()) {
            for ((id, name, where) in LunarCosmetics.particleEffects(p)) {
                val key = "cosmetic:$id"
                keep(p.uuid, key, name, model = where != 0)
                place(p.uuid, key, anchor(p, where, partial))
            }
        }
        val cam = ctx.levelState().cameraRenderState
        val camPos = cam.pos ?: return
        val q = cam.orientation ?: return
        val stale = now - 250_000_000L
        val it = emitters.entries.iterator()
        while (it.hasNext()) {
            val (uuid, map) = it.next()
            val player = level.getPlayerByUUID(uuid)
            val mi = map.entries.iterator()
            while (mi.hasNext()) {
                val (key, src) = mi.next()
                val e = src.emitter
                if (player == null || src.seen < stale) e.stop()
                val pos = positions["$uuid/$key"] ?: player?.getPosition(partial)
                if (pos != null) {
                    e.x = pos.x; e.y = pos.y; e.z = pos.z
                }
                e.yaw = (player?.yBodyRot ?: 0f).toDouble()
                e.update(dt) { x, y, z -> !level.getBlockState(BlockPos(x, y, z)).getCollisionShape(level, BlockPos(x, y, z)).isEmpty }
                if (e.done) {
                    mi.remove()
                    continue
                }
                draw(ctx, e, camPos, q, level, src.model)
            }
            if (map.isEmpty()) it.remove()
        }
    }

    /** Where a cosmetic's effect comes from on [p]: 0 feet, 1 back, 2 above the head. */
    private fun anchor(p: Player, place: Int, partial: Float): Vec3 {
        val base = p.getPosition(partial)
        return when (place) {
            1 -> {
                val r = Math.toRadians(p.yBodyRot.toDouble())
                base.add(Math.sin(r) * 0.3, 1.1, -Math.cos(r) * 0.3)
            }
            2 -> base.add(0.0, p.bbHeight + 0.2, 0.0)
            else -> base
        }
    }

    private fun texture(s: Snowstorm.Scheme): Identifier? {
        if (s.texture == LEGACY_SHEET || s.texture == "$LEGACY_SHEET.png") return legacy()
        val path = when {
            s.texture.startsWith("lunar:") -> LunarCosmeticIndex.asset(s.texture)
            // 1.8's texture folders are singular in 26.3.
            s.texture.startsWith("minecraft:") -> return Identifier.parse(s.texture.let { if (it.endsWith(".png")) it else "$it.png" }
                .replace("textures/blocks/", "textures/block/").replace("textures/items/", "textures/item/"))
            else -> "assets/lunar/" + s.texture
        }
        return LunarCosmetics.texture(path)
    }

    private fun draw(ctx: net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext, e: Snowstorm.Emitter, cam: Vec3,
                     q: org.joml.Quaternionf, level: net.minecraft.world.level.Level, model: Boolean) {
        if (e.particles.isEmpty()) return
        val tex = texture(e.scheme) ?: return
        val local = e.scheme.localPosition
        val n = e.particles.size
        val data = FloatArray(n * 4 * 7)
        val corner = Vector3f()
        val off = Vector3f()
        val turn = Math.toRadians(180 - e.yaw).toFloat()
        var w = 0
        for (p in e.particles) {
            var px: Double
            var py: Double
            var pz: Double
            if (local && model) {
                // Model space to the world: pixels to blocks, flipped as vanilla flips models, turned with the body.
                off.set(-p.x.toFloat() / 16f, -p.y.toFloat() / 16f, p.z.toFloat() / 16f).rotateY(turn)
                px = e.x + off.x - cam.x; py = e.y + off.y - cam.y; pz = e.z + off.z - cam.z
            } else {
                px = (if (local) e.x + p.x else p.x) - cam.x
                py = (if (local) e.y + p.y else p.y) - cam.y
                pz = (if (local) e.z + p.z else p.z) - cam.z
            }
            val light = if (e.scheme.lit) LightCoordsUtil.getLightCoords(level, BlockPos.containing(px + cam.x, py + cam.y, pz + cam.z)) else LightCoordsUtil.FULL_BRIGHT
            val rot = Math.toRadians(p.rot).toFloat()
            val c = Math.cos(rot.toDouble()).toFloat()
            val s = Math.sin(rot.toDouble()).toFloat()
            val corners = floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f, 1f, -1f)
            val us = floatArrayOf(p.u1, p.u1, p.u0, p.u0)
            val vs = floatArrayOf(p.v1, p.v0, p.v0, p.v1)
            for (k in 0 until 4) {
                val cx = corners[k * 2] * p.w.toFloat()
                val cy = corners[k * 2 + 1] * p.h.toFloat()
                corner.set(cx * c - cy * s, cx * s + cy * c, 0f)
                if (e.scheme.facing == "rotate_y" || e.scheme.facing == "lookat_y") {
                    // Upright, turned toward the camera about y only.
                    val yaw = Math.atan2(px, pz).toFloat()
                    val x0 = corner.x
                    corner.set(x0 * Math.cos(yaw.toDouble()).toFloat(), corner.y, -x0 * Math.sin(yaw.toDouble()).toFloat())
                } else q.transform(corner)
                data[w++] = (px + corner.x).toFloat(); data[w++] = (py + corner.y).toFloat(); data[w++] = (pz + corner.z).toFloat()
                data[w++] = us[k]; data[w++] = vs[k]
                data[w++] = Float.fromBits(p.argb)
                data[w++] = Float.fromBits(light)
            }
        }
        val type = RenderTypes.entityTranslucent(tex)
        ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), type) { pose, consumer ->
            var i = 0
            while (i < w) {
                consumer.addVertex(pose, data[i], data[i + 1], data[i + 2]).setColor(data[i + 5].toRawBits()).setUv(data[i + 3], data[i + 4])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(data[i + 6].toRawBits()).setNormal(pose, 0f, 1f, 0f)
                i += 7
            }
        }
    }

    // ---- Minecraft 1.8's particle sheet ----

    private fun legacy(): Identifier? {
        legacySheet?.let { return it }
        pendingSheet?.let { img ->
            pendingSheet = null
            val id = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/lunar/particles_1_8_9.png")
            mc.textureManager.register(id, dev.horizon.client.render.Textures.upload("lunar 1.8 particles", img))
            legacySheet = id
            return id
        }
        if (legacyLoading) return null
        legacyLoading = true
        LunarAssets.pool.execute {
            try {
                pendingSheet = ImageIO.read(ByteArrayInputStream(legacyBytes()))
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Lunar particles: no 1.8 particle sheet: {}", e.toString())
            }
        }
        return null
    }

    @Volatile
    private var pendingSheet: java.awt.image.BufferedImage? = null

    /** particles.png from a 1.8.9 client: kept, else a local jar, else Mojang's download. */
    private fun legacyBytes(): ByteArray {
        val kept = Storage.dir("lunar-cosmetics").resolve("particles-1.8.9.png")
        if (Files.exists(kept)) return Files.readAllBytes(kept)
        val appData = System.getenv("APPDATA")
        val local = listOfNotNull(
            appData?.let { Path.of(it, ".minecraft", "versions", "1.8.9", "1.8.9.jar") },
            Path.of(System.getProperty("user.home"), ".minecraft", "versions", "1.8.9", "1.8.9.jar"),
            Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft", "versions", "1.8.9", "1.8.9.jar"),
        ).firstOrNull { Files.exists(it) }
        val jar = local ?: download()
        val bytes = ZipFile(jar.toFile()).use { z ->
            val e = z.getEntry("assets/minecraft/textures/particle/particles.png") ?: error("no particles.png in ${jar.fileName}")
            z.getInputStream(e).use { it.readAllBytes() }
        }
        if (local == null) Files.deleteIfExists(jar)
        Files.write(kept, bytes)
        return bytes
    }

    private fun download(): Path {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
        fun get(url: String) = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body()
        val manifest = JsonParser.parseString(String(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))).asJsonObject
        val v = manifest.getAsJsonArray("versions").map { it.asJsonObject }.first { it.get("id").asString == "1.8.9" }
        val meta = JsonParser.parseString(String(get(v.get("url").asString))).asJsonObject
        val url = meta.getAsJsonObject("downloads").getAsJsonObject("client").get("url").asString
        val tmp = Files.createTempFile("horizon-1.8.9", ".jar")
        Files.write(tmp, get(url))
        return tmp
    }
}
