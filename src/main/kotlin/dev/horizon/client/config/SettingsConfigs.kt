package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import dev.horizon.client.setting.Setting
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** Anything with an id and a settings list saved by id: modules and HUD widgets. */
interface SettingsHolder {
    val id: String
    val settings: List<Setting<*>>
}

@Serializable
data class SettingEntry(val id: String, val value: String)

@Serializable
data class HolderEntry(val id: String, val settings: List<SettingEntry>)

@Serializable
data class ModulesFile(val id: String = "default", val modules: List<HolderEntry> = emptyList())

@Serializable
data class WidgetsFile(val id: String = "default", val widgets: List<HolderEntry> = emptyList())

/** One named config in memory: the saved settings of each holder. */
class NamedConfig(val id: String, var holders: List<HolderEntry> = emptyList())

/**
 * Named configs of settings holders in one folder (`modules/` or `widgets/`).
 * They live in memory: [reload] reads the folder, [writeAll] writes every config back, and loading, creating or deleting
 * a config only changes memory until the next autosave writes it.
 */
abstract class SettingsConfigs<F>(private val folder: String, private val serializer: KSerializer<F>, private val loadError: String) {
    protected abstract fun holders(): List<SettingsHolder>
    protected abstract fun wrap(id: String, entries: List<HolderEntry>): F
    protected abstract fun unwrap(file: F): NamedConfig

    /** The current config's name, kept in shared.hzn. */
    abstract var currentName: String

    /** Every config in the folder's order at the last [reload]; a config just snapshotted is moved to the end. */
    val list = ArrayList<NamedConfig>()

    var current = NamedConfig("default")

    val dir: Path get() = Storage.dir(folder)

    /** Read every `.hzn`; any unreadable file empties the list. The current one is created if missing. */
    fun reload() {
        val read = try {
            Files.list(dir).use { s -> s.filter { it.extension == "hzn" }.toList() }
                .map { unwrap(Storage.json.decodeFromString(serializer, Files.readString(it))) }
        } catch (e: Exception) {
            HorizonClient.LOG.warn(loadError)
            emptyList()
        }
        list.clear()
        list.addAll(read)
        current = list.firstOrNull { it.id == currentName } ?: NamedConfig(currentName).also { list += it }
    }

    /** Write every config to `<id>.hzn`. */
    fun writeAll() {
        for (c in list.toList()) Storage.write(dir.resolve(c.id + ".hzn"), serializer, wrap(c.id, c.holders))
    }

    /**
     * The live settings become config [id] (created if missing), which moves to the end of the
     * list. Entries of holders this client doesn't have (from an imported config) are kept after the live ones.
     */
    fun snapshot(id: String) {
        val cfg = list.firstOrNull { it.id == id } ?: NamedConfig(id)
        val live = holders().map { h -> HolderEntry(h.id, h.settings.map { SettingEntry(it.id, it.save()) }) }
        val ids = live.mapTo(HashSet()) { it.id }
        cfg.holders = live + cfg.holders.filter { it.id !in ids }
        list.removeIf { it.id == id }
        list += cfg
    }

    /** Every setting found in the current config takes its value; one that doesn't parse resets. */
    fun apply() {
        for (h in holders()) {
            val entry = current.holders.firstOrNull { it.id == h.id } ?: continue
            for (s in h.settings) {
                val v = entry.settings.firstOrNull { it.id == s.id } ?: continue
                try {
                    s.parse(v.value)
                } catch (e: Exception) {
                    s.reset()
                    HorizonClient.LOG.warn("Could not parse setting: {} in {}: {}", s.displayName, h.id, e.message)
                }
            }
        }
    }
}
