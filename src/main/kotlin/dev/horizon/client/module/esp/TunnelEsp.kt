package dev.horizon.client.module.esp

import com.mojang.blaze3d.pipeline.TextureTarget
import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.BlockStateEvent
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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Player-dug passages (the idea of Trouser-Streak's Hole/Tunnel/Stairs ESP, for following someone's route
 * to a base). A column of room with a floor and a ceiling, walled in on every side but the one or two it continues to, is
 * part of a passage; that one rule covers straight tunnels, ones zigzagging diagonally and winding ones, and with a step
 * up or down, stairs. [HuntShapes] joins the columns and drops anything too short or of varying height. [shafts] finds
 * one-block-wide vertical holes. A chunk waits for its neighbours, as what's on the far side of a border decides a column.
 */
class TunnelEsp : Module("tunnel-esp", Categories.ESP) {
    enum class Render { BOX, SHADER }

    val minLength = int("min-length", 10, 3..64)
    val minHeight = int("min-height", 2, 1..6)
    val maxHeight = int("max-height", 4, 2..8)
    val shafts = bool("shafts", true)
    /** Deep enough to skip the nether's natural gaps (up to 16 deep in real terrain, tools/examples/hunt-esp-terrain.txt). */
    val shaftDepth = int("shaft-depth", 20, 3..64) { shafts.value }
    val render = enum("render", Render.BOX)
    val shaderMode = enum("shader-mode", ShaderMode.BLOOM) { shader() }
    val color = color("color", Color(0x663C78FF.toInt(), true))
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
    val tracers = bool("tracers", false)
    val labels = bool("labels", true)
    val notify = bool("notify", false)

    private class Scan(val columns: List<HuntShapes.Column>, val shafts: List<AABB>)

    /** One thing to draw: its boxes, a word and length for the label, and where to put them. */
    private class Found(val boxes: List<AABB>, val what: String, val length: Int, val middle: Vec3)

    private val chunks = HashMap<Long, Scan>()
    private val dirty = HashSet<Long>()
    private var found: List<Found> = emptyList()
    private var changed = false
    private var grouped = ""
    private val announced = ArrayList<AABB>()
    private var level: ClientLevel? = null
    private var ticks = 0

    private var mask: TextureTarget? = null
    private var secondary: TextureTarget? = null
    private var masked = false

    init {
        on<TickEvent> { tick() }
        on<BlockStateEvent> { e ->
            val lvl = mc.level ?: return@on
            val was = e.previous ?: return@on
            if (open(lvl, e.pos, e.state) == open(lvl, e.pos, was)) return@on
            dirty += ChunkPos.pack(e.pos.x shr 4, e.pos.z shr 4)
            if (e.pos.x and 15 == 0) dirty += ChunkPos.pack((e.pos.x shr 4) - 1, e.pos.z shr 4)
            if (e.pos.x and 15 == 15) dirty += ChunkPos.pack((e.pos.x shr 4) + 1, e.pos.z shr 4)
            if (e.pos.z and 15 == 0) dirty += ChunkPos.pack(e.pos.x shr 4, (e.pos.z shr 4) - 1)
            if (e.pos.z and 15 == 15) dirty += ChunkPos.pack(e.pos.x shr 4, (e.pos.z shr 4) + 1)
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
        // Changing what counts means looking at everything again.
        val settings = "${minHeight.value} ${maxHeight.value} ${shafts.value} ${shaftDepth.value}"
        if (settings != grouped) {
            grouped = settings
            chunks.clear()
            changed = true
        }
        if (++ticks % 20 == 0 && chunks.keys.removeIf { lvl.chunkSource.getChunkNow(ChunkPos.getX(it), ChunkPos.getZ(it)) == null }) changed = true
        var budget = SCANS_PER_TICK
        val again = dirty.iterator()
        while (again.hasNext() && budget > 0) {
            val key = again.next()
            again.remove()
            if (key !in chunks) continue
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            val chunk = lvl.chunkSource.getChunkNow(cx, cz)
            // Without its neighbours it goes back to being a new chunk, and gets its turn when they load.
            if (chunk == null || !ready(lvl, cx, cz)) chunks.remove(key) else chunks[key] = scan(lvl, chunk)
            changed = true
            budget--
        }
        val cx = p.blockX shr 4
        val cz = p.blockZ shr 4
        rings@ for (r in 0..mc.options.renderDistance().get() + 1) {
            if (budget <= 0) break
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
        if (changed || minLength.value != lastLength) {
            changed = false
            lastLength = minLength.value
            group()
            announce()
        }
    }

    private var lastLength = -1

    /** Whether you can stand in this block (no liquid: a flooded passage isn't told apart from a lake). */
    private fun open(lvl: ClientLevel, pos: BlockPos, state: BlockState): Boolean =
        state.isAir || (state.fluidState.isEmpty && state.getCollisionShape(lvl, pos).isEmpty)

    private fun open(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos): Boolean {
        q.set(x, y, z)
        return open(lvl, q, lvl.getBlockState(q))
    }

    private fun solid(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos): Boolean {
        q.set(x, y, z)
        val state = lvl.getBlockState(q)
        return !state.isAir && !state.getCollisionShape(lvl, q).isEmpty
    }

    /** The room in the column standing on (x, y - 1, z), 0 if it isn't a floor-to-ceiling column of a passage's height. */
    private fun roomAt(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos): Int {
        if (!solid(lvl, x, y - 1, z, q)) return 0
        var h = 0
        while (h <= maxHeight.value && open(lvl, x, y + h, z, q)) h++
        if (h < minHeight.value || h > maxHeight.value) return 0
        return if (solid(lvl, x, y + h, z, q)) h else 0
    }

    /** Every passage column and shaft starting in [chunk]. */
    private fun scan(lvl: ClientLevel, chunk: LevelChunk): Scan {
        val columns = ArrayList<HuntShapes.Column>()
        val holes = ArrayList<AABB>()
        val x0 = chunk.pos.x() shl 4
        val z0 = chunk.pos.z() shl 4
        val q = BlockPos.MutableBlockPos()
        val sections = chunk.sections
        for (i in sections.indices) {
            val s = sections[i]
            // All air has no floors, and no air at all has no room.
            if (s.hasOnlyAir() || !s.states.maybeHas { it.isAir }) continue
            val y0 = lvl.getSectionYFromSectionIndex(i) shl 4
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                val wx = x0 + x
                val wy = y0 + y
                val wz = z0 + z
                // Not only air: a rail or a torch on the floor.
                if (!open(lvl, q.set(wx, wy, wz), s.getBlockState(x, y, z))) continue
                if (!solid(lvl, wx, wy - 1, wz, q)) continue
                column(lvl, wx, wy, wz, q)?.let { columns += it }
                if (shafts.value) shaft(lvl, wx, wy, wz, q)?.let { holes += it }
            }
        }
        return Scan(columns, holes)
    }

    /** The column here, if each of its four sides is a wall or the way on and there are only one or two ways on. */
    private fun column(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos): HuntShapes.Column? {
        val h = roomAt(lvl, x, y, z, q)
        if (h == 0) return null
        val links = ArrayList<HuntShapes.P>(2)
        for ((dx, dz) in SIDES) {
            val nx = x + dx
            val nz = z + dz
            val on = when {
                roomAt(lvl, nx, y, nz, q) > 0 -> y
                // Up a step needs head room for the walk, as does down one.
                h >= 3 && roomAt(lvl, nx, y + 1, nz, q) >= 3 -> y + 1
                h >= 3 && roomAt(lvl, nx, y - 1, nz, q) >= 3 -> y - 1
                else -> null
            }
            if (on != null) {
                if (links.size == 2) return null
                links += HuntShapes.P(nx, on, nz)
                continue
            }
            for (i in 0 until h) if (!solid(lvl, nx, y + i, nz, q)) return null
        }
        if (links.isEmpty()) return null
        return HuntShapes.Column(HuntShapes.P(x, y, z), h, links)
    }

    /** A one-wide hole going up from this floor, walled in all the way. */
    private fun shaft(lvl: ClientLevel, x: Int, y: Int, z: Int, q: BlockPos.MutableBlockPos): AABB? {
        var h = 0
        while (h < MAX_SHAFT && open(lvl, x, y + h, z, q) && SIDES.all { (dx, dz) -> solid(lvl, x + dx, y + h, z + dz, q) }) h++
        if (h < shaftDepth.value) return null
        return AABB(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, (y + h).toDouble(), z + 1.0)
    }

    private fun group() {
        val out = ArrayList<Found>()
        for (p in HuntShapes.passages(chunks.values.flatMap { it.columns }, minLength.value)) {
            val boxes = p.boxes.map { AABB(it.x0.toDouble(), it.y0.toDouble(), it.z0.toDouble(), it.x1 + 1.0, it.y1 + 1.0, it.z1 + 1.0) }
            out += Found(boxes, if (p.stairs) "Stairs" else "Tunnel", p.length, Vec3(p.middle.x + 0.5, p.middle.y + 0.5, p.middle.z + 0.5))
        }
        for (s in chunks.values.flatMap { it.shafts }.distinct()) {
            out += Found(listOf(s), "Shaft", (s.maxY - s.minY).toInt(), s.center)
        }
        found = out
    }

    /**
     * A chat line for each passage not seen before in this world (a few at most, then a count). One that grew, as more
     * of it loaded or was dug, still touches what was seen.
     */
    private fun announce() {
        val p = mc.player ?: return
        val fresh = found.filter { f -> f.boxes.none { b -> announced.any { it.intersects(b.inflate(0.5)) } } }
        for (f in found) announced += f.boxes
        if (!notify.value || fresh.isEmpty()) return
        val sorted = fresh.sortedBy { p.distanceToSqr(it.middle) }
        for (f in sorted.take(MAX_MESSAGES)) {
            moduleMessage(name.trim(), "${f.what}, ${f.length} long, at X: ${f.middle.x.toInt()} Y: ${f.middle.y.toInt()} Z: ${f.middle.z.toInt()}, ${p.position().distanceTo(f.middle).toInt()}m away")
        }
        if (sorted.size > MAX_MESSAGES) moduleMessage(name.trim(), "and ${sorted.size - MAX_MESSAGES} more")
    }

    private fun draw(view: Matrix4f) {
        if (found.isEmpty()) return
        val line = PlaceBoxes.withAlpha(color.value.rgb, 1f)
        if (tracers.value) for (f in found) World3D.line(Esp2.tracerStart(), f.middle, line, noCull = true)
        if (shader()) {
            drawMask(view)
            return
        }
        for (f in found) for (box in f.boxes) {
            if (fill.value) World3D.fill(box, color.value.rgb)
            if (outline.value) World3D.outline(box, line)
        }
    }

    /** SHADER: every passage's boxes into [mask] (behind the level unless [throughWalls]), for Render2D to composite. */
    private fun drawMask(view: Matrix4f) {
        val m = Chams.ensure(mask, "horizon tunnel esp").also { mask = it }
        secondary = Chams.ensure(secondary, "horizon tunnel esp 2")
        val main = mc.gameRenderer.mainRenderTarget()
        val walls = throughWalls.value
        World3D.draw(if (walls) Pipelines.WORLD_FILL else Pipelines.WORLD_DEPTH_FILL, m.colorTextureView!!,
            if (walls) null else main.depthTextureView, view) { vc ->
            for (f in found) for (box in f.boxes) World3D.boxFill(vc, box, -1, -1)
        }
        masked = true
    }

    /** What each one is and how long, on screen, above its middle. */
    private fun labels() {
        for (f in found) {
            val s = World3D.project(f.middle.add(0.0, 1.5, 0.0)) ?: continue
            Fonts.bold8.drawCentered("${f.what} ${f.length}", s[0], s[1], -1)
        }
    }

    private companion object {
        /** Chunks scanned per tick at most (each looks at every floor it has, so fewer than the palette-only scans). */
        const val SCANS_PER_TICK = 8

        /** Chat lines for new passages at once, before the rest is only counted. */
        const val MAX_MESSAGES = 5

        const val MAX_SHAFT = 256

        val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
