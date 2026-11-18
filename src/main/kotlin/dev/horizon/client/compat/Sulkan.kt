package dev.horizon.client.compat

import dev.horizon.shaders.ShadersGate
import net.minecraft.client.Minecraft
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Horizon's shaders (dev.horizon.shaders, built from Sulkan: Vulkan shaders on Sodium), while they run: their shader
 * packs, which one is selected and whether shaders are on, and the selected pack's options, for the client's Options
 * menu to draw in its own style in place of the shaders' own screens. Reached by reflection, so nothing of the module
 * (which needs Sodium's classes) loads while it's off.
 *
 * A pack's options are held as Sulkan's own settings screen holds them, in its settings model (whose labels, value
 * names and descriptions [Settings] uses, so they read as Sulkan's do) until [Settings.apply], which saves them and
 * rebuilds the pack when it's the live one.
 */
object Sulkan {
    private const val RUNTIME = "dev.horizon.shaders.runtime.ShaderRuntime"
    private const val BACKEND = "dev.horizon.shaders.runtime.BackendStatus"
    private const val NATIVE = "dev.horizon.shaders.graph.NativePackRuntime"
    private const val GRAPH = "dev.horizon.shaders.graph.PackGraph"
    private const val SCANNER = "dev.horizon.shaders.pack.ShaderPackScanner"
    private const val MODEL = "dev.horizon.shaders.screen.PackSettingsModel"

    /** The pack Sulkan ships with. */
    const val BUILTIN = "__builtin__"
    const val BUILTIN_NAME = "Sulkan Realistic"

    /** Whether the shaders run (Sodium installed, ShadersGate) and have what this reaches for. */
    val present: Boolean by lazy {
        ShadersGate.enabled() && runCatching {
            for (c in listOf(RUNTIME, BACKEND, NATIVE, GRAPH, SCANNER, MODEL)) cls(c)
            method(cls(RUNTIME), "applyConfig", 3)
            method(cls(NATIVE), "settings", 1)
            method(cls(NATIVE), "applyOptions", 2)
        }.isSuccess
    }

    class Pack(val id: String, val name: String)

    /** The packs Sulkan can run, and how many it found that it can't (Iris and OptiFine packs). */
    class Scan(val packs: List<Pack>, val unsupported: Int)

    /** Sulkan only draws on Vulkan; on OpenGL its shaders are paused. */
    fun vulkan(): Boolean = static(BACKEND, "vulkanActive") == true

    fun enabled(): Boolean = call(config(), "enabled") == true

    /** The selected pack's id ([BUILTIN] for the built-in one). */
    fun selected(): String = (call(config(), "selectedPackId") as? String)?.takeIf { it.isNotBlank() } ?: BUILTIN

    /** Why the selected pack failed to build, or null. */
    fun failure(): String? = static(NATIVE, "failure") as? String

    private val sceneFields: Pair<Field, Field>? by lazy {
        if (!present) null else runCatching { field(cls(NATIVE), "sceneScope") to field(cls(NATIVE), "active") }.getOrNull()
    }

    /**
     * During GameRenderer.renderLevel: whether a pack draws this frame's world into its own targets, so the finished
     * picture (hand included) is only in the main target after renderLevel returns. Cheap enough for every frame.
     */
    fun drawingScene(): Boolean {
        val (scope, active) = sceneFields ?: return false
        return scope.getBoolean(null) && active.get(null) != null
    }

    fun name(id: String) = if (id == BUILTIN) BUILTIN_NAME else id.removeSuffix(".zip")

    /** Scans the shader pack folder again. */
    fun scan(): Scan {
        val result = static(RUNTIME, "reloadPacks")
        val packs = (call(result, "supportedPacks") as? List<*>).orEmpty().mapNotNull { p ->
            val id = call(p, "id") as? String ?: return@mapNotNull null
            Pack(id, call(p, "displayName") as? String ?: name(id))
        }
        return Scan(packs, (call(result, "unsupportedPacks") as? List<*>)?.size ?: 0)
    }

    fun folder(): Path = static(SCANNER, "shaderPackDirectory") as Path

    /**
     * Turns shaders on or off and selects [packId], as Sulkan's own screen does ([reload]: rebuild the pack even when
     * nothing changed). Completes once Sulkan has applied it.
     */
    fun apply(enabled: Boolean, packId: String, reload: Boolean): CompletableFuture<*> {
        val next = call(config(), "withSelection", enabled, packId)
        return static(RUNTIME, "applyConfig", Minecraft.getInstance(), next, reload) as CompletableFuture<*>
    }

    /** [packId]'s options as saved, to change and [Settings.apply]. Throws when the pack's options can't be read. */
    fun settings(packId: String): Settings {
        val graph = static(NATIVE, "settings", packId)
        val model = cls(MODEL).getDeclaredConstructor(cls(GRAPH)).also { it.isAccessible = true }.newInstance(graph)
        return Settings(packId, model)
    }

    /** One pack's options, held by Sulkan's settings model until [apply]. */
    class Settings internal constructor(val packId: String, private val model: Any) {
        @Suppress("UNCHECKED_CAST")
        private val values = field(model.javaClass, "values").get(model) as MutableMap<String, Double>

        @Suppress("UNCHECKED_CAST")
        private val definitions = call(field(model.javaClass, "graph").get(model), "optionDefinitions") as Map<String, Any>

        /** The options on the Basic page, or all of them ([advanced]). */
        @Suppress("UNCHECKED_CAST")
        fun keys(advanced: Boolean): List<String> = call(model, "keys", advanced) as List<String>

        fun name(key: String) = call(model, "name", key) as String

        fun help(key: String) = call(model, "help", key) as String

        /** Which part of the picture it changes: lighting, water, sky and atmosphere, colour and effects. */
        fun group(key: String) = call(model, "group", key) as String

        /** False for water's options while enhanced water is off. */
        fun available(key: String) = call(model, "available", key) == true

        /** Whether it's an on/off option. */
        fun toggle(key: String) = call(model, "toggle", key, false) == true

        @Suppress("UNCHECKED_CAST")
        fun choices(key: String): List<Double> = (call(definitions[key], "values") as? List<Double>).orEmpty()

        fun value(key: String): Double = values[key] ?: 0.0

        fun isDefault(key: String) = (call(definitions[key], "defaultValue") as? Double)?.let { it == value(key) } ?: true

        /** How Sulkan names [v] for [key] (Off, Medium, Vibrant, 0.75...). */
        fun label(key: String, v: Double, advanced: Boolean): String {
            val old = values[key]
            values[key] = v
            try {
                return call(model, "value", key, advanced) as String
            } finally {
                if (old != null) values[key] = old else values.remove(key)
            }
        }

        /** Sets [key]; Sulkan's model also applies a preset's options and turns presets to Custom when they change. */
        fun set(key: String, v: Double) {
            if (modelSet) call(model, "set", key, v) else values[key] = v
        }

        // Sulkan builds without presets have no set(key, value): write the value then.
        private val modelSet = runCatching { method(model.javaClass, "set", 2) }.isSuccess

        /** Flips an on/off option, back to the value it had when on. */
        fun flip(key: String) {
            call(model, "change", key, false)
        }

        /** Whether there are changes not yet applied. */
        val dirty: Boolean get() = call(model, "dirty") == true

        /** Every option back to its default (applied with the rest). */
        fun reset() {
            call(model, "reset")
        }

        /** Saves the options, and rebuilds the pack with them when it's live. Throws when that fails. */
        fun apply() {
            static(NATIVE, "applyOptions", packId, HashMap(values))
            call(model, "applied")
        }
    }

    // ---- reflection ----

    private fun config() = static(RUNTIME, "config")

    private val classes = HashMap<String, Class<*>>()
    private val methods = HashMap<String, Method>()

    private fun cls(name: String): Class<*> = classes.getOrPut(name) { Class.forName(name, true, Sulkan::class.java.classLoader) }

    private fun method(c: Class<*>, name: String, args: Int): Method = methods.getOrPut("${c.name}#$name/$args") {
        var k: Class<*>? = c
        while (k != null) {
            k.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args }?.let {
                it.isAccessible = true
                return@getOrPut it
            }
            k = k.superclass
        }
        throw NoSuchMethodException("${c.name}.$name")
    }

    private fun field(c: Class<*>, name: String): Field = c.getDeclaredField(name).also { it.isAccessible = true }

    private fun static(c: String, name: String, vararg args: Any?): Any? = method(cls(c), name, args.size).invoke(null, *args)

    private fun call(o: Any?, name: String, vararg args: Any?): Any? = o?.let { method(it.javaClass, name, args.size).invoke(it, *args) }
}
