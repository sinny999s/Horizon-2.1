package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.compat.SodiumOptions
import dev.horizon.client.config.Storage
import dev.horizon.client.updater.AutoUpdater
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Fps
import dev.horizon.client.gui.Keys
import dev.horizon.client.mixin.AccessibilityOptionsScreenInvoker
import dev.horizon.client.mixin.ChatOptionsScreenInvoker
import dev.horizon.client.mixin.ControlsScreenInvoker
import dev.horizon.client.mixin.MouseSettingsScreenInvoker
import dev.horizon.client.mixin.OptionInstanceAccessor
import dev.horizon.client.mixin.TooltipAccessor
import dev.horizon.client.mixin.VideoSettingsScreenInvoker
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Not in the original: the Options menu in the client's style, over vanilla's (which it wraps, as [ListMenu] does): pages in
 * a sidebar, each a list of rows with switches, value pickers, sliders and key buttons drawn from vanilla's own option
 * values. With Sodium, video settings are Sodium's pages (and those of mods that add pages to Sodium's settings) instead
 * of vanilla's. Vanilla buttons with no page here (online, language, resource packs, difficulty, mods' buttons...) are
 * rows on the General page that press them. Hovering a row shows its tooltip under the list.
 */
class OptionsMenu(val vanilla: OptionsScreen) : Screen(Component.translatable("options.title")), MenuScreen {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val options get() = mc.options

    /** A sidebar entry: a page of rows, a section title, or a page that opens a screen of its own. */
    private class Page(val title: String, val sub: String, val rows: () -> List<Row>, val section: Boolean = false, val open: (() -> Unit)? = null)

    private sealed interface Row
    private class Header(val title: String) : Row
    private class Vanilla(val option: OptionInstance<*>) : Row
    private class Sodium(val option: SodiumOptions.Opt) : Row
    private class Toggle(val title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Row
    private class Link(val title: String, val note: String, val action: () -> Unit) : Row
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
    private var waiting: KeyMapping? = null
    private var hint = ""
    private var scroll = 0f
    private var scrollTarget = 0f
    private val pendingSlides = HashMap<OptionInstance<*>, Any?>()
    /** Each switch's knob position, by what it switches. */
    private val knobs = HashMap<Any, Animation>()
    private val enter = Animation(Easing.OUT_EXPO, 350)
    /** The sidebar highlight's y (below 0 until first placed). */
    private val marker = Animation(Easing.OUT_EXPO, 320).also { it.value = -1f }
    private val selectedAnims = HashMap<Any, Animation>()
    private val hoverAnims = HashMap<Any, Animation>()
    private val pageEnter = Animation(Easing.OUT_EXPO, 280).also { it.value = 1f; it.update(1f) }
    /** Which way the page came in: 1 from below (a page further down the list), -1 from above. */
    private var pageFrom = 1f
    private val done = MenuButton(Component.translatable("gui.done").string, Menus::back) { vanilla.onClose() }
    private val resetKeys = MenuButton(Component.translatable("controls.resetAll").string, { Menus.toggle(false) }) {
        for (k in options.keyMappings) k.setKey(k.defaultKey)
        KeyMapping.resetMapping()
        options.save()
    }
    private val oldMipmaps = options.mipmapLevels().get()
    private val oldAnisotropy = options.maxAnisotropyBit().get()
    private val oldFiltering = options.textureFiltering().get()

    override fun init() {
        vanilla.init(width, height)
        val more = vanilla.children().filterIsInstance<AbstractButton>().filter {
            it.visible && it.message.string.isNotBlank() && (it.message.contents as? TranslatableContents)?.key !in OWN_PAGES
        }
        val list = ArrayList<Page>()
        list += Page(GENERAL, "Field of view, and the rest of the options", {
            buildList {
                add(Vanilla(options.fov()))
                add(Header("Horizon 2.0"))
                add(Toggle("Auto-update", { Storage.shared.autoUpdate }) {
                    Storage.shared.autoUpdate = it
                    Storage.saveShared()
                })
                add(Link("Check for updates", if (AutoUpdater.status.isNotBlank()) AutoUpdater.status else "Current: v${AutoUpdater.currentVersion}") {
                    AutoUpdater.check(manual = true)
                })
                if (more.isNotEmpty()) add(Header("More"))
                for (b in more) add(Link(b.message.string, "") { b.onPress(KeyEvent(InputConstants.KEY_RETURN, 0, 0)) })
            }
        })
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
        })
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
        })
        list += Page(t("options.controls"), "Mouse and movement", {
            buildList {
                add(Header(t("options.mouse_settings")))
                MouseSettingsScreenInvoker.horizon_options(options).forEach { add(Vanilla(it)) }
                add(Header("Movement"))
                ControlsScreenInvoker.horizon_options(options).forEach { add(Vanilla(it)) }
            }
        })
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
        })
        list += Page(t("options.chat"), "Chat visibility, size and safety", { ChatOptionsScreenInvoker.horizon_options(options).map(::Vanilla) })
        list += Page(t("options.accessibility"), "Narrator, contrast, motion and more", {
            // (26.3's own list has the narrator; added only if it's missing.)
            val own = AccessibilityOptionsScreenInvoker.horizon_options(options)
            (if (options.narrator() in own) own.toList() else listOf(options.narrator()) + own).map(::Vanilla)
        })
        list += Page(t("options.skinCustomisation"), "Skin layers and main hand", {
            PlayerModelPart.values().map { part -> Toggle(part.getName().string, { options.isModelPartEnabled(part) }) { options.setModelPart(part, it) } } +
                Vanilla(options.mainHand())
        })
        if (sodium) for (mod in SodiumOptions.mods()) {
            if (mod.pages.isEmpty()) continue
            list += Page(mod.name, "", { emptyList() }, section = true)
            for (p in mod.pages) {
                val open = p.open
                list += if (open != null) {
                    Page(p.name, "", { emptyList() }) { open.accept(this) }
                } else {
                    Page(p.name, mod.name, {
                        buildList {
                            for (g in p.groups) {
                                if (g.name.isNotEmpty()) add(Header(g.name))
                                for (o in g.options) if (!o.hidden && o.kind != SodiumOptions.Kind.OTHER) add(Sodium(o))
                            }
                        }
                    })
                }
            }
        }
        pages = list
        val keep = pages.firstOrNull { it.title == page?.title && !it.section && it.open == null }
        show(keep ?: pages.first())
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
        hint = ""
        enter.update(1f)
        val k = enter.value
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(540f, w - 40f)
        val ph = minOf(400f, h - 40f)
        val px = floor((w - pw) / 2f)
        val py = floor((h - ph) / 2f + (1f - k) * 8f)
        Canvas.alpha = k
        Menus.glass(px, py, pw, ph, 8f, 0xFF383838.toInt(), 0x30FFFFFF)

        // The sidebar: the menu's title, a row per page (sections for Sodium and the mods on its settings), Done at the foot.
        val sx = px + 8f
        val sw = 116f
        Fonts.semibold12.draw(title.string, sx + 6f, py + 12f, Theme.text)
        // Where each row is; the selected one's highlight and marker slide there (the marker stretching on the way).
        val at = ArrayList<Pair<Page, Float>>()
        var sy = py + 34f
        for (p in pages) {
            if (p.section) sy += 5f
            at += p to sy
            sy += if (p.section) 13f else 18f
        }
        at.firstOrNull { it.first === page }?.let { (_, y) ->
            if (marker.value < 0f) marker.value = y
            marker.update(y)
            val my = marker.value
            Menus.rect(sx, my, sw, 17f, 4f, 0x24FFFFFF)
        }
        for ((p, y) in at) {
            if (p.section) {
                Fonts.medium65.draw(p.title.uppercase(), sx + 6f, y + 2f, 0xFF7A7A7A.toInt())
                continue
            }
            val hovered = mouseX >= sx && mouseX < sx + sw && mouseY >= y && mouseY < y + 17f
            if (hovered) Cursor.request(CursorTypes.POINTING_HAND)
            val sel = ease(selectedAnims, p, p === page, 220)
            val hov = ease(hoverAnims, p, hovered && p !== page, 140)
            if (hov > 0.01f) Menus.rect(sx, y, sw, 17f, 4f, Colors.withAlpha(-1, 0.07f * hov))
            val color = Colors.lerp(0xFFA8A8A8.toInt(), Theme.text, maxOf(sel, hov))
            Fonts.medium75.drawMiddle(Menus.fit(p.title, Fonts.medium75, sw - 18f), sx + 10f + 2f * sel, y + 8.5f, color)
            val target = p
            next += Hit(sx, y, sx + sw, y + 17f, { b -> if (b == Keys.MOUSE_LEFT && target !== page) { turnTo(target) } })
        }
        done.draw(sx, py + ph - 8f - 18f, sw, 18f, mouseX, mouseY, Fonts.medium75)
        Menus.rect(sx + sw + 8f, py + 10f, 0.5f, ph - 20f, 0f, 0x18FFFFFF)

        // The page: its title and what's on it, the rows, and the hovered row's description at the foot. A new page fades
        // in, rising (or dropping) the last few pixels the way the sidebar moved.
        val p = page ?: return
        pageEnter.update(1f)
        val pk = pageEnter.value
        Canvas.alpha = k * pk
        Canvas.pose.pushMatrix()
        Canvas.pose.translate(0f, (1f - pk) * 6f * pageFrom)
        val cx = sx + sw + 18f
        val cw = px + pw - 12f - cx
        Fonts.medium95.draw(p.title, cx, py + 11f, Theme.text)
        if (p.sub.isNotEmpty()) Fonts.medium65.draw(Menus.fit(p.sub, Fonts.medium65, cw - 100f), cx, py + 24f, Menus.GREY)
        if (rows.any { it is Key }) {
            resetKeys.active = options.keyMappings.any { !it.isDefault }
            resetKeys.draw(cx + cw - 80f, py + 10f, 80f, 15f, mouseX, mouseY, Fonts.medium65)
        }

        val ly = py + 38f
        val lh = py + ph - 24f - ly
        var content = 0f
        for (r in rows) content += height(r)
        val max = maxOf(0f, content - lh)
        scrollTarget = scrollTarget.coerceIn(0f, max)
        scroll = Fps.smooth(scroll, scrollTarget, 18f)
        if (abs(scroll - scrollTarget) < 0.1f) scroll = scrollTarget
        Canvas.pushScissor(cx - 6f, ly, cx + cw + 2f, ly + lh)
        var y = ly - scroll
        for (r in rows) {
            val rh = height(r)
            if (y + rh >= ly && y <= ly + lh) {
                val inList = mouseY >= ly && mouseY <= ly + lh
                drawRow(r, cx - 6f, y, cw + 6f, rh, mouseX, if (inList) mouseY else -1f, ly, ly + lh)
            }
            y += rh
        }
        Canvas.popScissor()
        if (max > 0f) {
            val barH = maxOf(16f, lh * lh / content)
            Menus.rect(cx + cw + 3f, ly + (lh - barH) * (scroll / max), 1.5f, barH, 0.75f, 0x40FFFFFF)
        }
        Canvas.pose.popMatrix()
        Canvas.alpha = k
        Menus.rect(cx, py + ph - 21f, cw, 0.5f, 0f, 0x14FFFFFF)
        if (hint.isNotEmpty()) Fonts.medium65.draw(Menus.fit(hint, Fonts.medium65, cw), cx, py + ph - 15f, Menus.GREY)
        Canvas.alpha = 1f
        hits = next
    }

    private fun height(r: Row) = when (r) {
        is Header -> 20f
        else -> 21f
    }

    /** A control's hit area, only where the list shows it. */
    private fun hit(x0: Float, y0: Float, x1: Float, y1: Float, top: Float, bottom: Float, click: (Int) -> Unit, drag: ((Float) -> Unit)? = null, release: (() -> Unit)? = null) {
        val a = maxOf(y0, top)
        val b = minOf(y1, bottom)
        if (b > a) next += Hit(x0, a, x1, b, click, drag, release)
    }

    private fun drawRow(r: Row, x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, top: Float, bottom: Float) {
        if (r is Header) {
            Fonts.medium65.draw(r.title.uppercase(), x + 6f, y + 9f, 0xFF7A7A7A.toInt())
            return
        }
        val hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + h - 1f
        if (hovered) Menus.rect(x, y, w, h - 1f, 4f, 0x16FFFFFF)
        val cw = minOf(150f, w * 0.5f)
        val ctrlX = x + w - 6f - cw
        val cy = y + (h - 1f) / 2f
        when (r) {
            is Vanilla -> vanillaRow(r.option, x, y, w, cw, ctrlX, cy, hovered, top, bottom)
            is Sodium -> sodiumRow(r.option, x, y, w, cw, ctrlX, cy, hovered, top, bottom)
            is Toggle -> {
                label(r.title, x, cy, w - cw - 18f, true)
                val on = r.get()
                switch(r, x + w - 6f - 24f, cy, on, true)
                hit(ctrlX, y, x + w, y + h, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { r.set(!on); options.save(); Menus.toggle(!on) } })
            }
            is Link -> {
                label(r.title.removeSuffix("...").trimEnd(), x, cy, w - 30f, true)
                Fonts.medium75.drawSymbolCentered(">", x + w - 12f + if (hovered) 2f else 0f, cy, if (hovered) Menus.ACCENT else Menus.GREY)
                if (hovered) Cursor.request(CursorTypes.POINTING_HAND)
                hit(x, y, x + w, y + h, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { Menus.open(); r.action() } })
            }
            is Key -> keyRow(r.mapping, x, y, w, cw, cy, top, bottom, mouseX, mouseY)
            is Header -> {}
        }
    }

    /** A row's label, vertically centred on [cy]. */
    private fun label(text: String, x: Float, cy: Float, max: Float, enabled: Boolean) {
        Fonts.medium75.drawMiddle(Menus.fit(text, Fonts.medium75, max), x + 6f, cy, if (enabled) 0xFFE0E0E0.toInt() else 0xFF707070.toInt())
    }

    @Suppress("UNCHECKED_CAST")
    private fun vanillaRow(o: OptionInstance<*>, x: Float, y: Float, w: Float, cw: Float, ctrlX: Float, cy: Float, hovered: Boolean, top: Float, bottom: Float) {
        val opt = o as OptionInstance<Any?>
        val acc = o as Any as OptionInstanceAccessor<Any?>
        val caption = acc.horizon_caption().string
        val value = if (pendingSlides.containsKey(o)) pendingSlides[o] else opt.get()
        label(caption, x, cy, w - cw - 18f, true)
        if (hovered) hint = tooltip(acc, value)
        val vs = opt.values()
        val cycles = vs is OptionInstance.CycleableValueSet<*> && !(vs is OptionInstance.SliderableOrCyclableValueSet<*> && !vs.createCycleButton())
        if (cycles) {
            val cyc = vs as OptionInstance.CycleableValueSet<Any?>
            val list = cyc.valueListSupplier().selectedList
            if (list.size == 2 && true in list && false in list) {
                val on = value == true
                switch(o, x + w - 6f - 24f, cy, on, true)
                hit(ctrlX, y, x + w, y + 24f, top, bottom, { b ->
                    if (b == Keys.MOUSE_LEFT) {
                        cyc.valueSetter().set(opt, !on)
                        options.save()
                        Menus.toggle(!on)
                    }
                })
            } else {
                cycle(ctrlX, cy, cw, valueLabel(acc, value, caption), true, top, bottom) { d ->
                    if (list.isNotEmpty()) {
                        val i = maxOf(0, list.indexOf(opt.get()))
                        cyc.valueSetter().set(opt, list[(i + d + list.size) % list.size])
                        options.save()
                    }
                }
            }
        } else if (vs is OptionInstance.SliderableValueSet<*>) {
            val sl = vs as OptionInstance.SliderableValueSet<Any?>
            slider(ctrlX, cy, cw, sl.toSliderValue(value).toFloat(), valueLabel(acc, value, caption), true, top, bottom, { t ->
                val v = sl.fromSliderValue(t.toDouble())
                if (sl.applyValueImmediately()) opt.set(v) else pendingSlides[o] = v
            }) {
                if (pendingSlides.containsKey(o)) opt.set(pendingSlides.remove(o))
                options.save()
            }
        }
    }

    private fun sodiumRow(o: SodiumOptions.Opt, x: Float, y: Float, w: Float, cw: Float, ctrlX: Float, cy: Float, hovered: Boolean, top: Float, bottom: Float) {
        val enabled = o.enabled
        label(o.name, x, cy, w - cw - 18f, enabled)
        if (hovered) hint = listOf(o.tooltip, o.impact.takeIf { it.isNotEmpty() }?.let { "Performance impact: $it" } ?: "").filter { it.isNotEmpty() }.joinToString("  -  ")
        when (o.kind) {
            SodiumOptions.Kind.BOOLEAN -> {
                val on = o.value == true
                switch(o, x + w - 6f - 24f, cy, on, enabled)
                if (enabled) hit(ctrlX, y, x + w, y + 24f, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { o.set(!on); Menus.toggle(!on) } })
            }
            SodiumOptions.Kind.INTEGER -> {
                val (min, max, step) = o.range
                val v = o.value as? Int ?: min
                val span = maxOf(1, max - min)
                slider(ctrlX, cy, cw, (v - min).toFloat() / span, o.format(v), enabled, top, bottom, { t ->
                    val nv = (min + ((t * span) / step).roundToInt() * step).coerceIn(min, max)
                    if (nv != o.value) o.set(nv)
                }) {}
            }
            SodiumOptions.Kind.ENUM -> {
                val values = o.values
                cycle(ctrlX, cy, cw, o.valueName(o.value), enabled, top, bottom) { d ->
                    if (values.isNotEmpty()) {
                        val i = maxOf(0, values.indexOf(o.value))
                        o.set(values[(i + d + values.size) % values.size])
                    }
                }
            }
            SodiumOptions.Kind.BUTTON -> {
                val open = o.open
                Fonts.medium75.drawSymbolCentered(">", x + w - 12f + if (hovered) 2f else 0f, cy, if (hovered) Menus.ACCENT else Menus.GREY)
                if (enabled && open != null) hit(x, y, x + w, y + 24f, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { Menus.open(); open.accept(this) } })
            }
            SodiumOptions.Kind.OTHER -> {}
        }
    }

    private fun keyRow(k: KeyMapping, x: Float, y: Float, w: Float, cw: Float, cy: Float, top: Float, bottom: Float, mouseX: Float, mouseY: Float) {
        label(Component.translatable(k.name).string, x, cy, w - cw - 18f, true)
        val bw = 96f
        val bx = x + w - 6f - bw
        val by = cy - 7f
        val conflict = !k.isUnbound && options.keyMappings.any { it !== k && it.same(k) }
        val text = when {
            waiting === k -> "> press a key <"
            else -> k.translatedKeyMessage.string
        }
        val over = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 14f
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        Menus.glass(bx, by, bw, 14f, 4f, if (over) 0xFF5A5A5A.toInt() else 0xFF3A3A3A.toInt(), if (waiting === k) Menus.OUTLINE else 0x30FFFFFF)
        val color = when {
            waiting === k -> Menus.ACCENT
            conflict -> 0xFFF87171.toInt()
            k.isDefault -> 0xFFE0E0E0.toInt()
            else -> Theme.text
        }
        Fonts.medium75.drawMiddleCentered(Menus.fit(text, Fonts.medium75, bw - 8f), bx + bw / 2f, cy, color)
        if (!k.isDefault && waiting !== k) Fonts.medium65.draw("*", bx - 8f, cy - 3f, Menus.FILL)
        hit(bx, by, bx + bw, by + 14f, top, bottom, { b ->
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

    /** How far [key]'s animation in [map] is towards [on] (0..1), over [ms]. */
    private fun ease(map: HashMap<Any, Animation>, key: Any, on: Boolean, ms: Long): Float {
        val target = if (on) 1f else 0f
        val a = map.getOrPut(key) { Animation(Easing.OUT_CUBIC, ms).also { it.value = target; it.update(target) } }
        a.update(target)
        return a.value
    }

    /** An on/off switch for [key] whose track starts at [x], centred on [cy]; the knob slides across when it flips. */
    private fun switch(key: Any, x: Float, cy: Float, on: Boolean, enabled: Boolean) {
        val target = if (on) 1f else 0f
        val anim = knobs.getOrPut(key) { Animation(Easing.OUT_CUBIC, 160).also { it.value = target; it.update(target) } }
        anim.update(target)
        val t = anim.value
        // On: a light track with a dark knob (the menus have no colour to fill it with).
        val track = Colors.lerp(0x30FFFFFF, Menus.FILL, t)
        Menus.rect(x, cy - 6f, 24f, 12f, 6f, if (enabled) track else Colors.withAlpha(track, Colors.alphaF(track) * 0.4f))
        val knob = Colors.lerp(0xFFB4B4B4.toInt(), 0xFF1C1C20.toInt(), t)
        Menus.rect(x + 2f + 12f * t, cy - 4f, 8f, 8f, 4f, if (enabled) knob else 0x80FFFFFF.toInt())
    }

    /** A value with arrows either side: the left half steps back, the right half forward. */
    private fun cycle(x: Float, cy: Float, w: Float, value: String, enabled: Boolean, top: Float, bottom: Float, step: (Int) -> Unit) {
        Menus.glass(x, cy - 7f, w, 14f, 4f, 0xFF3A3A3A.toInt(), 0x30FFFFFF)
        val color = if (enabled) 0xFFE0E0E0.toInt() else 0xFF707070.toInt()
        Fonts.medium75.drawSymbolCentered("<", x + 9f, cy, Menus.GREY)
        Fonts.medium75.drawSymbolCentered(">", x + w - 9f, cy, Menus.GREY)
        Fonts.medium75.drawMiddleCentered(Menus.fit(value, Fonts.medium75, w - 28f), x + w / 2f, cy, color)
        if (!enabled) return
        hit(x, cy - 10f, x + w / 2f, cy + 10f, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { step(-1); Menus.step(false) } else if (b == Keys.MOUSE_RIGHT) { step(1); Menus.step(true) } })
        hit(x + w / 2f, cy - 10f, x + w, cy + 10f, top, bottom, { b -> if (b == Keys.MOUSE_LEFT) { step(1); Menus.step(true) } else if (b == Keys.MOUSE_RIGHT) { step(-1); Menus.step(false) } })
    }

    /** A slider ([t] 0..1) with its value written left of the track; dragging calls [set], letting go [commit]. */
    private fun slider(x: Float, cy: Float, w: Float, t: Float, value: String, enabled: Boolean, top: Float, bottom: Float, set: (Float) -> Unit, commit: () -> Unit) {
        val vw = 46f
        val tx = x + vw
        val tw = w - vw - 6f
        val k = t.coerceIn(0f, 1f)
        Fonts.medium75.drawMiddle(Menus.fit(value, Fonts.medium75, vw - 4f), x, cy, if (enabled) 0xFFE0E0E0.toInt() else 0xFF707070.toInt())
        Menus.rect(tx, cy - 1.5f, tw, 3f, 1.5f, 0x40FFFFFF)
        Menus.rect(tx, cy - 1.5f, tw * k, 3f, 1.5f, if (enabled) Menus.FILL else 0x60FFFFFF)
        Menus.rect(tx + tw * k - 4f, cy - 4f, 8f, 8f, 4f, if (enabled) -1 else 0x80FFFFFF.toInt())
        if (!enabled) return
        val toT = { mx: Float -> ((mx - tx) / tw).coerceIn(0f, 1f) }
        hit(tx - 5f, cy - 8f, tx + tw + 5f, cy + 8f, top, bottom, {}, { mx -> set(toT(mx)) }, commit)
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
        if (event.button() == Keys.MOUSE_LEFT && (done.click(event) || (rows.any { it is Key } && resetKeys.click(event)))) return true
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
        scrollTarget -= scrollY.toFloat() * 28f
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
            Menus.back()
            vanilla.onClose()
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
        val GENERAL = "General"

        /** Vanilla's buttons for the pages drawn here (the rest become rows on the General page). */
        val OWN_PAGES = setOf(
            "options.video", "options.sounds", "options.controls", "options.chat", "options.accessibility",
            "options.skinCustomisation", "gui.done",
        )

        fun t(key: String): String = Component.translatable(key).string.removeSuffix("...").trimEnd()
    }
}
