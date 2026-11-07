package dev.horizon.client.config

import dev.horizon.client.Chat
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.ModernWindows
import net.minecraft.client.Minecraft
import dev.horizon.client.hud.WidgetConfig
import dev.horizon.client.module.Config
import dev.horizon.client.module.Modules
import dev.horizon.client.module.equip.Kits
import dev.horizon.client.render.Themes
import java.nio.file.Files
import java.nio.file.Path

/** The two kinds of named config the config panel shows (the original's `u`). */
enum class ConfigType { MODULES, WIDGETS }

/**
 * The original's config manager (`un`) and config actions (`yf`): start-up loading, the autosave, and Load / Delete / Create
 * from the config panel. Everything is kept in memory and written by the autosave (every 60 s in a world, every 2.5 s
 * while the Windows screen is open) and when the game closes.
 */
object Configs {
    fun of(type: ConfigType): SettingsConfigs<*> = if (type == ConfigType.MODULES) Config else WidgetConfig

    fun startup() {
        Storage.loadShared()
        Waypoints.load()
        Proxies.load()
        Macros.load()
        Friends.load()
        Kits.load()
        WidgetConfig.reload()
        Config.reload()
        Themes.load()
        Config.apply()
        Config.reconcile(null)
        WidgetConfig.apply()
        // Not in the original: Horizon's Lunar settings (global; carried over from Lunar Spoof's on first start).
        dev.horizon.client.lunar.LunarSettings.load()
    }

    /** The original's `un.j()`: the live settings go into the current configs, then every file is written. */
    fun saveAll() {
        Config.snapshot(Config.current.id)
        WidgetConfig.snapshot(WidgetConfig.current.id)
        Storage.saveShared()
        dev.horizon.client.lunar.LunarSettings.save()
        WidgetConfig.writeAll()
        Config.writeAll()
        Themes.save()
        Waypoints.save()
        Proxies.save()
        Macros.save()
        Friends.save()
        Kits.save()
    }

    /** The original's autosave (`zw`): save, then re-read the config folders; the config panel rebuilds on its next frame. */
    fun autosave() {
        saveAll()
        Config.reload()
        WidgetConfig.reload()
    }

    /**
     * The original's `yf.E`: the live settings are kept in the config being left, then [id] becomes current and is applied.
     * The name switches even when [id] doesn't exist. Prints the bound keys to chat.
     */
    fun load(id: String, type: ConfigType) {
        when (type) {
            ConfigType.MODULES -> {
                Config.snapshot(Config.currentName)
                val previous = Config.current
                Config.currentName = id
                val cfg = Config.list.firstOrNull { it.id == id } ?: return
                Config.current = cfg
                Config.apply()
                Config.reconcile(previous)
            }
            ConfigType.WIDGETS -> {
                WidgetConfig.snapshot(WidgetConfig.currentName)
                WidgetConfig.currentName = id
                val cfg = WidgetConfig.list.firstOrNull { it.id == id } ?: return
                WidgetConfig.current = cfg
                WidgetConfig.apply()
            }
        }
        Chat.send("§fKey Binds:")
        for (m in Modules.all.filter { it.bind.key != -1 }) Chat.send(m.name + "- " + "§f" + Keys.name(m.bind))
    }

    /**
     * The original's `yf.q`: deleting the current config loads the first one; deleting the only other one creates and loads
     * "default". No confirmation.
     */
    fun delete(id: String, type: ConfigType) {
        val configs = of(type)
        if (id == configs.current.id) {
            load(configs.list.first().id, type)
        } else if (configs.list.size == 1) {
            create("default", type)
            load("default", type)
        }
        configs.list.removeIf { it.id == id }
        Files.deleteIfExists(configs.dir.resolve("$id.hzn"))
    }

    /** The original's `yf.x`: the live settings saved as [id] (replacing one with that name); doesn't switch to it. */
    fun create(id: String, type: ConfigType) {
        val configs = of(type)
        configs.snapshot(id)
        configs.writeAll()
    }

    /**
     * The original's file drop: with the proxy window's "+" dialog open, a proxy list; anywhere else a `.hzn` is added as
     * a module config under the id written inside it.
     */
    fun filesDropped(paths: List<Path>) {
        val f = paths.firstOrNull() ?: return
        val screen = Minecraft.getInstance().gui.screen()
        if (screen === ModernWindows && ModernWindows.proxyDialogOpen) {
            val n = f.fileName.toString()
            if ((n.contains(".txt") || n.contains(".hzn")) && Proxies.import(f)) ModernWindows.proxiesImported()
            return
        }
        if (!f.fileName.toString().contains(".hzn")) return
        try {
            val file = Storage.json.decodeFromString(ModulesFile.serializer(), Files.readString(f))
            Config.list += NamedConfig(file.id, file.modules)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
