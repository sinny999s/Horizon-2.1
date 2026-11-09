package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import dev.horizon.client.gui.Keys
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.Setting
import net.fabricmc.loader.api.FabricLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * On the first start (no config folder yet) the client starts from what came before it: everything in the game folder's
 * old `kclient` folder if there is one, else the data in `~/.ctl`. Both are only read. From `~/.ctl`,
 * binds are converted from GLFW key codes to the game's.
 */
object ConfigImport {
    private const val OLD_EXTENSION = "ctl" + "on"

    /** Old names in a config, as they were saved. */
    private val OLD_VALUES = listOf(
        "\"value\": \"catlean.su\"" to "\"value\": \"Horizon\"",
        "\"value\": \" [CatLean]\"" to "\"value\": \" [Horizon]\"",
        "\"themeConfig\": \"CatLean\"" to "\"themeConfig\": \"Horizon\"",
        "\"id\": \"CatLean default\"" to "\"id\": \"Horizon default\"",
        "\"id\": \"CatLean\"" to "\"id\": \"Horizon\"",
    )

    /** `~/.ctl`; `-Dhorizon.ctl=<folder>` points the import elsewhere (used by the comparison runs). */
    private val source: Path get() = System.getProperty("horizon.ctl")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".ctl")

    /** Key names by GLFW code. */
    private val glfwNames: Map<Int, String> = buildMap {
        put(32, "Space"); put(39, "Apostrophe"); put(44, "Comma"); put(45, "Minus"); put(46, "Period"); put(47, "Slash")
        for (i in 0..9) put(48 + i, i.toString())
        put(59, "SemiColon"); put(61, "Equal")
        for (c in 'A'..'Z') put(c.code, c.toString())
        put(91, "LeftBracket"); put(92, "BackSlash"); put(93, "RightBracket"); put(96, "Grave")
        put(256, "Escape"); put(257, "Enter"); put(258, "Tab"); put(259, "BackSpace"); put(260, "Insert"); put(261, "Delete")
        put(262, "Right"); put(263, "Left"); put(264, "Down"); put(265, "Up"); put(266, "PageUp"); put(267, "PageDown")
        put(268, "Home"); put(269, "End"); put(280, "CapsLock"); put(281, "ScrollLock"); put(282, "NumLock")
        put(283, "PrintScreen"); put(284, "Pause")
        for (i in 1..25) put(289 + i, "F$i")
        for (i in 0..9) put(320 + i, "NUM $i")
        put(330, "Deci"); put(331, "Div"); put(332, "Mul"); put(333, "Sub"); put(334, "Add"); put(335, "Enter"); put(336, "Equal")
        put(340, "Shift"); put(341, "LCtrl"); put(342, "LAlt"); put(343, "LSuper"); put(344, "RShift"); put(345, "RCtrl")
        put(346, "RAlt"); put(347, "RSuper"); put(348, "Menu")
    }

    fun convert(b: Bind): Bind = when {
        b.key == -1 -> b
        b.mouse -> b.copy(key = b.key + 1)
        else -> b.copy(key = glfwNames[b.key]?.let { Keys.code(it) } ?: -1)
    }

    /** The old folder in the game folder, copied whole: every config, the map, bases, trails. */
    private fun importOldFolder(old: Path): Boolean {
        try {
            Files.walk(old).use { walk ->
                for (from in walk.toList()) {
                    val rel = old.relativize(from).toString()
                    if (Files.isDirectory(from)) {
                        Files.createDirectories(Storage.root.resolve(rel))
                        continue
                    }
                    if (from.extension != OLD_EXTENSION) {
                        val target = Storage.root.resolve(rel)
                        Files.createDirectories(target.parent)
                        Files.copy(from, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        continue
                    }
                    var text = Files.readString(from)
                    for ((a, b) in OLD_VALUES) text = text.replace(a, b)
                    val name = from.fileName.toString().removeSuffix(".$OLD_EXTENSION").let { if (from.parent.fileName.toString() == "themes") it.replace("CatLean", "Horizon") else it }
                    val target = Storage.root.resolve(rel).resolveSibling("$name.hzn")
                    Files.createDirectories(target.parent)
                    Files.writeString(target, text)
                }
            }
            HorizonClient.LOG.info("Moved the config over from {}", old)
            return true
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't move the config over from {}", old, e)
            return false
        }
    }

    fun runIfFirstStart() {
        val old = FabricLoader.getInstance().gameDir.resolve("kcli" + "ent")
        if (Files.isDirectory(old)) {
            val horizonModules = Storage.root.resolve("modules")
            val needsImport = !Files.exists(Storage.root) || !Files.exists(horizonModules) || (Files.list(horizonModules).use { it.count() } <= 1)
            if (needsImport && importOldFolder(old)) return
        }
        if (Files.exists(Storage.root)) return
        if (!Files.isDirectory(source)) return
        try {
            for (folder in listOf("themes", "widgets")) copyFolder(folder) { it }
            copyFolder("modules") { text -> convertBinds(text) }
            val macros = source.resolve("macro.$OLD_EXTENSION")
            if (Files.isRegularFile(macros)) Files.writeString(Storage.root.resolve("macro.hzn"), convertMacroBinds(Files.readString(macros)))
            for (f in listOf("shared", "waypoints", "proxy", "homies")) {
                val from = source.resolve("$f.$OLD_EXTENSION")
                if (Files.isRegularFile(from)) Files.copy(from, Storage.root.resolve("$f.hzn").also { Files.createDirectories(it.parent) })
            }
            val spammer = source.resolve("spammer.txt")
            if (Files.isRegularFile(spammer)) Files.copy(spammer, Storage.root.resolve("spammer.txt"))
            HorizonClient.LOG.info("Imported CatLean's data from {}", source)
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't import CatLean's data from {}", source, e)
        }
    }

    private fun copyFolder(folder: String, transform: (String) -> String) {
        val from = source.resolve(folder)
        if (!Files.isDirectory(from)) return
        val to = Storage.dir(folder)
        Files.list(from).use { s ->
            s.filter { it.extension == OLD_EXTENSION }.forEach { Files.writeString(to.resolve(it.nameWithoutExtension + ".hzn"), transform(Files.readString(it))) }
        }
    }

    /** Rewrites the `bind` object of every macro to the game's key codes. */
    private fun convertMacroBinds(text: String): String {
        val root = Storage.json.parseToJsonElement(text).jsonObject
        val out = JsonObject(root.mapValues { (_, list) ->
            if (list !is JsonArray) list
            else JsonArray(list.map { m ->
                val o = m.jsonObject
                val bind = o["bind"] ?: return@map m
                val b = convert(Storage.json.decodeFromJsonElement(Bind.serializer(), bind))
                JsonObject(o + ("bind" to Storage.json.encodeToJsonElement(Bind.serializer(), b)))
            })
        })
        return Storage.json.encodeToString(JsonObject.serializer(), out)
    }

    /** Rewrites every bind value (`{"key":..,"hold":..,"mouse":..}`) of a module config to the game's key codes. */
    private fun convertBinds(text: String): String {
        val file = Storage.json.decodeFromString(ModulesFile.serializer(), text)
        val converted = file.copy(modules = file.modules.map { m ->
            m.copy(settings = m.settings.map { s ->
                if (s.value.startsWith("{\"key\"")) {
                    try {
                        val b = Setting.JSON.decodeFromString(Bind.serializer(), s.value)
                        s.copy(value = Setting.JSON.encodeToString(Bind.serializer(), convert(b)))
                    } catch (_: Exception) {
                        s
                    }
                } else {
                    s
                }
            })
        })
        return Storage.json.encodeToString(ModulesFile.serializer(), converted)
    }
}
