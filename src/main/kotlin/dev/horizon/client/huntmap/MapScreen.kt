package dev.horizon.client.huntmap

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Modules
import dev.horizon.client.module.esp.NewChunks
import dev.horizon.client.module.world.HuntMap
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Overlay
import dev.horizon.client.setting.BoolSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hunt Map's screen. Drag to move, scroll to zoom, right-click a spot for what to do there, Space to
 * follow yourself again; the chips at the top switch layers. Drawn bottom to top: the seed's parchment, the recorded
 * terrain, chunk states, your trail, worker borders, markers and you.
 */
class MapScreen(private val module: HuntMap) : Screen(Component.literal("Hunt map")) {
    private class Layer(val label: String, val setting: BoolSetting, val color: Int)

    private val groups = listOf(
        "Map" to listOf(
            Layer("Terrain", module.showTerrain, 0x9AA5B1),
            Layer("Seed map", module.showSeed, 0xE7D3A6),
        ),
        "Chunks" to listOf(
            Layer("New", module.showNew, 0xFF4B43),
            Layer("Old", module.showOld, 0x3BD16B),
            Layer("Changed", module.showChanged, 0xFFB020),
        ),
        "Show" to listOf(
            Layer("Trail", module.showTrail, 0x4AA3FF),
            Layer("Finds", module.showFinds, 0xF0B429),
            Layer("Waypoints", module.showWaypoints, 0x6EC1FF),
            Layer("Logouts", module.showLogouts, 0xC9A0FF),
            Layer("Workers", module.showWorkers, 0xE6E6E6),
        ),
    )

    private class Chip(val layer: Layer, val x: Float, val y: Float, val w: Float, val h: Float)

    private var chips = emptyList<Chip>()

    /** The top panel's bottom-right corner, so clicks there don't drag and the tooltip stays off it. */
    private var topW = 0f
    private var topH = 0f

    private class Item(val label: String, val run: () -> Unit)

    private class Menu(val x: Float, val y: Float, val w: Float, val title: String, val items: List<Item>) {
        fun itemAt(mx: Float, my: Float): Item? {
            if (mx < x || mx > x + w) return null
            val i = floor((my - y - HEADER) / ROW).toInt()
            return if (my >= y + HEADER && i in items.indices) items[i] else null
        }
    }

    private var menu: Menu? = null
    private var dragging = false
    private var marks = HuntMap.Marks(emptyList(), emptyList(), emptyList())
    private var marksAt = 0L
    private var hovered: MapData.Find? = null

    override fun isPauseScreen() = false

    // No menu blur: the map covers the screen.
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        Overlay.late { draw(mouseX.toFloat(), mouseY.toFloat()) }
    }

    /** Whether the map hid the vanilla HUD (as F1 does) because it would draw over the map; shown again on close. */
    private var hidHud = false

    override fun init() {
        super.init()
        val hud = Minecraft.getInstance().gui.hud
        if (!hud.isHidden) {
            hud.toggle()
            hidHud = true
        }
    }

    override fun removed() {
        val hud = Minecraft.getInstance().gui.hud
        if (hidHud && hud.isHidden) hud.toggle()
        hidHud = false
        RegionTextures.clear()
        super.removed()
    }

    // ---- coordinates ----

    private val zoom get() = module.view.zoom
    private fun sx(wx: Double) = (width / 2.0 + (wx - module.view.x) * zoom).toFloat()
    private fun sz(wz: Double) = (height / 2.0 + (wz - module.view.z) * zoom).toFloat()
    private fun wx(sx: Double) = module.view.x + (sx - width / 2.0) / zoom
    private fun wz(sy: Double) = module.view.z + (sy - height / 2.0) / zoom

    // ---- drawing ----

    private fun draw(mx: Float, my: Float) {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val lvl = mc.level ?: return
        MapStore.drain()
        MapStore.rebuildBudget = 3
        RegionTextures.frame()
        SeedTiles.upload(6)
        val view = module.view
        if (view.follow) {
            view.x = p.x
            view.z = p.z
        }
        val now = System.currentTimeMillis()
        if (now - marksAt > 3000) {
            marks = module.marks()
            marksAt = now
        }
        val w = width.toFloat()
        val h = height.toFloat()
        Canvas.quad(0f, 0f, w, h, BG)
        val gen = if (module.showSeed.value) module.gen() else null
        if (gen != null) drawSeed(gen)
        drawRegions()
        if (module.showTrail.value) drawTrail()
        if (module.showWorkers.value) drawWorkers()
        drawMarks(mx, my)
        drawPlayer(p)
        drawTop(lvl, mx, my)
        drawStatus(mx, lvl, my)
        if (menu == null && !dragging) drawTooltip(mx, my)
        menu?.let { drawMenu(it, mx, my) }
        SeedTiles.trim()
        RegionTextures.trim()
    }

    private fun drawSeed(gen: SeedTerrain.Gen) {
        val level = floor(log2(1.0 / zoom)).toInt().coerceIn(SeedTiles.MIN_LEVEL, SeedTiles.MAX_LEVEL)
        val span = SeedTerrain.TILE * SeedTerrain.scale(level)
        val tx0 = floor(wx(0.0) / span).toInt()
        val tx1 = floor(wx(width.toDouble()) / span).toInt()
        val tz0 = floor(wz(0.0) / span).toInt()
        val tz1 = floor(wz(height.toDouble()) / span).toInt()
        // The threads take the newest request first, so ask for the middle last.
        val cx = (tx0 + tx1) / 2.0
        val cz = (tz0 + tz1) / 2.0
        val order = ArrayList<IntArray>()
        for (tz in tz0..tz1) for (tx in tx0..tx1) order += intArrayOf(tx, tz)
        order.sortByDescending { hypot(it[0] - cx, it[1] - cz) }
        for ((tx, tz) in order) {
            val x = sx(tx * span)
            val y = sz(tz * span)
            val x2 = sx((tx + 1) * span)
            val y2 = sz((tz + 1) * span)
            SeedTiles.draw(gen, level, tx, tz) { t, u0, v0, u1, v1 ->
                Canvas.texture(t, x, y, x2 - x, y2 - y, -1, -1, -1, -1, u0, v0, u1, v1)
            }
        }
    }

    private fun drawRegions() {
        val rx0 = floor(wx(0.0) / 512).toInt()
        val rx1 = floor(wx(width.toDouble()) / 512).toInt()
        val rz0 = floor(wz(0.0) / 512).toInt()
        val rz1 = floor(wz(height.toDouble()) / 512).toInt()
        val detailed = 512 * zoom >= 160
        val nc = Modules.get<NewChunks>()
        val newC = Colors.withAlpha(nc.newColor.value.rgb, 0.26f)
        val oldC = Colors.withAlpha(nc.oldColor.value.rgb, 0.3f)
        val showNew = module.showNew.value
        val showOld = module.showOld.value
        val showChanged = module.showChanged.value
        val style = (if (showNew) 1 else 0) or (if (showOld) 2 else 0) or (if (showChanged) 4 else 0) or (newC xor oldC * 31 shl 3)
        for (rz in rz0..rz1) for (rx in rx0..rx1) {
            val k = MapRegion.key(rx, rz)
            if (k !in MapStore.known) continue
            val x = sx(rx * 512.0)
            val y = sz(rz * 512.0)
            val rw = sx((rx + 1) * 512.0) - x
            val rh = sz((rz + 1) * 512.0) - y
            val s = MapStore.summaryFor(rx, rz)
            if (module.showTerrain.value) {
                var drew = false
                if (detailed) {
                    val r = MapStore.peek(rx, rz)
                    val t = if (r != null) RegionTextures.full(r) else null
                    if (t != null) {
                        Canvas.texture(t, x, y, rw, rh, -1, -1, -1, -1)
                        drew = true
                    }
                }
                if (!drew && s != null) Canvas.texture(RegionTextures.overview(k, s), x, y, rw, rh, -1, -1, -1, -1)
            }
            if (s != null && (showNew || showOld || showChanged)) {
                val t = RegionTextures.overlay(k, s, style) { state, diff ->
                    when {
                        showChanged && diff == MapRegion.CHANGED -> CHANGED
                        showNew && state == MapRegion.NEW -> newC
                        showOld && state == MapRegion.OLD -> oldC
                        else -> 0
                    }
                }
                Canvas.texture(t, x, y, rw, rh, -1, -1, -1, -1)
            }
        }
    }

    private fun drawTrail() {
        var lx = 0f
        var ly = 0f
        var have = false
        for (pt in marks.trail) {
            if (pt == null) {
                have = false
                continue
            }
            val x = sx(pt.x)
            val y = sz(pt.z)
            if (!have) {
                lx = x
                ly = y
                have = true
                continue
            }
            if (abs(x - lx) + abs(y - ly) < 1.5f) continue
            if (onScreen(x, y) || onScreen(lx, ly)) Canvas.line(lx, ly, x, y, TRAIL)
            lx = x
            ly = y
        }
    }

    private fun drawWorkers() {
        val groups = marks.crossings.groupBy { listOf(it.from, it.to).sorted().joinToString(" | ") }
        val font = Fonts.medium75
        for ((label, cs) in groups) {
            val alongX = cs.maxOf { it.x } - cs.minOf { it.x } >= cs.maxOf { it.z } - cs.minOf { it.z }
            val pts = if (alongX) cs.sortedBy { it.x } else cs.sortedBy { it.z }
            for (i in 0 until pts.size - 1) {
                dashed(sx(pts[i].x.toDouble()), sz(pts[i].z.toDouble()), sx(pts[i + 1].x.toDouble()), sz(pts[i + 1].z.toDouble()))
            }
            for ((i, c) in pts.withIndex()) {
                val x = sx(c.x.toDouble())
                val y = sz(c.z.toDouble())
                if (!onScreen(x, y)) continue
                Canvas.rect(x - 2.5f, y - 2.5f, 5f, 5f, 2.5f, WORKER, 1f, BG)
                if (i == 0 || zoom >= 0.25) font.draw(label, x + 6f, y - 4f, WORKER)
            }
        }
    }

    private fun dashed(x1: Float, y1: Float, x2: Float, y2: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1f || !onScreen(x1, y1) && !onScreen(x2, y2) && len < width) return
        val dx = (x2 - x1) / len
        val dy = (y2 - y1) / len
        var t = 0f
        while (t < len) {
            val e = minOf(t + 6f, len)
            Canvas.line(x1 + dx * t, y1 + dy * t, x1 + dx * e, y1 + dy * e, Colors.withAlpha(WORKER, 0.7f))
            t += 10f
        }
    }

    private fun shown(f: MapData.Find) = when (f.kind) {
        "waypoint" -> module.showWaypoints.value
        "logout" -> module.showLogouts.value
        else -> module.showFinds.value
    }

    private fun colorOf(kind: String) = 0xFF000000.toInt() or when (kind) {
        "base" -> 0xF0B429
        "stash" -> 0xE85D9A
        "portal" -> 0xB98CFF
        "island" -> 0xFFD700
        "waypoint" -> 0x6EC1FF
        "logout" -> 0xC9A0FF
        else -> 0x3FC1C9
    }

    /** The map's own finds are labelled by their kind ("portal"): capitalised for the map. */
    private fun labelOf(f: MapData.Find) = if (f.label == f.kind) f.label.replaceFirstChar { it.uppercase() } else f.label

    private fun drawMarks(mx: Float, my: Float) {
        hovered = null
        var best = 7f
        val font = Fonts.medium75
        for (f in marks.finds) {
            if (!shown(f)) continue
            val x = sx(f.x + 0.5)
            val y = sz(f.z + 0.5)
            if (!onScreen(x, y)) continue
            val d = hypot(x - mx, y - my)
            if (d < best) {
                best = d
                hovered = f
            }
        }
        for (f in marks.finds) {
            if (!shown(f)) continue
            val x = sx(f.x + 0.5)
            val y = sz(f.z + 0.5)
            if (!onScreen(x, y)) continue
            val c = colorOf(f.kind)
            when (f.kind) {
                "logout" -> {
                    Canvas.line(x - 3.5f, y - 3.5f, x + 3.5f, y + 3.5f, c)
                    Canvas.line(x + 3.5f, y - 3.5f, x - 3.5f, y + 3.5f, c)
                }
                "waypoint" -> Canvas.rect(x - 3f, y - 3f, 6f, 6f, 1.5f, c, 1f, BG)
                else -> Canvas.rect(x - 3.5f, y - 3.5f, 7f, 7f, 3.5f, c, 1f, BG)
            }
            val labelled = f === hovered || when (f.kind) {
                "base", "stash", "waypoint", "portal", "island" -> zoom >= 0.125
                else -> zoom >= 1.0
            }
            if (labelled) font.draw(labelOf(f).take(28), x + 6f, y - 4f, c)
        }
    }

    private fun drawPlayer(p: Player) {
        val x = sx(p.x)
        val y = sz(p.z)
        val yaw = Math.toRadians(p.yRot.toDouble())
        Canvas.line(x, y, x - sin(yaw).toFloat() * 13f, y + cos(yaw).toFloat() * 13f, -1)
        Canvas.rect(x - 4f, y - 4f, 8f, 8f, 4f, -1, 1.5f, BG)
    }

    private fun chipWidth(l: Layer) = Fonts.semibold8.width(l.label) + 20f

    /**
     * The title and the layer chips in their groups, each chip a coloured dot (filled when on, a ring when off) that
     * doubles as the map's key. A group that doesn't fit on the row moves to the next.
     */
    private fun drawTop(lvl: ClientLevel, mx: Float, my: Float) {
        val title = Fonts.bold12
        val small = Fonts.semibold8
        val label = Fonts.semibold65
        val mc = Minecraft.getInstance()
        val server = mc.currentServer?.ip ?: mc.singleplayerServer?.worldData?.levelName ?: "this world"
        val sub = "$server · ${dimName(lvl)}"
        val starts = ArrayList<Pair<Float, Int>>()
        var x = PAD
        var row = 0
        var right = PAD + title.width("Hunt map") + 6f + small.width(sub)
        for ((name, ls) in groups) {
            val w = label.width(name) + 4f + ls.sumOf { chipWidth(it).toDouble() + 2.0 }.toFloat()
            if (x > PAD) {
                if (x + SEP + w > width - PAD) {
                    row++
                    x = PAD
                } else x += SEP
            }
            starts += x to row
            x += w
            right = maxOf(right, x)
        }
        topW = right + 8.5f
        topH = ROW_TOP + row * 18f + 14f + 7.5f
        Canvas.box(8f, 8f, topW - 8f, topH - 8f, 7f, PANEL, 0.5f, BORDER)
        title.drawBaseline("Hunt map", PAD, 26.5f, TEXT)
        small.drawBaseline(sub, PAD + title.width("Hunt map") + 6f, 26.5f, MUTED)
        val out = ArrayList<Chip>()
        for ((group, start) in groups.zip(starts)) {
            val (name, ls) = group
            var cx = start.first
            val y = ROW_TOP + start.second * 18f
            if (cx > PAD) Canvas.quad(cx - 7.5f, y + 2f, 0.5f, 10f, SEP_LINE)
            // Half a unit below the row's middle, as the mockup's text sits.
            label.drawMiddle(name, cx, y + 7.5f, FAINT)
            cx += label.width(name) + 4f
            for (l in ls) {
                val w = chipWidth(l)
                val on = l.setting.value
                val over = menu == null && !dragging && mx >= cx && mx <= cx + w && my >= y && my <= y + 14f
                if (on || over) Canvas.box(cx, y, w, 14f, 4f, if (over) CHIP_HOVER else CHIP_ON)
                val c = 0xFF000000.toInt() or l.color
                if (on) Canvas.box(cx + 5f, y + 4.5f, 5f, 5f, 2.5f, c)
                else Canvas.box(cx + 5f, y + 4.5f, 5f, 5f, 2.5f, 0, 0.5f, Colors.withAlpha(c, 0.75f))
                small.drawMiddle(l.label, cx + 14f, y + 7.5f, if (on) TEXT else OFF)
                out += Chip(l, cx, y, w, 14f)
                cx += w + 2f
            }
        }
        chips = out
    }

    private fun overTop(x: Float, y: Float) = x >= 8f && x <= topW && y >= 8f && y <= topH

    private fun dimName(lvl: ClientLevel) = when (lvl.dimension()) {
        Level.NETHER -> "nether"
        Level.END -> "end"
        Level.OVERWORLD -> "overworld"
        else -> lvl.dimension().identifier().path
    }

    private fun drawStatus(mx: Float, lvl: ClientLevel, my: Float) {
        val font = Fonts.medium75
        val bx = floor(wx(mx.toDouble())).toInt()
        val bz = floor(wz(my.toDouble())).toInt()
        val other = when (lvl.dimension()) {
            Level.OVERWORLD -> "   nether X ${Math.floorDiv(bx, 8)} Z ${Math.floorDiv(bz, 8)}"
            Level.NETHER -> "   overworld X ${bx * 8} Z ${bz * 8}"
            else -> ""
        }
        val lines = ArrayList<String>()
        lines += "X $bx  Z $bz$other"
        lines += seedStatus(lvl)
        module.worker?.let { lines += "6b6t: $it" }
        val lh = 10f
        val boxW = lines.maxOf { font.width(it) } + 17f
        val boxH = lines.size * lh + 8f
        val top = height - boxH - 8f
        Canvas.box(8f, top, boxW, boxH, 7f, PANEL, 0.5f, BORDER)
        for ((i, s) in lines.withIndex()) font.drawMiddle(s, PAD, top + 9f + i * lh, if (i == 0) TEXT else MUTED)
        // Bottom right, in a panel level with the status box's last line: the hints (if they fit beside the status
        // box), then the scale bar and its length.
        var blocks = 1
        for (b in intArrayOf(1, 2, 5, 10, 16, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)) {
            blocks = b
            if (b * zoom >= 60) break
        }
        val len = (blocks * zoom).toFloat()
        val label = if (blocks >= 1000) "${blocks / 1000}k blocks" else "$blocks blocks"
        val hint = "Drag to move · scroll to zoom · right-click for options · Space: back to you"
        val scaleW = len + 5f + font.width(label)
        val withHint = 8.5f + font.width(hint) + 12f + scaleW + 8.5f
        val panelW = if (width - 8f - withHint > 8f + boxW + 12f) withHint else 8.5f + scaleW + 8.5f
        val px = width - 8f - panelW
        val cy = height - 17f
        Canvas.box(px, height - 26f, panelW, 18f, 7f, PANEL, 0.5f, BORDER)
        if (panelW == withHint) font.drawMiddle(hint, px + 8.5f, cy, MUTED)
        val bx2 = width - 16.5f - font.width(label) - 5f
        Canvas.line(bx2 - len, cy, bx2, cy, TEXT)
        Canvas.line(bx2 - len, cy - 2.5f, bx2 - len, cy + 2.5f, TEXT)
        Canvas.line(bx2, cy - 2.5f, bx2, cy + 2.5f, TEXT)
        font.drawMiddle(label, bx2 + 5f, cy, TEXT)
    }

    private fun seedStatus(lvl: ClientLevel): String {
        if (!module.predict.value) return "Seed map: off (Predict setting)"
        if (lvl.dimension() == Level.END) return "Seed map: not in the End"
        val seed = module.seed() ?: return "Seed map: no seed for this server (add it to the Seeds setting)"
        module.gen() ?: return SeedTerrain.failure?.let { "Seed map failed: $it" } ?: "Seed map: setting up seed $seed..."
        val n = module.columnsChecked
        if (n < 64) return "Seed $seed: checking it against the ground you load"
        val pct = 100 * module.columnsMatched / n
        // Untouched ground matches in about 70-90% of columns with the right seed and 35% with a wrong one; the
        // nether floor about 60% against 10%.
        val nether = lvl.dimension() == Level.NETHER
        val verdict = when {
            pct >= (if (nether) 40 else 55) -> "matches"
            pct >= (if (nether) 25 else 45) -> "partly matches"
            else -> "doesn't match (wrong seed, or old terrain)"
        }
        return "Seed $seed $verdict: $pct% of ${"%,d".format(n)} checked columns"
    }

    private fun drawTooltip(mx: Float, my: Float) {
        val lines = ArrayList<String>()
        val f = hovered
        if (f != null) {
            lines += labelOf(f)
            if (f.detail.isNotBlank()) lines += f.detail.take(70)
            lines += "X ${f.x} Y ${f.y} Z ${f.z}"
        } else {
            if (overTop(mx, my)) return
            val bx = floor(wx(mx.toDouble())).toInt()
            val bz = floor(wz(my.toDouble())).toInt()
            val cx = bx shr 4
            val cz = bz shr 4
            val s = MapStore.summaryFor(cx shr 5, cz shr 5) ?: return
            val i = (cz and 31) * 32 + (cx and 31)
            val state = when (s.states[i].toInt()) {
                MapRegion.NEW -> "new (generated when you got there)"
                MapRegion.OLD -> "old (someone loaded it before you)"
                MapRegion.SEEN -> "loaded"
                else -> return
            }
            lines += "Chunk $cx, $cz: $state"
            when (s.diff[i].toInt()) {
                MapRegion.CHANGED -> lines += "Ground differs from the seed: dug, built, a structure, or older terrain"
                MapRegion.MATCH -> lines += "Ground matches the seed"
            }
        }
        val font = Fonts.medium75
        val w = lines.maxOf { font.width(it) } + 12f
        val h = lines.size * 10f + 6f
        val x = minOf(mx + 12f, width - w - 4f)
        val y = minOf(my + 12f, height - h - 4f)
        Canvas.box(x, y, w, h, 5f, PANEL, 0.5f, POPUP_BORDER)
        for ((i, s) in lines.withIndex()) font.drawMiddle(s, x + 6f, y + 8f + i * 10f, if (i == 0) TEXT else MUTED)
    }

    private fun drawMenu(m: Menu, mx: Float, my: Float) {
        val font = Fonts.medium8
        val h = HEADER + m.items.size * ROW + 4f
        Canvas.box(m.x, m.y, m.w, h, 6f, MENU, 0.5f, POPUP_BORDER)
        font.drawMiddle(m.title, m.x + 8f, m.y + 8f, MUTED)
        val over = m.itemAt(mx, my)
        for ((i, item) in m.items.withIndex()) {
            val y = m.y + HEADER + i * ROW
            if (item === over) Canvas.box(m.x + 2f, y, m.w - 4f, ROW, 4f, CHIP_HOVER)
            font.drawMiddle(item.label, m.x + 8f, y + ROW / 2f + 0.5f, TEXT)
        }
    }

    private fun onScreen(x: Float, y: Float) = x >= -20 && y >= -20 && x <= width + 20 && y <= height + 20

    // ---- input ----

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val x = event.x().toFloat()
        val y = event.y().toFloat()
        val b = event.button()
        val m = menu
        if (m != null) {
            menu = null
            if (b == Keys.MOUSE_LEFT) m.itemAt(x, y)?.run?.invoke()
            return true
        }
        if (b == Keys.MOUSE_LEFT) {
            val chip = chips.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
            if (chip != null) {
                chip.layer.setting.value = !chip.layer.setting.value
                return true
            }
            if (overTop(x, y)) return true
            dragging = true
            return true
        }
        if (b == Keys.MOUSE_RIGHT) {
            menu = menuAt(x, y)
            return true
        }
        return super.mouseClicked(event, doubled)
    }

    private fun menuAt(x: Float, y: Float): Menu {
        val bx = floor(wx(x.toDouble())).toInt()
        val bz = floor(wz(y.toDouble())).toInt()
        val nether = Minecraft.getInstance().level?.dimension() == Level.NETHER
        val items = listOf(
            Item(if (nether) "Fly here with Baritone" else "Fly here with Elytra Autopilot") {
                module.flyTo(bx, bz)
                onClose()
            },
            Item("Walk here with Baritone") {
                module.walkTo(bx, bz)
                onClose()
            },
            Item("Add waypoint") { module.addWaypoint(bx, bz) },
            Item("Copy coordinates") { module.copy(bx, bz) },
        )
        val font = Fonts.medium8
        val title = "X $bx  Z $bz"
        val w = maxOf(items.maxOf { font.width(it.label) }, font.width(title)) + 16f
        val h = HEADER + items.size * ROW + 4f
        return Menu(minOf(x, width - w - 4f), minOf(y, height - h - 4f), w, title, items)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = false
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (!dragging) return super.mouseDragged(event, dx, dy)
        val v = module.view
        v.x -= dx / zoom
        v.z -= dy / zoom
        v.follow = false
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val v = module.view
        val nz = (v.zoom * 1.25.pow(scrollY)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (!v.follow) {
            // Keep the spot under the cursor where it is.
            val bx = wx(x)
            val bz = wz(y)
            v.x = bx - (x - width / 2.0) / nz
            v.z = bz - (y - height / 2.0) / nz
        }
        v.zoom = nz
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        if (key == InputConstants.KEY_SPACE) {
            module.view.follow = true
            return true
        }
        val open = module.openKey.value
        if (!open.mouse && open.key != -1 && key == open.key) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    private companion object {
        const val HEADER = 15f
        const val ROW = 14f
        const val MIN_ZOOM = 1.0 / 32
        const val MAX_ZOOM = 8.0
        // The top panel: content inset (8 padding inside the 0.5 border), the chip row's top, the gap between groups.
        const val PAD = 16.5f
        const val ROW_TOP = 34.5f
        const val SEP = 14.5f
        val BG = 0xFF141317.toInt()
        val PANEL = 0xE6141317.toInt()
        val BORDER = 0x12FFFFFF
        val CHIP_ON = 0x14FFFFFF
        val CHIP_HOVER = 0x24FFFFFF
        val SEP_LINE = 0x17FFFFFF
        // The tooltip and right-click menu: a firmer edge, as they sit over anything.
        val MENU = 0xF2141317.toInt()
        val POPUP_BORDER = 0x30FFFFFF
        val TEXT = 0xFFEDEDED.toInt()
        val MUTED = 0xFF9A9AA2.toInt()
        val FAINT = 0xFF6E6E78.toInt()
        val OFF = 0xFF85858E.toInt()
        val TRAIL = 0xFF4AA3FF.toInt()
        val WORKER = 0xFFE6E6E6.toInt()
        val CHANGED = 0x8CFFB020.toInt()
    }
}
