package dev.horizon.client.hud.widgets

import com.mojang.authlib.GameProfile
import dev.horizon.client.config.Friends
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.resources.Identifier
import net.minecraft.util.StringUtil
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemCooldowns
import net.minecraft.world.level.GameType
import net.minecraft.world.scores.PlayerTeam
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A row of the animated list widgets: slides in over 250 ms (OUT_QUINT), out once [removing]. */
open class ListRow {
    val anim = Animation(Easing.OUT_QUINT, 250)
    var removing = false

    /** The per-frame step inside `removeIf`: true once it has faded out. */
    fun step(): Boolean {
        anim.update(if (removing) 0f else 1f)
        return removing && anim.value <= 0f
    }
}

/**
 * "Key Binds" and a row per enabled, bound module (not the ClickGUI). Rows with [KeyBind.extra] come
 * from setting macros and show that text instead of the name.
 * The new look, a plate for the name and one for the key (hud.js's list).
 */
object KeybindsWidget : HudWidget("keybinds-widget") {
    class KeyBind(val name: String, val bind: String, val extra: String = "") : ListRow()

    val rows = ArrayList<KeyBind>()
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    override fun render() {
        for (m in Modules.all.filter { it !is ClickGuiModule }) {
            val notListed = rows.none { it.name == m.name }
            if (m.bind.key != -1 && m.enabled) {
                if (notListed) rows += KeyBind(m.name, Keys.name(m.bind))
                continue
            }
            if (notListed) continue
            rows.filter { it.name == m.name && it.extra == "" }.forEach { it.removing = true }
        }
        rows.removeIf { it.step() }
        val title = "Key Binds"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (rows.isNotEmpty() || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)
        var width = headerWidth(title)
        for (e in rows) width = max(width, cellWidth(label(e)) + 1f + cellWidth(e.bind))
        var y = 17f
        for (e in rows) {
            val label = label(e)
            val lw = cellWidth(label)
            val t = e.anim.value
            val rx = if (alignRight) left + width - (lw + 1f + cellWidth(e.bind)) + (1 - t) * 10 else left - (1 - t) * 10
            cell(label, rx, top + y, alpha = t)
            cell(e.bind, rx + (lw + 1f) * t, top + y, alpha = t)
            y += 14 * t
        }
        size(width, y - 1)
    }

    private fun label(e: KeyBind) = if (e.extra.isEmpty()) e.name.trim() else e.extra
}

/**
 * "Cooldowns" and a row per item on cooldown (name, m:ss, the item at 0.55 scale).
 * The new look, plates for the item, its name and the time left.
 */
object CooldownsWidget : HudWidget("cooldowns-widget") {
    private class Row(val item: Item, val endTick: Int) : ListRow()

    private val rows = ArrayList<Row>()
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    private val cooldownsField = ItemCooldowns::class.java.getDeclaredField("cooldowns").apply { isAccessible = true }
    private val tickCountField = ItemCooldowns::class.java.getDeclaredField("tickCount").apply { isAccessible = true }
    private val endTimeMethod by lazy {
        Class.forName("net.minecraft.world.item.ItemCooldowns\$CooldownInstance").getDeclaredMethod("endTime").apply { isAccessible = true }
    }

    private fun time(ticks: Int) = "${ticks / 1200}:" + "%02d".format(ticks % 1200 / 20)

    override fun render() {
        val p = mc.player ?: return
        val cooldowns = p.cooldowns
        try {
            @Suppress("UNCHECKED_CAST")
            for ((id, inst) in cooldownsField.get(cooldowns) as Map<Identifier, Any>) {
                val item = BuiltInRegistries.ITEM.getValue(id)
                if (rows.none { it.item == item }) rows += Row(item, endTimeMethod.invoke(inst) as Int)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        for (e in rows) if (!cooldowns.isOnCooldown(e.item.defaultInstance)) e.removing = true
        rows.removeIf { it.step() }
        val tick = tickCountField.getInt(cooldowns)
        val title = "Cooldowns"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (rows.isNotEmpty() || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)
        var width = headerWidth(title)
        for (e in rows) width = max(width, 14f + cellWidth(I18n.get(e.item.descriptionId)) + 1f + cellWidth(time(e.endTick - tick)))
        var y = 17f
        for (e in rows) {
            val name = I18n.get(e.item.descriptionId)
            val time = time(e.endTick - tick)
            val t = e.anim.value
            val rw = 14f + cellWidth(name) + 1f + cellWidth(time)
            val rx = if (alignRight) left + width - rw + (1 - t) * 10 else left - (1 - t) * 10
            plate(rx, top + y, 13f, 13f, alpha = t)
            val nw = cell(name, rx + 14f, top + y, alpha = t)
            cell(time, rx + 14f + (nw + 1f) * t, top + y, alpha = t)
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(rx + 2.1f, top + y + 2.1f)
            pose.scale(0.55f, 0.55f)
            if (t > 0.8f) Canvas.graphics?.item(e.item.defaultInstance, 0, 0)
            pose.popMatrix()
            y += 14 * t
        }
        size(width, y - 1)
    }
}

/**
 * "Potions" and a row per active effect (icon, name in red when harmful, m:ss, flicker under 10 s).
 * The new look, plates for the effect's icon, its name and the time left.
 */
object PotionsWidget : HudWidget("potions-widget") {
    private class Row(val effect: MobEffectInstance) : ListRow()

    private val rows = ArrayList<Row>()
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    private fun name(e: MobEffectInstance) = I18n.get(e.descriptionId) + if (e.amplifier > 0) " " + (e.amplifier + 1) else ""

    private fun duration(e: MobEffectInstance) =
        if (e.isInfiniteDuration) "*:*" else "${e.duration / 1200}:" + "%02d".format(e.duration % 1200 / 20)

    override fun render() {
        val p = mc.player ?: return
        val active = p.activeEffects
        for (e in active) if (rows.none { it.effect == e }) rows += Row(e)
        for (row in rows) if (active.none { row.effect == it }) row.removing = true
        rows.removeIf { it.step() }
        val title = "Potions"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (rows.isNotEmpty() || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)
        var width = headerWidth(title)
        for (row in rows) width = max(width, 14f + cellWidth(name(row.effect)) + 1f + cellWidth(duration(row.effect)))
        var y = 17f
        for (row in rows) {
            val name = name(row.effect)
            val dur = duration(row.effect)
            val a = row.anim.value
            val harmful = row.effect.effect.value().category == MobEffectCategory.HARMFUL
            val rw = 14f + cellWidth(name) + 1f + cellWidth(dur)
            val rx = if (alignRight) left + width - rw + (1f - a) * 10 else left - (1f - a) * 10
            // The duration in ticks taken as radians: a pseudo-random flicker in the last 10 s.
            val blink = if (row.effect.endsWithin(200)) 1f - sin(row.effect.duration.toDouble()).toFloat() * 0.5f else 1f
            plate(rx, top + y, 13f, 13f, alpha = a)
            val nw = cellWidth(name)
            plate(rx + 14f, top + y, nw, 13f, alpha = a)
            ctext(name, rx + 18f, top + y + 6.5f, Colors.withAlpha(if (harmful) HARM else Tok.T1, a * blink))
            plate(rx + 14f + (nw + 1f) * a, top + y, cellWidth(dur), 13f, alpha = a)
            ctext(dur, rx + 18f + (nw + 1f) * a, top + y + 6.5f, Colors.withAlpha(Tok.T1, a * blink))
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(rx + 2f, top + y + 2f)
            pose.scale(0.5f, 0.5f)
            val id = row.effect.effect.unwrapKey().orElseThrow().identifier()
            Canvas.graphics?.blit(RenderPipelines.GUI_TEXTURED, id.withPath { "textures/mob_effect/$it.png" },
                0, 0, 0f, 0f, 18, 18, 18, 18, Colors.withAlpha(-1, a * blink))
            pose.popMatrix()
            y += 14f * a
        }
        size(width, y - 1f)
    }

    /** hud.css .harm. */
    private const val HARM = 0xFFFF7A7A.toInt()
}

/**
 * One detected staff entry, tracking their team-formatted name, vanished flag, and current
 * world/tab/gamemode status.
 */
class StaffEntry(val profile: GameProfile, var vanished: Boolean = true) {
    val info = PlayerInfo(profile, false)
    private var cachedName: String? = null

    /** Lazily cached team-formatted display name. */
    fun formattedName(): String {
        if (cachedName == null) cachedName = StaffDetector.formatName(info).string
        return cachedName!!
    }

    /** Dynamic status prefix (`(near)`, `Spec!`, `(gm3)`, or `""`), updating [vanished]. */
    fun statusPrefix(): String {
        val mc = Minecraft.getInstance()
        val name = profile.name
        if (mc.level?.players()?.any { it.name.string == name } == true) {
            vanished = false
            return "${ChatFormatting.YELLOW} (near)${ChatFormatting.RESET}"
        }
        val conn = mc.connection
        val onlineInfo = conn?.getPlayerInfo(name)
        val notInTab = conn?.onlinePlayers?.none { it.profile.name == name } ?: true
        if (notInTab) {
            vanished = true
            return "${ChatFormatting.RED} Spec!${ChatFormatting.RESET}"
        }
        vanished = false
        if (onlineInfo != null && onlineInfo.gameMode == GameType.SPECTATOR) {
            return "${ChatFormatting.YELLOW} (gm3)${ChatFormatting.RESET}"
        }
        return ""
    }

    fun rowText(): String = statusPrefix() + " " + formattedName()

    override fun equals(other: Any?): Boolean = other is StaffEntry && profile.name == other.profile.name

    override fun hashCode(): Int = javaClass.hashCode()
}

/**
 * Listens for `ClientboundSetPlayerTeamPacket` (`Action.ADD`), checks team prefixes against the
 * staff rank list, and maintains the sorted active/vanished staff list for [StaffWidget].
 */
object StaffDetector {
    private val mc get() = Minecraft.getInstance()
    private val tracked = CopyOnWriteArrayList<StaffEntry>()

    @Volatile
    var entries: List<StaffEntry> = emptyList()
        private set

    private val PREFIXES = arrayOf(
        "админ", "admin", "ml.admin", "moder", "st.moder",
        "s.moder", "gl.moder", "j.moder", "модератор", "модер",
        "st.helper", "хелпер", "helper", "helper", "yt",
    )

    fun init() {
        EventBus.on<AfterReceivePacketEvent>(this) { e ->
            if (!StaffWidget.enabled) return@on
            val pk = e.packet as? ClientboundSetPlayerTeamPacket ?: return@on
            if (pk.teamAction != ClientboundSetPlayerTeamPacket.Action.ADD &&
                pk.playerAction != ClientboundSetPlayerTeamPacket.Action.ADD
            ) return@on
            for (playerName in pk.players) {
                val entry = StaffEntry(GameProfile(UUID.fromString("66123666-622-6666-6666-666612366600"), playerName), true)
                if (entry.profile.name == mc.player?.gameProfile?.name) return@on
                val hasPrefix = try {
                    pk.parameters.orElse(null)?.playerPrefix?.string?.isNotEmpty() == true ||
                        mc.level?.scoreboard?.getPlayerTeam(pk.name)?.playerPrefix?.string?.isNotEmpty() == true
                } catch (_: Throwable) {
                    false
                }
                if (hasPrefix) {
                    CompletableFuture.runAsync {
                        try {
                            Thread.sleep(250L)
                            if (matchesStaff(entry.formattedName()) && !tracked.contains(entry)) {
                                tracked.add(entry)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        EventBus.on<PlayerUpdateEvent>(this) {
            if (!StaffWidget.enabled || tracked.isEmpty()) return@on
            tracked.removeIf { entry -> if (entry.info.team != null) false else entry.vanished }
            entries = tracked.sortedBy { rankOrder(it) }
        }
    }

    fun formatName(entry: PlayerInfo): MutableComponent {
        val base = entry.tabListDisplayName?.copy()
            ?: PlayerTeam.formatNameForTeam(entry.team, Component.literal(entry.profile.name))
        return if (entry.gameMode == GameType.SPECTATOR) base.withStyle(ChatFormatting.ITALIC) else base
    }

    private fun matchesStaff(displayName: String): Boolean {
        val s = StringUtil.stripColor(displayName.lowercase(Locale.ROOT))
        if (s.contains("§")) return false
        if (s.split(" ").any { token -> PREFIXES.any { token.startsWith(it) } }) return true
        return mc.player?.connection?.onlinePlayers?.any { it.profile.name == s } == true
    }

    private fun rankOrder(entry: StaffEntry): Int {
        val words = StringUtil.stripColor(entry.formattedName().lowercase(Locale.ROOT)).split(' ')
        var best = 11
        for (word in words) {
            val rank = when (word) {
                "админ", "admin" -> 0
                "ml.admin" -> 1
                "gl.moder" -> 2
                "s.moder", "st.moder" -> 3
                "модератор", "moder" -> 4
                "j.moder" -> 5
                "st.helper" -> 6
                "helper+" -> 7
                "helper" -> 8
                "yt+" -> 9
                "yt" -> 10
                else -> 999
            }
            if (rank < best) best = rank
        }
        return best
    }
}

/**
 * "Staff" and a row per detected staff member from [StaffDetector].
 * The new look, a plate per member with the status and team colours.
 */
object StaffWidget : HudWidget("staff-widget") {
    private class Row(val name: String) : ListRow()

    private val rows = ArrayList<Row>()
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    /** The detected staff, as "status name" strings. */
    var staff: List<String> = emptyList()

    override fun render() {
        val current = if (StaffDetector.entries.isNotEmpty()) StaffDetector.entries.map { it.rowText() } else staff
        for (s in current) if (rows.none { it.name == s }) rows += Row(s)
        for (row in rows) if (current.none { it == row.name }) row.removing = true
        rows.removeIf { it.step() }
        val title = "Staff"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (rows.isNotEmpty() || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)
        var width = headerWidth(title)
        for (row in rows) width = max(width, colored(row.name.trim(), 0f, 0f, Tok.T1, draw = false) + 8f)
        var y = 17f
        for (row in rows) {
            val text = row.name.trim()
            val a = row.anim.value
            val pw = colored(text, 0f, 0f, Tok.T1, draw = false) + 8f
            val rx = if (alignRight) left + width - pw + (1f - a) * 10 else left - (1f - a) * 10
            plate(rx, top + y, pw, 13f, alpha = a)
            colored(text, rx + 4f, top + y + 6.5f, Tok.T1, a)
            y += 14f * a
        }
        size(width, y - 1f)
    }
}

/**
 * "Players" and a row per other player (friends in green, hp, distance, ping, skin head).
 * The new look's rows of plates (hud.js's players).
 */
object PlayerListWidget : HudWidget("player-list-widget") {
    private class Row(val player: AbstractClientPlayer) : ListRow() {
        /** Where the row was drawn this frame. */
        var x = 0f
        var y = 0f
    }

    private val rows = ArrayList<Row>()
    private val headerAnim = Animation(Easing.OUT_BACK, 250)

    override fun render() {
        val level = mc.level ?: return
        val others = level.players().filter { it != mc.player }
        for (p in others) if (rows.none { it.player == p }) rows += Row(p)
        for (row in rows) if (row.player !in level.players()) row.removing = true
        rows.removeIf { it.step() }
        val title = "Players"
        val alignRight = left - screenW / 2f > 0
        headerAnim.update(if (rows.isNotEmpty() || editorMode) 1f else 0f)
        header(title, headerAnim.value, alignRight)
        var width = headerWidth(title)
        for (row in rows) width = max(width, rowWidth(cells(row.player)))
        var y = 17f
        for (row in rows) {
            val c = cells(row.player)
            if (c.isEmpty()) continue
            val a = row.anim.value
            val rx = if (alignRight) left + width - rowWidth(c) + (1f - a) * 10 else left - (1f - a) * 10
            row.x = rx
            row.y = top + y
            val friend = Friends.isFriend(c[0])
            val nw = cellWidth(c[0]) + 12f
            plate(rx, top + y, nw, 13f, alpha = a)
            ctext(c[0], rx + 16f, top + y + 6.5f, Colors.withAlpha(if (friend) FRIEND else Tok.T1, a))
            var cx = rx + nw + 1f
            for (s in c.drop(1)) cx += cell(s, cx, top + y, alpha = a) + 1f
            y += 14f * a
        }
        size(width, y - 1f)
    }

    /** The new look's cells, hud.js's players: the head and name, health, distance, ping. */
    private fun cells(p: AbstractClientPlayer): List<String> {
        val me = mc.player ?: return emptyList()
        return listOf(p.gameProfile.name, "${(p.health + p.absorptionAmount).roundToInt()} hp",
            "${me.distanceTo(p).roundToInt()} m", "${ServerInfo.ping(p)} ms")
    }

    private fun rowWidth(c: List<String>) = c.withIndex().sumOf { (i, s) -> (cellWidth(s) + (if (i == 0) 12f else 0f) + 1f).toDouble() }.toFloat() - 1f

    /** The skin heads, over the plates. */
    override fun postRender() {
        for (row in rows) Skins.roundedHead(row.player, row.x + 4f, row.y + 2f, 9f, 2f, row.anim.value)
    }

    /** hud.css .friend. */
    private const val FRIEND = 0xFF6CF08F.toInt()
}
