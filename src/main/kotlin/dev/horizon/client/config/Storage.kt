package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * The client's files, laid out like the original's `~/.ctl`: `modules/`, `widgets/` and `themes/` hold named configs
 * (`<name>.hzn`), `shared.hzn` the ones in use, and `waypoints.hzn`, `proxy.hzn`, `macro.hzn`, `homies.hzn`
 * the window data. The folder is `<game dir>/horizon`.
 */
object Storage {
    val root: Path get() = FabricLoader.getInstance().gameDir.resolve("horizon")

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "    "
    }

    /** The original's `shared.hzn`: which named configs are in use. */
    @Serializable
    data class Shared(
        var modulesConfig: String = "default",
        var widgetsConfig: String = "default",
        var inventoriesConfig: String = "default",
        var themeConfig: String = "Horizon",
        var rpcAsked: Boolean = false,
        /** Not in the original: the redesign's themes (ThemeData.presets) were added to the list once. */
        var themePresets: Boolean = false,
        /** Not in the original: the Monochrome theme (added after the redesign's) was added to the list once. */
        var themeMonochrome: Boolean = false,
        /** Not in the original: the server last joined (its name and address), for the title screen's Rejoin row. */
        var lastServerName: String = "",
        var lastServerIp: String = "",
        var autoUpdate: Boolean = true,
    )

    var shared = Shared()
        private set

    fun dir(name: String): Path = root.resolve(name).also { Files.createDirectories(it) }

    /** The config names in a folder (files ending in .hzn), sorted. */
    fun names(folder: String): List<String> {
        val d = dir(folder)
        return Files.list(d).use { s -> s.filter { it.extension == "hzn" }.map { it.nameWithoutExtension }.sorted().toList() }
    }

    fun <T> read(path: Path, serializer: KSerializer<T>): T? {
        if (!Files.exists(path)) return null
        return try {
            json.decodeFromString(serializer, Files.readString(path))
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't read {}", path, e)
            null
        }
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(serializer, value))
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't save {}", path, e)
        }
    }

    fun loadShared() {
        shared = read(root.resolve("shared.hzn"), Shared.serializer()) ?: Shared()
    }

    fun saveShared() = write(root.resolve("shared.hzn"), Shared.serializer(), shared)
}
