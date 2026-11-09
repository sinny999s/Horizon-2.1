package dev.horizon.client.module.esp

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.config.Storage
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode2
import dev.horizon.client.module.Module
import dev.horizon.client.module.ShaderMode
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A line along the path you've walked (or flown, or ridden), so you can find your way back. A point is
 * dropped every [spacing] blocks at your feet; a jump of more than [BREAK] blocks in one tick (a teleport, a pearl, a
 * respawn) starts a new piece rather than drawing a line across. Each server and dimension has its own trail, the last
 * [length] points, kept on disk under `horizon/breadcrumbs/` with [save] (so it survives relogs and restarts). Drawn
 * within [range] blocks of the camera, through walls or not: as a line whose older points fade with [fade], or (SHADER)
 * as a ribbon [width] blocks wide, facing the camera, put on the screen through the chams shader of [shaderMode].
 */
class Breadcrumbs : Module("breadcrumbs", Categories.ESP) {
    enum class Render { LINE, SHADER }

    val render = enum("render", Render.LINE)
    val shaderMode = enum("shader-mode", ShaderMode.BLOOM) { shader() }
    val color = color("color", Color(0xFF4FC3FF.toInt(), true))
    val secondColor = color("second-color", Color(0xFFB45CFF.toInt(), true)) { mode(ShaderMode.DOUBLE) || mode(ShaderMode.CAMOUFLAGE) }
    val fade = bool("fade", true) { render.value == Render.LINE }
    val throughWalls = bool("through-walls", true)
    val width = float("width", 0.15f, 0.02f..1.0f) { shader() }
    val glass = bool("glass", false) { mode(ShaderMode.MIRROR) }
    val border = bool("border", false) { mode(ShaderMode.MIRROR) }
    val alpha = float("alpha", 0.2f, 0.0f..2.0f) { mode(ShaderMode.MIRROR) }
    val radius = float("radius", 6.0f, 0.1f..15.0f) { mode(ShaderMode.BLOOM) }
    val distance = float("distance", 1.3f, 0.1f..15.0f) { mode(ShaderMode.BLOOM) }
    val glowAlpha = float("glow-alpha", 1.7f, 1.0f..5.0f) { mode(ShaderMode.BLOOM) }
    val lineWidth = float("line-width", 2.0f, 1.0f..4.0f) { mode(ShaderMode.DEFAULT) || mode(ShaderMode.BLOOM) || mode(ShaderMode.CAMOUFLAGE) }
    val colorMode = enum("color-mode", ColorMode2.RGB) { mode(ShaderMode.DOUBLE) }
    val glowQuality = int("glow-quality", 2, 1..4) { mode(ShaderMode.DOUBLE) }
    val glowRadius = float("glow-radius", 4.0f, 1.0f..16.0f) { mode(ShaderMode.DOUBLE) }
    val glowAlphaMultiplier = float("glow-alpha-multiplier", 2.0f, 0.0f..4.0f) { mode(ShaderMode.DOUBLE) }
    val fillAlphaMultiplier = float("fill-alpha-multiplier", 0.6f, 0.0f..1.0f) { mode(ShaderMode.DOUBLE) }
    val saturation = float("saturation", 0.7f, 0.0f..5.0f) { mode(ShaderMode.DOUBLE) }
    val brightness = float("brightness", 2.0f, 0.0f..45.0f) { mode(ShaderMode.DOUBLE) }
    val length = int("length", 5000, 100..20000)
    val spacing = float("spacing", 0.5f, 0.1f..5.0f)
    val height = float("height", 0.1f, 0.0f..2.0f)
    val range = int("range", 256, 16..1024)
    val save = bool("save", true)
    val clear = action("clear") { clearTrail() }

    /** Every trail seen this session, by [key]; null entries are breaks between pieces. */
    private val trails = HashMap<String, ArrayDeque<Vec3?>>()
    private val dirty = HashSet<String>()
    private var key: String? = null
    private var ticks = 0

    /** SHADER's mask of the ribbon, BLOOM's scratch target, and whether the mask was drawn this frame. */
    private var mask: TextureTarget? = null
    private var secondary: TextureTarget? = null
    private var masked = false

    init {
        on<TickEvent> { tick() }
        on<Render3DEvent> { e -> draw(e.view) }
        on<Render2DEvent> {
            val m = mask
            val s = secondary
            if (masked && m != null && s != null) {
                Chams.composite(shaderMode.value, color.value.rgb, m, s, glass.value, border.value, radius.value, distance.value, glowAlpha.value,
                    lineWidth.value, colorMode.value.ordinal, color.value.rgb, secondColor.value.rgb, glowQuality.value, glowRadius.value,
                    glowAlphaMultiplier.value, fillAlphaMultiplier.value, saturation.value, brightness.value, alpha.value)
            }
            masked = false
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { if (enabled) saveAll() }
    }

    private fun shader() = render.value == Render.SHADER

    private fun mode(m: ShaderMode) = shader() && shaderMode.value == m

    override fun onDisable() {
        saveAll()
        key = null
    }

    /** The trail of the world you're in for the hunt map (nulls are breaks), even while the module is off. */
    fun trailForMap(): List<Vec3?> {
        val k = currentKey() ?: return emptyList()
        return trails[k]?.toList() ?: load(k).toList()
    }

    /** The trail's name: the server's address (or the singleplayer world's name) and the dimension. */
    private fun currentKey(): String? {
        val level = mc.level ?: return null
        val server = mc.singleplayerServer?.worldData?.levelName?.let { "singleplayer-$it" } ?: mc.currentServer?.ip ?: "unknown"
        return (server + "_" + level.dimension().identifier().path).replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun tick() {
        val k = currentKey()
        if (k != key) {
            // Left the world or changed dimension: write the old trail, pick up the new one.
            saveAll()
            key = k
            if (k != null && k !in trails) trails[k] = load(k)
        }
        val p = mc.player ?: return
        if (k == null) return
        val trail = trails[k] ?: return
        val pos = p.position()
        val last = trail.lastOrNull()
        when {
            last == null -> trail.addLast(pos)
            last.distanceTo(pos) > BREAK -> {
                trail.addLast(null)
                trail.addLast(pos)
            }
            last.distanceTo(pos) >= spacing.value -> trail.addLast(pos)
            else -> return
        }
        while (trail.size > length.value || trail.firstOrNull() == null && trail.isNotEmpty()) trail.removeFirst()
        dirty += k
        if (++ticks >= 1200) {
            ticks = 0
            saveAll()
        }
    }

    private fun draw(view: Matrix4f) {
        val trail = trails[key ?: return] ?: return
        if (trail.isEmpty()) return
        val p = mc.player ?: return
        val pt = World3D.partialTick.toDouble()
        val feet = Vec3(Mth.lerp(pt, p.xo, p.x), Mth.lerp(pt, p.yo, p.y), Mth.lerp(pt, p.zo, p.z))
        if (shader()) {
            drawMask(view, trail, feet)
            return
        }
        val up = height.value.toDouble()
        val base = color.value.rgb
        val n = trail.size
        fun shade(i: Int): Int {
            if (!fade.value || n < 2) return base
            return PlaceBoxes.withAlpha(base, (base ushr 24) / 255f * (0.15f + 0.85f * i / (n - 1)))
        }
        segments(trail, feet) { a, b, i ->
            val s = a.add(0.0, up, 0.0)
            val e = b.add(0.0, up, 0.0)
            val c1 = shade(i - 1)
            val c2 = shade(min(i, n - 1))
            if (throughWalls.value) World3D.line(s, e, c1, c2, noCull = true) else World3D.depthLine(s, e, c1, c2)
        }
    }

    /**
     * Every piece of the trail within [range] of the camera, as its two ends and the second one's index; then on to
     * [feet] (index = the trail's size), so the trail follows you between points.
     */
    private fun segments(trail: ArrayDeque<Vec3?>, feet: Vec3, fn: (Vec3, Vec3, Int) -> Unit) {
        val cam = World3D.camera
        val r2 = range.value.toDouble() * range.value
        fun near(a: Vec3, b: Vec3) = a.distanceToSqr(cam) <= r2 || b.distanceToSqr(cam) <= r2
        var prev: Vec3? = null
        for (i in 0 until trail.size) {
            val point = trail[i]
            if (point != null && prev != null && near(prev, point)) fn(prev, point, i)
            prev = point
        }
        val last = trail.last() ?: return
        if (last.distanceTo(feet) <= BREAK && near(last, feet)) fn(last, feet, trail.size)
    }

    /** SHADER: the ribbon into [mask] (behind the level unless [throughWalls]), for Render2D to composite. */
    private fun drawMask(view: Matrix4f, trail: ArrayDeque<Vec3?>, feet: Vec3) {
        val m = Chams.ensure(mask, "horizon breadcrumbs").also { mask = it }
        secondary = Chams.ensure(secondary, "horizon breadcrumbs 2")
        val main = mc.gameRenderer.mainRenderTarget()
        val walls = throughWalls.value
        World3D.draw(if (walls) Pipelines.WORLD_FILL else Pipelines.WORLD_DEPTH_FILL, m.colorTextureView!!,
            if (walls) null else main.depthTextureView, view) { vc ->
            segments(trail, feet) { a, b, _ -> ribbon(vc, a, b) }
        }
        masked = true
    }

    /**
     * One piece of the ribbon: a quad from [a] to [b] ([height] up), [width] wide across the line of sight, each end
     * pushed out by half the width so the pieces overlap at turns instead of leaving gaps.
     */
    private fun ribbon(vc: VertexConsumer, a: Vec3, b: Vec3) {
        val cam = World3D.camera
        val up = height.value.toDouble()
        val half = width.value / 2.0
        var dx = b.x - a.x
        var dy = b.y - a.y
        var dz = b.z - a.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6) return
        dx /= len
        dy /= len
        dz /= len
        val ax = a.x - cam.x - dx * half
        val ay = a.y + up - cam.y - dy * half
        val az = a.z - cam.z - dz * half
        val bx = b.x - cam.x + dx * half
        val by = b.y + up - cam.y + dy * half
        val bz = b.z - cam.z + dz * half
        // Across both the piece and the ray from the camera to its middle.
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
        val mz = (az + bz) / 2
        var sx = dy * mz - dz * my
        var sy = dz * mx - dx * mz
        var sz = dx * my - dy * mx
        val s = sqrt(sx * sx + sy * sy + sz * sz)
        if (s < 1e-6) return
        sx *= half / s
        sy *= half / s
        sz *= half / s
        vc.addVertex((ax - sx).toFloat(), (ay - sy).toFloat(), (az - sz).toFloat()).setColor(-1)
        vc.addVertex((ax + sx).toFloat(), (ay + sy).toFloat(), (az + sz).toFloat()).setColor(-1)
        vc.addVertex((bx + sx).toFloat(), (by + sy).toFloat(), (bz + sz).toFloat()).setColor(-1)
        vc.addVertex((bx - sx).toFloat(), (by - sy).toFloat(), (bz - sz).toFloat()).setColor(-1)
    }

    /** The trail of the world you're in, even while the module is off. */
    private fun clearTrail() {
        val k = key ?: currentKey() ?: return
        trails[k]?.clear()
        dirty -= k
        Files.deleteIfExists(file(k))
    }

    private fun file(k: String): Path = Storage.dir("breadcrumbs").resolve("$k.txt")

    /** One point per line ("x y z", to the centimetre), "-" for a break. */
    private fun load(k: String): ArrayDeque<Vec3?> {
        val trail = ArrayDeque<Vec3?>()
        if (!save.value) return trail
        val f = file(k)
        if (!Files.exists(f)) return trail
        try {
            for (line in Files.readAllLines(f)) {
                if (line == "-") {
                    trail.addLast(null)
                    continue
                }
                val parts = line.split(' ')
                if (parts.size == 3) trail.addLast(Vec3(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble()))
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't read {}", f, e)
        }
        return trail
    }

    private fun saveAll() {
        if (save.value) {
            for (k in dirty) {
                val trail = trails[k] ?: continue
                val sb = StringBuilder(trail.size * 24)
                for (point in trail) {
                    if (point == null) sb.append('-') else sb.append(cm(point.x)).append(' ').append(cm(point.y)).append(' ').append(cm(point.z))
                    sb.append('\n')
                }
                try {
                    Files.writeString(file(k), sb)
                } catch (e: Exception) {
                    HorizonClient.LOG.warn("Couldn't save {}", file(k), e)
                }
            }
        }
        dirty.clear()
    }

    private fun cm(v: Double) = Math.round(v * 100.0) / 100.0

    private companion object {
        /** Blocks moved in one tick beyond which the trail breaks instead of drawing a line across. */
        const val BREAK = 16.0
    }
}
