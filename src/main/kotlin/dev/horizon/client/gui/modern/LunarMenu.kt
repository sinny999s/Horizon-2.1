package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.lunar.LunarApollo
import dev.horizon.client.lunar.LunarNetwork
import dev.horizon.client.lunar.LunarSettings
import dev.horizon.client.module.misc.LunarSpoof
import dev.horizon.client.lunar.LunarSocial
import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.BoolSetting
import net.minecraft.client.Minecraft

/**
 * Not in the original: the ClickGUI's Lunar menu (the title bar's Lunar tool), Horizon's built-in Lunar side in one card.
 * Left: Lunar's services on or off with where the connection is, the locker and friends list, and what shows by names
 * and in the friends list. Right: cosmetics, and the server side: Lunar Spoof (passing for Lunar Client on the server,
 * the one part a server sees) and what its Apollo settings turn off. Settings save to `lunar.hzn` as they change.
 */
internal class LunarMenu(private val ax: Float, private val ay: Float) : Popover() {
    private val mc get() = Minecraft.getInstance()

    override fun draw(): Boolean {
        card(ax - 150f, ay, 444f, 192f)
        val col = (w - 24f) / 3f
        val lx = x + 6f
        val rx = x + 12f + col
        val tx = x + 18f + 2f * col

        // ---- left: the connection ----
        var py = y + 5f
        Ui.text(Type.section, "LUNAR", lx + 2f, py + 5f, Tok.T4)
        val state = LunarNetwork.state
        val (label, color) = when (state) {
            3 -> "Connected" to 0xFF4ADE80.toInt()
            2 -> "Connecting" to 0xFFFFC94A.toInt()
            1 -> "Connects on joining a server" to 0xFFFFC94A.toInt()
            else -> "Off" to Tok.T4
        }
        val sw = Type.small.width(label)
        Ui.box(lx + col - sw - 9f, py + 3f, 4f, 4f, 2f, color)
        Ui.textR(Type.small, label, lx + col - 2f, py + 5f, Tok.T3)
        py += 12f
        val on = LunarSettings.network.value
        val netOver = Ui.hit("lunar:network", lx, py, col, 22f, { if (it == Keys.MOUSE_LEFT) flip(LunarSettings.network) })
        Ui.box(lx, py, col, 22f, 5f, if (netOver) Tok.FILL2 else Tok.FILL, 0.5f, if (on) Tok.alpha(Tok.a1, 0.45f) else Tok.LINE)
        Ui.text(Type.semi, "Lunar services", lx + 6f, py + 7.5f, Tok.T1)
        Ui.text(Type.tiny, "Signs in with your account", lx + 6f, py + 15.5f, Tok.T3)
        Ui.switch("lunar:network", lx + col - 27f, py + 5f, on, big = true)
        py += 26f
        val bw = (col - 3f) / 2f
        button("locker", lx, py, bw, "Locker", "cosmetic") { LunarLockerScreen.open() }
        button("friends", lx + bw + 3f, py, bw, "Friends", "users") { LunarFriendsScreen.open() }
        py += 19f
        py = section("NAME TAGS AND TAB", lx, py)
        py = row(lx, py, col, "Logo on name tags", LunarSettings.nametagLogo, on)
        py = row(lx, py, col, "Badges on name tags", LunarSettings.nametagBadge, on)
        py = row(lx, py, col, "Badges in tab list", LunarSettings.tabBadge, on)
        py = section("FRIENDS", lx, py)
        py = row(lx, py, col, "Friends list", LunarSettings.friends, on)
        py = row(lx, py, col, "Share my server", LunarSettings.shareServer, on && LunarSettings.friends.value)
        row(lx, py, col, "Messages in chat", LunarSettings.friendMessages, on && LunarSettings.friends.value)

        Ui.box(x + 9f + col, y + 6f, 0.5f, h - 12f, 0f, Tok.LINE)

        // ---- right: cosmetics and the server side ----
        py = y + 5f
        py = section("COSMETICS", rx, py)
        val cos = on && LunarSettings.cosmetics.value
        py = row(rx, py, col, "Others' cosmetics", LunarSettings.cosmetics, on)
        py = row(rx, py, col, "Cloaks", LunarSettings.cloaks, cos)
        py = row(rx, py, col, "3D cosmetics", LunarSettings.models, cos)
        py = row(rx, py, col, "Emotes", LunarSettings.emotes, cos)
        py = row(rx, py, col, "Particles", LunarSettings.particles, cos)
        py = row(rx, py, col, "Owned picks on my outfit", LunarSettings.lockerAccount, on)
        py = row(rx, py, col, "Share picks with Horizon", LunarSettings.sharePicks, LunarSettings.shareUrl.value.isNotBlank())
        py = section("SERVER", rx, py)
        val spoof = LunarSpoof.instance
        val spoofOn = spoof?.enabled == true
        val over = Ui.hit("lunar:spoof", rx, py, col, 14f, { if (it == Keys.MOUSE_LEFT) spoof?.toggle() })
        if (over) Ui.box(rx, py, col, 14f, 4f, Tok.FILL2)
        Ui.text(Type.label, "Pass as Lunar (Lunar Spoof)", rx + 4f, py + 7f, if (spoofOn) Tok.T1 else Tok.T2)
        Ui.switch("lunar:spoofsw", rx + col - 21f, py + 2f, spoofOn)
        py += 14f
        row(rx, py, col, "Apollo server features", LunarSettings.apolloFeatures, true)

        Ui.box(x + 15f + 2f * col, y + 6f, 0.5f, h - 12f, 0f, Tok.LINE)

        // ---- third: sprays, emotes, alerts ----
        py = y + 5f
        py = section("SPRAYS AND EMOTES", tx, py)
        py = row(tx, py, col, "Others' sprays", LunarSettings.sprays, on)
        val owned = LunarSocial.ownedSprays()
        val chosen = owned.firstOrNull { it.id == LunarSettings.spray.value } ?: owned.firstOrNull()
        val sprayOver = Ui.hit("lunar:spray", tx, py, col, 14f, { b ->
            if (b == Keys.MOUSE_LEFT && owned.isNotEmpty()) {
                val i = owned.indexOf(chosen)
                LunarSettings.spray.value = owned[(i + 1) % owned.size].id
                LunarSettings.save()
            }
        })
        if (sprayOver) Ui.box(tx, py, col, 14f, 4f, Tok.FILL2)
        Ui.text(Type.label, "My spray", tx + 4f, py + 7f, Tok.T2)
        Ui.textR(Type.small, Ui.fit(Type.small, chosen?.name ?: if (on) "none owned" else "-", col - 50f), tx + col - 4f, py + 7f, Tok.T1)
        py += 14f
        py = keyRow(tx, py, col, "Spray key", LunarSettings.sprayKey)
        py = keyRow(tx, py, col, "Emote key (wheel)", LunarSettings.emoteKey)
        py = row(tx, py, col, "Emote music", LunarSettings.emoteMusic, on)
        py = section("ALERTS", tx, py)
        py = row(tx, py, col, "Lunar notifications", LunarSettings.notifications, on)
        py = row(tx, py, col, "Chat notices", LunarSettings.notify, true)
        button("apollo", tx, py + 4f, col, "What this server blocks", "info") { LunarApollo.tellBlocks() }
        return true
    }

    /** A bind's row: click, then press a key or mouse button (Esc clears it). */
    private fun keyRow(sx: Float, sy: Float, w: Float, label: String, b: BindSetting): Float {
        val capturing = LunarSettings.capturing === b
        val over = Ui.hit("lunar:key:${b.id}", sx, sy, w, 14f, { if (it == Keys.MOUSE_LEFT) LunarSettings.capturing = if (capturing) null else b })
        if (over || capturing) Ui.box(sx, sy, w, 14f, 4f, Tok.FILL2)
        Ui.text(Type.label, label, sx + 4f, sy + 7f, Tok.T2)
        val name = if (capturing) "press a key" else Keys.name(b.value)
        val kw = Type.chip.width(name) + 6f
        Ui.keycap(sx + w - kw - 3f, sy + 2.5f, name, none = !capturing && b.value.key == -1)
        return sy + 14f
    }

    private fun flip(s: BoolSetting) {
        s.value = !s.value
        LunarSettings.save()
    }

    private fun section(text: String, sx: Float, sy: Float): Float {
        Ui.text(Type.section, text, sx + 2f, sy + 7f, Tok.T4)
        return sy + 13f
    }

    /** A setting's row: its label and switch, dimmed when what it needs is off (still clickable). */
    private fun row(sx: Float, sy: Float, w: Float, label: String, s: BoolSetting, live: Boolean): Float {
        val over = Ui.hit("lunar:${s.id}", sx, sy, w, 14f, { if (it == Keys.MOUSE_LEFT) flip(s) })
        if (over) Ui.box(sx, sy, w, 14f, 4f, Tok.FILL2)
        Ui.text(Type.label, label, sx + 4f, sy + 7f, if (!live) Tok.T4 else if (s.value) Tok.T1 else Tok.T2)
        Ui.switch("lunar:sw:${s.id}", sx + w - 21f, sy + 2f, s.value)
        return sy + 14f
    }

    private fun button(id: String, bx: Float, by: Float, bw: Float, label: String, icon: String, click: () -> Unit) {
        val over = Ui.hit("lunar:btn:$id", bx, by, bw, 16f, { if (it == Keys.MOUSE_LEFT) click() })
        Ui.box(bx, by, bw, 16f, 4.5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        val tw = Type.small.width(label) + 10f
        val tx = bx + (bw - tw) / 2f
        Ui.icon(icon, tx, by + 4.5f, 7f, if (over) Tok.T1 else Tok.T2)
        Ui.text(Type.small, label, tx + 10f, by + 8f, if (over) Tok.T1 else Tok.T2)
    }
}
