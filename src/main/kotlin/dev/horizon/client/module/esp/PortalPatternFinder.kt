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
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Where nether portals used to be, a sign of a base's old way in. Two ways to tell:
 * - [emptyFrames]: a whole obsidian frame with no portal in it. Works anywhere, but only if the frame was left standing.
 * - The idea of Trouser-Streak's Portal Pattern Finder: carved caves are filled with cave air and a broken portal leaves
 *   plain air, so a flat portal-shaped patch of plain air in cave air is a portal someone removed, frame and all. Only
 *   works where there is cave air. In terrain 26.3 generates (tools/examples/hunt-cave-air.txt) that's some of the
 *   overworld's deep regions and none of the nether; older terrain may have more.
 * Sections without what each looks for are skipped on their palettes. A chunk waits for its neighbours, as a shape can
 * lie across a border.
 */
class PortalPatternFinder : Module("portal-pattern-finder", Categories.ESP) {
    enum class Render { BOX, SHADER }

    val emptyFrames = bool("empty-frames", true)

    /** The largest shape, frame included. */
    val maxWidth = int("max-width", 8, 4..23)
    val maxHeight = int("max-height", 8, 5..23)

    /** How many of the blocks in front of and behind the shape may be plain air too. */
    val adjacentAir = int("adjacent-air", 15, 0..50)
    val render = enum("render", Render.BOX)
    val shaderMode = enum("shader-mode", ShaderMode.BLOOM) { shader() }
    val color = color("color", Color(0x96AA00FF.toInt(), true))
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

    /** One find: the box of the portal's inside, or of the patch of air; [frame] if it's an empty frame. */
    private data class Found(val box: AABB, val frame: Boolean)

    /** What each scanned chunk found. A shape across a border is found from both sides, once each. */
    private val chunks = HashMap<Long, List<Found>>()
    private val dirty = HashSet<Long>()
    private var found: List<Found> = emptyList()
    private var scannedFrames = true
    private var changed = false
    private val announced = HashSet<AABB>()
    private var level: ClientLevel? = null
    private var ticks = 0

    private var mask: TextureTarget? = null
    private var secondary: TextureTarget? = null
    private var masked = false

    init {
        on<TickEvent> { tick() }
        on<BlockStateEvent> { e ->
            val air = Blocks.AIR
            val cave = Blocks.CAVE_AIR
            val was = e.previous?.block
            if (e.state.block === air || e.state.block === cave || was === air || was === cave) {
                dirty += ChunkPos.pack(e.pos.x shr 4, e.pos.z shr 4)
                if (e.pos.x and 15 == 0) dirty += ChunkPos.pack((e.pos.x shr 4) - 1, e.pos.z shr 4)
                if (e.pos.x and 15 == 15) dirty += ChunkPos.pack((e.pos.x shr 4) + 1, e.pos.z shr 4)
                if (e.pos.z and 15 == 0) dirty += ChunkPos.pack(e.pos.x shr 4, (e.pos.z shr 4) - 1)
                if (e.pos.z and 15 == 15) dirty += ChunkPos.pack(e.pos.x shr 4, (e.pos.z shr 4) + 1)
            }
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
        found = emptyList()
        announced.clear()
        level = null
    }

    /** A chunk's shapes can reach into the four next to it, so it's only scanned with them loaded. */
    private fun ready(lvl: ClientLevel, cx: Int, cz: Int) = lvl.chunkSource.getChunkNow(cx - 1, cz) != null &&
        lvl.chunkSource.getChunkNow(cx + 1, cz) != null && lvl.chunkSource.getChunkNow(cx, cz - 1) != null &&
        lvl.chunkSource.getChunkNow(cx, cz + 1) != null

    private fun tick() {
        val lvl = mc.level ?: return
        val p = mc.player ?: return
        if (lvl !== level) {
            // A new world or dimension: nothing found so far applies.
            reset()
            level = lvl
        }
        if (scannedFrames != emptyFrames.value) {
            scannedFrames = emptyFrames.value
            chunks.clear()
            changed = true
        }
        if (++ticks % 20 == 0 && chunks.keys.removeIf { lvl.chunkSource.getChunkNow(ChunkPos.getX(it), ChunkPos.getZ(it)) == null }) changed = true
        for (key in dirty) {
            if (key !in chunks) continue
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            val chunk = lvl.chunkSource.getChunkNow(cx, cz)
            // Without its neighbours it goes back to being a new chunk, and gets its turn when they load.
            if (chunk == null || !ready(lvl, cx, cz)) chunks.remove(key) else chunks[key] = scan(lvl, chunk)
            changed = true
        }
        dirty.clear()
        val cx = p.blockX shr 4
        val cz = p.blockZ shr 4
        var budget = SCANS_PER_TICK
        rings@ for (r in 0..mc.options.renderDistance().get() + 1) {
            for (dx in -r..r) for (dz in -r..r) {
                if (max(abs(dx), abs(dz)) != r) continue
                val key = ChunkPos.pack(cx + dx, cz + dz)
                if (key in chunks) continue
                val chunk = lvl.chunkSource.getChunkNow(cx + dx, cz + dz) ?: continue
                if (!ready(lvl, cx + dx, cz + dz)) continue
                chunks[key] = scan(lvl, chunk)
                changed = true
                if (--budget == 0) break@rings
            }
        }
        if (changed) {
            changed = false
            // A kept frame around a patch of plain air is both: the frame says more.
            found = chunks.values.flatten().sortedByDescending { it.frame }.distinctBy { it.box }
            announce(lvl)
        }
    }

    /** Every portal shape touching [chunk]. */
    private fun scan(lvl: ClientLevel, chunk: LevelChunk): List<Found> {
        val out = ArrayList<Found>()
        val x0 = chunk.pos.x() shl 4
        val z0 = chunk.pos.z() shl 4
        val seenX = HashSet<Long>()
        val seenZ = HashSet<Long>()
        val q = BlockPos.MutableBlockPos()
        val sections = chunk.sections
        for (i in sections.indices) {
            val s = sections[i]
            if (s.hasOnlyAir()) continue
            val y0 = lvl.getSectionYFromSectionIndex(i) shl 4
            if (emptyFrames.value && s.states.maybeHas { it.block === Blocks.OBSIDIAN }) {
                for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                    if (s.getBlockState(x, y, z).block !== Blocks.OBSIDIAN) continue
                    frame(lvl, x0 + x, y0 + y, z0 + z, 1, 0, q)?.let { out += Found(it, true) }
                    frame(lvl, x0 + x, y0 + y, z0 + z, 0, 1, q)?.let { out += Found(it, true) }
                }
            }
            if (!s.states.maybeHas { it.block === Blocks.CAVE_AIR } || !s.states.maybeHas { it.block === Blocks.AIR }) continue
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                if (s.getBlockState(x, y, z).block !== Blocks.AIR) continue
                val wx = x0 + x
                val wy = y0 + y
                val wz = z0 + z
                // Along X the shape's front and back are north and south; along Z, west and east.
                if (isCave(lvl, q.set(wx, wy, wz - 1)) || isCave(lvl, q.set(wx, wy, wz + 1))) {
                    shape(lvl, wx, wy, wz, true, seenX)?.let { out += Found(it, false) }
                }
                if (isCave(lvl, q.set(wx - 1, wy, wz)) || isCave(lvl, q.set(wx + 1, wy, wz))) {
                    shape(lvl, wx, wy, wz, false, seenZ)?.let { out += Found(it, false) }
                }
            }
        }
        return out
    }

    private fun obsidian(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos) =
        lvl.getBlockState(q.set(x, y, z)).block === Blocks.OBSIDIAN

    private fun empty(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos) =
        lvl.getBlockState(q.set(x, y, z)).isAir

    /**
     * The inside of an empty frame whose bottom row has this block at its first column, the frame running along
     * ([dx], [dz]). Being the first column (a pillar to its left) finds each frame once. The frame's corners don't matter,
     * as for a portal.
     */
    private fun frame(lvl: ClientLevel, x: Int, y: Int, z: Int, dx: Int, dz: Int, q: BlockPos.MutableBlockPos): AABB? {
        if (!empty(lvl, x, y + 1, z, q) || !obsidian(lvl, x - dx, y + 1, z - dz, q)) return null
        val widest = maxWidth.value - 2
        val tallest = maxHeight.value - 2
        var w = 0
        while (w <= widest && empty(lvl, x + dx * w, y + 1, z + dz * w, q) && obsidian(lvl, x + dx * w, y, z + dz * w, q)) w++
        if (w < 2 || w > widest || !obsidian(lvl, x + dx * w, y + 1, z + dz * w, q)) return null
        var h = 0
        while (h <= tallest && empty(lvl, x, y + 1 + h, z, q) && obsidian(lvl, x - dx, y + 1 + h, z - dz, q)) h++
        if (h < 3 || h > tallest || !obsidian(lvl, x, y + 1 + h, z, q)) return null
        for (i in 0 until w) {
            if (!obsidian(lvl, x + dx * i, y, z + dz * i, q) || !obsidian(lvl, x + dx * i, y + 1 + h, z + dz * i, q)) return null
            for (j in 0 until h) if (!empty(lvl, x + dx * i, y + 1 + j, z + dz * i, q)) return null
        }
        for (j in 0 until h) {
            if (!obsidian(lvl, x - dx, y + 1 + j, z - dz, q) || !obsidian(lvl, x + dx * w, y + 1 + j, z + dz * w, q)) return null
        }
        return AABB(x.toDouble(), y + 1.0, z.toDouble(), x + dx * (w - 1) + 1.0, y + 1.0 + h, z + dz * (w - 1) + 1.0)
    }

    private fun isCave(lvl: ClientLevel, pos: BlockPos) = lvl.getBlockState(pos).block === Blocks.CAVE_AIR

    private fun isAir(lvl: ClientLevel, pos: BlockPos) = lvl.getBlockState(pos).block === Blocks.AIR

    /** The patch of plain air on the plane through this block ([alongX]: x and y, else z and y), if it's a portal's. */
    private fun shape(lvl: ClientLevel, wx: Int, wy: Int, wz: Int, alongX: Boolean, seen: HashSet<Long>): AABB? {
        val q = BlockPos.MutableBlockPos()
        if (!seen.add(BlockPos.asLong(wx, wy, wz))) return null
        val cap = maxWidth.value * maxHeight.value
        val cells = HashSet<HuntShapes.Cell>()
        val queue = ArrayDeque<HuntShapes.Cell>()
        val start = HuntShapes.Cell(if (alongX) wx else wz, wy)
        cells += start
        queue.addLast(start)
        var tooBig = false
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for ((du, dv) in NEIGHBOURS) {
                val n = HuntShapes.Cell(c.u + du, c.v + dv)
                if (n in cells) continue
                if (alongX) q.set(n.u, n.v, wz) else q.set(wx, n.v, n.u)
                if (!isAir(lvl, q)) continue
                cells += n
                seen.add(q.asLong())
                if (cells.size > cap) {
                    tooBig = true
                    queue.clear()
                    break
                }
                queue.addLast(n)
            }
        }
        if (tooBig) return null
        val r = HuntShapes.portalRect(cells, maxWidth.value, maxHeight.value) ?: return null
        // A real one is one block thick, with mostly cave air around it.
        var air = 0
        var cave = 0
        for (c in cells) for (side in intArrayOf(-1, 1)) {
            if (alongX) q.set(c.u, c.v, wz + side) else q.set(wx + side, c.v, c.u)
            val b = lvl.getBlockState(q).block
            if (b === Blocks.AIR) air++ else if (b === Blocks.CAVE_AIR) cave++
        }
        val sides = cells.size * 2
        if (air * 100 > sides * adjacentAir.value || cave * 100 < sides * MIN_CAVE_PERCENT) return null
        return if (alongX) AABB(r.u0.toDouble(), r.v0.toDouble(), wz.toDouble(), r.u1 + 1.0, r.v1 + 1.0, wz + 1.0)
        else AABB(wx.toDouble(), r.v0.toDouble(), r.u0.toDouble(), wx + 1.0, r.v1 + 1.0, r.u1 + 1.0)
    }

    /** A chat line for each shape not seen before in this world (a few at most, then a count). */
    private fun announce(lvl: ClientLevel) {
        val p = mc.player ?: return
        val fresh = found.filter { it.box !in announced }
        announced += fresh.map { it.box }
        for (f in fresh) EventBus.post(FindEvent("clue", f.box.center))
        if (!notify.value || fresh.isEmpty()) return
        val sorted = fresh.sortedBy { p.distanceToSqr(it.box.center) }
        for (f in sorted.take(MAX_MESSAGES)) {
            val box = f.box
            val x = floor(box.center.x).toInt()
            val y = box.minY.toInt()
            val z = floor(box.center.z).toInt()
            val other = when (lvl.dimension()) {
                Level.NETHER -> " (overworld X: ${x * 8} Z: ${z * 8})"
                Level.OVERWORLD -> " (nether X: ${Math.floorDiv(x, 8)} Z: ${Math.floorDiv(z, 8)})"
                else -> ""
            }
            moduleMessage(name.trim(), "${if (f.frame) "Empty portal frame" else "Possible broken portal"} at X: $x Y: $y Z: $z, ${p.position().distanceTo(box.center).toInt()}m away$other")
        }
        if (sorted.size > MAX_MESSAGES) moduleMessage(name.trim(), "and ${sorted.size - MAX_MESSAGES} more")
    }

    private fun draw(view: Matrix4f) {
        if (found.isEmpty()) return
        val line = PlaceBoxes.withAlpha(color.value.rgb, 1f)
        if (tracers.value) for (f in found) World3D.line(Esp2.tracerStart(), f.box.center, line, noCull = true)
        if (shader()) {
            drawMask(view)
            return
        }
        for (f in found) {
            if (fill.value) World3D.fill(f.box, color.value.rgb)
            if (outline.value) World3D.outline(f.box, line)
        }
    }

    /** SHADER: every shape's box into [mask] (behind the level unless [throughWalls]), for Render2D to composite. */
    private fun drawMask(view: Matrix4f) {
        val m = Chams.ensure(mask, "horizon portal pattern").also { mask = it }
        secondary = Chams.ensure(secondary, "horizon portal pattern 2")
        val main = mc.gameRenderer.mainRenderTarget()
        val walls = throughWalls.value
        World3D.draw(if (walls) Pipelines.WORLD_FILL else Pipelines.WORLD_DEPTH_FILL, m.colorTextureView!!,
            if (walls) null else main.depthTextureView, view) { vc ->
            for (f in found) World3D.boxFill(vc, f.box, -1, -1)
        }
        masked = true
    }

    /** The distance to each shape on screen, above its middle. */
    private fun labels() {
        val cam = World3D.camera
        for (f in found) {
            val s = World3D.project(Vec3(f.box.center.x, f.box.maxY + 0.5, f.box.center.z)) ?: continue
            Fonts.bold8.drawCentered("${cam.distanceTo(f.box.center).toInt()}m", s[0], s[1], -1)
        }
    }

    private companion object {
        /** Chunks scanned per tick at most. */
        const val SCANS_PER_TICK = 16

        /** Chat lines for new shapes at once, before the rest is only counted. */
        const val MAX_MESSAGES = 5

        /** How much of what's in front of and behind a shape must be cave air (one side can be a wall). */
        const val MIN_CAVE_PERCENT = 40

        val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
