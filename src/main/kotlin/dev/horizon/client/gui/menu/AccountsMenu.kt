package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.account.AccountHeads
import dev.horizon.client.account.Accounts
import dev.horizon.client.account.LauncherBridge
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Fps
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.ModernGui
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Type
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The title screen's Accounts: every account the game can play as ([Accounts]), one a row with its face, its name and
 * where its sign-in is kept (the Horizon launcher, or the game itself). A click plays as that account from then on,
 * with no restart; Add account signs another Microsoft account in, in the browser; an account signed in from the game
 * has a bin that asks once more before it forgets it.
 *
 * It's drawn as the Options menu is: the ClickGUI's glass window and parts, in white instead of the theme's colours.
 * The window is as tall as its rows (four to seven of them, scrolling beyond that), and a sign-in takes the rows' place
 * while it is under way.
 */
class AccountsMenu(private val parent: Screen) : Screen(Component.literal("Accounts")), MenuScreen {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** Where a click goes, from the last frame; the last one drawn is on top. */
    private class Hit(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val click: () -> Unit) {
        fun contains(x: Float, y: Float) = x >= x0 && x < x1 && y >= y0 && y < y1
    }

    private var hits = ArrayList<Hit>()
    private var next = ArrayList<Hit>()
    private val enter = Animation(Easing.OUT_EXPO, 350)
    private var openedAt = 0L
    private var scroll = 0f
    private var scrollTarget = 0f
    /** The rows' area: its height eases as accounts come and go, and the wheel scrolls it. */
    private var listH = -1f
    private var listTop = 0f
    private var panelY = 0f
    private var panelH = 0f
    private val hover = HashMap<UUID, Animation>()
    /** 0 for the rows, 1 for a sign-in over them. */
    private val signing = Animation(Easing.OUT_EXPO, 320)
    /** The sign-in last shown, so it fades out with what it said. */
    private var shown: Accounts.SignIn? = null
    /** The account whose bin was clicked once: it asks, for [ARMED_MS], before it removes. */
    private var armed: UUID? = null
    private var armedAt = 0L
    private var copiedAt = 0L
    private var lastStatus: Accounts.Status? = null

    /** The window's glass, for fading the ends of the list into it. */
    private val backdrop = Ui.Backdrop(null, { y -> Ui.vboxAt(panelY, panelH, tint(true), tint(false), y) })

    override fun added() {
        enter.reset(0f)
        openedAt = System.currentTimeMillis()
        Accounts.open()
        lastStatus = Accounts.status
    }

    // A sign-in waits on this screen: leaving ends it.
    override fun removed() = Accounts.cancelSignIn()

    override fun onClose() {
        mc.gui.setScreen(parent)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractPanorama(graphics, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    // ---- drawing ----

    override fun draw(mouseX: Float, mouseY: Float) {
        next = ArrayList()
        enter.update(1f)
        val k = enter.value
        val entries = Accounts.entries
        val signIn = Accounts.signIn
        if (signIn != null) shown = signIn
        signing.update(if (signIn != null) 1f else 0f)
        val sk = signing.value

        // The sound of what just happened: switched or added, or it didn't work.
        val status = Accounts.status
        if (status !== lastStatus) {
            lastStatus = status
            when (status?.kind) {
                Accounts.Status.Kind.DONE -> Menus.toggle(true)
                Accounts.Status.Kind.ERROR -> Menus.toggle(false)
                else -> {}
            }
        }
        if (armed != null && System.currentTimeMillis() - armedAt > ARMED_MS) armed = null

        val want = entries.size.coerceIn(MIN_ROWS, MAX_ROWS) * SLOT - ROW_GAP + 2f * LIST_PAD
        listH = if (listH < 0f) want else Fps.smooth(listH, want, 16f).let { if (abs(it - want) < 0.1f) want else it }
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(PANEL_W, w - 32f)
        val ph = HEAD + listH + FOOT
        val px = floor((w - pw) / 2f)
        val py = floor(maxOf(8f, (h - ph) / 2f) + (1f - k) * 8f)
        panelY = py
        panelH = ph

        Canvas.alpha = k
        window(px, py, pw, ph)
        header(px, py, pw, mouseX, mouseY)
        val top = py + HEAD
        val bottom = top + listH
        Ui.box(px, top, pw, 0.5f, 0f, Tok.LINE)
        Ui.box(px, bottom, pw, 0.5f, 0f, Tok.LINE)
        listTop = top
        if (sk < 0.995f) {
            Canvas.alpha = k * (1f - sk)
            list(entries, px, top, pw, listH, mouseX, mouseY, signIn == null)
        }
        if (sk > 0.005f) shown?.let {
            Canvas.alpha = k * sk
            signInPane(it, px, top + (1f - sk) * 6f, pw, listH, mouseX, mouseY, it === signIn)
        }
        Canvas.alpha = k
        foot(entries, px, bottom, pw, mouseX, mouseY)
        Canvas.alpha = 1f
        hits = next
    }

    /** The glass, as the ClickGUI's window: its shadow, the blurred backdrop under a grey tint, a hairline round it. */
    private fun window(px: Float, py: Float, pw: Float, ph: Float) {
        Ui.glow(px + 15f, py + 40f, pw - 30f, ph - 30f, 11f, 40f, 0x8C000000.toInt())
        Ui.box(px - 0.5f, py - 0.5f, pw + 1f, ph + 1f, 11.5f, 0, 0.5f, 0x59000000)
        Ui.blur(px, py, pw, ph, 11f)
        Ui.vbox(px, py, pw, ph, 11f, tint(true), tint(false), 0.5f, 0x17FFFFFF)
        Ui.insetTop(px, py, pw, 11f, 0x12FFFFFF)
    }

    /** The glass's tint at its top or bottom: the ClickGUI's, without the theme's hue, as opaque as its window. */
    private fun tint(top: Boolean): Int {
        val g = Modules.get<ClickGuiModule>().opacity.value
        return if (top) Colors.withAlpha(0xFF161618.toInt(), g) else Colors.withAlpha(0xFF0B0B0D.toInt(), g + 0.06f)
    }

    /** The title, who the game is playing as, and Add account. */
    private fun header(px: Float, py: Float, pw: Float, mx: Float, my: Float) {
        val lx = px + 14f
        Ui.text(Type.h2, "Accounts", lx, py + 17f, Tok.T1)
        val label = "Playing as "
        Ui.text(Type.desc, label, lx, py + 30.5f, Tok.T3)
        val bw = buttonWidth(ADD, "plus")
        val nx = lx + Type.desc.width(label)
        Ui.text(Type.desc, Ui.fit(Type.desc, mc.user.name, px + pw - 14f - bw - 10f - nx), nx, py + 30.5f, Tok.T2)
        val free = Accounts.signIn == null && Accounts.busy == null
        button(px + pw - 14f - bw, py + 15f, ADD, "plus", free, mx, my) {
            Menus.open()
            Accounts.add()
        }
    }

    /** The accounts: they scroll once there are more than fit, fading into the glass at the ends. */
    private fun list(entries: List<Accounts.Entry>, px: Float, top: Float, pw: Float, lh: Float, mx: Float, my: Float, live: Boolean) {
        val content = entries.size * SLOT - ROW_GAP + 2f * LIST_PAD
        val max = maxOf(0f, content - lh)
        scrollTarget = scrollTarget.coerceIn(0f, max)
        scroll = Fps.smooth(scroll, scrollTarget, 18f)
        if (abs(scroll - scrollTarget) < 0.1f) scroll = scrollTarget
        val a = Canvas.alpha
        val inList = live && my >= top && my < top + lh
        Ui.pushClip(px, top, pw, lh)
        var y = top + LIST_PAD - scroll
        for ((i, e) in entries.withIndex()) {
            if (y + ROW_H >= top && y <= top + lh) {
                // Each row comes in a little after the one above it.
                val ek = entrance(i)
                Canvas.alpha = a * ek
                row(e, px + 7f, y + (1f - ek) * 5f, pw - 14f, mx, if (inList) my else -1f, top, top + lh, live)
            }
            y += SLOT
        }
        Canvas.alpha = a
        // With room to spare, what this is for.
        if (entries.size <= 2) {
            val free = top + lh - y
            if (free > 24f) Ui.textC(Type.small, "Add a Microsoft account to switch to it without a restart", px + pw / 2f, y + (free - LIST_PAD) / 2f, Tok.T4)
        }
        Ui.popClip()
        Ui.fade(backdrop, px + 0.5f, top, pw - 1f, FADE, false, scroll / FADE)
        Ui.fade(backdrop, px + 0.5f, top + lh, pw - 1f, FADE, true, (max - scroll) / FADE)
        if (max > 0f) ModernGui.scrollbar(px + pw - 5f, top + 3f, lh - 6f, scroll, max)
    }

    /** How far row [i] is into its entrance (0..1) since the screen opened. */
    private fun entrance(i: Int): Float {
        val t = (System.currentTimeMillis() - openedAt - 60L - i * 28L) / 380.0
        return Easing.OUT_EXPO.apply(t.coerceIn(0.0, 1.0)).toFloat()
    }

    /**
     * One account: its face, its name over where its sign-in is kept, and at the right what a click does (or that it's
     * the one being played as). The mouse is ([mx], [my]), off the list when [my] is below 0.
     */
    private fun row(e: Accounts.Entry, x: Float, y: Float, w: Float, mx: Float, my: Float, top: Float, bottom: Float, live: Boolean) {
        val now = System.currentTimeMillis()
        val active = e.uuid == Accounts.active
        val busy = Accounts.busy == e.uuid
        // The launcher's own account, signed out: only the launcher can sign it in again (a click asks it once more).
        val dead = e.signedOut && e.source == Accounts.Source.LAUNCHER && !e.saved
        val can = live && Accounts.canSwitch(e)
        val over = live && my >= 0f && mx >= x && mx < x + w && my >= y && my < y + ROW_H
        val asking = armed == e.uuid
        val hk = hover.getOrPut(e.uuid) { Animation(Easing.OUT_EXPO, 260) }.also { it.update(if (over && (can || asking)) 1f else 0f) }.value
        if (over && can && !asking) Cursor.request(CursorTypes.POINTING_HAND)
        val a = Canvas.alpha
        // The row's own click, under the ones for what is on it (its bin), which are added after.
        hit(x, y, x + w, y + ROW_H, top, bottom) {
            when {
                armed != null -> armed = null
                can -> {
                    Menus.open()
                    Accounts.switchTo(e)
                }
            }
        }

        // The account being played as sits on the ClickGUI's selected pill; a hovered one lightens.
        if (active) Ui.hbox(x, y, w, ROW_H, 6f, 0x1AFFFFFF, 0x0BFFFFFF, 0.5f, 0x14FFFFFF)
        if (hk > 0.005f && !active) {
            Canvas.alpha = a * hk
            Ui.box(x, y, w, ROW_H, 6f, Tok.FILL, 0.5f, Tok.LINE)
            Canvas.alpha = a
        }
        // Just switched to or added: a light that dies away.
        Accounts.flash?.takeIf { it.first == e.uuid }?.let { (_, at) ->
            val t = (now - at) / FLASH_MS.toFloat()
            if (t in 0f..1f) Ui.box(x, y, w, ROW_H, 6f, Colors.withAlpha(Tok.WHITE, 0.10f * (1f - t) * (1f - t)))
        }

        val hx = x + 7f
        val hy = y + (ROW_H - HEAD_SIZE) / 2f
        val face = AccountHeads.find(e.uuid, e.skin)
        if (face != null) {
            face(face, hx, hy, Canvas.alpha * (if (dead) 0.45f else 1f))
            Ui.box(hx, hy, HEAD_SIZE, HEAD_SIZE, 4f, 0, 0.5f, 0x1FFFFFFF)
        } else {
            Ui.box(hx, hy, HEAD_SIZE, HEAD_SIZE, 4f, Tok.FILL2, 0.5f, Tok.LINE)
            Ui.icon("player", hx + (HEAD_SIZE - 8f) / 2f, hy + (HEAD_SIZE - 8f) / 2f, 8f, Tok.T3)
        }

        // The right side first: the name gets what is left.
        var right = x + w - 9f
        val cy = y + ROW_H / 2f
        if (Accounts.canRemove(e) && live) {
            if (asking) {
                val label = "Remove?"
                val bw = Type.small.width(label) + 10f
                val bx = right - bw + 2f
                val on = over && mx >= bx
                if (on) Cursor.request(CursorTypes.POINTING_HAND)
                Ui.box(bx, cy - 7f, bw, 14f, 4.5f, Colors.withAlpha(ERROR, if (on) 0.22f else 0.13f), 0.5f, Colors.withAlpha(ERROR, if (on) 0.6f else 0.38f))
                Ui.textC(Type.small, label, bx + bw / 2f, cy, if (on) Tok.T1 else ERROR)
                hit(bx, cy - 7f, bx + bw, cy + 7f, top, bottom) {
                    armed = null
                    Accounts.remove(e)
                }
                right = bx - 7f
            } else {
                val bx = right - 12f
                val on = over && mx >= bx - 2f
                if (hk > 0.005f || on) {
                    if (on) Cursor.request(CursorTypes.POINTING_HAND)
                    if (on) Ui.box(bx - 1f, cy - 7f, 14f, 14f, 4.5f, Tok.FILL2, 0.5f, Tok.LINE2)
                    Ui.icon("trash", bx + 2f, cy - 4f, 8f, Colors.withAlpha(if (on) Tok.T1 else Tok.T3, Colors.alphaF(if (on) Tok.T1 else Tok.T3) * maxOf(hk, if (on) 1f else 0f)))
                }
                // (The slot is kept whether or not the bin shows, so nothing moves when it does.)
                hit(bx - 2f, y, x + w, y + ROW_H, top, bottom) {
                    Menus.open()
                    armed = e.uuid
                    armedAt = System.currentTimeMillis()
                }
                right = bx - 8f
            }
        }
        if (!asking) right = state(e, right, cy, active, busy, dead, hk)

        val tx = hx + HEAD_SIZE + 8f
        val room = right - 8f - tx
        Ui.text(Type.name, Ui.fit(Type.name, e.name, room), tx, y + 11f, if (dead) Tok.T3 else Tok.T1)
        Ui.text(Type.small, Ui.fit(Type.small, where(e), room), tx, y + 20.5f, Tok.T3)
    }

    /**
     * A face ([AccountHeads]) with rounded corners, each of its pixels a whole number of screen pixels. The rounded
     * texture shape spreads its picture over a quad 3 units larger than the size asked for, starting a unit before it,
     * and softens its edge over 2 units: drawn in half screen pixels, with the size that makes that quad the face's.
     */
    private fun face(texture: GpuTextureView, x: Float, y: Float, opacity: Float) {
        val k = mc.window.guiScale * 2f
        Canvas.pose.pushMatrix()
        Canvas.pose.scale(1f / k, 1f / k)
        Canvas.texturedRect(texture, x * k + 1f, y * k + 1f, HEAD_SIZE * k - 3f, HEAD_SIZE * k - 3f, 4f * k, 0f, 0f, opacity, -1, 0)
        Canvas.pose.popMatrix()
    }

    /** The row's state at its right end, right-aligned to [right]: returns where it starts. */
    private fun state(e: Accounts.Entry, right: Float, cy: Float, active: Boolean, busy: Boolean, dead: Boolean, hk: Float): Float {
        val font = Type.small
        when {
            busy -> {
                val label = "Switching"
                val x = right - font.width(label)
                Ui.text(font, label, x, cy, Tok.T2)
                dots(x - 15f, cy)
                return x - 15f
            }
            active -> {
                val label = "Active"
                val x = right - font.width(label)
                Ui.text(font, label, x, cy, Tok.T1)
                Ui.icon("check", x - 10f, cy - 3.5f, 7f, Tok.T1)
                return x - 10f
            }
            dead && hk <= 0.005f -> {
                val label = "Signed out"
                val x = right - font.width(label)
                Ui.text(font, label, x, cy, Tok.T4)
                return x
            }
            e.signedOut || hk > 0.005f -> {
                // What a click does; an account that needs its sign-in again says so without being hovered.
                val label = if (dead) "Try again" else if (e.signedOut) "Sign in again" else "Switch"
                val shownK = if (e.signedOut && !dead) 1f else hk
                val cx = right - 6f - 2f * (1f - hk)
                val x = right - 6f - 5f - font.width(label)
                val a = Canvas.alpha
                Canvas.alpha = a * shownK
                Ui.text(font, label, x, cy, Colors.lerp(Tok.T2, Tok.T1, hk))
                Ui.icon("chev", cx, cy - 3f, 6f, Colors.lerp(Tok.T3, Tok.T2, hk))
                Canvas.alpha = a
                return x
            }
        }
        return right
    }

    /** Where an account's sign-in is kept, under its name. */
    private fun where(e: Accounts.Entry): String = when {
        e.signedOut && e.source == Accounts.Source.LAUNCHER && !e.saved -> "Sign in again in the Horizon launcher"
        e.signedOut -> "Its sign-in ran out"
        e.source == Accounts.Source.LAUNCHER -> "Horizon launcher"
        e.source == Accounts.Source.SAVED -> "Signed in here"
        else -> "This session only"
    }

    /** Three dots lighting up one after another, for something under way. */
    private fun dots(x: Float, cy: Float) {
        val t = System.currentTimeMillis() % 1200L / 1200f
        for (i in 0 until 3) {
            val p = sin((t - i * 0.16f) * 2f * Math.PI.toFloat())
            Ui.box(x + i * 4f, cy - 1f, 2f, 2f, 1f, Colors.withAlpha(Tok.WHITE, 0.25f + 0.6f * maxOf(0f, p)))
        }
    }

    /**
     * A sign-in in the rows' place: that the browser has Microsoft's page, then what is being done with what it came
     * back with, or why it didn't work. [live] is false while it fades out.
     */
    private fun signInPane(s: Accounts.SignIn, px: Float, top: Float, pw: Float, lh: Float, mx: Float, my: Float, live: Boolean) {
        val error = s.error
        val step = s.step
        val title = when {
            error != null -> "The sign-in didn't work"
            step.isNotEmpty() -> step
            else -> "Sign in with Microsoft"
        }
        val text = when {
            error != null -> error
            step.isNotEmpty() -> "Nearly there."
            else -> "Microsoft's sign-in page is open in your browser. Pick the account there and you'll be brought back here."
        }
        val lines = Ui.wrap(Type.desc, text, pw - 90f, 3)
        val buttons: List<Pair<String, () -> Unit>> = when {
            error != null -> listOf("Try again" to {
                Menus.open()
                Accounts.cancelSignIn()
                Accounts.add()
            }, "Back" to {
                Menus.back()
                Accounts.cancelSignIn()
            })
            step.isNotEmpty() -> emptyList()
            else -> listOf("Open again" to {
                Menus.open()
                Accounts.reopen()
            }, (if (System.currentTimeMillis() - copiedAt < 1600L) "Copied" else "Copy link") to {
                Menus.open()
                mc.keyboardHandler.clipboard = s.url
                copiedAt = System.currentTimeMillis()
            }, "Cancel" to {
                Menus.back()
                Accounts.cancelSignIn()
            })
        }
        val bar = error == null
        val content = 26f + 10f + 9f + 6f + lines.size * 9f + (if (bar) 13f else 0f) + (if (buttons.isNotEmpty()) 12f + 14f else 0f)
        val cx = px + pw / 2f
        var y = floor(top + (lh - content) / 2f)

        Ui.box(cx - 13f, y, 26f, 26f, 8f, Tok.FILL2, 0.5f, Tok.LINE2)
        Ui.icon(if (error != null) "info" else "globe", cx - 5.5f, y + 7.5f, 11f, if (error != null) ERROR else Tok.T1)
        y += 26f + 10f
        Ui.textC(Type.name, title, cx, y + 4f, Tok.T1)
        y += 9f + 6f
        for (l in lines) {
            Ui.textC(Type.desc, l, cx, y + 4f, if (error != null) Tok.T2 else Tok.T3)
            y += 9f
        }
        if (bar) {
            sweep(cx - 50f, y + 6f, 100f)
            y += 13f
        }
        if (buttons.isNotEmpty()) {
            y += 12f
            val gap = 5f
            val total = buttons.sumOf { buttonWidth(it.first, null).toDouble() }.toFloat() + gap * (buttons.size - 1)
            var bx = floor(cx - total / 2f)
            for ((label, click) in buttons) {
                button(bx, y, label, null, live, if (live) mx else -1f, my, click)
                bx += buttonWidth(label, null) + gap
            }
        }
    }

    /** The indeterminate progress: a faint track with a short bar sweeping along it, easing in and out. */
    private fun sweep(x: Float, y: Float, w: Float) {
        Ui.box(x, y, w, 2f, 1f, 0x1FFFFFFF)
        val t = (System.currentTimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        val eased = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        val bar = w * 0.28f
        val from = -bar + (w + bar) * eased
        val left = maxOf(0f, from)
        val right = minOf(w, from + bar)
        if (right - left >= 3f) Ui.box(x + left, y, right - left, 2f, 1f, Menus.FILL)
    }

    /** The foot: what just happened (or what the hovered row is), and Done. */
    private fun foot(entries: List<Accounts.Entry>, px: Float, top: Float, pw: Float, mx: Float, my: Float) {
        val cy = top + 0.5f + FOOT / 2f
        val bw = buttonWidth(DONE, null)
        button(px + pw - 14f - bw, cy - 7f, DONE, null, true, mx, my) { close() }
        val lx = px + 14f
        val room = px + pw - 14f - bw - 10f - lx
        val status = Accounts.status
        when {
            status != null -> {
                val c = if (status.kind == Accounts.Status.Kind.ERROR) ERROR else Tok.T2
                Ui.icon(if (status.kind == Accounts.Status.Kind.DONE) "check" else "info", lx, cy - 3.5f, 7f, c)
                Ui.text(Type.status, Ui.fit(Type.status, status.text, room - 11f), lx + 11f, cy, c)
            }
            Accounts.loading -> Ui.text(Type.status, "Asking the launcher for its accounts" + Menus.dots(), lx, cy, Tok.T3)
            else -> Ui.text(Type.status, Ui.fit(Type.status, summary(entries), room), lx, cy, Tok.T3)
        }
    }

    private fun summary(entries: List<Accounts.Entry>): String {
        val n = entries.size
        val count = if (n == 1) "1 account" else "$n accounts"
        return if (LauncherBridge.available && entries.any { it.source == Accounts.Source.LAUNCHER }) "$count  ·  shared with the Horizon launcher" else count
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
        if (enabled) next += Hit(x, y, x + w, y + 14f, click)
    }

    /** A click target inside the list, cut to what shows of it between [top] and [bottom]. */
    private fun hit(x0: Float, y0: Float, x1: Float, y1: Float, top: Float, bottom: Float, click: () -> Unit) {
        val a = maxOf(y0, top)
        val b = minOf(y1, bottom)
        if (b > a) next += Hit(x0, a, x1, b, click)
    }

    // ---- input ----

    private fun close() {
        Menus.back()
        onClose()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != Keys.MOUSE_LEFT) return false
        val x = event.x().toFloat()
        val y = event.y().toFloat()
        val h = hits.lastOrNull { it.contains(x, y) }
        if (h == null) {
            armed = null
            return false
        }
        h.click()
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (Accounts.signIn == null) scrollTarget -= scrollY.toFloat() * SLOT
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() != Keys.ESCAPE) return false
        // Esc leaves a sign-in first, then the screen.
        if (Accounts.signIn != null) {
            Menus.back()
            Accounts.cancelSignIn()
        } else close()
        return true
    }

    override fun shouldCloseOnEsc() = false

    private companion object {
        const val ADD = "Add account"
        const val DONE = "Done"

        // In GUI units, the ClickGUI's (the mockup's CSS px / 2).
        const val PANEL_W = 300f
        const val HEAD = 43f
        const val FOOT = 25f
        const val ROW_H = 30f
        const val ROW_GAP = 2f
        const val SLOT = ROW_H + ROW_GAP
        const val LIST_PAD = 6f
        const val MIN_ROWS = 4
        const val MAX_ROWS = 7
        /** A face: 8 skin pixels across, so a whole number of screen pixels each at any GUI scale. */
        const val HEAD_SIZE = 16f
        const val FADE = 8f
        const val ARMED_MS = 3500L
        const val FLASH_MS = 900L
        const val SWEEP_MS = 1400L
        /** Something went wrong, or is about to be removed (as a clashing key in Options). */
        val ERROR = 0xFFF87171.toInt()
    }
}
