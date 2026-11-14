package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.lunar.LockerFilter
import dev.horizon.client.lunar.LunarAssets
import dev.horizon.client.lunar.LunarCosmetics
import dev.horizon.client.lunar.LunarEmotes
import dev.horizon.client.lunar.LunarLocker
import dev.horizon.client.lunar.LunarSettings
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A cosmetics locker like Lunar Client's own, in the new GUI's style. Your player turns in a stage
 * on the left wearing what you've picked; tabs of Lunar's cosmetics (cloaks, hats, face, wings, back, body, pets,
 * auras) and emotes fill the right as a searchable grid, with a filter row over it: everything, what your Lunar account
 * owns or what you've picked; newest first or by name; and a Filters panel of Lunar's colour and style tags
 * ([LockerFilter]). Click a cosmetic to wear it (one of each kind, as in Lunar),
 * again to take it off; hovering one tries it on. A tile that is yours says so over the foot of its picture: **Owned**
 * by your Lunar account (**On Lunar** once it's on your Lunar outfit). The rest are client-side, which only you see
 * (the status bar says so, once). Owned picks also go on your Lunar outfit
 * ([dev.horizon.client.lunar.LunarAccount], with Lunar Spoof's `locker-account`); client-side ones never leave this
 * client. Emotes play on your own player, client-side; an emote's tile shows you in its pose, and plays it while the
 * mouse is on it ([CosmeticThumbs.drawEmote]).
 */
object LunarLockerScreen : Screen(Component.literal("horizon-lunar-locker")) {
    const val W = 560f
    const val H = 330f
    private const val BAR = 29f
    private const val STATUS = 15f
    private const val STAGE = 168f
    private const val TILE_W = 64f
    private const val TILE_H = 66f
    private const val GAP = 6f

    /** The stage's floor line, where the player stands, and the player's size (units to a block). */
    private const val FLOOR = H - STATUS - 63f
    private const val PLAYER = 92f

    /** The window's background, for fading the ends of the grid ([Ui.fade]). */
    private val backdrop = Ui.Backdrop({ Ui.ambient(-90f, -50f) }, { y -> Ui.vboxAt(0f, H, 0xEB18151E.toInt(), 0xF20B0A0F.toInt(), y) })

    private val mc get() = Minecraft.getInstance()
    private var mouseX = 0f
    private var mouseY = 0f
    private var ox = 0f
    private var oy = 0f
    private var k = 1f
    private var closing = false
    private var tab = 0
    private var scroll = 0f
    private var scrollMax = 0f
    private val search = Ui.Field(max = 40) { scroll = 0f }

    /** The filter row's choices (kept while the game runs), and whether its Filters panel is open. */
    private var filter = LockerFilter.Filter()
    private var panel = false
    private const val ROW = 21f
    private val emotesTab get() = tab == LunarLocker.tabs.size
    private val outfitsTab get() = tab == LunarLocker.tabs.size + 1
    private val badgesTab get() = tab == LunarLocker.tabs.size + 2

    /** The player on the stage: turned [spin] degrees and tilted [tilt] by dragging, still turning at [spinV] after a flick. */
    private var spin = 0f
    private var tilt = 0f
    private var spinV = 0f
    private var turning = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastFrame = 0L

    /** The tile under the mouse and since when (it turns). */
    private var hoverTile = -1
    private var hoverSince = 0L

    /** The outfit picked in the Outfits tab (for rename, delete and conditions). */
    private var pickedOutfit: java.util.UUID? = null

    fun open() = mc.gui.setScreen(this)

    /** For ShotBot: opens the locker on the tab labelled [label]. */
    @JvmStatic
    fun testTab(label: String) {
        tab = labels.indexOfFirst { it.equals(label, true) }.coerceAtLeast(0)
        scroll = 0f
        open()
    }

    override fun isPauseScreen() = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        this.mouseX = mouseX.toFloat()
        this.mouseY = mouseY.toFloat()
    }

    override fun added() {
        super.added()
        mc.textInputManager().startTextInput(this)
        closing = false
        Ui.animFrom("locker", 450, 0f)
        LunarAssets.ensure()
    }

    override fun removed() {
        super.removed()
        mc.textInputManager().stopTextInput(this)
        Ui.focused = null
        LunarLocker.preview = null
    }

    override fun onClose() {
        if (closing) return
        closing = true
        Ui.focused = null
        LunarLocker.preview = null
        Ui.animFrom("locker", 160, 1f, Easing.IN_QUAD)
    }

    /** One frame (from Overlay's screen layer); [graphics] takes the player, drawn over the window. */
    fun draw(graphics: GuiGraphicsExtractor) {
        val sw = width.toFloat()
        val sh = height.toFloat()
        k = min(1f, min((sw - 16f) / W, (sh - 16f) / H))
        ox = (sw - W * k) / 2f
        oy = (sh - H * k) / 2f
        val t = if (closing) Ui.anim("locker", 0f, 160, Easing.IN_QUAD) else Ui.anim("locker", 1f, 450)
        if (closing && t <= 0.02f) {
            closing = false
            super.onClose()
            return
        }
        Ui.mx = (mouseX - ox) / k
        Ui.my = (mouseY - oy) / k
        if (Ui.isDragging) Ui.drag()
        Ui.beginFrame()
        Canvas.alpha = t
        Ui.blur(0f, 0f, sw, sh, 0f)
        Ui.box(0f, 0f, sw, sh, 0f, 0x61000000)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(ox, oy)
        pose.scale(k, k)
        window()
        pose.popMatrix()
        Canvas.alpha = 1f
        // The player on the stage, over the window: vanilla's inventory figure, turned by dragging.
        val p = mc.player ?: return
        if (t < 0.5f) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        if (!turning && spinV != 0f) {
            spin += spinV * dt * 60f
            spinV *= kotlin.math.exp(-dt * 5f)
            if (kotlin.math.abs(spinV) < 0.05f) spinV = 0f
        }
        spin %= 360f
        // Its feet on the stage's floor line: vanilla draws the figure about the box's middle, half its height (and a
        // sixteenth of a block) above its feet. The box reaches as far up as down, across the whole stage.
        val middle = FLOOR - (p.bbHeight / p.scale / 2f + 0.0625f) * PLAYER
        val reach = H - STATUS - 40f - middle
        val x0 = (ox + 2f * k).toInt()
        val y0 = (oy + (middle - reach) * k).toInt()
        val x1 = (ox + (STAGE - 2f) * k).toInt()
        val y1 = (oy + (middle + reach) * k).toInt()
        player(graphics, p, x0, y0, x1, y1, (PLAYER * k).toInt())
    }

    /** As vanilla's `extractEntityInInventoryFollowsMouse`, but facing [spin] and seen from [tilt] above or below. */
    private fun player(graphics: GuiGraphicsExtractor, p: net.minecraft.world.entity.LivingEntity, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int) {
        val state = mc.entityRenderDispatcher.getRenderer(p).createRenderState(p, 1f)
        state.shadowPieces.clear()
        state.outlineColor = 0
        if (state is net.minecraft.client.renderer.entity.state.LivingEntityRenderState) {
            state.bodyRot = 180f + spin
            state.yRot = 0f
            state.xRot = 0f
            state.boundingBoxWidth /= state.scale
            state.boundingBoxHeight /= state.scale
            state.scale = 1f
        }
        val camera = org.joml.Quaternionf().rotateX(Math.toRadians(tilt.toDouble()).toFloat())
        val rotation = org.joml.Quaternionf().rotateZ(Math.PI.toFloat()).mul(camera)
        val translation = org.joml.Vector3f(0f, state.boundingBoxHeight / 2f + 0.0625f, 0f)
        graphics.entity(state, scale.toFloat(), translation, rotation, camera, x0, y0, x1, y1)
    }

    private fun window() {
        Ui.glow(15f, 40f, W - 30f, H - 30f, 11f, 40f, 0x8C000000.toInt())
        Ui.box(-0.5f, -0.5f, W + 1f, H + 1f, 11.5f, 0, 0.5f, 0x59000000)
        Ui.blur(0f, 0f, W, H, 11f)
        Ui.ambient(-90f, -50f)
        Ui.vbox(0f, 0f, W, H, 11f, 0xEB18151E.toInt(), 0xF20B0A0F.toInt(), 0.5f, 0x17FFFFFF)
        Ui.insetTop(0f, 0f, W, 11f, 0x12FFFFFF)
        Ui.fadeLine(W * 0.1f, 0f, W * 0.8f, 0.5f, Tok.alpha(Tok.light(Tok.a1, 0.3f), 0.55f))
        Ui.block("locker", 0f, 0f, W, H)
        titleBar()
        stage()
        tabs()
        grid()
        statusBar()
    }

    // ---- title bar ----

    private fun titleBar() {
        Ui.box(0f, BAR, W, 0.5f, 0f, Tok.LINE)
        Ui.glow(12.5f, 10.75f, 8f, 8f, 4f, 5f, Tok.alpha(Tok.a1, 0.3f))
        Ui.mark(9.5f, 7.75f, 14f)
        Type.brand.drawGradientMiddle("Locker", 28.5f, 14.75f, Tok.a1, Tok.a2)
        val vx = 28.5f + Type.brand.width("Locker") + 5f
        val vw = Type.ver.width("LUNAR") + 7f
        Ui.box(vx, 10.25f, vw, 9f, 3f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.textC(Type.ver, "LUNAR", vx + vw / 2f, 14.75f, Tok.T3)

        val sw = 220f
        val sx = (W - sw) / 2f
        val focusedNow = Ui.focused === search
        val over = Ui.hit(Ui.fieldId(search), sx, 5.75f, sw, 18f, { if (it == Keys.MOUSE_LEFT) Ui.focused = search })
        if (focusedNow) Ui.box(sx - 2f, 3.75f, sw + 4f, 22f, 7.5f, 0, 2f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(sx, 5.75f, sw, 18f, 5.5f, if (focusedNow) 0x59000000 else 0x40000000, 0.5f,
            if (focusedNow) Tok.alpha(Tok.a1, 0.55f) else if (over) 0x26FFFFFF else Tok.LINE2)
        Ui.icon("search", sx + 6f, 10.75f, 8f, Tok.T3)
        Ui.fieldText(search, sx + 19f, 14.75f, sw - 26f, Type.label, when {
            emotesTab -> "Search emotes"
            outfitsTab -> "Name for a new outfit, or a new name"
            badgesTab -> "Search badges"
            else -> "Search ${LunarLocker.tabs[tab].label.lowercase()}"
        })

        val cx = W - 8.5f - 17f
        val o = Ui.hit("close", cx, 6.25f, 17f, 17f, { if (it == Keys.MOUSE_LEFT) onClose() })
        if (o) Ui.box(cx, 6.25f, 17f, 17f, 5f, Tok.FILL2)
        Ui.icon("x", cx + 4.5f, 10.75f, 8f, if (o) Tok.T1 else Tok.T2)
    }

    // ---- the stage ----

    private fun stage() {
        Ui.box(STAGE, BAR, 0.5f, H - BAR - STATUS, 0f, Tok.LINE)
        // A soft floor under the player.
        Ui.glow(STAGE / 2f - 36f, FLOOR - 3f, 72f, 8f, 4f, 14f, Tok.alpha(Tok.a1, 0.22f))
        Ui.box(STAGE / 2f - 40f, FLOOR, 80f, 2f, 1f, 0x14FFFFFF)
        val name = mc.player?.name?.string ?: ""
        Ui.textC(Type.name, name, STAGE / 2f, BAR + 13f, Tok.T1)
        // Drag the player to turn it (and tilt it a little); let go mid-drag and it keeps turning a moment.
        Ui.hit("stage-player", 10f, BAR + 22f, STAGE - 20f, H - STATUS - 80f - BAR, drag = { x, y ->
            if (!turning) {
                turning = true
                spinV = 0f
            } else {
                val dx = (x - lastX) * 1.8f
                spin += dx
                spinV = dx
                tilt = (tilt + (y - lastY) * 0.9f).coerceIn(-35f, 35f)
            }
            lastX = x
            lastY = y
        }, release = { turning = false })
        Ui.textC(Type.small, "Drag to turn", STAGE / 2f, BAR + 24f, Tok.T4)
        val worn = LunarLocker.equippedIds().size
        Ui.textC(Type.small, if (worn == 0) "Wearing your Lunar outfit" else "$worn picked here", STAGE / 2f, H - STATUS - 47f, Tok.T3)

        // Show in game: Lunar's cosmetics.
        val on = LunarSettings.cosmetics.value
        val y = H - STATUS - 36f
        Ui.box(10f, y, STAGE - 20f, 15f, 5f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.text(Type.label, "Show in game", 16f, y + 7.5f, if (on) Tok.T1 else Tok.T2)
        Ui.switch("show", STAGE - 32f, y + 2.5f, on) {
            LunarSettings.cosmetics.value = !on
            LunarSettings.save()
        }
        val by = H - STATUS - 18f
        Ui.button("clear", 10f, by, 13f, "Take all off", "trash") { LunarLocker.clear() }
        if (emotesTab) Ui.button("stop", STAGE - 10f - 44f, by, 13f, "Stop", "x") { dev.horizon.client.lunar.LunarSocial.stopEmote() }
    }

    // ---- tabs and grid ----

    private val labels get() = LunarLocker.tabs.map { it.label } + "Emotes" + "Outfits" + "Badges"

    private fun tabs() {
        val l = labels
        val w = Ui.segWidth(l)
        val x = STAGE + (W - STAGE - w) / 2f
        Ui.seg("tabs", x, BAR + 7f, w, 17f, l, tab, natural = true) {
            tab = it
            scroll = 0f
            Ui.focused = null
            panel = false
            // Style tags differ from tab to tab; a colour carries over.
            filter = filter.copy(style = null)
        }
    }

    private class Tile(val id: Int, val name: String, val type: String)

    private fun tiles(): List<Tile> {
        if (outfitsTab || badgesTab) return emptyList()
        val q = search.text.trim().lowercase()
        if (emotesTab) {
            val social = dev.horizon.client.lunar.LunarSocial
            val wheel = dev.horizon.client.lunar.LunarProfileEdits.wheel.values.toSet()
            val list = LunarEmotes.emotes().map { Tile(it.id, it.name.split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }, "emote") }
                .filter { t ->
                    (q.isEmpty() || q in t.name.lowercase()) && when (filter.show) {
                        LockerFilter.Show.ALL -> true
                        LockerFilter.Show.OWNED -> social.emotesKnown && social.ownsEmote(t.id)
                        LockerFilter.Show.PICKED -> t.id in wheel
                    }
                }
            return if (filter.sort == LockerFilter.Sort.NAME) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) else list
        }
        return LunarLocker.list(LunarLocker.tabs[tab], q, filter).map { Tile(it.id, it.name, it.type) }
    }

    private fun grid() {
        val gx = STAGE + 10f
        val filtered = !outfitsTab && !badgesTab
        val gy = BAR + 31f + if (filtered) ROW else 0f
        val gw = W - STAGE - 20f
        val gh = H - STATUS - 6f - gy
        val cols = max(1, ((gw + GAP) / (TILE_W + GAP)).toInt())
        val used = cols * TILE_W + (cols - 1) * GAP
        val left = gx + (gw - used) / 2f
        // The filter row lines up with the tiles under it.
        if (filtered) filterRow(left, BAR + 31f, used)
        CosmeticThumbs.frame()
        if (outfitsTab) {
            LunarProfilePanes.outfits(gx, gy, gw, gh, search, pickedOutfit) { pickedOutfit = it }
            return
        }
        if (badgesTab) {
            LunarProfilePanes.badges(gx, gy, gw, gh, search.text)
            return
        }
        val list = tiles()
        val rows = ceil(list.size / cols.toFloat()).toInt()
        scrollMax = max(0f, rows * (TILE_H + GAP) - GAP - gh)
        scroll = scroll.coerceIn(0f, scrollMax)
        val sy = Ui.anim("lockerScroll", scroll, 300)
        Ui.hit("grid", gx, gy, gw, gh, scroll = { a -> scroll = (scroll - a.toFloat() * (TILE_H + GAP)).coerceIn(0f, scrollMax); true }, pointer = false)
        if (LunarAssets.index == null) {
            Ui.textC(Type.label, LunarAssets.lastError?.let { "Couldn't reach Lunar's cosmetics" } ?: "Loading Lunar's cosmetics...", gx + gw / 2f, gy + gh / 2f, Tok.T3)
            return
        }
        if (list.isEmpty()) {
            val why = when {
                emotesTab && LunarEmotes.emotes().isEmpty() -> "Loading emotes..."
                filter.show == LockerFilter.Show.OWNED && !LunarLocker.signedIn && !emotesTab -> "Turn on Lunar in the ClickGUI's Lunar menu to see what you own"
                filter.show == LockerFilter.Show.PICKED -> "Nothing picked here yet"
                else -> "Nothing matches"
            }
            Ui.textC(Type.label, why, gx + gw / 2f, gy + gh / 2f, Tok.T3)
        }
        var hovered: Int? = null
        Ui.pushClip(gx - 4f, gy, gw + 8f, gh)
        val first = max(0, ((sy - TILE_H) / (TILE_H + GAP)).toInt() * cols)
        for (i in first until list.size) {
            val r = i / cols
            val c = i % cols
            val x = left + c * (TILE_W + GAP)
            val y = gy + r * (TILE_H + GAP) - sy
            if (y > gy + gh) break
            if (y + TILE_H < gy) continue
            if (tile(list[i], x, y, gy, gy + gh)) hovered = list[i].id
        }
        Ui.popClip()
        Ui.fade(backdrop, gx - 4f, gy, gw + 8f, 9f, false, sy / 9f)
        Ui.fade(backdrop, gx - 4f, gy + gh, gw + 8f, 9f, true, (scrollMax - sy) / 9f)
        LunarLocker.preview = if (emotesTab || panel) null else hovered
        if (hovered == null) hoverTile = -1
        // A thin scroll bar.
        if (scrollMax > 0f) {
            val bh = max(20f, gh * gh / (gh + scrollMax))
            val by = gy + (gh - bh) * (sy / scrollMax)
            Ui.box(W - 6.5f, by, 2f, bh, 1f, 0x26FFFFFF)
        }
        if (panel && !emotesTab) filterPanel(gx, gy, gw, gh)
    }

    // ---- filters ----

    /** All / Owned / Picked, Newest / A-Z, the Filters button and its chips (not for emotes: they have no tags), Clear. */
    private fun filterRow(x: Float, y: Float, w: Float) {
        val h = 15f
        val shows = LockerFilter.Show.entries
        val sw = Ui.segWidth(shows.map { it.label })
        Ui.seg("show", x, y, sw, h, shows.map { it.label }, filter.show.ordinal, plain = true, natural = true) {
            filter = filter.copy(show = shows[it])
            scroll = 0f
        }
        val sorts = LockerFilter.Sort.entries
        val ow = Ui.segWidth(sorts.map { it.label })
        Ui.seg("sort", x + sw + 6f, y, ow, h, sorts.map { it.label }, filter.sort.ordinal, plain = true, natural = true) {
            filter = filter.copy(sort = sorts[it])
            scroll = 0f
        }
        var bx = x + sw + 6f + ow + 6f
        if (!emotesTab) {
            val n = filter.panelCount
            bx += Ui.button("filters", bx, y, h, if (n > 0) "Filters  $n" else "Filters", "sliders", active = panel || n > 0) { panel = !panel } + 6f
            // What the panel has set, as chips here too: one click drops it.
            filter.color?.let { c -> bx += chip("rowColor", bx, y + 1.5f, 12f, LockerFilter.label(c), c) { filter = filter.copy(color = null) } + 4f }
            filter.style?.let { st -> bx += chip("rowStyle", bx, y + 1.5f, 12f, LockerFilter.label(st), null) { filter = filter.copy(style = null) } + 4f }
        }
        if (filter != LockerFilter.Filter() || search.text.isNotEmpty()) {
            val cw = Type.small.width("Clear") + 9f
            Ui.button("clearFilters", x + w - cw, y, h, "Clear", null) {
                filter = LockerFilter.Filter()
                search.text = ""
                scroll = 0f
            }
        }
    }

    /** A removable chip: [label] with a dot for [color] (a colour tag) and an x; returns its width. */
    private fun chip(id: String, x: Float, y: Float, h: Float, label: String, color: String?, remove: () -> Unit): Float {
        val dot = if (color != null) 9f else 0f
        val w = Type.chip.width(label) + dot + 18f
        val over = Ui.hit("chip:$id", x, y, w, h, { if (it == Keys.MOUSE_LEFT) remove() })
        Ui.box(x, y, w, h, h / 2f, Tok.alpha(Tok.a1, if (over) 0.28f else 0.18f), 0.5f, Tok.alpha(Tok.a1, 0.45f))
        if (color != null) swatch(color, x + 4f, y + (h - 6f) / 2f, 6f)
        Ui.text(Type.chip, label, x + 5f + dot, y + h / 2f, Tok.T1)
        Ui.icon("x", x + w - 10f, y + (h - 5f) / 2f, 5f, if (over) Tok.T1 else Tok.T2)
        return w
    }

    private fun swatch(color: String, x: Float, y: Float, d: Float) {
        val c = LockerFilter.COLORS[color] ?: return
        if (color == "CHROMA") Ui.dbox(x, y, d, d, d / 2f, 0xFFFF4FD8.toInt(), 0xFF4FC3FF.toInt())
        else Ui.box(x, y, d, d, d / 2f, c, 0.5f, 0x40FFFFFF)
    }

    /** The Filters panel over the grid: Lunar's colour tags as swatches, the style tags this tab uses as chips. */
    private fun filterPanel(x: Float, y: Float, w: Float, h: Float) {
        // A click anywhere else on the grid closes it.
        Ui.hit("panelOutside", x - 4f, y, w + 8f, h, { panel = false }, scroll = { true }, pointer = false)
        val styles = LockerFilter.styles(LunarLocker.all(LunarLocker.tabs[tab]))
        val pad = 9f
        val iw = w - pad * 2f
        // Lay the style chips out first, to size the panel.
        val chipH = 13f
        val placed = ArrayList<Triple<String, Float, Float>>()
        var cx = 0f
        var cy = 0f
        for (st in styles) {
            val cw = Type.small.width(LockerFilter.label(st)) + 12f
            if (cx > 0f && cx + cw > iw) {
                cx = 0f
                cy += chipH + 4f
            }
            placed += Triple(st, cx, cy)
            cx += cw + 4f
        }
        val stylesH = if (styles.isEmpty()) 12f else cy + chipH
        val ph = min(h, pad + 10f + 18f + 10f + 10f + stylesH + pad)
        Ui.glow(x, y + 4f, w, ph, 8f, 16f, 0x99000000.toInt())
        Ui.box(x, y, w, ph, 8f, 0xF5141219.toInt(), 0.5f, Tok.LINE2)
        Ui.block("panel", x, y, w, ph)
        // Colours.
        var yy = y + pad
        Ui.text(Type.status, "COLOUR", x + pad, yy + 3f, Tok.T3)
        yy += 10f
        val d = 14f
        val step = min(24f, iw / LockerFilter.COLORS.size)
        for ((i, c) in LockerFilter.COLORS.keys.withIndex()) {
            val sx = x + pad + i * step
            val on = filter.color == c
            val over = Ui.hit("swatch:$c", sx, yy, d + 4f, d + 4f, { b ->
                if (b == Keys.MOUSE_LEFT) {
                    filter = filter.copy(color = if (on) null else c)
                    scroll = 0f
                }
            })
            if (on) Ui.box(sx - 0.5f, yy - 0.5f, d + 5f, d + 5f, (d + 5f) / 2f, 0, 1.2f, Tok.a1)
            else if (over) Ui.box(sx, yy, d + 4f, d + 4f, (d + 4f) / 2f, 0, 0.5f, Tok.LINE2)
            swatch(c, sx + 2f, yy + 2f, d)
        }
        // The hovered (else the picked) colour's name, beside the label.
        val named = LockerFilter.COLORS.keys.firstOrNull { Ui.hot("swatch:$it") } ?: filter.color
        if (named != null) Ui.text(Type.status, LockerFilter.label(named), x + pad + Type.status.width("COLOUR") + 6f, y + pad + 3f, Tok.T1)
        yy += 18f + 10f
        // Styles.
        Ui.text(Type.status, "STYLE", x + pad, yy + 3f, Tok.T3)
        yy += 10f
        if (styles.isEmpty()) Ui.text(Type.small, "This tab has no style tags", x + pad, yy + 6f, Tok.T4)
        Ui.pushClip(x, y, w, ph)
        for ((st, ox, oy) in placed) {
            val label = LockerFilter.label(st)
            val cw = Type.small.width(label) + 12f
            val bx = x + pad + ox
            val by = yy + oy
            if (by > y + ph) break
            val on = filter.style == st
            val over = Ui.hit("style:$st", bx, by, cw, chipH, { b ->
                if (b == Keys.MOUSE_LEFT) {
                    filter = filter.copy(style = if (on) null else st)
                    scroll = 0f
                }
            })
            if (on) Ui.dbox(bx, by, cw, chipH, chipH / 2f, Tok.a1, Tok.a2)
            else Ui.box(bx, by, cw, chipH, chipH / 2f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
            Ui.textC(Type.small, label, bx + cw / 2f, by + chipH / 2f, if (on) Tok.WHITE else if (over) Tok.T1 else Tok.T2)
        }
        Ui.popClip()
    }

    /** One tile; returns whether the mouse is over it. */
    private fun tile(t: Tile, x: Float, y: Float, top: Float, bottom: Float): Boolean {
        // Only the part inside the grid takes the mouse (a tile scrolled under the tabs doesn't).
        val y0 = max(y, top)
        val y1 = min(y + TILE_H, bottom)
        val over = y1 > y0 && Ui.hit("tile:${t.type}:${t.id}", x, y0, TILE_W, y1 - y0, { b ->
            if (b == Keys.MOUSE_LEFT) {
                if (t.type == "emote") dev.horizon.client.lunar.LunarSocial.playEmote(t.id) else LunarLocker.toggle(t.id)
            } else if (b == Keys.MOUSE_RIGHT && t.type == "emote") {
                // Right click: on or off the emote wheel (the first free slot).
                val edits = dev.horizon.client.lunar.LunarProfileEdits
                val slot = edits.wheel.entries.firstOrNull { it.value == t.id }?.key
                if (slot != null) edits.setSlot(slot, 0)
                else (0 until edits.SLOTS).firstOrNull { it !in edits.wheel }?.let { edits.setSlot(it, t.id) }
            }
        })
        val on = t.type != "emote" && LunarLocker.isEquipped(t.id)
        val lift = Ui.anim("lift:${t.id}", if (over) 1f else 0f, 250)
        if (on) Ui.glow(x, y, TILE_W, TILE_H, 6f, 6f, Tok.alpha(Tok.a1, 0.28f))
        Ui.box(x, y, TILE_W, TILE_H, 6f, Colors.lerp(Tok.FILL, Tok.FILL2, lift), if (on) 1f else 0.5f,
            if (on) Tok.alpha(Tok.a1, 0.85f) else if (over) Tok.LINE2 else Tok.LINE)
        // The picture: a cloak's front, else the kind's icon.
        val px = x + 6f
        val py = y + 5f
        val pw = TILE_W - 12f
        val ph = 40f
        Ui.box(px, py, pw, ph, 4f, 0x33000000)
        val cloak = if (t.type == "cloak") LunarCosmetics.cloakTexture(t.id) else null
        // A hovered model turns, from where it rests.
        if (over && hoverTile != t.id) {
            hoverTile = t.id
            hoverSince = System.nanoTime()
        }
        val turn = if (over) ((System.nanoTime() - hoverSince) / 1e9f * 70f) % 360f else null
        if (cloak != null) {
            val ch = ph - 6f
            val cw = ch * 10f / 16f
            val tex = Skins.texture(cloak)
            Canvas.texture(tex, px + (pw - cw) / 2f, py + 3f, cw, ch, -1, -1, -1, -1, 1f / 64f, 1f / 32f, 11f / 64f, 17f / 32f)
        } else if (t.type == "emote" && emotePicture(t.id, px, py, pw, ph, over)) {
            // You, in the emote's pose.
        } else if (t.type != "emote" && t.type != "cloak" && CosmeticThumbs.draw(t.id, t.type, px + 2f, py + 2f, pw - 4f, ph - 4f, turn)) {
            // The model itself.
        } else {
            val icon = if (t.type == "emote") "play" else LunarLocker.tabs.firstOrNull { t.type in it.types }?.icon ?: "cosmetic"
            Ui.icon(icon, px + pw / 2f - 8f, py + ph / 2f - 8f, 16f, if (over || on) Tok.T1 else Tok.T3)
        }
        if (on) {
            Ui.dbox(x + TILE_W - 15f, y + 3f, 12f, 12f, 6f, Tok.a1, Tok.a2)
            Ui.icon("check", x + TILE_W - 13f, y + 5f, 8f, Tok.WHITE)
        }
        Ui.textC(Type.small, Ui.fit(Type.small, t.name, TILE_W - 6f), x + TILE_W / 2f, y + 55.5f, if (over || on) Tok.T1 else Tok.T2)
        // What is yours says so, over the foot of its picture. The rest is client-side, which the status bar explains
        // once: said on every tile it was the same word a few hundred times.
        val label = if (t.type == "emote") {
            val social = dev.horizon.client.lunar.LunarSocial
            val wheelSlot = dev.horizon.client.lunar.LunarProfileEdits.wheel.entries.firstOrNull { it.value == t.id }?.key
            if (wheelSlot != null) "Wheel ${wheelSlot + 1}" else if (social.emotesKnown && social.ownsEmote(t.id)) "Owned" else null
        } else when (LunarLocker.kind(t.id)) {
            LunarLocker.Kind.OWNED -> if (dev.horizon.client.lunar.LunarAccount.onAccount(t.id)) "On Lunar" else "Owned"
            else -> null
        }
        if (label != null) {
            val cw = Type.chip.width(label) + 8f
            val cx = x + (TILE_W - cw) / 2f
            val cy = py + ph - 11f
            Ui.box(cx, cy, cw, 9f, 3.5f, 0xD9141219.toInt())
            Ui.box(cx, cy, cw, 9f, 3.5f, Tok.alpha(Tok.a1, 0.22f), 0.5f, Tok.alpha(Tok.a1, 0.4f))
            Ui.textC(Type.chip, label, x + TILE_W / 2f, cy + 4.5f, Tok.light(Tok.a1, 0.35f))
        }
        return over
    }

    /**
     * An emote's picture: you in its pose, playing it while the mouse is on the tile. Cut to the picture: playing, a
     * pose can reach past where the still one fitted.
     */
    private fun emotePicture(id: Int, px: Float, py: Float, pw: Float, ph: Float, over: Boolean): Boolean {
        val tick = if (over) (System.nanoTime() - hoverSince) / 5e7f else null
        Ui.pushClip(px, py, pw, ph)
        val drawn = CosmeticThumbs.drawEmote(id, px + 1f, py + 1f, pw - 2f, ph - 2f, tick)
        Ui.popClip()
        return drawn
    }

    // ---- status bar ----

    private fun statusBar() {
        val y = H - STATUS
        Ui.box(0f, y, W, 0.5f, 0f, Tok.LINE)
        val n = tiles().size
        val owned = LunarLocker.ownedCount
        val left = when {
            !LunarLocker.signedIn -> "$n here  -  turn on Lunar in the ClickGUI's Lunar menu to see what your account owns"
            owned < 0 -> "$n here  -  your Lunar account owns every cosmetic"
            else -> "$n here  -  your Lunar account owns $owned"
        }
        Ui.text(Type.status, left, 10f, y + STATUS / 2f, Tok.T3)
        val account = LunarSettings.network.value && LunarSettings.lockerAccount.value
        Ui.textR(Type.status, if (account) "Owned picks go on your Lunar outfit; client-side ones only you see" else "Picked here: only you see it",
            W - 10f, y + STATUS / 2f, Tok.T3)
    }

    // ---- input ----

    private fun toWindow(x: Double, y: Double) {
        Ui.mx = ((x - ox) / k).toFloat()
        Ui.my = ((y - oy) / k).toFloat()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        toWindow(event.x(), event.y())
        val had = Ui.focused
        val id = Ui.press(event.button())
        if (had != null && id != Ui.fieldId(had)) Ui.focused = null
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        toWindow(event.x(), event.y())
        Ui.release()
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        toWindow(event.x(), event.y())
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        toWindow(x, y)
        Ui.scroll(scrollY)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val f = Ui.focused
        if (key == Keys.ESCAPE) {
            when {
                panel -> panel = false
                f === search && search.text.isNotEmpty() -> { search.text = ""; scroll = 0f }
                f != null -> Ui.focused = null
                else -> onClose()
            }
            return true
        }
        if (f != null) {
            if (key == Keys.ENTER || key == Keys.TAB) Ui.focused = null else f.key(key)
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val s = event.codepointAsString()
        if (s.isNotBlank() && Ui.focused == null) Ui.focused = search
        Ui.focused?.let { f -> s.forEach { f.type(it) } }
        return true
    }
}
