package dev.horizon.client.gui.menu

import dev.horizon.client.compat.Via
import dev.horizon.client.mixin.FaviconTextureAccessor
import dev.horizon.client.mixin.JoinMultiplayerScreenAccessor
import dev.horizon.client.mixin.NetworkServerEntryAccessor
import dev.horizon.client.mixin.OnlineServerEntryAccessor
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * The Multiplayer screen in the client's style ([ListMenu] over vanilla's): each server as a pane with
 * its icon, name and MOTD (in Minecraft's font, as servers design them), its player count, ping in ms and signal bars;
 * LAN games below, as vanilla lists them. Vanilla pings a server when its row is drawn, so each frame the rows are also
 * drawn into a frame that never reaches the screen. With ViaFabricPlus, its button becomes "Join as <version>", which
 * opens a [VersionPicker] over this screen; right-clicking a server picks a version for that server alone. Servers can
 * be dragged into a new order, saved to the server list as vanilla's Shift+arrows would.
 */
class ServersScreen(vanilla: JoinMultiplayerScreen) : ListMenu<ServerSelectionList.Entry>(vanilla, "Multiplayer") {
    private val list get() = (vanilla as JoinMultiplayerScreenAccessor).horizon_list()
    private val joinAs = MenuButton("") { pick(null) }

    override fun keep(button: AbstractButton) = !(Via.present && button.message.string == VIA_BUTTON)

    override fun extraHeader(): List<MenuButton> {
        if (!Via.present) return emptyList()
        joinAs.label = "Join as " + Via.name(Via.target())
        return listOf(joinAs)
    }

    override fun rowRightClicked(entry: ServerSelectionList.Entry) {
        if (!Via.present || entry !is ServerSelectionList.OnlineServerEntry) return
        select(entry)
        pick(entry.serverData)
    }

    /** Opens the version picker for all servers, or for [data] alone. */
    private fun pick(data: ServerData?) {
        val current = if (data == null) Via.target() else Via.server(data)
        Menus.open()
        popup = VersionPicker(data?.name, current, { v -> chosen(data, v) }) { popup = null }
    }

    private fun chosen(data: ServerData?, version: Any?) {
        val screen = vanilla as JoinMultiplayerScreen
        if (data == null) {
            if (version != null) Via.setTarget(version)
            // Ping them all again: whether each is compatible depends on the version.
            screen.pinger.removeAll()
            for (i in 0 until screen.servers.size()) screen.servers.get(i).setState(ServerData.State.INITIAL)
        } else {
            Via.setServer(data, version)
            screen.servers.save()
            screen.pinger.removeAll()
            data.setState(ServerData.State.INITIAL)
        }
    }

    override fun reorderable(entry: ServerSelectionList.Entry) = entry is ServerSelectionList.OnlineServerEntry

    override fun key(entry: ServerSelectionList.Entry): Any = (entry as? ServerSelectionList.OnlineServerEntry)?.serverData ?: entry

    override fun moved(entry: ServerSelectionList.Entry, from: Int, to: Int) {
        val servers = (vanilla as JoinMultiplayerScreen).servers
        val data = (entry as ServerSelectionList.OnlineServerEntry).serverData
        var i = from
        while (i != to) {
            val j = if (to > i) i + 1 else i - 1
            servers.swap(i, j)
            i = j
        }
        servers.save()
        list.updateOnlineServers(servers)
        list.children().firstOrNull { it is ServerSelectionList.OnlineServerEntry && it.serverData === data }?.let(::select)
    }

    override fun entries(): List<ServerSelectionList.Entry> = list.children()

    override fun selected(): ServerSelectionList.Entry? = list.selected

    override fun select(entry: ServerSelectionList.Entry) = list.setSelected(entry)

    override fun selectable(entry: ServerSelectionList.Entry) =
        entry is ServerSelectionList.OnlineServerEntry || entry is ServerSelectionList.NetworkServerEntry

    override fun rowHeight(entry: ServerSelectionList.Entry) = if (selectable(entry)) 42f else 22f

    override fun open(entry: ServerSelectionList.Entry) = entry.join()

    override fun subtitle(): String {
        val n = list.children().count { it is ServerSelectionList.OnlineServerEntry }
        return if (n == 1) "1 server" else "$n servers"
    }

    override fun beforeRows() {
        val graphics = scratchGraphics()
        for (e in list.children()) if (e is ServerSelectionList.OnlineServerEntry) e.extractContent(graphics, -1, -1, false, 0f)
    }

    override fun drawRow(entry: ServerSelectionList.Entry, x: Float, y: Float, w: Float, h: Float, hovered: Boolean, selected: Boolean) {
        when (entry) {
            is ServerSelectionList.OnlineServerEntry -> online(entry, x, y, w, h, hovered, selected)
            is ServerSelectionList.NetworkServerEntry -> lan(entry, x, y, w, h, hovered, selected)
            // Vanilla's "Scanning for games on your local network" row.
            else -> Fonts.medium75.drawMiddleCentered(SCANNING + Menus.dots(), x + w / 2f, y + h / 2f, Menus.GREY)
        }
    }

    private fun online(entry: ServerSelectionList.OnlineServerEntry, x: Float, y: Float, w: Float, h: Float, hovered: Boolean, selected: Boolean) {
        Menus.card(x, y, w, h, hovered, selected)
        val data = entry.serverData
        val icon = if (data.iconBytes != null) ((entry as Any as OnlineServerEntryAccessor).horizon_icon() as FaviconTextureAccessor).horizon_texture() else null
        val texture = icon?.textureView ?: mc.textureManager.getTexture(UNKNOWN_SERVER).textureView
        Menus.image(texture, x + 5f, y + 5f, 32f, 32f, 5f)

        val font = Fonts.medium75
        val right = x + w - 8f
        val state = data.state()
        val (status, statusColor) = when (state) {
            ServerData.State.SUCCESSFUL -> data.status.string to Menus.GREY
            ServerData.State.INCOMPATIBLE -> data.version.string to RED
            ServerData.State.UNREACHABLE -> "Offline" to RED
            else -> "Pinging" + Menus.dots() to Menus.GREY
        }
        val statusW = font.width(status)
        bars(right - 14f, y + 6f, state, data.ping)
        font.draw(status, right - 19f - statusW, y + 6.5f, statusColor)
        if (state == ServerData.State.SUCCESSFUL) {
            val ms = "${data.ping} ms"
            Fonts.medium65.draw(ms, right - Fonts.medium65.width(ms), y + 17f, 0xFF7A7A7A.toInt())
        }
        // The server's own ViaFabricPlus version, if it has one; the MOTD keeps clear of it.
        var motdRight = right - 48f
        Via.server(data)?.let {
            val own = "as " + Via.name(it)
            val ow = Fonts.medium65.width(own)
            Fonts.medium65.draw(own, right - ow, y + 28f, Menus.FILL)
            motdRight = minOf(motdRight, right - ow - 8f)
        }

        val tx = x + 45f
        Fonts.medium95.draw(Menus.fit(data.name, Fonts.medium95, right - 25f - statusW - tx), tx, y + 5f, Theme.text)
        // (Vanilla text draws over the client's layer, so none while a popup is open, or under a row being dragged.)
        val graphics = Canvas.graphics?.takeIf { popup == null && !underDrag(entry, y, h) } ?: return
        val lines = mc.font.split(data.motd, (motdRight - tx).toInt().coerceAtLeast(20))
        for ((i, line) in lines.take(2).withIndex()) graphics.text(mc.font, line, tx.toInt(), (y + 19f).toInt() + i * 10, MOTD, false)
    }

    private fun lan(entry: ServerSelectionList.NetworkServerEntry, x: Float, y: Float, w: Float, h: Float, hovered: Boolean, selected: Boolean) {
        Menus.card(x, y, w, h, hovered, selected)
        val server = (entry as NetworkServerEntryAccessor).horizon_lanServer()
        Menus.image(mc.textureManager.getTexture(UNKNOWN_SERVER).textureView, x + 5f, y + 5f, 32f, 32f, 5f)
        val tx = x + 45f
        val tw = w - 52f
        Fonts.medium95.draw(LAN_TITLE, tx, y + 5f, Theme.text)
        Fonts.medium75.draw(Menus.fit(server.motd, Fonts.medium75, tw), tx, y + 17f, Menus.GREY)
        if (!mc.options.hideServerAddress) Fonts.medium75.draw(Menus.fit(server.address, Fonts.medium75, tw), tx, y + 27f, 0xFF7A7A7A.toInt())
    }

    /** Five signal bars: lit by the ping, green to red; a sweep while pinging; red stubs when it couldn't be reached. */
    private fun bars(x: Float, y: Float, state: ServerData.State, ping: Long) {
        val lit = when {
            state != ServerData.State.SUCCESSFUL -> 0
            ping < 150 -> 5
            ping < 300 -> 4
            ping < 600 -> 3
            ping < 1000 -> 2
            else -> 1
        }
        val color = when (lit) {
            5, 4 -> 0xFF4ADE80.toInt()
            3 -> 0xFFFACC15.toInt()
            2 -> 0xFFFB923C.toInt()
            else -> RED
        }
        val sweep = (System.currentTimeMillis() / 150 % 8).toInt()
        for (i in 0 until 5) {
            val bh = 3f + i * 1.5f
            val c = when (state) {
                ServerData.State.SUCCESSFUL, ServerData.State.INCOMPATIBLE -> if (i < lit) color else 0x30FFFFFF
                ServerData.State.UNREACHABLE -> 0x80F87171.toInt()
                else -> if (i == sweep) 0xC0FFFFFF.toInt() else 0x30FFFFFF
            }
            Menus.rect(x + i * 3f, y + 9f - bh, 2f, bh, 0.5f, c)
        }
    }

    private companion object {
        val UNKNOWN_SERVER: Identifier = Identifier.withDefaultNamespace("textures/misc/unknown_server.png")
        val SCANNING: String = Component.translatable("lanServer.scanning").string.trimEnd('.')
        val LAN_TITLE: String = Component.translatable("lanServer.title").string
        const val VIA_BUTTON = "ViaFabricPlus"
        const val RED = 0xFFF87171.toInt()
        const val MOTD = 0xFF8A8A8A.toInt()
    }
}
