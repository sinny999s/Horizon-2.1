package dev.horizon.client.module.esp

import com.mojang.blaze3d.pipeline.TextureTarget
import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.BlockStateEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FindEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ColorMode2
import dev.horizon.client.module.Module
import dev.horizon.client.module.ShaderMode
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NetherPortalBlock
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Lit nether portals in the loaded chunks, for base hunting (a portal out in the middle of nowhere is
 * someone's way home). Chunks are scanned as they arrive, and again when a portal block in them is lit or broken,
 * skipping every section whose palette has no portal block. Touching portal blocks of one axis are one portal, drawn as
 * one box: filled and outlined (BOX), or through the chams shader of [shaderMode] (SHADER). Optional tracers, a
 * distance label, and a chat line for each new portal with the other dimension's coordinates. Portals within
 * [spawnRadius] blocks of 0 0 on X and Z (in this dimension's own coordinates) are ignored.
 */
class PortalEsp : Module("portal-esp", Categories.ESP) {
    enum class Render { BOX, SHADER }

    val render = enum("render", Render.SHADER)
    val shaderMode = enum("shader-mode", ShaderMode.BLOOM) { shader() }
    val color = color("color", Color(0x96B14CFF.toInt(), true))
    val secondColor = color("second-color", Color(0xFFFF4FD8.toInt(), true)) { mode(ShaderMode.DOUBLE) || mode(ShaderMode.CAMOUFLAGE) }
    val throughWalls = bool("through-walls", true) { shader() }
    val fill = bool("fill", true) { !shader() }
    val outline = bool("outline", true) { !shader() }
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
    val tracers = bool("tracers", true)
    val labels = bool("labels", true)
    val notify = bool("notify", true)
    val spawnRadius = int("spawn-radius", 0, 0..100000)

    /** One portal: the blocks' box, flattened to the portal's thickness, and where its bottom middle is. */
    private class Portal(val box: AABB) {
        val center: Vec3 = box.center
        val bottom = BlockPos.containing(center.x, box.minY, center.z)
    }

    /** The portal blocks of each scanned chunk (empty for none), and whether each is on the X axis. */
    private val chunks = HashMap<Long, Map<BlockPos, Boolean>>()
    private val dirty = HashSet<Long>()
    private var portals: List<Portal> = emptyList()
    private var changed = false
    private var groupedRadius = -1
    private val announced = ArrayList<AABB>()
    private var level: ClientLevel? = null
    private var ticks = 0

    private var mask: TextureTarget? = null
    private var secondary: TextureTarget? = null
    private var masked = false

    init {
        on<TickEvent> { tick() }
        on<BlockStateEvent> { e ->
            if (e.state.block === Blocks.NETHER_PORTAL || e.previous?.block === Blocks.NETHER_PORTAL) dirty += ChunkPos.pack(e.pos.x shr 4, e.pos.z shr 4)
        }
        on<AfterReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundLevelChunkWithLightPacket ?: return@on
            dirty += ChunkPos.pack(p.x, p.z)
        }
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
            if (labels.value) labels()
        }
    }

    private fun shader() = render.value == Render.SHADER

    private fun mode(m: ShaderMode) = shader() && shaderMode.value == m

    override fun onEnable() = reset()

    private fun reset() {
        chunks.clear()
        dirty.clear()
        portals = emptyList()
        announced.clear()
        level = null
    }

    private fun tick() {
        val lvl = mc.level ?: return
        val p = mc.player ?: return
        if (lvl !== level) {
            // A new world or dimension: nothing found so far applies.
            reset()
            level = lvl
        }
        // Chunks that unloaded take their portals with them.
        if (++ticks % 20 == 0 && chunks.keys.removeIf { lvl.chunkSource.getChunkNow(ChunkPos.getX(it), ChunkPos.getZ(it)) == null }) changed = true
        for (key in dirty) {
            if (key !in chunks) continue
            val chunk = lvl.chunkSource.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key))
            if (chunk == null) chunks.remove(key) else chunks[key] = scan(lvl, chunk)
            changed = true
        }
        dirty.clear()
        // New chunks, nearest first, a few at a time (most are skipped on their palettes alone).
        val cx = p.blockX shr 4
        val cz = p.blockZ shr 4
        var budget = SCANS_PER_TICK
        rings@ for (r in 0..mc.options.renderDistance().get() + 1) {
            for (dx in -r..r) for (dz in -r..r) {
                if (max(abs(dx), abs(dz)) != r) continue
                val key = ChunkPos.pack(cx + dx, cz + dz)
                if (key in chunks) continue
                val chunk = lvl.chunkSource.getChunkNow(cx + dx, cz + dz) ?: continue
                chunks[key] = scan(lvl, chunk)
                changed = true
                if (--budget == 0) break@rings
            }
        }
        if (changed || groupedRadius != spawnRadius.value) {
            changed = false
            groupedRadius = spawnRadius.value
            portals = group(lvl)
            announce(lvl)
        }
    }

    /** Every portal block in [chunk], with its axis. */
    private fun scan(lvl: ClientLevel, chunk: LevelChunk): Map<BlockPos, Boolean> {
        var found: HashMap<BlockPos, Boolean>? = null
        val x0 = chunk.pos.x() shl 4
        val z0 = chunk.pos.z() shl 4
        val sections = chunk.sections
        for (i in sections.indices) {
            val s = sections[i]
            if (s.hasOnlyAir() || !s.states.maybeHas { it.block === Blocks.NETHER_PORTAL }) continue
            val y0 = lvl.getSectionYFromSectionIndex(i) shl 4
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                val state = s.getBlockState(x, y, z)
                if (state.block !== Blocks.NETHER_PORTAL) continue
                if (found == null) found = HashMap()
                found[BlockPos(x0 + x, y0 + y, z0 + z)] = state.getValue(NetherPortalBlock.AXIS) == Direction.Axis.X
            }
        }
        return found ?: emptyMap()
    }

    /** Touching portal blocks of the same axis, each group as one [Portal]; those within [spawnRadius] are left out. */
    private fun group(lvl: ClientLevel): List<Portal> {
        val all = HashMap<BlockPos, Boolean>()
        for (c in chunks.values) all.putAll(c)
        val seen = HashSet<BlockPos>()
        val list = ArrayList<Portal>()
        val queue = ArrayDeque<BlockPos>()
        for ((start, xAxis) in all) {
            if (!seen.add(start)) continue
            var x0 = start.x
            var y0 = start.y
            var z0 = start.z
            var x1 = x0
            var y1 = y0
            var z1 = z0
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val b = queue.removeFirst()
                x0 = minOf(x0, b.x); y0 = minOf(y0, b.y); z0 = minOf(z0, b.z)
                x1 = maxOf(x1, b.x); y1 = maxOf(y1, b.y); z1 = maxOf(z1, b.z)
                for (d in Direction.entries) {
                    val q = b.relative(d)
                    if (all[q] == xAxis && seen.add(q)) queue.addLast(q)
                }
            }
            // As thick as the block's own shape (6 to 10 sixteenths across the portal).
            val box = if (xAxis) AABB(x0.toDouble(), y0.toDouble(), z0 + 0.375, x1 + 1.0, y1 + 1.0, z1 + 0.625)
            else AABB(x0 + 0.375, y0.toDouble(), z0.toDouble(), x1 + 0.625, y1 + 1.0, z1 + 1.0)
            val portal = Portal(box)
            val r = spawnRadius.value.toDouble()
            if (r > 0 && abs(portal.center.x) <= r && abs(portal.center.z) <= r) continue
            list += portal
        }
        return list
    }

    /**
     * A chat line for each portal not seen before in this world (a few at most, then a count). One that grew as more of
     * it loaded, or was relit, still touches what was seen.
     */
    private fun announce(lvl: ClientLevel) {
        val p = mc.player ?: return
        val fresh = portals.filter { portal -> announced.none { it.intersects(portal.box.inflate(1.0)) } }
        announced += fresh.map { it.box }
        // At the bottom middle, so the map gives the same Y as the chat message below.
        for (portal in fresh) EventBus.post(FindEvent("portal", Vec3(portal.center.x, portal.box.minY, portal.center.z)))
        if (!notify.value || fresh.isEmpty()) return
        val sorted = fresh.sortedBy { p.distanceToSqr(it.center) }
        for (portal in sorted.take(MAX_MESSAGES)) {
            val b = portal.bottom
            val other = when (lvl.dimension()) {
                Level.NETHER -> " (overworld X: ${b.x * 8} Z: ${b.z * 8})"
                Level.OVERWORLD -> " (nether X: ${Math.floorDiv(b.x, 8)} Z: ${Math.floorDiv(b.z, 8)})"
                else -> ""
            }
            moduleMessage(name.trim(), "Portal at X: ${b.x} Y: ${b.y} Z: ${b.z}, ${p.position().distanceTo(portal.center).toInt()}m away$other")
        }
        if (sorted.size > MAX_MESSAGES) moduleMessage(name.trim(), "and ${sorted.size - MAX_MESSAGES} more portals")
    }

    private fun draw(view: Matrix4f) {
        if (portals.isEmpty()) return
        val line = PlaceBoxes.withAlpha(color.value.rgb, 1f)
        if (tracers.value) for (portal in portals) World3D.line(Esp2.tracerStart(), portal.center, line, noCull = true)
        if (shader()) {
            drawMask(view)
            return
        }
        for (portal in portals) {
            if (fill.value) World3D.fill(portal.box, color.value.rgb)
            if (outline.value) World3D.outline(portal.box, line)
        }
    }

    /** SHADER: every portal's box into [mask] (behind the level unless [throughWalls]), for Render2D to composite. */
    private fun drawMask(view: Matrix4f) {
        val m = Chams.ensure(mask, "horizon portal esp").also { mask = it }
        secondary = Chams.ensure(secondary, "horizon portal esp 2")
        val main = mc.gameRenderer.mainRenderTarget()
        val walls = throughWalls.value
        World3D.draw(if (walls) Pipelines.WORLD_FILL else Pipelines.WORLD_DEPTH_FILL, m.colorTextureView!!,
            if (walls) null else main.depthTextureView, view) { vc ->
            for (portal in portals) World3D.boxFill(vc, portal.box, -1, -1)
        }
        masked = true
    }

    /** The distance to each portal on screen, above its middle. */
    private fun labels() {
        val cam = World3D.camera
        for (portal in portals) {
            val top = Vec3(portal.center.x, portal.box.maxY + 0.5, portal.center.z)
            val s = World3D.project(top) ?: continue
            Fonts.bold8.drawCentered("${cam.distanceTo(portal.center).toInt()}m", s[0], s[1], -1)
        }
    }

    private companion object {
        /** Chunks scanned per tick at most. */
        const val SCANS_PER_TICK = 32

        /** Chat lines for new portals at once, before the rest is only counted. */
        const val MAX_MESSAGES = 5
    }
}
