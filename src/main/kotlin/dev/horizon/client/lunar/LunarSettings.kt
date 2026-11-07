package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.SettingEntry
import dev.horizon.client.config.SettingsHolder
import dev.horizon.client.config.Storage
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.SkinEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Config
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.IntSetting
import dev.horizon.client.setting.StringSetting
import dev.horizon.client.setting.Setting
import kotlinx.serialization.builtins.ListSerializer
import net.minecraft.client.Minecraft

/**
 * Not in the original: Horizon's built-in Lunar features and their settings, kept once for every config in
 * `horizon/lunar.hzn` and set from the ClickGUI's Lunar menu (`gui/modern/LunarMenu`). [network] signs in to Lunar's
 * services with your account ([LunarNetwork]); the rest need it: Lunar's logo and badges by Lunar players' names, your
 * friends list and messages ([LunarFriends]), other players' cosmetics ([LunarCosmetics]) and the locker. None of this is
 * seen by the Minecraft server you're on; passing for Lunar Client on a server is Lunar Spoof's (the module).
 *
 * Until `lunar.hzn` exists, the values come from Lunar Spoof's settings in the current module config, where they were
 * before (Lunar Network counts as on only if the module was on too).
 */
object LunarSettings : SettingsHolder {
    override val id = "lunar"
    override val settings = ArrayList<Setting<*>>()

    private fun bool(id: String, default: Boolean) = BoolSetting(id, default).also { settings += it }

    /** Signs in to Lunar's services with your account (Lunar sees you online "on Lunar"). */
    val network = bool("lunar-network", false)

    val nametagLogo = bool("nametag-logo", true)
    val nametagBadge = bool("nametag-badge", true)
    val tabBadge = bool("tab-badge", true)

    val friends = bool("friends", true)
    val shareServer = bool("share-server", true)
    val friendMessages = bool("friend-messages", true)

    val cosmetics = bool("cosmetics", false)
    val cloaks = bool("cloaks", true)
    val models = bool("models", true)
    val emotes = bool("emotes", true)
    val particles = bool("particles", true)
    val lockerAccount = bool("locker-account", true)

    /** Chat lines: connected to Lunar, friends coming online, a server's Apollo blocks. */
    val notify = bool("notify", true)

    /** Lunar's own notifications as Horizon toasts ([LunarSocial]). */
    val notifications = bool("notifications", true)

    /** Your locker picks go to other Horizon users, and theirs come to you ([HorizonShare]). */
    val sharePicks = bool("share-picks", true)

    /** Horizon's share service (`share-worker/`, a Cloudflare Worker); empty: off. */
    val shareUrl = StringSetting("share-url", HorizonShare.DEFAULT_URL).also { settings += it }

    /** Emotes' music (jams) around the emoting player ([LunarJams]). */
    val emoteMusic = bool("emote-music", true)

    /** Lunar players' sprays on walls ([LunarSocial]). */
    val sprays = bool("sprays", true)

    /** What an Apollo server shows (waypoints, team markers, holograms, titles...: [ApolloFeatures]). */
    val apolloFeatures = bool("apollo-features", true)

    /** Puts your chosen spray on the face you look at. */
    val sprayKey = BindSetting("spray-key").also { settings += it }

    /** Plays the emote you last played (for other Lunar players too when you own it). */
    val emoteKey = BindSetting("emote-key").also { settings += it }

    /** The spray [sprayKey] puts up. */
    val spray = IntSetting("spray", 0, 0..100000).also { settings += it }

    /** A bind being set in the ClickGUI's Lunar menu: the next key or mouse button goes to it. */
    @Volatile
    var capturing: BindSetting? = null

    /**
     * Every key and mouse press (`module/Binds`): a bind being set takes it (Esc clears it); else, with no screen open,
     * the spray and emote binds. True when it was used here.
     */
    fun input(key: Int, mouse: Boolean, press: Boolean): Boolean {
        fun hit(b: BindSetting) = b.value.key == key && b.value.mouse == mouse && key != -1
        // Letting go of the emote key plays what the wheel points at.
        if (!press) {
            if (hit(emoteKey) && mc.gui.screen() === dev.horizon.client.gui.modern.LunarEmoteWheel) {
                dev.horizon.client.gui.modern.LunarEmoteWheel.release()
                return true
            }
            return false
        }
        capturing?.let { b ->
            capturing = null
            b.value = if (!mouse && key == dev.horizon.client.gui.Keys.ESCAPE) Bind() else Bind(key, false, mouse)
            save()
            return true
        }
        if (mc.gui.screen() != null || !network.value) return false
        if (hit(sprayKey)) {
            LunarSocial.chosenSpray = spray.value
            LunarSocial.spray()
            return true
        }
        if (hit(emoteKey)) {
            // The emote wheel while held; with nothing on it, the emote last played.
            if (LunarProfileEdits.wheel.isNotEmpty()) dev.horizon.client.gui.modern.LunarEmoteWheel.open()
            else if (LunarSocial.lastEmote != 0) LunarSocial.playEmote(LunarSocial.lastEmote)
            else tell("put emotes on your wheel: right-click them in the locker's Emotes tab")
            return true
        }
        return false
    }

    private val mc: Minecraft get() = Minecraft.getInstance()
    private val file get() = Storage.root.resolve("lunar.hzn")
    private val serializer = ListSerializer(SettingEntry.serializer())

    /** Lunar's services are on (signed in or signing in). */
    val on get() = network.value

    /** A chat line from Horizon's Lunar side. */
    fun tell(text: String) = moduleMessage("Lunar", text)

    fun init() {
        EventBus.on<TickEvent>(this) {
            // Lunar's version (claimed to Lunar's own services too), refreshed daily.
            if ((network.value || dev.horizon.client.module.misc.LunarSpoof.instance?.enabled == true) && LunarVersion.due) LunarVersion.fetchAsync()
            LunarNetwork.tick(network.value)
            LunarCosmetics.tick()
            LunarEmotes.tick()
            LunarJams.tick()
            HorizonShare.tick()
        }
        EventBus.on<SkinEvent>(this) { e ->
            val p = e.player ?: return@on
            e.skin = LunarCosmetics.skin(p, e.skin ?: return@on)
        }
    }

    /** At start-up, after the module configs are read. */
    fun load() {
        val saved = Storage.read(file, serializer)
        if (saved != null) {
            apply(saved)
            return
        }
        // From Lunar Spoof's settings in the current config, where these were before.
        val old = Config.current.holders.firstOrNull { it.id == "lunar-spoof" } ?: return
        apply(old.settings)
        val moduleOn = old.settings.firstOrNull { it.id == "enabled" }?.value == "true"
        if (!moduleOn) network.value = false
        HorizonClient.LOG.info("Lunar: settings carried over from Lunar Spoof in config {} (Lunar Network {})", Config.current.id, network.value)
        save()
    }

    private fun apply(entries: List<SettingEntry>) {
        for (s in settings) {
            val v = entries.firstOrNull { it.id == s.id } ?: continue
            try {
                s.parse(v.value)
            } catch (e: Exception) {
                s.reset()
            }
        }
    }

    fun save() = Storage.write(file, serializer, settings.map { SettingEntry(it.id, it.save()) })
}
