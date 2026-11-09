package dev.horizon.client.compat

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Sodium's options, its own and those of every mod that adds pages through Sodium's config API, when
 * Sodium is installed, for the client's Options menu to draw in its own style. Reached by reflection, so the client
 * neither needs nor bundles Sodium. Sodium holds a change until it's applied; [Opt.set] applies it at once, as Sodium's
 * Apply button would (renderer reloads and saving included).
 */
object SodiumOptions {
    class Mod(val name: String, val pages: List<Page>)

    /** A page of option groups, or one that opens a screen of its own ([open]). */
    class Page(val name: String, val groups: List<Group>, val open: Consumer<Screen>?)

    class Group(val name: String, val options: List<Opt>)

    enum class Kind { BOOLEAN, INTEGER, ENUM, BUTTON, OTHER }

    class Opt(private val raw: Any) {
        val kind = when {
            types[0]?.isInstance(raw) == true -> Kind.BOOLEAN
            types[1]?.isInstance(raw) == true -> Kind.INTEGER
            types[2]?.isInstance(raw) == true -> Kind.ENUM
            types[3]?.isInstance(raw) == true -> Kind.BUTTON
            else -> Kind.OTHER
        }
        val name: String = text(call(raw, "getName"))

        val tooltip: String get() = text(call(raw, "getTooltip"))

        /** Sodium's "Performance impact" for the option, or "". */
        val impact: String get() = call(raw, "getImpact")?.let { text(call(it, "getName")) } ?: ""

        val enabled: Boolean get() = call(raw, "isEnabled") == true

        val hidden: Boolean get() = !enabled && call(raw, "shouldHideControl") == true

        val value: Any? get() = call(raw, "getValidatedValue")

        fun set(v: Any?) {
            call(raw, "modifyValue", v)
            apply()
        }

        /** An integer option's min, max and step. */
        val range: Triple<Int, Int, Int>
            get() {
                val r = call(raw, "getSteppedValidator") ?: return Triple(0, 1, 1)
                return Triple(call(r, "min") as? Int ?: 0, call(r, "max") as? Int ?: 1, maxOf(1, call(r, "step") as? Int ?: 1))
            }

        fun format(v: Int): String = text(call(raw, "formatValue", v)).ifEmpty { v.toString() }

        /** An enum option's allowed values, in declaration order. */
        val values: List<Any>
            get() {
                val all = (call(raw, "getEnumClass") as? Class<*>)?.enumConstants ?: return emptyList()
                val allowed = config?.let { c -> call(raw, "getAllowedValues")?.let { call(it, "get", c) } } as? Set<*>
                return all.filterNotNull().filter { allowed == null || it in allowed }
            }

        fun valueName(v: Any?): String = text(call(raw, "getElementName", v)).ifEmpty { v.toString() }

        /** What a button option opens, handed the screen to come back to. */
        val open: Consumer<Screen>? get() = (call(raw, "getCurrentScreenConsumer") as? Consumer<*>)?.let { c -> Consumer { s: Screen -> (c as Consumer<Any?>).accept(s) } }
    }

    private val installed = FabricLoader.getInstance().isModLoaded("sodium")
    private val methods = HashMap<Pair<Class<*>, String>, Method?>()
    private var loaded = false
    private var config: Any? = null
    private val types = arrayOfNulls<Class<*>>(4)

    /** Sodium is installed and its config answered. */
    val present: Boolean get() = installed && load() != null

    private fun load(): Any? {
        if (loaded) return config
        loaded = true
        try {
            val loader = SodiumOptions::class.java.classLoader
            fun type(name: String) = Class.forName("net.caffeinemc.mods.sodium.client.config.structure.$name", false, loader)
            types[0] = type("BooleanOption")
            types[1] = type("IntegerOption")
            types[2] = type("EnumOption")
            types[3] = type("ExternalButtonOption")
            config = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager", true, loader).getField("CONFIG").get(null)
        } catch (_: ReflectiveOperationException) {
            config = null
        } catch (_: LinkageError) {
            config = null
        }
        return config
    }

    /** Every mod's options (Sodium's first), as Sodium lists them. */
    fun mods(): List<Mod> {
        val c = load() ?: return emptyList()
        return (call(c, "getModOptions") as? List<*>).orEmpty().filterNotNull().map { mod ->
            val pages = (call(mod, "pages") as? List<*>).orEmpty().filterNotNull().map { page ->
                val open = call(page, "currentScreenConsumer") as? Consumer<*>
                val groups = (call(page, "groups") as? List<*>).orEmpty().filterNotNull().map { g ->
                    Group(text(call(g, "name")), (call(g, "options") as? List<*>).orEmpty().filterNotNull().map(::Opt))
                }
                Page(text(call(page, "name")), groups, open?.let { o -> Consumer { s: Screen -> (o as Consumer<Any?>).accept(s) } })
            }
            Mod(call(mod, "name") as? String ?: "", pages)
        }
    }

    fun apply() {
        config?.let { call(it, "applyAllOptions") }
    }

    private fun text(c: Any?): String = (c as? Component)?.string ?: ""

    private fun call(on: Any, name: String, vararg args: Any?): Any? {
        val m = methods.getOrPut(on.javaClass to name) {
            on.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }?.also { it.isAccessible = true }
        } ?: return null
        return try {
            m.invoke(on, *args)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
