package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.compat.SodiumOptions
import dev.horizon.client.config.Storage
import dev.horizon.client.updater.AutoUpdater
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Fps
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.ModernGui
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Type
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.mixin.AccessibilityOptionsScreenInvoker
import dev.horizon.client.mixin.ChatOptionsScreenInvoker
import dev.horizon.client.mixin.ControlsScreenInvoker
import dev.horizon.client.mixin.MouseSettingsScreenInvoker
import dev.horizon.client.mixin.OptionInstanceAccessor
import dev.horizon.client.mixin.TooltipAccessor
import dev.horizon.client.mixin.VideoSettingsScreenInvoker
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.PlayerModelPart
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Options menu in the client's style, over vanilla's (which it wraps, as [ListMenu] does): pages in
 * a sidebar, each a list of rows with switches, value pickers, sliders and key buttons drawn from vanilla's own option
 * values. With Sodium, video settings are Sodium's pages (and those of mods that add pages to Sodium's settings) instead
 * of vanilla's. Vanilla buttons with no page here (online, language, resource packs, difficulty, mods' buttons...) are
 * rows on the General page that press them. Hovering a row shows its tooltip at the foot.
 *
 * It's drawn with the ClickGUI's parts (its glass window, sidebar, switches, sliders and keycaps) in white instead of
 * the theme's colours, as the menus are. The sidebar scrolls above Done once the mods' pages make it longer than the
 * window, its ends fading out while there's more that way.
 */
class OptionsMenu(val vanilla: OptionsScreen) : Screen(Component.translatable("options.title")), MenuScreen {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val options get() = mc.options

    /**
     * A sidebar entry: a page of rows, a section title, or a page that opens a screen of its own. [group] is the section
     * it's in, shown over its title.
     */
    private class Page(val title: String, val sub: String, val rows: () -> List<Row>, val section: Boolean = false,
                       val icon: String? = null, val group: String = "", val open: (() -> Unit)? = null)

    private sealed interface Row
    private class Header(val title: String) : Row
    private class Vanilla(val option: OptionInstance<*>) : Row
    private class Sodium(val option: SodiumOptions.Opt) : Row
    private class Toggle(val title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Row
    private class Link(val title: String, val note: () -> String, val action: () -> Unit) : Row
    private class Key(val mapping: KeyMapping) : Row

    /** Where a click, drag or release goes, from the last frame. */
    private class Hit(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val click: (Int) -> Unit, val drag: ((Float) -> Unit)? = null, val release: (() -> Unit)? = null) {
        fun contains(x: Float, y: Float) = x >= x0 && x < x1 && y >= y0 && y < y1
    }

    private var pages = emptyList<Page>()
    private var page: Page? = null
    private var rows = emptyList<Row>()
    private var hits = ArrayList<Hit>()
    private var next = ArrayList<Hit>()
    private var dragging: Hit? = null
    /** The slider being dragged (it follows the mouse instead of easing). */
    private var dragKey: Any? = null
    private var waiting: KeyMapping? = null
    /** The hovered row's name and tooltip, for the foot. */
    private var hintName = ""
    private var hint = ""
    private var scroll = 0f
    private var scrollTarget = 0f
    private var navScroll = 0f
    private var navTarget = 0f
    /** The sidebar's scrolling list, from the last frame (the wheel scrolls it there). */
    private var navX0 = 0f
    private var navX1 = 0f
    private var navTop = 0f
    private var navBottom = 0f
    private var panelY = 0f
    private var panelH = 0f
    /** The mouse as the row being drawn sees it (off the list: above it). */
    private var rowMx = 0f
    private var rowMy = -1f
    private val pendingSlides = HashMap<OptionInstance<*>, Any?>()
    private val enter = Animation(Easing.OUT_EXPO, 350)
    /** The selected page's pill's place in the sidebar list (below 0 until first placed). */
    private val pill = Animation(Easing.OUT_EXPO, 500).also { it.value = -1f }
    private val pageEnter = Animation(Easing.OUT_EXPO, 280).also { it.value = 1f; it.update(1f) }
    /** Which way the page came in: 1 from below (a page further down the list), -1 from above. */
    private var pageFrom = 1f
    private val doneLabel = Component.translatable("gui.done").string
    private val oldMipmaps = options.mipmapLevels().get()
    private val oldAnisotropy = options.maxAnisotropyBit().get()
    private val oldFiltering = options.textureFiltering().get()

    /** The window's glass, for fading the ends of its lists into it ([Ui.fade]). */
    private val backdrop = Ui.Backdrop(null, { y -> Ui.vboxAt(panelY, panelH, tint(true), tint(false), y) })

    override fun init() {
        vanilla.init(width, height)
        val more = vanilla.children().filterIsInstance<AbstractButton>().filter {
            it.visible && it.message.string.isNotBlank() && (it.message.contents as? TranslatableContents)?.key !in OWN_PAGES
        }
        val list = ArrayList<Page>()
        list += Page(MINECRAFT, "", { emptyList() }, section = true)
        list += Page(GENERAL, "Field of view, updates and the rest of the options", {
            buildList {
                add(Vanilla(options.fov()))
                add(Header("Horizon"))
                add(Toggle("Auto-update", { Storage.shared.autoUpdate }) {
                    Storage.shared.autoUpdate = it
                    Storage.saveShared()
                })
                add(Link("Check for updates", { AutoUpdater.status.ifBlank { "v${AutoUpdater.currentVersion}" } }) {
                    AutoUpdater.check(manual = true)
                })
                if (more.isNotEmpty()) add(Header("More"))
                for (b in more) add(Link(b.message.string, { "" }) { b.onPress(KeyEvent(InputConstants.KEY_RETURN, 0, 0)) })
            }
        }, icon = "gear", group = MINECRAFT)
        val sodium = SodiumOptions.present
        if (!sodium) list += Page(t("options.video"), "Display, quality and preferences", {
            buildList {
                add(Header(t("options.video.display.header")))
                VideoSettingsScreenInvoker.horizon_display(options).forEach { add(Vanilla(it)) }
                add(Header(t("options.video.quality.header")))
                add(Vanilla(options.graphicsPreset()))
                VideoSettingsScreenInvoker.horizon_quality(options).forEach { add(Vanilla(it)) }
                add(Header(t("options.video.preferences.header")))
                VideoSettingsScreenInvoker.horizon_preferences(options).forEach { add(Vanilla(it)) }
            }
        }, icon = "layout", group = MINECRAFT)
        list += Page(t("options.sounds"), "Volume for each kind of sound", {
            buildList {
                add(Vanilla(options.getSoundSourceOptionInstance(SoundSource.MASTER)))
                for (s in SoundSource.values()) if (s != SoundSource.MASTER) add(Vanilla(options.getSoundSourceOptionInstance(s)))
                add(Header("Output"))
                add(Vanilla(options.soundDevice()))
                add(Vanilla(options.showSubtitles()))
                add(Vanilla(options.directionalAudio()))
                add(Vanilla(options.musicFrequency()))
                add(Vanilla(options.musicToast()))
            }
        }, icon = "music", group = MINECRAFT)
        list += Page(t("options.controls"), "Mouse and movement", {
            buildList {
                add(Header(t("options.mouse_settings")))
                MouseSettingsScreenInvoker.horizon_options(options).forEach { add(Vanilla(it)) }
                add(Header("Movement"))
                ControlsScreenInvoker.horizon_options(options).forEach { add(Vanilla(it)) }
            }
        }, icon = "sliders", group = MINECRAFT)
        list += Page(t("controls.keybinds"), "Click a key to change it, right-click to reset it, Esc to unbind", {
            buildList {
                var last: KeyMapping.Category? = null
                for (k in options.keyMappings.sorted()) {
                    if (k.category != last) {
                        last = k.category
                        add(Header(k.category.label().string))
                    }
                    add(Key(k))
                }
            }
        }, icon = "key", group = MINECRAFT)
        list += Page(t("options.chat"), "Chat visibility, size and safety", { ChatOptionsScreenInvoker.horizon_options(options).map(::Vanilla) },
            icon = "chat", group = MINECRAFT)
        list += Page(t("options.accessibility"), "Narrator, contrast, motion and more", {
            // (26.3's own list has the narrator; added only if it's missing.)
            val own = AccessibilityOptionsScreenInvoker.horizon_options(options)
            (if (options.narrator() in own) own.toList() else listOf(options.narrator()) + own).map(::Vanilla)
        }, icon = "eye", group = MINECRAFT)
        list += Page(t("options.skinCustomisation"), "Skin layers and main hand", {
            PlayerModelPart.values().map { part -> Toggle(part.getName().string, { options.isModelPartEnabled(part) }) { options.setModelPart(part, it) } } +
                Vanilla(options.mainHand())
        }, icon = "player", group = MINECRAFT)
        if (sodium) for (mod in SodiumOptions.mods()) {
            if (mod.pages.isEmpty()) continue
            list += Page(mod.name, "", { emptyList() }, section = true)
            for (p in mod.pages) {
                val open = p.open
                list += if (open != null) {
                    Page(p.name, "", { emptyList() }, group = mod.name) { open.accept(this) }
                } else {
                    Page(p.name, "", {
                        buildList {
                            for (g in p.groups) {
                                if (g.name.isNotEmpty()) add(Header(g.name))
                                for (o in g.options) if (!o.hidden && o.kind != SodiumOptions.Kind.OTHER) add(Sodium(o))
                            }
                        }
                    }, group = mod.name)
                }
            }
        }
        pages = list
        val keep = pages.firstOrNull { it.title == page?.title && it.group == page?.group && !it.section && it.open == null }
        show(keep ?: pages.first { !it.section })
    }

    /** Goes to another page, with the ClickGUI's tab sound for the direction. */
    private fun turnTo(p: Page) {
        val open = p.open
        if (open != null) {
            Menus.open()
            open()
            return
        }
        val down = pages.indexOf(p) > pages.indexOf(page)
        Menus.step(down)
        pageFrom = if (down) 1f else -1f
        pageEnter.reset(0f)
        show(p)
    }

    private fun show(p: Page) {
        page = p
        rows = p.rows()
        scroll = 0f
        scrollTarget = 0f
        waiting = null
    }

    override fun tick() = vanilla.tick()

    // Eases in each time it's shown, coming back from a screen it opened too.
    override fun added() = enter.reset(0f)

    override fun removed() = apply()

    override fun onClose() = vanilla.onClose()

    private fun close() {
        Menus.back()
        vanilla.onClose()
    }

    /** On leaving: save, and what vanilla's video screen does when its texture settings changed. */
    private fun apply() {
        for ((o, v) in pendingSlides) @Suppress("UNCHECKED_CAST") (o as OptionInstance<Any?>).set(v)
        pendingSlides.clear()
        vanilla.removed()
        options.save()
        if (options.mipmapLevels().get() != oldMipmaps || options.maxAnisotropyBit().get() != oldAnisotropy || options.textureFiltering().get() != oldFiltering) {
            mc.updateMaxMipLevel(options.mipmapLevels().get())
            mc.delayTextureReload()
        }
        mc.window.changeFullscreenVideoMode()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (mc.level == null) extractPanorama(graphics, delta) else super.extractBackground(graphics, mouseX, mouseY, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun isPauseScreen() = vanilla.isPauseScreen

    // ---- drawing ----

    override fun draw(mouseX: Float, mouseY: Float) {
        next = ArrayList()
        hintName = ""
        hint = ""
        enter.update(1f)
        val k = enter.value
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(PANEL_W, w - 32f)
        val ph = minOf(PANEL_H, h - 32f)
        val px = floor((w - pw) / 2f)
        val py = floor((h - ph) / 2f + (1f - k) * 8f)
        panelY = py
        panelH = ph
        Canvas.alpha = k
        window(px, py, pw, ph)
        sidebar(px, py, ph, mouseX, mouseY)
        pane(px, py, pw, ph, mouseX, mouseY, k)
        Canvas.alpha = 1f
        hits = next
    }

    /** The glass, as the ClickGUI's window: its shadow, the blurred world under a grey tint, a hairline round it. */
    private fun window(px: Float, py: Float, pw: Float, ph: Float) {
        Ui.glow(px + 15f, py + 40f, pw - 30f, ph - 30f, 11f, 40f, 0x8C000000.toInt())
        Ui.box(px - 0.5f, py - 0.5f, pw + 1f, ph + 1f, 11.5f, 0, 0.5f, 0x59000000)
        Ui.blur(px, py, pw, ph, 11f)
        Ui.vbox(px, py, pw, ph, 11f, tint(true), tint(false), 0.5f, 0x17FFFFFF)
        Ui.insetTop(px, py, pw, 11f, 0x12FFFFFF)
        Ui.box(px + SIDE, py, 0.5f, ph, 0f, Tok.LINE)
    }

    /** The glass's tint at its top or bottom: the ClickGUI's, without the theme's hue, as opaque as its window. */
    private fun tint(top: Boolean): Int {
        val g = Modules.get<ClickGuiModule>().opacity.value
        return if (top) Colors.withAlpha(0xFF161618.toInt(), g) else Colors.withAlpha(0xFF0B0B0D.toInt(), g + 0.06f)
    }

    /** The sidebar: the title, the pages under their sections (scrolling, the selected one on a pill), Done at the foot. */
    private fun sidebar(px: Float, py: Float, ph: Float, mx: Float, my: Float) {
        Ui.text(Type.h2, title.string, px + 13f, py + 18f, Tok.T1)
        val x = px + 7f
        val rw = SIDE - 14f
        val top = py + 30f
        val bottom = py + ph - 8f - DONE_H - 6f
        navX0 = px
        navX1 = px + SIDE
        navTop = top
        navBottom = bottom
        val at = ArrayList<Pair<Page, Float>>()
        var y = 2f
        for ((i, p) in pages.withIndex()) {
            if (p.section && i > 0) y += 5f
            at += p to y
            y += if (p.section) SECTION else ROW
        }
        val max = maxOf(0f, y + 4f - (bottom - top))
        navTarget = navTarget.coerceIn(0f, max)
        navScroll = Fps.smooth(navScroll, navTarget, 18f)
        if (abs(navScroll - navTarget) < 0.1f) navScroll = navTarget
        val sy = navScroll
        Ui.pushClip(px, top, SIDE, bottom - top)
        at.firstOrNull { it.first === page }?.let { (_, y0) ->
            if (pill.value < 0f) pill.value = y0
            pill.update(y0)
            Ui.hbox(x, top + pill.value - sy, rw, ROW, 5f, 0x1AFFFFFF, 0x0BFFFFFF, 0.5f, 0x14FFFFFF)
        }
        for ((p, y0) in at) {
            val ry = top + y0 - sy
            if (ry + ROW < top || ry > bottom) continue
            if (p.section) {
                Ui.text(Type.section, p.title.uppercase(Locale.ROOT), x + 5f, ry + 10f, Tok.T4)
                continue
            }
            val active = p === page
            val over = !active && mx >= x && mx < x + rw && my >= maxOf(ry, top) && my < minOf(ry + ROW, bottom)
            if (over) {
                Cursor.request(CursorTypes.POINTING_HAND)
                Ui.box(x, ry, rw, ROW, 5f, Tok.FILL)
            }
            val c = if (active) Tok.WHITE else if (over) Tok.T1 else Tok.T2
            p.icon?.let { Ui.icon(it, x + 5f, ry + 4.5f, 8f, c) }
            val opens = p.open != null
            Ui.text(Type.nav, Ui.fit(Type.nav, p.title, rw - 18f - if (opens) 14f else 4f), x + 18f, ry + 8.5f, c)
            // A page that opens the mod's own screen: a chevron says it goes elsewhere.
            if (opens) Ui.icon("chev", x + rw - 11f + if (over) 1.5f else 0f, ry + 5.5f, 6f, if (over) Tok.T2 else Tok.T4)
            val target = p
            hit(x, ry, x + rw, ry + ROW, top, bottom, { b -> if (b == Keys.MOUSE_LEFT && target !== page) turnTo(target) })
        }
        Ui.popClip()
        Ui.fade(backdrop, px + 0.5f, top, SIDE - 0.5f, FADE, false, sy / FADE)
        Ui.fade(backdrop, px + 0.5f, bottom, SIDE - 0.5f, FADE, true, (max - sy) / FADE)
        val dy = py + ph - 8f - DONE_H
        val over = mx >= x && mx < x + rw && my >= dy && my < dy + DONE_H
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        Ui.box(x, dy, rw, DONE_H, 5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        Ui.textC(Type.nav, doneLabel, x + rw / 2f, dy + DONE_H / 2f, if (over) Tok.T1 else Tok.T2)
        next += Hit(x, dy, x + rw, dy + DONE_H, { b -> if (b == Keys.MOUSE_LEFT) close() })
    }

    /**
     * The page: its section, title and what's on it, the rows, and the hovered row's name and description at the foot.
     * A new page fades in, rising (or dropping) the last few units the way the sidebar moved.
     */
    private fun pane(px: Float, py: Float, pw: Float, ph: Float, mx: Float, my: Float, k: Float) {
        val p = page ?: return
        val x0 = px + SIDE + 0.5f
        val paneW = pw - SIDE - 0.5f
        val lx = x0 + 14f
        val right = px + pw - 14f
        pageEnter.update(1f)
        val pk = pageEnter.value
        Canvas.alpha = k * pk
        Canvas.pose.pushMatrix()
        Canvas.pose.translate(0f, (1f - pk) * 6f * pageFrom)
        var titleRight = right
        if (rows.any { it is Key }) {
            val changed = options.keyMappings.any { !it.isDefault }
            val label = Component.translatable("controls.resetAll").string
            val bw = buttonWidth(label, "reset")
            button(right - bw, py + 21f, label, "reset", changed, mx, my) {
                for (key in options.keyMappings) key.setKey(key.defaultKey)
                KeyMapping.resetMapping()
                options.save()
                Menus.toggle(false)
            }
            titleRight = right - bw - 8f
        }
        if (p.group.isNotEmpty()) Ui.text(Type.crumb, p.group.uppercase(Locale.ROOT), lx, py + 15f, Tok.T4)
        Ui.text(Type.h2, Ui.fit(Type.h2, p.title, titleRight - lx), lx, py + 28f, Tok.T1)
        if (p.sub.isNotEmpty()) Ui.text(Type.desc, Ui.fit(Type.desc, p.sub, right - lx), lx, py + 40.5f, Tok.T3)
        Canvas.pose.popMatrix()
        Canvas.alpha = k
        val ly = py + HEAD
        Ui.box(x0, ly, paneW, 0.5f, 0f, Tok.LINE)
        val lb = py + ph - FOOT
        Ui.box(x0, lb, paneW, 0.5f, 0f, Tok.LINE)

        // The rows: they scroll, and fade into the glass at the ends while there's more that way.
        val top = ly + 0.5f
        val lh = lb - top
        var content = 8f
        for (r in rows) content += height(r)
        val max = maxOf(0f, content - lh)
        scrollTarget = scrollTarget.coerceIn(0f, max)
        scroll = Fps.smooth(scroll, scrollTarget, 18f)
        if (abs(scroll - scrollTarget) < 0.1f) scroll = scrollTarget
        val rx = x0 + 7f
        val rw = paneW - 14f - 3f
        Canvas.alpha = k * pk
        Canvas.pose.pushMatrix()
        Canvas.pose.translate(0f, (1f - pk) * 6f * pageFrom)
        Ui.pushClip(x0, top, paneW, lh)
        var y = top + 4f - scroll
        val inList = my >= top && my <= lb
        for (r in rows) {
            val rh = height(r)
            if (y + rh >= top && y <= lb) drawRow(r, rx, y, rw, rh, mx, if (inList) my else -1f, top, lb)
            y += rh
        }
        Ui.popClip()
        Canvas.pose.popMatrix()
        Canvas.alpha = k
        Ui.fade(backdrop, x0 + 0.5f, top, paneW - 1f, FADE, false, scroll / FADE)
        Ui.fade(backdrop, x0 + 0.5f, lb, paneW - 1f, FADE, true, (max - scroll) / FADE)
        if (max > 0f) ModernGui.scrollbar(px + pw - 6f, top + 3f, lh - 6f, scroll, max)

        // The foot: the hovered row's name and what it does.
        val cy = lb + FOOT / 2f
        if (hintName.isNotEmpty()) {
            Ui.text(Type.statusB, Ui.fit(Type.statusB, hintName, right - lx), lx, cy, Tok.T2)
            val nx = lx + Type.statusB.width(hintName) + 4f
            if (hint.isNotEmpty() && right - nx > 30f) Ui.text(Type.status, Ui.fit(Type.status, "·  $hint", right - nx), nx, cy, Tok.T3)
        }
    }

    private fun height(r: Row) = if (r is Header) 22f else ROW_H

    /** Whether vanilla shows [o] as a button that steps through its values (else a slider). */
    private fun cycles(o: OptionInstance<*>): Boolean {
        val vs = o.values()
        return vs is OptionInstance.CycleableValueSet<*> && !(vs is OptionInstance.SliderableOrCyclableValueSet<*> && !vs.createCycleButton())
    }

    /** A control's hit area, only where the list shows it. */
    private fun hit(x0: Float, y0: Float, x1: Float, y1: Float, top: Float, bottom: Float, click: (Int) -> Unit, drag: ((Float) -> Unit)? = null, release: (() -> Unit)? = null) {
        val a = maxOf(y0, top)
        val b = minOf(y1, bottom)
        if (b > a) next += Hit(x0, a, x1, b, click, drag, release)
    }

    private fun drawRow(r: Row, x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, top: Float, bottom: Float) {
        if (r is Header) {
            Ui.text(Type.section, r.title.uppercase(Locale.ROOT), x + 6f, y + 14f, Tok.T4)
            return
        }
        val hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + h
        rowMx = mouseX
        rowMy = mouseY
        if (hovered) Ui.box(x, y, w, h, 5f, 0x08FFFFFF)
        val lx = x + 6f
        val right = x + w - 6f
        val cy = y + 10f
        when (r) {
            is Vanilla -> vanillaRow(r.option, x, y, w, lx, right, cy, hovered, top, bottom)
            is Sodium -> sodiumRow(r.option, x, y, w, lx, right, cy, hovered, top, bottom)
            is Toggle -> {
                val on = r.get()
                label(r.title, lx, cy, right - 24f - lx, true)
                if (hovered) hintName = r.title
                switch(r.title, right - 17f, cy - 5f, on, true, hovered)
                hit(x, y, x + w, y + h, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { r.set(!on); options.save(); Menus.toggle(!on) } })
            }
            is Link -> {
                val title = r.title.removeSuffix("...").trimEnd()
                val note = r.note()
                val slide = if (hovered) 1.5f else 0f
                val noteW = if (note.isEmpty()) 0f else Type.small.width(note) + 10f
                label(title, lx, cy, right - 12f - noteW - lx, true)
                if (note.isNotEmpty()) Ui.textR(Type.small, note, right - 12f, cy, Tok.T3)
                Ui.icon("chev", right - 6f + slide, cy - 3f, 6f, if (hovered) Tok.T1 else Tok.T3)
                if (hovered) {
                    hintName = title
                    Cursor.request(CursorTypes.POINTING_HAND)
                }
                hit(x, y, x + w, y + h, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { Menus.open(); r.action() } })
            }
            is Key -> keyRow(r.mapping, lx, right, cy, hovered, top, bottom, mouseX, mouseY)
            is Header -> {}
        }
    }

    /** A row's label, vertically centred on [cy], cut to [max]. */
    private fun label(text: String, x: Float, cy: Float, max: Float, enabled: Boolean) =
        Ui.text(Type.label, Ui.fit(Type.label, text, max), x, cy, if (enabled) LABEL else Tok.T4)

    @Suppress("UNCHECKED_CAST")
    private fun vanillaRow(o: OptionInstance<*>, x: Float, y: Float, w: Float, lx: Float, right: Float, cy: Float, hovered: Boolean, top: Float, bottom: Float) {
        val opt = o as OptionInstance<Any?>
        val acc = o as Any as OptionInstanceAccessor<Any?>
        val caption = acc.horizon_caption().string
        val value = if (pendingSlides.containsKey(o)) pendingSlides[o] else opt.get()
        if (hovered) {
            hintName = caption
            hint = tooltip(acc, value)
        }
        val vs = opt.values()
        if (cycles(o)) {
            val cyc = vs as OptionInstance.CycleableValueSet<Any?>
            val list = cyc.valueListSupplier().selectedList
            if (list.size == 2 && true in list && false in list) {
                val on = value == true
                label(caption, lx, cy, right - 24f - lx, true)
                switch(o, right - 17f, cy - 5f, on, true, hovered)
                hit(x, y, x + w, y + ROW_H, top, bottom, { b ->
                    if (b == Keys.MOUSE_LEFT) {
                        cyc.valueSetter().set(opt, !on)
                        options.save()
                        Menus.toggle(!on)
                    }
                })
            } else {
                val labels = list.map { valueLabel(acc, it, caption) }
                val i = list.indexOf(value)
                val pw = pickerWidth(labels, i, w * 0.55f)
                label(caption, lx, cy, right - pw - 8f - lx, true)
                picker(o, right - pw, cy, pw, w * 0.55f, labels, i, true, top, bottom) { n ->
                    cyc.valueSetter().set(opt, list[n])
                    options.save()
                }
            }
        } else if (vs is OptionInstance.SliderableValueSet<*>) {
            val sl = vs as OptionInstance.SliderableValueSet<Any?>
            slider(o, caption, true, x, y, w, sl.toSliderValue(value).toFloat(), valueLabel(acc, value, caption), hovered, top, bottom, { t ->
                val v = sl.fromSliderValue(t.toDouble())
                if (sl.applyValueImmediately()) opt.set(v) else pendingSlides[o] = v
            }) {
                if (pendingSlides.containsKey(o)) opt.set(pendingSlides.remove(o))
                options.save()
            }
        } else {
            label(caption, lx, cy, right - lx, true)
        }
    }

    private fun sodiumRow(o: SodiumOptions.Opt, x: Float, y: Float, w: Float, lx: Float, right: Float, cy: Float, hovered: Boolean, top: Float, bottom: Float) {
        val enabled = o.enabled
        if (hovered) {
            hintName = o.name
            hint = listOf(o.tooltip, o.impact.takeIf { it.isNotEmpty() }?.let { "Performance impact: $it" } ?: "").filter { it.isNotEmpty() }.joinToString("  ·  ")
        }
        when (o.kind) {
            SodiumOptions.Kind.BOOLEAN -> {
                val on = o.value == true
                label(o.name, lx, cy, right - 24f - lx, enabled)
                switch(o, right - 17f, cy - 5f, on, enabled, hovered)
                if (enabled) hit(x, y, x + w, y + ROW_H, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { o.set(!on); Menus.toggle(!on) } })
            }
            SodiumOptions.Kind.INTEGER -> {
                val (min, max, step) = o.range
                val v = o.value as? Int ?: min
                val span = maxOf(1, max - min)
                slider(o, o.name, enabled, x, y, w, (v - min).toFloat() / span, o.format(v), hovered, top, bottom, { t ->
                    val nv = (min + ((t * span) / step).roundToInt() * step).coerceIn(min, max)
                    if (nv != o.value) o.set(nv)
                }) {}
            }
            SodiumOptions.Kind.ENUM -> {
                val values = o.values
                val labels = values.map { o.valueName(it) }
                val i = values.indexOf(o.value)
                val pw = pickerWidth(labels, i, w * 0.55f)
                label(o.name, lx, cy, right - pw - 8f - lx, enabled)
                picker(o, right - pw, cy, pw, w * 0.55f, labels, i, enabled, top, bottom) { n -> o.set(values[n]) }
            }
            SodiumOptions.Kind.BUTTON -> {
                val open = o.open
                label(o.name, lx, cy, right - 12f - lx, enabled)
                Ui.icon("chev", right - 6f + if (hovered) 1.5f else 0f, cy - 3f, 6f, if (hovered && enabled) Tok.T1 else Tok.T3)
                if (enabled && open != null) {
                    if (hovered) Cursor.request(CursorTypes.POINTING_HAND)
                    hit(x, y, x + w, y + ROW_H, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { Menus.open(); open.accept(this) } })
                }
            }
            SodiumOptions.Kind.OTHER -> {}
        }
    }

    private fun keyRow(k: KeyMapping, lx: Float, right: Float, cy: Float, hovered: Boolean, top: Float, bottom: Float, mouseX: Float, mouseY: Float) {
        val name = Component.translatable(k.name).string
        if (hovered) hintName = name
        val capturing = waiting === k
        val text = if (capturing) "Press a key..." else k.translatedKeyMessage.string
        val bw = maxOf(58f, Type.sub.width(text) + 16f)
        val bx = right - bw
        val by = cy - 6.5f
        val shown = Ui.fit(Type.label, name, bx - 10f - lx)
        Ui.text(Type.label, shown, lx, cy, LABEL)
        // Changed from its default: a dot after the name.
        if (!k.isDefault) Ui.box(lx + Type.label.width(shown) + 3.5f, cy - 1.25f, 2.5f, 2.5f, 1.25f, Tok.T2)
        val conflict = !k.isUnbound && options.keyMappings.any { it !== k && it.same(k) }
        val over = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 13f
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        when {
            capturing -> Ui.box(bx, by, bw, 13f, 4f, 0x1AFFFFFF, 0.5f, 0x8CFFFFFF.toInt())
            over -> Ui.box(bx, by, bw, 13f, 4f, 0x1AFFFFFF, 0.5f, Tok.LINE2)
            else -> Ui.box(bx, by, bw, 13f, 4f, Tok.FILL2, 0.5f, Tok.LINE)
        }
        val color = when {
            capturing -> Tok.T1
            conflict -> 0xFFF87171.toInt()
            k.isUnbound -> Tok.T3
            over -> Tok.T1
            else -> Tok.T2
        }
        Ui.textC(Type.sub, Ui.fit(Type.sub, text, bw - 8f), bx + bw / 2f, cy, color)
        hit(bx, by, bx + bw, by + 13f, top, bottom, { b ->
            when (b) {
                Keys.MOUSE_LEFT -> {
                    Menus.open()
                    waiting = k
                }
                Keys.MOUSE_RIGHT -> {
                    Menus.toggle(false)
                    k.setKey(k.defaultKey)
                    KeyMapping.resetMapping()
                    options.save()
                }
            }
        })
    }

    // ---- controls ----

    private fun id(key: Any) = System.identityHashCode(key).toString()

    /**
     * The ClickGUI's switch (17 x 10, the knob springing over), in white: off, a faint track and a white knob; on, a
     * white track and a dark knob.
     */
    private fun switch(key: Any, x: Float, y: Float, on: Boolean, enabled: Boolean, hovered: Boolean) {
        val t = Ui.anim("opt:sw:" + id(key), if (on) 1f else 0f, 450, Easing.OUT_BACK)
        val f = t.coerceIn(0f, 1f)
        val a = Canvas.alpha
        if (!enabled) Canvas.alpha = a * 0.4f
        if (f > 0.01f) Ui.glow(x, y, 17f, 10f, 5f, 5.5f, Colors.withAlpha(-1, 0.12f * f))
        Ui.hbox(x, y, 17f, 10f, 5f, Colors.lerp(0x1FFFFFFF, ON_LEFT, f), Colors.lerp(0x1FFFFFFF, ON_RIGHT, f))
        val kx = x + 1f + 7f * t
        Ui.glow(kx, y + 1f, 8f, 8f, 4f, 1.5f, 0x40000000, 1f)
        Ui.box(kx, y + 1f, 8f, 8f, 4f, Colors.lerp(if (hovered) 0xFFF4F4F6.toInt() else Tok.WHITE, KNOB_ON, f))
        Canvas.alpha = a
    }

    /** How wide [picker] draws: the segments when there are two or three short ones, else a box round the value. */
    private fun pickerWidth(labels: List<String>, index: Int, max: Float): Float {
        if (segmented(labels, max)) return segWidth(labels)
        val text = labels.getOrElse(index) { "" }
        return (Type.sub.width(text) + 22f).coerceIn(44f, minOf(120f, max))
    }

    private fun segmented(labels: List<String>, max: Float) = labels.size in 2..3 && segWidth(labels) <= minOf(160f, max)

    private fun segWidth(labels: List<String>) = labels.sumOf { (Type.small.width(it) + 12f).toDouble() }.toFloat() + 3f

    /**
     * A value from a list, [w] wide from [x]: two or three short ones as the ClickGUI's segmented control (a grey thumb
     * sliding to the one picked), more as a box with the value that steps forward on a click, back on a right-click.
     */
    private fun picker(key: Any, x: Float, cy: Float, w: Float, max: Float, labels: List<String>, index: Int, enabled: Boolean, top: Float, bottom: Float, pick: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val n = labels.size
        val y = cy - 6.5f
        if (segmented(labels, max)) {
            Ui.box(x, y, w, 13f, 5f, 0x40000000, 0.5f, Tok.LINE)
            val widths = labels.map { Type.small.width(it) + 12f }
            val lefts = widths.runningFold(x + 1.5f) { a, b -> a + b }
            val tx = Ui.anim("opt:seg:" + id(key), index.coerceAtLeast(0).toFloat(), 450)
            val i0 = tx.toInt().coerceIn(0, n - 1)
            val i1 = (i0 + 1).coerceAtMost(n - 1)
            val f = tx - i0
            if (index >= 0) {
                val thx = lefts[i0] + (lefts[i1] - lefts[i0]) * f
                val thw = widths[i0] + (widths[i1] - widths[i0]) * f
                Ui.box(thx, y + 1.5f, thw, 10f, 4f, 0x1AFFFFFF, 0.5f, 0x14FFFFFF)
            }
            for ((i, l) in labels.withIndex()) {
                val bx = lefts[i]
                val bw = widths[i]
                val over = enabled && rowMx >= bx && rowMx < bx + bw && rowMy >= y && rowMy < y + 13f
                val c = when {
                    !enabled -> Tok.T4
                    i == index -> Tok.WHITE
                    over -> Tok.T2
                    else -> Tok.T3
                }
                Ui.textC(Type.small, l, bx + bw / 2f, cy, c)
                if (enabled) hit(bx, y, bx + bw, y + 13f, top, bottom, { b -> if (b == Keys.MOUSE_LEFT && i != index) { pick(i); Menus.step(i > index) } })
            }
            return
        }
        val text = labels.getOrElse(index) { "" }
        val over = enabled && rowMx >= x && rowMx <= x + w && rowMy >= y && rowMy <= y + 13f
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        Ui.box(x, y, w, 13f, 4f, if (over) 0x1AFFFFFF else Tok.FILL2, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        Ui.text(Type.sub, Ui.fit(Type.sub, text, w - 18f), x + 5.5f, cy, if (enabled) Tok.T1 else Tok.T4)
        Ui.icon("updown", x + w - 10.5f, cy - 3.5f, 7f, if (enabled) Tok.T3 else Tok.T4)
        if (enabled) hit(x, y, x + w, y + 13f, top, bottom, { b ->
            when (b) {
                Keys.MOUSE_LEFT -> {
                    pick((index + 1).mod(n))
                    Menus.step(true)
                }
                Keys.MOUSE_RIGHT -> {
                    pick((index - 1).mod(n))
                    Menus.step(false)
                }
            }
        })
    }

    /**
     * The ClickGUI's split slider in white, on one line: [name] on the left; the value, then the track on the right (the
     * filled part, a gap, the handle, a gap, the rest). Dragging calls [set] with 0..1; letting go calls [commit].
     */
    private fun slider(key: Any, name: String, enabled: Boolean, x: Float, y: Float, w: Float, frac: Float, value: String, hovered: Boolean,
                       top: Float, bottom: Float, set: (Float) -> Unit, commit: () -> Unit) {
        val lx = x + 6f
        val right = x + w - 6f
        val cy = y + ROW_H / 2f
        val tw = minOf(SLIDER_W, w * 0.42f)
        val tx = right - tw
        val vw = Type.value.width(value)
        Ui.textR(Type.value, value, tx - 8f, cy, if (enabled) Tok.T1 else Tok.T4)
        label(name, lx, cy, tx - 8f - vw - 10f - lx, enabled)
        val ty = cy - 6.5f
        val dragged = dragKey === key
        val target = frac.coerceIn(0f, 1f)
        val eased = Ui.anim("opt:sl:" + id(key), target, 300)
        val f = if (dragged) target else eased
        val hx = tx + 1f + (tw - 2f) * f
        val gap = 3f
        val actW = hx - gap - tx
        if (actW > 2.5f) Ui.hbox(tx, ty + 3f, actW, 7f, 3.5f, if (enabled) ON_LEFT else 0x40FFFFFF, if (enabled) ON_RIGHT else 0x30FFFFFF)
        val inaX = hx + gap
        val inaW = tx + tw - inaX
        if (inaW > 2.5f) {
            Ui.box(inaX, ty + 3f, inaW, 7f, 3.5f, if (hovered && enabled) 0x1FFFFFFF else 0x17FFFFFF)
            Ui.box(tx + tw - 4.5f, ty + 5.5f, 2f, 2f, 1f, 0x59FFFFFF)
        }
        val hw = if (dragged) 1f else 2f
        Ui.box(hx - hw / 2f, ty, hw, 13f, hw / 2f, if (enabled) Tok.WHITE else 0x80FFFFFF.toInt())
        if (enabled) hit(tx - 4f, y, tx + tw + 4f, y + ROW_H, top, bottom, {}, { mx ->
            dragKey = key
            set(((mx - tx) / tw).coerceIn(0f, 1f))
        }) {
            dragKey = null
            commit()
        }
    }

    private fun buttonWidth(label: String, icon: String?) = Type.small.width(label) + (if (icon != null) 10f else 0f) + 9f

    /** The ClickGUI's small bordered button, 14 tall; dimmed and dead when not [enabled]. */
    private fun button(x: Float, y: Float, label: String, icon: String?, enabled: Boolean, mx: Float, my: Float, click: () -> Unit) {
        val w = buttonWidth(label, icon)
        val over = enabled && mx >= x && mx <= x + w && my >= y && my <= y + 14f
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        Ui.box(x, y, w, 14f, 4.5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        val c = if (!enabled) Tok.T4 else if (over) Tok.T1 else Tok.T2
        var tx = x + 4.5f
        if (icon != null) {
            Ui.icon(icon, tx, y + 3.5f, 7f, c)
            tx += 10f
        }
        Ui.text(Type.small, label, tx, y + 7f, c)
        if (enabled) next += Hit(x, y, x + w, y + 14f, { b -> if (b == Keys.MOUSE_LEFT) click() })
    }

    // ---- input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toFloat()
        val y = event.y().toFloat()
        val k = waiting
        if (k != null) {
            // Binding a key: a mouse button binds that button.
            k.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()))
            Menus.toggle(true)
            KeyMapping.resetMapping()
            options.save()
            waiting = null
            return true
        }
        val h = hits.lastOrNull { it.contains(x, y) } ?: return false
        if (h.drag != null) {
            if (event.button() != Keys.MOUSE_LEFT) return true
            dragging = h
            h.drag.invoke(x)
        } else {
            h.click(event.button())
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        dragging?.drag?.invoke(event.x().toFloat()) ?: return false
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val d = dragging ?: return false
        dragging = null
        d.release?.invoke()
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val fx = x.toFloat()
        val fy = y.toFloat()
        if (fx >= navX0 && fx < navX1 && fy >= navTop && fy <= navBottom) navTarget -= scrollY.toFloat() * ROW
        else scrollTarget -= scrollY.toFloat() * 28f
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val k = waiting
        if (k != null) {
            k.setKey(if (event.key() == Keys.ESCAPE) InputConstants.UNKNOWN else InputConstants.getKey(event))
            Menus.toggle(event.key() != Keys.ESCAPE)
            KeyMapping.resetMapping()
            options.save()
            waiting = null
            return true
        }
        if (event.key() == Keys.ESCAPE) {
            close()
            return true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun valueLabel(acc: OptionInstanceAccessor<Any?>, value: Any?, caption: String): String {
        val s = try {
            acc.horizon_label().apply(value).string
        } catch (_: RuntimeException) {
            value.toString()
        }
        // Sliders write "Caption: value"; the caption is already on the row.
        return s.removePrefix("$caption: ")
    }

    private fun tooltip(acc: OptionInstanceAccessor<Any?>, value: Any?): String = try {
        (acc.horizon_tooltip().apply(value) as? TooltipAccessor)?.horizon_message()?.string ?: ""
    } catch (_: RuntimeException) {
        ""
    }

    private companion object {
        const val MINECRAFT = "Minecraft"
        const val GENERAL = "General"

        // In GUI units, the ClickGUI's (the mockup's CSS px / 2).
        const val PANEL_W = 560f
        const val PANEL_H = 410f
        /** The sidebar's width, its rows' and section labels' heights, and Done's. */
        const val SIDE = 128f
        const val ROW = 17f
        const val SECTION = 16f
        const val DONE_H = 17f
        /** The page's header above its rows and the foot under them. */
        const val HEAD = 50f
        const val FOOT = 20f
        const val ROW_H = 20f
        /** A slider's track at most. */
        const val SLIDER_W = 150f
        /** How far from their ends the lists fade out. */
        const val FADE = 8f
        val LABEL = 0xDBF5F5F7.toInt()
        /** A switch's track and a slider's filled part, on: white, a little darker to the right. */
        val ON_LEFT = 0xFFF0F0F2.toInt()
        val ON_RIGHT = 0xFFC4C4CA.toInt()
        val KNOB_ON = 0xFF1C1C20.toInt()

        /** Vanilla's buttons for the pages drawn here (the rest become rows on the General page). */
        val OWN_PAGES = setOf(
            "options.video", "options.sounds", "options.controls", "options.chat", "options.accessibility",
            "options.skinCustomisation", "gui.done",
        )

        fun t(key: String): String = Component.translatable(key).string.removeSuffix("...").trimEnd()
    }
}
