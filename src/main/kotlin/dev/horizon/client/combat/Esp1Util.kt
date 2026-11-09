package dev.horizon.client.combat

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.config.Friends
import dev.horizon.client.module.legit.Teams
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.module.RenderMode
import dev.horizon.client.module.basic.HoleAnchor
import dev.horizon.client.module.esp.HoleEsp
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import org.joml.Matrix3x2f
import java.util.Locale
import java.util.Optional
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector4d
import org.joml.Vector4f
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Hole cache, shared by hole-esp, hole-filler and hole-anchor: every tick in which someone asked for the
 * holes during the last second, a new scan around the player is started on a virtual thread; readers get the last scan
 * that has finished. Nobody asking for a second stops the scanning and empties the list.
 */
object Holes {
    enum class Safety { UNSAFE, SAFE }
    enum class Shape { SINGLE, DOUBLE_X, DOUBLE_Z, QUAD }

    /** A hole by its min-x/min-z air cell. */
    class Hole(val pos: BlockPos, val safety: Safety, val shape: Shape) {
        /** The hole's cells, [height] tall. */
        fun box(height: Float): AABB {
            val x = pos.x.toDouble()
            val y = pos.y.toDouble()
            val z = pos.z.toDouble()
            return when (shape) {
                Shape.DOUBLE_X -> AABB(x, y, z, x + 2.0, y + height, z + 1.0)
                Shape.DOUBLE_Z -> AABB(x, y, z, x + 1.0, y + height, z + 2.0)
                Shape.QUAD -> AABB(x, y, z, x + 2.0, y + height, z + 2.0)
                Shape.SINGLE -> AABB(x, y, z, x + 1.0, y + height, z + 1.0)
            }
        }
    }

    private val UNBREAKABLE: Set<Block> = setOf(Blocks.BEDROCK, Blocks.BARRIER)
    private val RESISTANT: Set<Block> = setOf(Blocks.OBSIDIAN, Blocks.ENDER_CHEST, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK)

    // The walls of each shape besides (-1, 0, 0) and (0, 0, -1), which every hole has.
    private val QUAD_WALLS = listOf(Vec3i(-1, 0, 1), Vec3i(0, 0, 2), Vec3i(1, 0, 2), Vec3i(2, 0, 1), Vec3i(2, 0, 0), Vec3i(1, 0, -1))
    private val DOUBLE_Z_WALLS = listOf(Vec3i(-1, 0, 1), Vec3i(0, 0, 2), Vec3i(1, 0, 1), Vec3i(1, 0, 0))
    private val DOUBLE_X_WALLS = listOf(Vec3i(0, 0, 1), Vec3i(1, 0, 1), Vec3i(2, 0, 0), Vec3i(1, 0, -1))

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val lastQuery = MsTimer()

    @Volatile
    private var holes: List<Hole> = emptyList()
    private var scan: Future<List<Hole>>? = null

    init {
        EventBus.on<PlayerUpdateEvent>(this) {
            if (lastQuery.passed(1000)) {
                if (holes.isNotEmpty()) holes = emptyList()
                return@on
            }
            scan = CompletableFuture.supplyAsync({ scan() }, executor).exceptionally { emptyList() }
        }
    }

    /** Plugs the cache's tick handler. */
    fun init() {}

    /** The latest finished scan; asking keeps the scanning going. */
    fun holes(): List<Hole> {
        lastQuery.reset()
        val s = scan
        if (s != null && s.isDone) holes = s.get()
        return holes
    }

    /** Whether [pos] is a cell of a cached hole. */
    fun contains(pos: BlockPos): Boolean = holes().any { h ->
        val o = h.pos
        pos == o || when (h.shape) {
            Shape.DOUBLE_X -> pos == o.offset(1, 0, 0)
            Shape.DOUBLE_Z -> pos == o.offset(0, 0, 1)
            Shape.QUAD -> pos == o.offset(0, 0, 1) || pos == o.offset(1, 0, 1) || pos == o.offset(1, 0, 0)
            Shape.SINGLE -> false
        }
    }

    private fun horizontal() = minOf(32, maxOf(Modules.get<HoleAnchor>().horizontalRange.value, Modules.get<HoleEsp>().rangeXY.value))

    private fun vertical() = minOf(32, maxOf(Modules.get<HoleAnchor>().verticalRange.value, Modules.get<HoleEsp>().rangeY.value))

    /** A block read off the main thread; air for anything that can't be read. */
    private fun block(x: Int, y: Int, z: Int): BlockState = try {
        val level = Minecraft.getInstance().level!!
        val chunk = level.getChunk(x shr 4, z shr 4)
        val index = level.getSectionIndex(y)
        val sections = chunk.sections
        if (index < 0 || index >= sections.size || sections[index].hasOnlyAir()) Blocks.AIR.defaultBlockState()
        else sections[index].getBlockState(x and 15, y and 15, z and 15)
    } catch (t: Throwable) {
        Blocks.AIR.defaultBlockState()
    }

    /** X, then y, then z over the scan box around the player's feet block. */
    private fun scan(): List<Hole> {
        val out = ArrayList<Hole>()
        val p = BlockPos.containing(Minecraft.getInstance().player!!.position())
        val h = horizontal()
        val v = vertical()
        for (x in p.x - h..p.x + h) for (y in p.y - v..p.y + v) for (z in p.z - h..p.z + h) {
            if (!block(x, y, z).canBeReplaced()) continue
            if (!block(x, y + 1, z).canBeReplaced()) continue
            if (!block(x, y + 2, z).canBeReplaced()) continue
            val floor = block(x, y - 1, z).block
            if (floor !in RESISTANT && floor !in UNBREAKABLE) continue
            classify(x, y, z)?.let { out += it }
        }
        return out
    }

    private class Walls {
        var resistant = 0
        var unbreakable = 0
        val total get() = resistant + unbreakable

        fun count(x: Int, y: Int, z: Int) {
            val b = block(x, y, z).block
            if (b in RESISTANT) resistant++ else if (b in UNBREAKABLE) unbreakable++
        }
    }

    /** The hole whose min corner is (x, y, z), if any; a shape that fails its walls doesn't fall back to a smaller one. */
    private fun classify(x: Int, y: Int, z: Int): Hole? {
        val w = Walls()
        w.count(x - 1, y, z)
        w.count(x, y, z - 1)
        if (w.total < 2) return null
        val pos = BlockPos(x, y, z)
        val airZ = block(x, y, z + 1).isAir
        val airX = block(x + 1, y, z).isAir
        if (airZ && airX) {
            if (!block(x + 1, y, z + 1).isAir) return null
            for (o in QUAD_WALLS) w.count(x + o.x, y + o.y, z + o.z)
            if (w.total != 8) return null
            return Hole(pos, if (w.unbreakable == 8) Safety.SAFE else Safety.UNSAFE, Shape.QUAD)
        }
        if (airZ && block(x, y, z + 1).canBeReplaced()) {
            for (o in DOUBLE_Z_WALLS) w.count(x + o.x, y + o.y, z + o.z)
            if (w.total != 6) return null
            return Hole(pos, if (w.unbreakable == 6) Safety.SAFE else Safety.UNSAFE, Shape.DOUBLE_Z)
        }
        if (airX && block(x + 1, y, z).canBeReplaced()) {
            for (o in DOUBLE_X_WALLS) w.count(x + o.x, y + o.y, z + o.z)
            if (w.total != 6) return null
            return Hole(pos, if (w.unbreakable == 6) Safety.SAFE else Safety.UNSAFE, Shape.DOUBLE_X)
        }
        w.count(x, y, z + 1)
        w.count(x + 1, y, z)
        if (w.total != 4) return null
        return Hole(pos, if (w.unbreakable == 4) Safety.SAFE else Safety.UNSAFE, Shape.SINGLE)
    }
}

/** 2D ESP frames: an entity's render box as a screen rectangle with a black border. */
object Frames {
    /** The entity's box at its interpolated position, 0.05 wider on each side and 0.15 taller. */
    fun renderBox(e: Entity): AABB {
        val pt = World3D.partialTick.toDouble()
        val x = Mth.lerp(pt, e.xo, e.x)
        val y = Mth.lerp(pt, e.yo, e.y)
        val z = Mth.lerp(pt, e.zo, e.z)
        val bb = e.boundingBox
        return AABB(bb.minX - e.x + x - 0.05, bb.minY - e.y + y, bb.minZ - e.z + z - 0.05,
            bb.maxX - e.x + x + 0.05, bb.maxY - e.y + y + 0.15, bb.maxZ - e.z + z + 0.05)
    }

    /**
     * The (min x, min y, max x, max y) of the on-screen corners of [e]'s render box, or null when none is on screen. The
     * max x starts at the first corner's depth and the max y at 0 (on purpose).
     */
    fun screenBox(e: Entity): FloatArray? {
        val b = renderBox(e)
        val corners = arrayOf(Vec3(b.minX, b.minY, b.minZ), Vec3(b.minX, b.maxY, b.minZ), Vec3(b.maxX, b.minY, b.minZ),
            Vec3(b.maxX, b.maxY, b.minZ), Vec3(b.minX, b.minY, b.maxZ), Vec3(b.minX, b.maxY, b.maxZ),
            Vec3(b.maxX, b.minY, b.maxZ), Vec3(b.maxX, b.maxY, b.maxZ))
        var r: DoubleArray? = null
        for (c in corners) {
            val v = Projection.project(c)
            if (!(v.z > 0.0 && v.z < 1.0)) continue
            val q = r ?: doubleArrayOf(v.x, v.y, v.z, 0.0).also { r = it }
            q[0] = minOf(v.x, q[0])
            q[1] = minOf(v.y, q[1])
            q[2] = maxOf(v.x, q[2])
            q[3] = maxOf(v.y, q[3])
        }
        val q = r ?: return null
        return floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
    }

    /** Four black bars, then the four coloured half-unit lines. */
    fun draw(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val black = java.awt.Color.BLACK.rgb
        bar(x1 - 1.0f, y1, x1 + 0.5f, y2 + 0.5f, black)
        bar(x1 - 1.0f, y1 - 0.5f, x2 + 0.5f, y1 + 1.0f, black)
        bar(x2 - 1.0f, y1, x2 + 0.5f, y2 + 0.5f, black)
        bar(x1 - 1.0f, y2 - 1.0f, x2 + 0.5f, y2 + 0.5f, black)
        bar(x1 - 0.5f, y1, x1, y2, color)
        bar(x1, y2 - 0.5f, x2, y2, color)
        bar(x1 - 0.5f, y1, x2, y1 + 0.5f, color)
        bar(x2 - 0.5f, y1, x2, y2, color)
    }

    /** The rectangle between (x1, y1) and (x2, y2). */
    private fun bar(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) = Canvas.quad(x1, y1, x2 - x1, y2 - y1, color)
}

/**
 * Batched glass plates (used by tags): [add] remembers a plate with the pose it was added under, [flush] draws all of
 * them (radius 3, border 0.75) in the order added.
 */
class GlassPlates {
    private class Plate(val pose: Matrix3x2f, val x: Float, val y: Float, val w: Float, val h: Float, val alpha: Float)

    private val plates = ArrayList<Plate>()

    fun add(x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        plates += Plate(Matrix3x2f(Canvas.pose), x, y, w, h, alpha)
    }

    fun flush(fill: Int, outline: Int) {
        val pose = Canvas.pose
        for (p in plates) {
            pose.pushMatrix()
            pose.set(p.pose)
            Canvas.glass(p.x, p.y, p.w, p.h, 3f, 1.5f, 0f, p.alpha, fill, outline)
            pose.popMatrix()
        }
        plates.clear()
    }
}

/** A component as a §-coded string, a colour code wherever the colour changes, ending in "§r". */
fun legacyText(c: Component): String {
    val sb = StringBuilder()
    var last = Int.MIN_VALUE
    c.visit({ style: Style, text: String ->
        if (text.isNotEmpty()) {
            val color = style.color?.let { it.value and 0xFFFFFF } ?: -1
            if (color != last) {
                if (color == -1) sb.append("§r") else sb.append(colorCode(color))
                last = color
            }
            sb.append(text)
        }
        Optional.empty<Unit>()
    }, Style.EMPTY)
    sb.append('§').append('r')
    return sb.toString()
}

/** The legacy code of a chat colour, or §x§R§R§G§G§B§B for any other. */
private fun colorCode(rgb: Int): String {
    val code = when (rgb) {
        0 -> '0'; 170 -> '1'; 43520 -> '2'; 43690 -> '3'; 5592405 -> '8'; 5592575 -> '9'; 5635925 -> 'a'; 5636095 -> 'b'
        11141120 -> '4'; 11141290 -> '5'; 11184810 -> '7'; 16733525 -> 'c'; 16733695 -> 'd'; 16755200 -> '6'
        16777045 -> 'e'; 16777215 -> 'f'
        else -> null
    }
    if (code != null) return "§$code"
    val hex = String.format(Locale.ROOT, "%06x", rgb and 0xFFFFFF)
    val sb = StringBuilder(14).append('§').append('x')
    for (ch in hex) sb.append('§').append(ch)
    return sb.toString()
}

/** World → screen helpers, with this frame's Render3D matrices. */
object Projection {
    /**
     * [pos] in GUI coordinates (x, y) and window depth z; behind the camera it is (NaN, NaN, −1). On screen
     * exactly when 0 < z < 1.
     */
    fun project(pos: Vec3): Vec3 {
        val cam = World3D.camera
        val eye = World3D.view.transform(Vector4f((pos.x - cam.x).toFloat(), (pos.y - cam.y).toFloat(), (pos.z - cam.z).toFloat(), 1f))
        val clip = World3D.projection.transform(Vector4f(eye))
        if (clip.w <= 0f) return Vec3(Double.NaN, Double.NaN, -1.0)
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        val winX = (clip.x / clip.w * 0.5f + 0.5f) * w
        val winY = (clip.y / clip.w * 0.5f + 0.5f) * h
        val winZ = (1f + clip.z / clip.w) * 0.5f
        val sc = window.guiScale.toFloat()
        return Vec3((winX / sc).toDouble(), ((h - winY) / sc).toDouble(), winZ.toDouble())
    }

    /** (x, y, max(x, z), 0) when [pos] is on screen, else null. */
    fun box(pos: Vec3): Vector4d? {
        val v = project(pos)
        return if (v.z > 0.0 && v.z < 1.0) Vector4d(v.x, v.y, maxOf(v.x, v.z), 0.0) else null
    }
}

/**
 * Player shapes: the player model, without its outer layer, as flat-coloured quads (fill) or quad
 * outlines, through walls. Used by player-esp's "default" type and logout-spots' DEFAULT mode.
 */
object PlayerShapes {
    private val mc get() = Minecraft.getInstance()

    /** Takes the model's vertex positions four at a time and emits a quad, or its four edges. */
    private class Collector(val out: VertexConsumer, val outline: Boolean, val color: Int) : VertexConsumer {
        private val xs = FloatArray(4)
        private val ys = FloatArray(4)
        private val zs = FloatArray(4)
        private var n = 0

        private fun put(i: Int) {
            out.addVertex(xs[i], ys[i], zs[i]).setColor(color)
        }

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            xs[n] = x
            ys[n] = y
            zs[n] = z
            n++
            if (n == 4) {
                if (outline) {
                    put(0); put(1); put(1); put(2); put(2); put(3); put(3); put(0)
                } else {
                    put(0); put(1); put(2); put(3)
                }
                n = 0
            }
            return this
        }

        override fun setColor(r: Int, g: Int, b: Int, a: Int) = this
        override fun setColor(argb: Int) = this
        override fun setUv(u: Float, v: Float) = this
        override fun setUv1(u: Int, v: Int) = this
        override fun setUv2(u: Int, v: Int) = this
        override fun setUv3(u: Float, v: Float) = this
        override fun setNormal(x: Float, y: Float, z: Float) = this
        override fun setLineWidth(w: Float) = this
    }

    /**
     * Every player in the level within [maxH] blocks horizontally and [maxV] vertically of the camera entity
     * (yourself only in third person), friends in the friend colours.
     */
    fun drawLevel(mode: RenderMode, fill: Int, line: Int, friendFill: Int, friendLine: Int, maxV: Int, maxH: Int) {
        val self = mc.player ?: return
        val cam = (mc.cameraEntity ?: self).position()
        val players = mc.level!!.entitiesForRendering().filterIsInstance<Player>().filter { e ->
            if (e == self && mc.options.cameraType == CameraType.FIRST_PERSON) return@filter false
            Math.hypot(cam.x - e.x, cam.z - e.z) < maxH && Math.abs(cam.y - e.y) < maxV
        }
        if (players.isEmpty()) return
        val friend = players.map { Friends.isFriend(it.gameProfile.name()) || Teams.isEspAlly(it) }
        draw(mode, players.mapIndexed { i, p -> p to if (friend[i]) friendFill else fill },
            players.mapIndexed { i, p -> p to if (friend[i]) friendLine else line }, World3D.partialTick)
    }

    /**
     * The given players at their last tick's position; yourself only in third person and within [maxH] /
     * [maxV] of the camera entity, everyone else always.
     */
    fun drawList(mode: RenderMode, fill: Int, line: Int, list: List<Player>, maxV: Int, maxH: Int) {
        val self = mc.player ?: return
        val cam = (mc.cameraEntity ?: self).position()
        val players = list.filter { p ->
            p != self || (mc.options.cameraType != CameraType.FIRST_PERSON && Math.hypot(cam.x - p.x, cam.z - p.z) < maxH &&
                Math.abs(cam.y - p.y) < maxV)
        }
        if (players.isEmpty()) return
        draw(mode, players.map { it to fill }, players.map { it to line }, 1.0f)
    }

    private fun draw(mode: RenderMode, fills: List<Pair<Player, Int>>, lines: List<Pair<Player, Int>>, t: Float) {
        val main = mc.gameRenderer.mainRenderTarget()
        if (mode == RenderMode.BOTH || mode == RenderMode.FILL) {
            World3D.draw(Pipelines.WORLD_FILL, main.colorTextureView!!, null, World3D.view) { vc ->
                for ((p, c) in fills) shape(p, Collector(vc, false, c), t)
            }
        }
        if (mode == RenderMode.BOTH || mode == RenderMode.OUTLINE) {
            World3D.draw(Pipelines.LINES, main.colorTextureView!!, null, World3D.view) { vc ->
                for ((p, c) in lines) shape(p, Collector(vc, true, c), t)
            }
        }
    }

    /** The model at the player's position interpolated by [t], vertices relative to the camera. */
    private fun shape(player: Player, vc: VertexConsumer, t: Float) {
        val p = player as? AbstractClientPlayer ?: return
        @Suppress("UNCHECKED_CAST")
        val renderer = mc.entityRenderDispatcher.getRenderer(p) as? AvatarRenderer<AbstractClientPlayer> ?: return
        val model: PlayerModel = renderer.model
        val rs = renderer.createRenderState(p, World3D.partialTick) as AvatarRenderState
        val cam = mc.entityRenderDispatcher.camera!!.position()
        val x = p.xo + (p.x - p.xo) * t - cam.x
        val y = p.yo + (p.y - p.yo) * t - cam.y
        val z = p.zo + (p.z - p.zo) * t - cam.z
        val main = mc.gameRenderer.mainCamera()
        // The view rotation and its undoing below cancel out: the vertices end up camera-relative, unrotated.
        val stack = PoseStack()
        stack.last().pose().set(World3D.view)
        stack.rotateDegrees(Axis.YP, -main.yRot() + 180.0f)
        stack.rotateDegrees(Axis.XP, -main.xRot())
        stack.translate(x.toFloat(), y.toFloat(), z.toFloat())
        stack.scale(rs.scale, rs.scale, rs.scale)
        setupRotations(rs, stack, rs.bodyRot)
        stack.scale(-1.0f, -1.0f, 1.0f)
        stack.scale(0.9375f, 0.9375f, 0.9375f)
        stack.translate(0.0f, if (p.isShiftKeyDown) -1.37f else -1.501f, 0.0f)
        model.setupAnim(rs)
        model.jacket.visible = false
        model.leftSleeve.visible = false
        model.rightSleeve.visible = false
        model.leftPants.visible = false
        model.rightPants.visible = false
        model.hat.visible = false
        model.renderToBuffer(stack, vc, 15728880, 0, -1)
    }

    /** Vanilla's model rotations without the death roll, sleeping, upside-down and freezing. */
    fun setupRotations(rs: AvatarRenderState, stack: PoseStack, bodyRot: Float) {
        if (rs.isFallFlying) {
            stack.rotateDegrees(Axis.YP, 180.0f - bodyRot)
            if (!rs.isAutoSpinAttack) stack.rotateDegrees(Axis.XP, rs.fallFlyingScale() * (-90.0f - rs.xRot))
            if (rs.shouldApplyFlyingYRot) stack.rotate(Axis.YP, rs.flyingYRot)
            return
        }
        if (rs.swimAmount > 0.0f) {
            stack.rotateDegrees(Axis.YP, 180.0f - bodyRot)
            stack.rotateDegrees(Axis.XP, Mth.lerp(rs.swimAmount, 0.0f, if (rs.isInWater) -90.0f - rs.xRot else -90.0f))
            if (rs.isVisuallySwimming) stack.translate(0.0f, -1.0f, 0.3f)
            return
        }
        stack.rotateDegrees(Axis.YP, 180.0f - bodyRot)
    }
}
