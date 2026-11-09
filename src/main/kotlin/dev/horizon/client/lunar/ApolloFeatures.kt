package dev.horizon.client.lunar

import com.google.protobuf.Any
import com.lunarclient.apollo.beam.v1.DisplayBeaconBeamMessage
import com.lunarclient.apollo.beam.v1.RemoveBeaconBeamMessage
import com.lunarclient.apollo.beam.v1.ResetBeaconBeamsMessage
import com.lunarclient.apollo.border.v1.DisplayBorderMessage
import com.lunarclient.apollo.border.v1.RemoveBorderMessage
import com.lunarclient.apollo.border.v1.ResetBordersMessage
import com.lunarclient.apollo.common.v1.Component
import com.lunarclient.apollo.cooldown.v1.DisplayCooldownMessage
import com.lunarclient.apollo.cooldown.v1.RemoveCooldownMessage
import com.lunarclient.apollo.cooldown.v1.ResetCooldownsMessage
import com.lunarclient.apollo.glow.v1.OverrideGlowEffectMessage
import com.lunarclient.apollo.glow.v1.ResetGlowEffectMessage
import com.lunarclient.apollo.glow.v1.ResetGlowEffectsMessage
import com.lunarclient.apollo.hologram.v1.DisplayHologramMessage
import com.lunarclient.apollo.hologram.v1.RemoveHologramMessage
import com.lunarclient.apollo.hologram.v1.ResetHologramsMessage
import com.lunarclient.apollo.notification.v1.DisplayNotificationMessage
import com.lunarclient.apollo.stopwatch.v1.ResetStopwatchMessage
import com.lunarclient.apollo.stopwatch.v1.StartStopwatchMessage
import com.lunarclient.apollo.stopwatch.v1.StopStopwatchMessage
import com.lunarclient.apollo.team.v1.ResetTeamMembersMessage
import com.lunarclient.apollo.team.v1.UpdateTeamMembersMessage
import com.lunarclient.apollo.title.v1.DisplayTitleMessage
import com.lunarclient.apollo.title.v1.ResetTitlesMessage
import com.lunarclient.apollo.title.v1.TitleType
import com.lunarclient.apollo.tntcountdown.v1.SetTntCountdownMessage
import com.lunarclient.apollo.waypoint.v1.DisplayWaypointMessage
import com.lunarclient.apollo.waypoint.v1.RemoveWaypointMessage
import com.lunarclient.apollo.waypoint.v1.ResetWaypointsMessage
import dev.horizon.client.HorizonClient
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.World3D
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What an Apollo server shows Lunar Client players, drawn with Horizon's own HUD and 3D drawing. These come only after
 * Lunar Spoof's Apollo handshake, and are cleared on each join.
 * - Waypoints: a coloured label with the distance and a line up from the spot. Beacon beams: a coloured beam.
 * - Team view: a coloured marker over each teammate (where the server says they are when they're out of sight).
 * - Holograms: their lines over the spot. Titles: big text mid-screen with its fade times.
 * - Cooldowns: a list on the left with the item and seconds left. Stopwatch: the time at the top.
 * - Borders: the region's edges as coloured lines. Glow: an outline in the server's colour around those players.
 * - Notifications: Horizon toasts. TNT countdown: seconds left over the TNT the server set a fuse for.
 * - Name tags: the lines the server sets over players. Coloured fire, hidden armour pieces and body parts, flipped
 *   entities and rainbow sheep, on the players and entities it names.
 * - Nick hider: your name as the server hides it. Vignette: its texture over the screen. Ping markers: a marker where a
 *   player pinged. Live chat: lines the server can change or take back. Staff mods: said in chat.
 * - Rich presence: what the server says you're doing ([richPresence]), for Discord RPC's status.
 * Off with the Lunar menu's `apollo-features` (rich presence is kept either way).
 */
object ApolloFeatures {
    private val mc: Minecraft get() = Minecraft.getInstance()

    class Waypoint(val pos: Vec3, val color: Int, val hidden: Boolean)
    class Beam(val pos: Vec3, val color: Int)
    class TeamMember(val name: String, val pos: Vec3, val color: Int)
    class Hologram(val pos: Vec3, val lines: List<String>, val throughWalls: Boolean)
    class Cooldown(val ends: Long, val total: Long, val item: ItemStack?)
    class Border(val world: String, val minX: Double, val minZ: Double, val maxX: Double, val maxZ: Double, val color: Int)
    class Title(val text: String, val subtitle: Boolean, val start: Long, val fadeIn: Long, val stay: Long, val fadeOut: Long, val scale: Float)

    val waypoints = ConcurrentHashMap<String, Waypoint>()
    val beams = ConcurrentHashMap<String, Beam>()
    val team = ConcurrentHashMap<UUID, TeamMember>()
    val holograms = ConcurrentHashMap<String, Hologram>()
    val cooldowns = ConcurrentHashMap<String, Cooldown>()
    val borders = ConcurrentHashMap<String, Border>()
    val glow = ConcurrentHashMap<UUID, Int>()
    private val tnt = ConcurrentHashMap<Int, Int>()
    private val titles = ConcurrentHashMap<Boolean, Title>()

    /** Name tag lines the server set for players (top first). */
    val nametags = ConcurrentHashMap<UUID, List<String>>()

    /** Fire colours the server set for players. */
    val fire = ConcurrentHashMap<UUID, Int>()

    /** Armour slots (Apollo's ArmorPiece numbers, 1 helmet .. 4 boots) and body parts (1 head .. 6 right leg) hidden per player. */
    val hiddenArmor = ConcurrentHashMap<UUID, Set<Int>>()
    val hiddenParts = ConcurrentHashMap<UUID, Set<Int>>()

    /** Entities the server turned upside down, and sheep it made rainbow. */
    val flipped: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val rainbowSheep: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Your name as the server's nick hider shows it (null: not hidden). */
    @Volatile
    var nick: String? = null

    /** The server's vignette: its texture and opacity (null: none). */
    @Volatile
    private var vignette: Pair<Identifier, Float>? = null

    /** Ping markers: where, colour, until (ms). */
    class Ping(val pos: Vec3, val color: Int, val until: Long, val type: String)
    val pings = ConcurrentHashMap<UUID, Ping>()

    /** Live chat lines the server can change or take back, by its id. */
    private val liveChat = ConcurrentHashMap<Int, net.minecraft.network.chat.Component>()

    /**
     * What the server says you're doing, for your Discord status (Apollo's `richpresence`): the game and its variant, the
     * game's and your state, the map, the sub-server, your team's size and its most. Empty strings and 0 are left out.
     */
    class RichPresence(val game: String, val variant: String, val gameState: String, val playerState: String, val map: String,
                       val subServer: String, val teamSize: Int, val teamMax: Int) {
        /**
         * The two Discord lines as Lunar's launcher writes them: "<player state, else Playing> <game> on <server>", then
         * "<variant> on <map>", the variant alone, or the Minecraft version; the team's size after it, as Discord shows a
         * party ("(2 of 4)"). The game and sub-server states aren't shown. Null with no game. Game-free.
         */
        fun discord(server: String, version: String): Pair<String, String>? {
            if (game.isBlank()) return null
            val first = "${playerState.ifBlank { "Playing" }} $game on $server"
            var second = when {
                variant.isNotBlank() && map.isNotBlank() -> "$variant on $map"
                variant.isNotBlank() -> variant
                else -> version
            }
            if (teamSize > 0 && teamMax > 0) second += " ($teamSize of $teamMax)"
            return first to second
        }
    }

    /** The server's rich presence (null: it hasn't set one). Kept whatever the `apollo-features` setting. */
    @Volatile
    var richPresence: RichPresence? = null

    /** The stopwatch: started at (ms), stopped at (ms, 0 running), or not shown. */
    @Volatile
    private var stopwatchStart = 0L

    @Volatile
    private var stopwatchStop = 0L

    private val shown get() = LunarSettings.apolloFeatures.value

    fun init() {
        EventBus.on<Render3DEvent>(this) { draw3d() }
        EventBus.on<Render2DEvent>(this) { draw2d() }
    }

    fun clear() {
        waypoints.clear(); beams.clear(); team.clear(); holograms.clear(); cooldowns.clear(); borders.clear(); glow.clear(); tnt.clear(); titles.clear()
        nametags.clear(); fire.clear(); hiddenArmor.clear(); hiddenParts.clear(); flipped.clear(); rainbowSheep.clear(); pings.clear(); liveChat.clear()
        nick = null
        vignette = null
        richPresence = null
        stopwatchStart = 0L
        stopwatchStop = 0L
    }

    /** An Apollo message from the server (`lunar:apollo`); true when it was one of these. Any thread. */
    fun handle(any: Any): Boolean {
        try {
            when {
                any.`is`(DisplayWaypointMessage::class.java) -> any.unpack(DisplayWaypointMessage::class.java).let { m ->
                    val l = m.location
                    waypoints[m.name] = Waypoint(Vec3(l.x + 0.5, l.y.toDouble(), l.z + 0.5), argb(m.color.color), m.hidden)
                }
                any.`is`(RemoveWaypointMessage::class.java) -> waypoints.remove(any.unpack(RemoveWaypointMessage::class.java).name)
                any.`is`(ResetWaypointsMessage::class.java) -> waypoints.clear()
                any.`is`(DisplayBeaconBeamMessage::class.java) -> any.unpack(DisplayBeaconBeamMessage::class.java).let { m ->
                    val l = m.location
                    beams[m.id] = Beam(Vec3(l.x + 0.5, l.y.toDouble(), l.z + 0.5), argb(m.color.color))
                }
                any.`is`(RemoveBeaconBeamMessage::class.java) -> beams.remove(any.unpack(RemoveBeaconBeamMessage::class.java).id)
                any.`is`(ResetBeaconBeamsMessage::class.java) -> beams.clear()
                any.`is`(UpdateTeamMembersMessage::class.java) -> {
                    team.clear()
                    for (t in any.unpack(UpdateTeamMembersMessage::class.java).membersList) {
                        val u = UUID(t.playerUuid.high64, t.playerUuid.low64)
                        val name = text(t.playerDisplayName).ifEmpty { t.adventureJsonPlayerName.ifEmpty { "?" } }
                        team[u] = TeamMember(name, Vec3(t.location.x, t.location.y, t.location.z), argb(t.markerColor.color))
                    }
                }
                any.`is`(ResetTeamMembersMessage::class.java) -> team.clear()
                any.`is`(DisplayHologramMessage::class.java) -> any.unpack(DisplayHologramMessage::class.java).let { m ->
                    val lines = m.linesList.map { text(it) }.ifEmpty { m.adventureJsonLinesList.map { plain(it) } }
                    holograms[m.id] = Hologram(Vec3(m.location.x, m.location.y, m.location.z), lines, m.showThroughWalls)
                }
                any.`is`(RemoveHologramMessage::class.java) -> holograms.remove(any.unpack(RemoveHologramMessage::class.java).id)
                any.`is`(ResetHologramsMessage::class.java) -> holograms.clear()
                any.`is`(DisplayTitleMessage::class.java) -> any.unpack(DisplayTitleMessage::class.java).let { m ->
                    val sub = m.titleType == TitleType.TITLE_TYPE_SUBTITLE
                    val text = text(m.message).ifEmpty { plain(m.adventureJsonMessage) }
                    titles[sub] = Title(text, sub, System.currentTimeMillis(), ms(m.fadeInTime, 500), ms(m.displayTime, 3000), ms(m.fadeOutTime, 500),
                        if (m.scale > 0f) m.scale else 1f)
                }
                any.`is`(ResetTitlesMessage::class.java) -> titles.clear()
                any.`is`(DisplayCooldownMessage::class.java) -> any.unpack(DisplayCooldownMessage::class.java).let { m ->
                    val total = ms(m.duration, 0)
                    val item = if (m.icon.hasItemStack()) itemStack(m.icon.itemStack.itemName) else null
                    cooldowns[m.name] = Cooldown(System.currentTimeMillis() + total, total, item)
                }
                any.`is`(RemoveCooldownMessage::class.java) -> cooldowns.remove(any.unpack(RemoveCooldownMessage::class.java).name)
                any.`is`(ResetCooldownsMessage::class.java) -> cooldowns.clear()
                any.`is`(DisplayBorderMessage::class.java) -> any.unpack(DisplayBorderMessage::class.java).let { m ->
                    val b = m.bounds
                    borders[m.id] = Border(m.world, b.minX, b.minZ, b.maxX, b.maxZ, argb(m.color.color))
                }
                any.`is`(RemoveBorderMessage::class.java) -> borders.remove(any.unpack(RemoveBorderMessage::class.java).id)
                any.`is`(ResetBordersMessage::class.java) -> borders.clear()
                any.`is`(OverrideGlowEffectMessage::class.java) -> any.unpack(OverrideGlowEffectMessage::class.java).let { m ->
                    glow[UUID(m.playerUuid.high64, m.playerUuid.low64)] = argb(m.color.color)
                }
                any.`is`(ResetGlowEffectMessage::class.java) -> any.unpack(ResetGlowEffectMessage::class.java).let { m ->
                    glow.remove(UUID(m.playerUuid.high64, m.playerUuid.low64))
                }
                any.`is`(ResetGlowEffectsMessage::class.java) -> glow.clear()
                any.`is`(DisplayNotificationMessage::class.java) -> any.unpack(DisplayNotificationMessage::class.java).let { m ->
                    if (shown) {
                        val secs = (ms(m.displayTime, 5000) / 1000f).coerceIn(1f, 30f)
                        mc.execute { Notifications.post(m.title.ifEmpty { "Server" } + ": ", m.description, secs, NotificationType.INFO) }
                    }
                }
                any.`is`(StartStopwatchMessage::class.java) -> {
                    stopwatchStart = System.currentTimeMillis()
                    stopwatchStop = 0L
                }
                any.`is`(StopStopwatchMessage::class.java) -> if (stopwatchStart != 0L && stopwatchStop == 0L) stopwatchStop = System.currentTimeMillis()
                any.`is`(ResetStopwatchMessage::class.java) -> {
                    stopwatchStart = 0L
                    stopwatchStop = 0L
                }
                any.`is`(SetTntCountdownMessage::class.java) -> any.unpack(SetTntCountdownMessage::class.java).let { m ->
                    tnt[m.entityId.entityId] = m.durationTicks
                }
                any.`is`(com.lunarclient.apollo.nametag.v1.OverrideNametagMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.nametag.v1.OverrideNametagMessage::class.java).let { m ->
                        val lines = m.linesList.map { text(it) }.ifEmpty { m.adventureJsonLinesList.map { plain(it) } }
                        nametags[uuid(m.playerUuid)] = lines
                    }
                any.`is`(com.lunarclient.apollo.nametag.v1.ResetNametagMessage::class.java) ->
                    nametags.remove(uuid(any.unpack(com.lunarclient.apollo.nametag.v1.ResetNametagMessage::class.java).playerUuid))
                any.`is`(com.lunarclient.apollo.nametag.v1.ResetNametagsMessage::class.java) -> nametags.clear()
                any.`is`(com.lunarclient.apollo.coloredfire.v1.OverrideColoredFireMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.coloredfire.v1.OverrideColoredFireMessage::class.java).let { m -> fire[uuid(m.playerUuid)] = argb(m.color.color) }
                any.`is`(com.lunarclient.apollo.coloredfire.v1.ResetColoredFireMessage::class.java) ->
                    fire.remove(uuid(any.unpack(com.lunarclient.apollo.coloredfire.v1.ResetColoredFireMessage::class.java).playerUuid))
                any.`is`(com.lunarclient.apollo.coloredfire.v1.ResetColoredFiresMessage::class.java) -> fire.clear()
                any.`is`(com.lunarclient.apollo.limb.v1.HideArmorPiecesMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.limb.v1.HideArmorPiecesMessage::class.java).let { m ->
                        val u = uuid(m.playerUuid)
                        hiddenArmor[u] = (hiddenArmor[u] ?: emptySet()) + m.armorPiecesList.map { it.number }
                    }
                any.`is`(com.lunarclient.apollo.limb.v1.ResetArmorPiecesMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.limb.v1.ResetArmorPiecesMessage::class.java).let { m ->
                        val u = uuid(m.playerUuid)
                        hiddenArmor[u] = (hiddenArmor[u] ?: emptySet()) - m.armorPiecesList.map { it.number }.toSet()
                    }
                any.`is`(com.lunarclient.apollo.limb.v1.HideBodyPartMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.limb.v1.HideBodyPartMessage::class.java).let { m ->
                        val u = uuid(m.playerUuid)
                        hiddenParts[u] = (hiddenParts[u] ?: emptySet()) + m.bodyPartsList.map { it.number }
                    }
                any.`is`(com.lunarclient.apollo.limb.v1.ResetBodyPartMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.limb.v1.ResetBodyPartMessage::class.java).let { m ->
                        val u = uuid(m.playerUuid)
                        hiddenParts[u] = (hiddenParts[u] ?: emptySet()) - m.bodyPartsList.map { it.number }.toSet()
                    }
                any.`is`(com.lunarclient.apollo.entity.v1.FlipEntityMessage::class.java) ->
                    flipped += any.unpack(com.lunarclient.apollo.entity.v1.FlipEntityMessage::class.java).entityIdsList.map { it.entityId }
                any.`is`(com.lunarclient.apollo.entity.v1.ResetFlipedEntityMessage::class.java) ->
                    flipped -= any.unpack(com.lunarclient.apollo.entity.v1.ResetFlipedEntityMessage::class.java).entityIdsList.map { it.entityId }.toSet()
                any.`is`(com.lunarclient.apollo.entity.v1.OverrideRainbowSheepMessage::class.java) ->
                    rainbowSheep += any.unpack(com.lunarclient.apollo.entity.v1.OverrideRainbowSheepMessage::class.java).entityIdsList.map { it.entityId }
                any.`is`(com.lunarclient.apollo.entity.v1.ResetRainbowSheepMessage::class.java) ->
                    rainbowSheep -= any.unpack(com.lunarclient.apollo.entity.v1.ResetRainbowSheepMessage::class.java).entityIdsList.map { it.entityId }.toSet()
                any.`is`(com.lunarclient.apollo.nickhider.v1.OverrideNickHiderMessage::class.java) ->
                    nick = any.unpack(com.lunarclient.apollo.nickhider.v1.OverrideNickHiderMessage::class.java).nick.ifEmpty { null }
                any.`is`(com.lunarclient.apollo.nickhider.v1.ResetNickHiderMessage::class.java) -> nick = null
                any.`is`(com.lunarclient.apollo.vignette.v1.DisplayVignetteMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.vignette.v1.DisplayVignetteMessage::class.java).let { m ->
                        val id = Identifier.tryParse(m.resourceLocation.ifEmpty { "minecraft:textures/misc/vignette.png" })
                        vignette = id?.let { it to m.opacity.coerceIn(0f, 1f) }
                    }
                any.`is`(com.lunarclient.apollo.vignette.v1.ResetVignetteMessage::class.java) -> vignette = null
                any.`is`(com.lunarclient.apollo.staffmod.v1.EnableStaffModsMessage::class.java) ->
                    staff(any.unpack(com.lunarclient.apollo.staffmod.v1.EnableStaffModsMessage::class.java).staffModsList.map { it.name }, true)
                any.`is`(com.lunarclient.apollo.staffmod.v1.DisableStaffModsMessage::class.java) ->
                    staff(any.unpack(com.lunarclient.apollo.staffmod.v1.DisableStaffModsMessage::class.java).staffModsList.map { it.name }, false)
                any.`is`(com.lunarclient.apollo.pingmarker.v1.DisplayPlayerPingMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.pingmarker.v1.DisplayPlayerPingMessage::class.java).let { m ->
                        val l = m.location
                        pings[uuid(m.id)] = Ping(Vec3(l.x, l.y, l.z), if (m.hasColor()) argb(m.color.color) else 0xFFFFFF55.toInt(),
                            System.currentTimeMillis() + ms(m.duration, 6000), m.type)
                    }
                any.`is`(com.lunarclient.apollo.pingmarker.v1.RemovePlayerPingMessage::class.java) ->
                    pings.remove(uuid(any.unpack(com.lunarclient.apollo.pingmarker.v1.RemovePlayerPingMessage::class.java).id))
                any.`is`(com.lunarclient.apollo.pingmarker.v1.ResetPlayerPingMessage::class.java) -> pings.clear()
                any.`is`(com.lunarclient.apollo.chat.v1.DisplayLiveChatMessageMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.chat.v1.DisplayLiveChatMessageMessage::class.java).let { m ->
                        mc.execute { liveLine(m.messageId, net.minecraft.network.chat.Component.literal(plain(m.adventureJsonLines))) }
                    }
                any.`is`(com.lunarclient.apollo.chat.v1.RemoveLiveChatMessageMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.chat.v1.RemoveLiveChatMessageMessage::class.java).let { m -> mc.execute { liveLine(m.messageId, null) } }
                any.`is`(com.lunarclient.apollo.richpresence.v1.OverrideServerRichPresenceMessage::class.java) ->
                    any.unpack(com.lunarclient.apollo.richpresence.v1.OverrideServerRichPresenceMessage::class.java).let { m ->
                        richPresence = RichPresence(m.gameName, m.gameVariantName, m.gameState, m.playerState, m.mapName, m.subServer,
                            m.teamCurrentSize, m.teamMaxSize)
                    }
                any.`is`(com.lunarclient.apollo.richpresence.v1.ResetServerRichPresenceMessage::class.java) -> richPresence = null
                else -> return false
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Apollo: couldn't read {}: {}", any.typeUrl, e.toString())
        }
        return true
    }

    private fun uuid(u: com.lunarclient.apollo.common.v1.Uuid) = UUID(u.high64, u.low64)

    /** Lunar's staff mods (X-ray) the server gives or takes: said in chat (Horizon has its own X-ray module). */
    private fun staff(mods: List<String>, on: Boolean) {
        val names = mods.filter { it != "UNRECOGNIZED" && !it.endsWith("UNSPECIFIED") }.map { ApolloBlocks.modName(it.removePrefix("STAFF_MOD_").lowercase()) }
        if (names.isEmpty()) return
        mc.execute { LunarSettings.tell("this server " + (if (on) "gives you Lunar's staff " else "takes away Lunar's staff ") + names.joinToString(", ")) }
    }

    /** A live chat line: shown (replacing the one with [id]) or, with null, taken back. Game thread. */
    private fun liveLine(id: Int, line: net.minecraft.network.chat.Component?) {
        val chat = mc.gui.hud.chat
        val old = liveChat.remove(id)
        if (old != null) {
            val all = (chat as dev.horizon.client.mixin.ChatComponentAccessor).horizon_allMessages()
            if (all.removeIf { it.content() == old }) chat.rescaleChat()
        }
        if (line != null) {
            liveChat[id] = line
            mc.player?.sendSystemMessage(line)
        }
    }

    /** The fire colour the server set for the entity with id [entityId], or null. */
    @JvmStatic
    fun fireColor(entityId: Int): Int? {
        if (!shown || fire.isEmpty()) return null
        val e = mc.level?.getEntity(entityId) ?: return null
        return fire[e.uuid]
    }

    @JvmStatic
    fun isFlipped(entityId: Int) = shown && entityId in flipped

    @JvmStatic
    fun isRainbowSheep(entityId: Int) = shown && entityId in rainbowSheep

    /** Whether armour slot [piece] (1 helmet .. 4 boots) is hidden on the player with entity id [entityId]. */
    @JvmStatic
    fun armorHidden(entityId: Int, piece: Int): Boolean {
        if (!shown || hiddenArmor.isEmpty()) return false
        val e = mc.level?.getEntity(entityId) ?: return false
        return hiddenArmor[e.uuid]?.contains(piece) == true
    }

    /** The body parts hidden on the player with entity id [entityId] (1 head, 2 torso, 3 left arm, 4 right arm, 5 left leg, 6 right leg). */
    @JvmStatic
    fun partsHidden(entityId: Int): Set<Int> {
        if (!shown || hiddenParts.isEmpty()) return emptySet()
        val e = mc.level?.getEntity(entityId) ?: return emptySet()
        return hiddenParts[e.uuid] ?: emptySet()
    }

    @JvmStatic
    fun anyHiddenParts() = hiddenParts.values.any { it.isNotEmpty() }

    /** The server's name tag lines for [uuid] (top first), or null. */
    @JvmStatic
    fun nametag(uuid: UUID): List<String>? = if (shown) nametags[uuid] else null

    /** [text] with your name shown as the server's nick (Lunar's nick hider). */
    @JvmStatic
    fun hideNick(text: String): String {
        val n = nick ?: return text
        if (!shown) return text
        val me = mc.user.name
        return if (me.isNotEmpty() && text.contains(me)) text.replace(me, n) else text
    }

    /** Apollo's colour (ARGB; an alpha of 0 read as opaque). */
    private fun argb(c: Int) = if (c ushr 24 == 0) c or (0xFF shl 24) else c

    private fun ms(d: com.google.protobuf.Duration, default: Long): Long =
        if (d.seconds == 0L && d.nanos == 0) default else d.seconds * 1000 + d.nanos / 1_000_000

    /** An Apollo Component's text (its content and its children's). Game-free. */
    fun text(c: Component): String = c.content + c.childrenList.joinToString("") { text(it) }

    /** Text from Adventure's JSON (its "text" values, in order). Game-free. */
    fun plain(json: String): String {
        if (json.isBlank()) return ""
        return try {
            val out = StringBuilder()
            fun walk(e: com.google.gson.JsonElement) {
                when {
                    e.isJsonPrimitive -> out.append(e.asString)
                    e.isJsonArray -> e.asJsonArray.forEach { walk(it) }
                    e.isJsonObject -> {
                        val o = e.asJsonObject
                        o.get("text")?.let { if (it.isJsonPrimitive) out.append(it.asString) }
                        o.get("extra")?.let { walk(it) }
                    }
                }
            }
            walk(com.google.gson.JsonParser.parseString(json))
            out.toString()
        } catch (e: Exception) {
            json
        }
    }

    private fun itemStack(name: String): ItemStack? {
        if (name.isEmpty()) return null
        val id = Identifier.tryParse(name.lowercase()) ?: return null
        val item = BuiltInRegistries.ITEM.getOptional(id).orElse(null) ?: return null
        return ItemStack(item)
    }

    // ---- drawing ----

    private fun draw3d() {
        if (!shown) return
        val level = mc.level ?: return
        val top = level.maxY.toDouble() + 64
        for (b in beams.values) {
            val box = AABB(b.pos.x - 0.15, b.pos.y + 1, b.pos.z - 0.15, b.pos.x + 0.15, top, b.pos.z + 0.15)
            World3D.fill(box, (b.color and 0xFFFFFF) or 0x66000000)
        }
        for (w in waypoints.values) {
            if (w.hidden) continue
            World3D.line(w.pos, w.pos.add(0.0, 3.0, 0.0), w.color, (w.color and 0xFFFFFF), noCull = true)
        }
        // The borders: their edges at your height.
        val p = mc.player ?: return
        val y0 = p.y - 2
        val y1 = p.y + 4
        for (b in borders.values) {
            for (y in listOf(y0, p.y + 1, y1)) {
                World3D.line(Vec3(b.minX, y, b.minZ), Vec3(b.maxX, y, b.minZ), b.color)
                World3D.line(Vec3(b.maxX, y, b.minZ), Vec3(b.maxX, y, b.maxZ), b.color)
                World3D.line(Vec3(b.maxX, y, b.maxZ), Vec3(b.minX, y, b.maxZ), b.color)
                World3D.line(Vec3(b.minX, y, b.maxZ), Vec3(b.minX, y, b.minZ), b.color)
            }
        }
        if (glow.isNotEmpty()) for (pl in level.players()) {
            val c = glow[pl.uuid] ?: continue
            if (pl === p && mc.options.cameraType.isFirstPerson) continue
            World3D.outline(pl.boundingBox.inflate(0.05), c)
        }
    }

    private fun draw2d() {
        if (!shown) return
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        val cam = mc.cameraEntity?.position() ?: return
        // Waypoints: name and distance.
        for ((name, w) in waypoints) {
            if (w.hidden) continue
            val s = World3D.project(w.pos.add(0.0, 1.2, 0.0)) ?: continue
            val d = cam.distanceTo(w.pos)
            val text = "$name  ${d.toInt()}m"
            val tw = Fonts.semibold8.width(text)
            Canvas.box(s[0] - tw / 2f - 4f, s[1] - 6f, tw + 8f, 12f, 3f, 0x99000000.toInt(), 1f, w.color)
            Fonts.semibold8.queue(text, s[0] - tw / 2f, s[1] - 5f, -1)
        }
        // Teammates: a marker over each (their entity when it's loaded).
        val me = mc.player?.uuid
        for ((u, t) in team) {
            if (u == me) continue
            val e = level.getPlayerByUUID(u)
            val pos = e?.position()?.add(0.0, e.bbHeight + 0.6, 0.0) ?: t.pos.add(0.0, 2.4, 0.0)
            val s = World3D.project(pos) ?: continue
            Canvas.box(s[0] - 4f, s[1] - 4f, 8f, 8f, 4f, t.color, 1f, 0xFF000000.toInt())
            val tw = Fonts.medium75.width(t.name)
            Fonts.medium75.queue(t.name, s[0] - tw / 2f, s[1] - 14f, t.color)
        }
        // Holograms.
        for (h in holograms.values) {
            var y = 0f
            for (line in h.lines) {
                val s = World3D.project(h.pos) ?: break
                val tw = Fonts.medium8.width(line)
                Canvas.box(s[0] - tw / 2f - 2f, s[1] + y - 1f, tw + 4f, 10f, 0f, 0x55000000)
                Fonts.medium8.queue(line, s[0] - tw / 2f, s[1] + y, -1)
                y += 10f
            }
        }
        // Ping markers, until they time out.
        for ((id, p) in pings) {
            if (now > p.until) {
                pings.remove(id)
                continue
            }
            val s = World3D.project(p.pos) ?: continue
            Canvas.box(s[0] - 5f, s[1] - 5f, 10f, 10f, 5f, p.color, 1.5f, 0xFF000000.toInt())
            val text = "${cam.distanceTo(p.pos).toInt()}m"
            Fonts.medium75.queue(text, s[0] - Fonts.medium75.width(text) / 2f, s[1] + 7f, p.color)
        }
        // The server's vignette, darkening the edges as vanilla's does (its pipeline: the texture's brightness darkens).
        vignette?.let { (tex, a) ->
            val v = (a * 255).toInt().coerceIn(0, 255)
            val w = mc.window.guiScaledWidth
            val h = mc.window.guiScaledHeight
            Canvas.graphics?.blit(net.minecraft.client.renderer.RenderPipelines.VIGNETTE, tex, 0, 0, 0f, 0f, w, h, w, h, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        // TNT the server set a fuse for: seconds left.
        if (tnt.isNotEmpty()) for (e in level.entitiesForRendering()) {
            if (e !is PrimedTnt) continue
            val fuse = tnt[e.id] ?: continue
            val left = ((fuse - e.tickCount).coerceAtLeast(0)) / 20f
            val s = World3D.project(e.position().add(0.0, 1.3, 0.0)) ?: continue
            val text = String.format("%.1f", left)
            val tw = Fonts.semibold8.width(text)
            Fonts.semibold8.queue(text, s[0] - tw / 2f, s[1], if (left < 1f) 0xFFFF5555.toInt() else -1)
        }
        val sw = mc.window.guiScaledWidth.toFloat()
        val sh = mc.window.guiScaledHeight.toFloat()
        // Cooldowns: on the left, mid-height.
        var cy = sh / 2f - cooldowns.size * 9f
        val g = Canvas.graphics
        for ((name, c) in cooldowns.entries.sortedBy { it.value.ends }) {
            val left = c.ends - now
            if (left <= 0) {
                cooldowns.remove(name)
                continue
            }
            Canvas.box(4f, cy, 96f, 16f, 4f, 0x88000000.toInt())
            if (c.total > 0) Canvas.box(4f, cy + 14f, 96f * left / c.total, 2f, 1f, 0xFFFFFFFF.toInt())
            c.item?.let { g?.item(it, 6, cy.toInt()) }
            Fonts.medium75.queue(name, 24f, cy + 4f, -1)
            val secs = String.format("%.1fs", left / 1000f)
            Fonts.medium75.queue(secs, 98f - Fonts.medium75.width(secs), cy + 4f, 0xFFDDDDDD.toInt())
            cy += 18f
        }
        // The stopwatch, at the top.
        if (stopwatchStart != 0L) {
            val t = ((if (stopwatchStop != 0L) stopwatchStop else now) - stopwatchStart) / 1000.0
            val text = String.format("%d:%04.1f", (t / 60).toInt(), t % 60)
            val tw = Fonts.semibold12.width(text)
            Canvas.box(sw / 2f - tw / 2f - 6f, 4f, tw + 12f, 16f, 4f, 0x88000000.toInt())
            Fonts.semibold12.queue(text, sw / 2f - tw / 2f, 6f, if (stopwatchStop != 0L) 0xFFFFC94A.toInt() else -1)
        }
        // Titles, with their fades.
        for (t in titles.values.toList()) {
            val age = now - t.start
            val total = t.fadeIn + t.stay + t.fadeOut
            if (age > total) {
                titles.remove(t.subtitle)
                continue
            }
            val a = when {
                age < t.fadeIn -> age / t.fadeIn.toFloat()
                age > t.fadeIn + t.stay -> 1f - (age - t.fadeIn - t.stay) / t.fadeOut.toFloat()
                else -> 1f
            }.coerceIn(0.05f, 1f)
            val font = if (t.subtitle) Fonts.semibold12 else Fonts.bold25
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(sw / 2f, sh / 2f + (if (t.subtitle) 6f else -26f))
            pose.scale(t.scale, t.scale)
            font.draw(t.text, -font.width(t.text) / 2f, 0f, ((a * 255).toInt() shl 24) or 0xFFFFFF)
            pose.popMatrix()
        }
        Fonts.semibold8.flush()
        Fonts.medium75.flush()
        Fonts.medium8.flush()
        Fonts.semibold12.flush()
    }

    /** For ShotBot (`apollofeatures`): a set of Apollo messages around you, as a server would send them. */
    @JvmStatic
    fun testShow() {
        val p = mc.player ?: return
        val x = p.blockX
        val y = p.blockY
        val z = p.blockZ
        fun loc(dx: Int, dz: Int) = com.lunarclient.apollo.common.v1.BlockLocation.newBuilder().setWorld("world").setX(x + dx).setY(y).setZ(z + dz).build()
        fun color(c: Int) = com.lunarclient.apollo.common.v1.Color.newBuilder().setColor(c).build()
        fun comp(s: String) = Component.newBuilder().setContent(s).build()
        fun secs(s: Long) = com.google.protobuf.Duration.newBuilder().setSeconds(s).build()
        handle(Any.pack(DisplayWaypointMessage.newBuilder().setName("Base").setLocation(loc(8, -4)).setColor(color(0xFF55FFFF.toInt())).build()))
        handle(Any.pack(DisplayBeaconBeamMessage.newBuilder().setId("objective").setLocation(loc(10, 4)).setColor(color(0xFFFF5555.toInt())).build()))
        handle(Any.pack(DisplayHologramMessage.newBuilder().setId("h").setLocation(com.lunarclient.apollo.common.v1.Location.newBuilder()
            .setWorld("world").setX(x + 6.5).setY(y + 2.5).setZ(z + 0.5).build()).addLines(comp("Welcome to the arena")).addLines(comp("Kills: 3")).build()))
        handle(Any.pack(DisplayTitleMessage.newBuilder().setTitleType(TitleType.TITLE_TYPE_TITLE).setMessage(comp("Round 2")).setScale(1f)
            .setDisplayTime(secs(30)).build()))
        handle(Any.pack(DisplayTitleMessage.newBuilder().setTitleType(TitleType.TITLE_TYPE_SUBTITLE).setMessage(comp("Fight!")).setScale(1f)
            .setDisplayTime(secs(30)).build()))
        handle(Any.pack(DisplayCooldownMessage.newBuilder().setName("Ender Pearl").setDuration(secs(15))
            .setIcon(com.lunarclient.apollo.common.v1.Icon.newBuilder().setItemStack(com.lunarclient.apollo.common.v1.ItemStackIcon.newBuilder()
                .setItemName("minecraft:ender_pearl"))).build()))
        handle(Any.pack(com.lunarclient.apollo.border.v1.DisplayBorderMessage.newBuilder().setId("zone").setWorld("world").setColor(color(0xFFFFAA00.toInt()))
            .setBounds(com.lunarclient.apollo.common.v1.Cuboid2D.newBuilder().setMinX(x - 12.0).setMinZ(z - 12.0).setMaxX(x + 12.0).setMaxZ(z + 12.0)).build()))
        handle(Any.pack(StartStopwatchMessage.getDefaultInstance()))
        handle(Any.pack(DisplayNotificationMessage.newBuilder().setTitle("Arena").setDescription("You joined the red team").setDisplayTime(secs(10)).build()))
    }

    /**
     * For ShotBot (`apolloextras`): the fake player [uuid] with two name tag lines, its left arm hidden, turned upside
     * down; a ping marker in front of you; a red vignette; your name hidden as "Hidden".
     */
    @JvmStatic
    fun testExtras(uuid: UUID) {
        val u = com.lunarclient.apollo.common.v1.Uuid.newBuilder().setHigh64(uuid.mostSignificantBits).setLow64(uuid.leastSignificantBits).build()
        val pl = mc.level?.getPlayerByUUID(uuid) ?: return
        handle(Any.pack(com.lunarclient.apollo.nametag.v1.OverrideNametagMessage.newBuilder().setPlayerUuid(u)
            .addLines(Component.newBuilder().setContent("[RED TEAM]")).addLines(Component.newBuilder().setContent("Kills: 4")).build()))
        handle(Any.pack(com.lunarclient.apollo.limb.v1.HideBodyPartMessage.newBuilder().setPlayerUuid(u)
            .addBodyParts(com.lunarclient.apollo.limb.v1.BodyPart.BODY_PART_LEFT_ARM).build()))
        handle(Any.pack(com.lunarclient.apollo.entity.v1.FlipEntityMessage.newBuilder()
            .addEntityIds(com.lunarclient.apollo.common.v1.EntityId.newBuilder().setEntityId(pl.id)).build()))
        val me = mc.player ?: return
        handle(Any.pack(com.lunarclient.apollo.pingmarker.v1.DisplayPlayerPingMessage.newBuilder()
            .setId(com.lunarclient.apollo.common.v1.Uuid.newBuilder().setHigh64(1).setLow64(2))
            .setLocation(com.lunarclient.apollo.common.v1.Location.newBuilder().setWorld("world").setX(me.x + 6).setY(me.y + 1).setZ(me.z - 3))
            .setColor(com.lunarclient.apollo.common.v1.Color.newBuilder().setColor(0xFFFFAA00.toInt()))
            .setDuration(com.google.protobuf.Duration.newBuilder().setSeconds(60)).build()))
        handle(Any.pack(com.lunarclient.apollo.vignette.v1.DisplayVignetteMessage.newBuilder()
            .setResourceLocation("minecraft:textures/misc/vignette.png").setOpacity(0.8f).build()))
        handle(Any.pack(com.lunarclient.apollo.nickhider.v1.OverrideNickHiderMessage.newBuilder().setNick("Hidden").build()))
    }

    /** For ShotBot: the fake player [uuid] as a teammate in [argb], and glowing in [glowArgb]. */
    @JvmStatic
    fun testTeam(uuid: UUID, name: String, argb: Int, glowArgb: Int) {
        val u = com.lunarclient.apollo.common.v1.Uuid.newBuilder().setHigh64(uuid.mostSignificantBits).setLow64(uuid.leastSignificantBits).build()
        val pl = mc.level?.getPlayerByUUID(uuid)
        val l = com.lunarclient.apollo.common.v1.Location.newBuilder().setWorld("world").setX(pl?.x ?: 0.0).setY(pl?.y ?: 0.0).setZ(pl?.z ?: 0.0).build()
        handle(Any.pack(UpdateTeamMembersMessage.newBuilder().addMembers(com.lunarclient.apollo.team.v1.TeamMember.newBuilder().setPlayerUuid(u)
            .setPlayerDisplayName(Component.newBuilder().setContent(name)).setLocation(l)
            .setMarkerColor(com.lunarclient.apollo.common.v1.Color.newBuilder().setColor(argb))).build()))
        handle(Any.pack(OverrideGlowEffectMessage.newBuilder().setPlayerUuid(u).setColor(com.lunarclient.apollo.common.v1.Color.newBuilder().setColor(glowArgb)).build()))
    }
}
