package dev.horizon.client.module.esp

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.vertex.PoseStack
import dev.horizon.client.combat.Frames
import dev.horizon.client.combat.GlassPlates
import dev.horizon.client.combat.Projection
import dev.horizon.client.combat.legacyText
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Chams
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.World3D
import net.minecraft.core.component.DataComponents
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.awt.Color
import dev.horizon.client.module.ShaderMode as ChamsMode

/**
 * A tag over every dropped item (or, with [merge], over each cluster of items within 3
 * blocks: "name xcount" lines), fading in over the item's first 15 ticks, with a shulker's contents beside it; plus the
 * [highlight] (boxes, outlines or 2D frames in [highlightColor]). [filter] picks which items count.
 */
class ItemEsp : Module("item-esp", Categories.ESP) {
    enum class Highlight { BOX, OUTLINE, FRAME, SHADER, OFF }
    enum class ShaderMode { SELECT, DEFAULT }
    enum class Filter { NONE, WHITE_LIST, BLACK_LIST }

    val highlight = enum("highlight", Highlight.OFF)
    val shaderMode = enum("shader-mode", ShaderMode.DEFAULT) { highlight.value == Highlight.SHADER }
    val merge = bool("merge", true)
    val onePlate = bool("one-plate", true) { merge.value }
    val shulkers = bool("shulkers", true)
    val fill = color("fill", Color(0xFF969696.toInt(), true))
    val outline = color("outline", Color(0xFFFFFFFF.toInt(), true))
    val highlightColor = color("highlight-color", Color(0xFFFFFFFF.toInt(), true))
    val lineWidth = float("line-width", 4.0f, 1.0f..4.0f) { highlight.value == Highlight.SHADER || shaderMode.value == ShaderMode.DEFAULT }
    val filter = enum("filter", Filter.NONE)
    val itemsSetting = items("items") { filter.value != Filter.NONE }

    private val plates = GlassPlates()

    /** The items' mask for the SHADER highlight (drawn by [maskItems], put on the screen in Render2D). */
    private var mask: TextureTarget? = null

    /** Items tagged together. */
    private data class Cluster(val items: MutableList<ItemEntity>) {
        /** The items' average interpolated position, 0.7 up. */
        fun pos(): Vec3 {
            if (items.isEmpty()) return Vec3.ZERO
            val pt = World3D.partialTick.toDouble()
            var x = 0.0
            var y = 0.0
            var z = 0.0
            for (i in items) {
                val p = i.position()
                x += Mth.lerp(pt, i.xo, p.x)
                y += Mth.lerp(pt, i.yo, p.y)
                z += Mth.lerp(pt, i.zo, p.z)
            }
            return Vec3(x / items.size, y / items.size + 0.7, z / items.size)
        }

        /** "name xcount" per item name (with its colours), in first-seen order. */
        fun lines(): List<String> {
            val counts = LinkedHashMap<String, Int>()
            for (i in items) {
                val key = legacyText(i.item.hoverName)
                counts[key] = (counts[key] ?: 0) + i.item.count
            }
            return counts.map { (k, v) -> "$k x$v" }
        }
    }

    init {
        on<Render3DEvent> { e ->
            if (highlight.value == Highlight.SHADER) {
                mask = Chams.ensure(mask, "horizon item esp")
                maskItems(e.view)
            }
            if (highlight.value == Highlight.OFF || highlight.value == Highlight.SHADER) return@on
            for (item in items()) {
                val box = Frames.renderBox(item)
                val c = highlightColor.value.rgb
                when (highlight.value) {
                    Highlight.BOX -> {
                        World3D.fill(box, c)
                        World3D.outline(box, Colors.withAlpha(c, 1.0f))
                    }
                    Highlight.OUTLINE -> World3D.outline(box, Colors.withAlpha(c, 1.0f))
                    else -> return@on
                }
            }
        }
        on<Render2DEvent> {
            val player = mc.player!!
            for (cluster in clusters().sortedWith { a, b -> player.distanceToSqr(b.pos()).compareTo(player.distanceToSqr(a.pos())) }) {
                val alpha = minOf(cluster.items.first().tickCount / 15.0f, 1.0f)
                val box = Projection.box(cluster.pos())
                val lines = cluster.lines()
                if (box == null) continue
                var maxW = 0.0f
                for (l in lines) {
                    val w = Fonts.bold8.width(l)
                    if (w - maxW > 0) maxW = w
                }
                val x = (box.x + (box.z - box.x).toFloat() / 2.0f - maxW / 2).toFloat()
                if (onePlate.value) plates.add(x - 3.0f, (box.y - 1.0f - 12.0f * lines.size).toFloat(), maxW + 5.0f, 12.0f * lines.size, alpha)
                for ((i, l) in lines.withIndex()) {
                    val w = Fonts.bold8.width(l)
                    if (!onePlate.value) {
                        plates.add(x - 3.0f + maxW / 2.0f - w / 2.0f, (box.y - 13.0f - 12.0f * i).toFloat(), w + 5.0f, 12.0f, alpha)
                    }
                    Fonts.bold8.queue(l, x - 1.0f + maxW / 2.0f - w / 2.0f, box.y.toFloat() - 13.0f - 12.0f * i,
                        Colors.withAlpha(Color.WHITE.rgb, alpha))
                }
                if (!shulkers.value) continue
                for (item in cluster.items) {
                    val it = item.item.item
                    if (it !is BlockItem || it.block !is ShulkerBoxBlock) continue
                    val pose = Canvas.pose
                    pose.pushMatrix()
                    pose.translate(box.x.toFloat() - 58.2f, box.y.toFloat() - 4.0f)
                    pose.scale(0.6f)
                    shulker(item.item)
                    pose.popMatrix()
                }
            }
            plates.flush(fill.value.rgb, outline.value.rgb)
            Fonts.bold8.flush()
            if (highlight.value == Highlight.FRAME) {
                for (item in items()) {
                    val r = Frames.screenBox(item) ?: continue
                    Frames.draw(r[0], r[1], r[2], r[3], highlightColor.value.rgb)
                }
            }
            val m = mask
            if (highlight.value == Highlight.SHADER && m != null) {
                // The mask as its own scratch target; SELECT puts nothing on the screen (on purpose).
                Chams.composite(if (shaderMode.value == ShaderMode.DEFAULT) ChamsMode.DEFAULT else null, highlightColor.value.rgb, m, m,
                    false, false, 0.0f, 0.0f, 0.0f, lineWidth.value, 0, Color.WHITE.rgb, Color.WHITE.rgb, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            }
        }
    }

    /** The [items]' real models drawn into [mask] (shadows off) at their interpolated positions, through walls. */
    private fun maskItems(view: Matrix4f) {
        val target = mask ?: return
        val cam = mc.gameRenderer.mainCamera().position()
        val pt = World3D.partialTick
        val shadows = mc.options.entityShadows().get()
        mc.options.entityShadows().set(false)
        val state = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        val dispatcher = mc.entityRenderDispatcher
        val stack = PoseStack()
        stack.last().pose().set(view)
        Chams.renderInto(target) { storage ->
            for (item in items()) {
                if (!dispatcher.shouldRender(item, state.cullFrustum, cam.x, cam.y, cam.z, pt)) continue
                val rs = dispatcher.getRenderer(item).createRenderState(item, pt)
                dispatcher.submit(rs, state, Mth.lerp(pt.toDouble(), item.xOld, item.x) - cam.x, Mth.lerp(pt.toDouble(), item.yOld, item.y) - cam.y,
                    Mth.lerp(pt.toDouble(), item.zOld, item.z) - cam.z, stack, storage)
            }
        }
        mc.options.entityShadows().set(shadows)
    }

    /** The dropped items that pass the [filter]. */
    private fun items(): List<ItemEntity> = mc.level!!.entitiesForRendering().filterIsInstance<ItemEntity>().filter { e ->
        when (filter.value) {
            Filter.NONE -> true
            Filter.WHITE_LIST -> e.item.item in itemsSetting.value
            Filter.BLACK_LIST -> e.item.item !in itemsSetting.value
        }
    }

    /**
     * For each item not yet taken, a cluster of it and the untaken items closer than 3 blocks (none without
     * [merge]); the first item itself isn't marked taken, so it can end up in a later cluster too.
     */
    private fun clusters(): List<Cluster> {
        val out = ArrayList<Cluster>()
        val items = items()
        val taken = ArrayList<ItemEntity>()
        for (a in items) {
            if (taken.contains(a)) continue
            val c = Cluster(arrayListOf(a))
            for (b in items) {
                if (a == b) continue
                if (a.distanceToSqr(b) - (if (merge.value) 9.0 else 0.0) >= 0) continue
                if (taken.contains(b)) continue
                c.items += b
                taken += b
            }
            out += c
        }
        return out.distinct()
    }

    /** A shulker box's contents on a plate, nine to a row. */
    private fun shulker(stack: ItemStack) {
        val contents = stack.get(DataComponents.CONTAINER) ?: return
        val item = stack.item
        if (item !is BlockItem || item.block !is ShulkerBoxBlock) return
        val list = contents.itemCopies().toList()
        if (list.isEmpty()) return
        plates.add(12.0f, -79.0f, 169.0f, 60.0f, 2.0f)
        val g = Canvas.graphics ?: return
        var row = 0
        var col = 0
        for (s in list) {
            g.item(s, 16 + col * 18, -75 + row * 18)
            g.itemDecorations(mc.font, s, 16 + col * 18, -75 + row * 18)
            col++
            if (col >= 9) {
                col = 0
                row++
            }
        }
    }
}
