package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.widgets.Skins
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
 * Not in the original: a cosmetics locker like Lunar Client's own, in the new GUI's style. Your player turns in a stage
 * on the left wearing what you've picked; tabs of Lunar's cosmetics (cloaks, hats, face, wings, back, body, pets,
 * auras) and emotes fill the right as a searchable grid. Click a cosmetic to wear it (one of each kind, as in Lunar),
 * again to take it off; hovering one tries it on. Each tile says what it is for you: **Owned** by your Lunar account,
 * (**On Lunar** once it's on your Lunar outfit), or **Client-side**, which only you see ("Not signed in" before Lunar
 * Network has signed in). Owned picks also go on your Lunar outfit ([dev.horizon.client.lunar.LunarAccount], with
 * Lunar Spoof's `locker-account`); client-side ones never leave this client. Emotes play on your own player,
 * client-side.
 */
object LunarLockerScreen : Screen(Component.literal("horizon-lunar-locker")) {
    const val W = 560f
    const val H = 330f
    private const val BAR = 29f
    private const val STATUS = 15f
    private const val STAGE = 168f
    private const val TILE_W = 64f
    private const val TILE_H = 74f
    private const val GAP = 6f

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
        val x0 = (ox + 14f * k).toInt()
        val y0 = (oy + (BAR + 22f) * k).toInt()
        val x1 = (ox + (STAGE - 14f) * k).toInt()
        val y1 = (oy + (H - STATUS - 58f) * k).toInt()
        player(graphics, p, x0, y0, x1, y1, ((y1 - y0) * 0.36f).toInt())
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
        Ui.glow(STAGE / 2f - 36f, H - STATUS - 66f, 72f, 8f, 4f, 14f, Tok.alpha(Tok.a1, 0.22f))
        Ui.box(STAGE / 2f - 40f, H - STATUS - 63f, 80f, 2f, 1f, 0x14FFFFFF)
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
        }
    }

    private class Tile(val id: Int, val name: String, val type: String)

    private fun tiles(): List<Tile> {
        if (outfitsTab || badgesTab) return emptyList()
        val q = search.text.trim().lowercase()
        if (emotesTab) return LunarEmotes.emotes().filter { q.isEmpty() || q in it.name.lowercase() }
            .map { Tile(it.id, it.name.split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }, "emote") }
        return LunarLocker.list(LunarLocker.tabs[tab], q).map { Tile(it.id, it.name, it.type) }
    }

    private fun grid() {
        val gx = STAGE + 10f
        val gy = BAR + 31f
        val gw = W - STAGE - 20f
        val gh = H - STATUS - 6f - gy
        if (outfitsTab) {
            LunarProfilePanes.outfits(gx, gy, gw, gh, search, pickedOutfit) { pickedOutfit = it }
            return
        }
        if (badgesTab) {
            LunarProfilePanes.badges(gx, gy, gw, gh, search.text)
            return
        }
        val list = tiles()
        val cols = max(1, ((gw + GAP) / (TILE_W + GAP)).toInt())
        val used = cols * TILE_W + (cols - 1) * GAP
        val left = gx + (gw - used) / 2f
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
            Ui.textC(Type.label, if (emotesTab) "Loading emotes..." else "Nothing matches", gx + gw / 2f, gy + gh / 2f, Tok.T3)
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
        LunarLocker.preview = if (emotesTab) null else hovered
        if (hovered == null) hoverTile = -1
        // A thin scroll bar.
        if (scrollMax > 0f) {
            val bh = max(20f, gh * gh / (gh + scrollMax))
            val by = gy + (gh - bh) * (sy / scrollMax)
            Ui.box(W - 6.5f, by, 2f, bh, 1f, 0x26FFFFFF)
        }
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
        Ui.textC(Type.small, Ui.fit(Type.small, t.name, TILE_W - 6f), x + TILE_W / 2f, y + 53f, if (over || on) Tok.T1 else Tok.T2)
        // What it is for you.
        val wheelSlot = if (t.type == "emote") dev.horizon.client.lunar.LunarProfileEdits.wheel.entries.firstOrNull { it.value == t.id }?.key else null
        val (label, color) = if (t.type == "emote") {
            val social = dev.horizon.client.lunar.LunarSocial
            if (wheelSlot != null) "Wheel ${wheelSlot + 1}" to Tok.light(Tok.a1, 0.35f)
            else if (!social.emotesKnown) "Client-side" to Tok.T3
            else if (social.ownsEmote(t.id)) "Owned" to Tok.light(Tok.a1, 0.35f) else "Client-side" to Tok.T3
        } else when (LunarLocker.kind(t.id)) {
            LunarLocker.Kind.OWNED -> (if (dev.horizon.client.lunar.LunarAccount.onAccount(t.id)) "On Lunar" else "Owned") to Tok.light(Tok.a1, 0.35f)
            LunarLocker.Kind.CLIENT_SIDE -> "Client-side" to Tok.T3
            LunarLocker.Kind.UNKNOWN -> "Not signed in" to Tok.T4
        }
        val cw = Type.chip.width(label) + 8f
        val owned = label == "Owned" || label == "On Lunar" || wheelSlot != null
        Ui.box(x + (TILE_W - cw) / 2f, y + 60f, cw, 9f, 3.5f, if (owned) Tok.alpha(Tok.a1, 0.18f) else Tok.FILL)
        Ui.textC(Type.chip, label, x + TILE_W / 2f, y + 64.5f, color)
        return over
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
