package dev.horizon.client.setting

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import java.awt.Color
import java.util.Locale
import kotlin.reflect.KProperty

/**
 * A module setting. [value] starts as the same object as [default]. The settings of a group point at
 * the group's [Group] value through [parent]; [visible] is evaluated live.
 */
abstract class Setting<T : Any>(val id: String, val default: T, val parent: Group? = null, var visible: () -> Boolean = { true }) {
    var value: T = default

    /** The display name: every id part as " " + Capitalised, so it starts with a space (" Ignore Walls"). */
    val displayName: String = id.split("-").filter { it.isNotEmpty() }
        .joinToString("") { " " + it.substring(0, 1).uppercase(Locale.ROOT) + it.substring(1).lowercase(Locale.ROOT) }

    fun reset() {
        value = default
    }

    /** Loads [value] from its saved string. */
    abstract fun parse(s: String)

    /** The saved string. */
    abstract fun save(): String

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: T) {
        value = v
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

class BoolSetting(id: String, default: Boolean, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<Boolean>(id, default, parent, visible) {
    override fun parse(s: String) {
        value = s.toBoolean()
    }

    override fun save() = value.toString()
}

class IntSetting(id: String, default: Int, val range: IntRange, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<Int>(id, default, parent, visible) {
    override fun parse(s: String) {
        value = s.toInt()
    }

    override fun save() = value.toString()
}

class FloatSetting(id: String, default: Float, val range: ClosedFloatingPointRange<Float>, parent: Group? = null,
                   visible: () -> Boolean = { true }) : Setting<Float>(id, default, parent, visible) {
    override fun parse(s: String) {
        value = s.toFloat()
    }

    override fun save() = value.toString()
}

/** An enum setting; values show as their name lower-cased, split on '_', each word capitalised. */
class EnumSetting<E : Enum<E>>(id: String, default: E, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<E>(id, default, parent, visible) {
    val constants: List<E> = default.declaringJavaClass.enumConstants.toList()

    override fun parse(s: String) {
        value = constants.firstOrNull { it.name == s } ?: default
    }

    override fun save(): String = value.name

    /** Sets the value from any constant of this enum (the GUI's chips). */
    @Suppress("UNCHECKED_CAST")
    fun select(e: Enum<*>) {
        value = e as E
    }
}

class ColorSetting(id: String, default: Color, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<Color>(id, default, parent, visible) {
    override fun parse(s: String) {
        value = Color(s.toInt(), true)
    }

    override fun save() = value.rgb.toString()
}

/** A key or mouse button; [key] -1 is none. Key codes are the game's (26.3: SDL scancodes, mouse buttons from 1). */
@Serializable
data class Bind(val key: Int = -1, val hold: Boolean = false, val mouse: Boolean = false)

class BindSetting(id: String, default: Bind = Bind(), parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<Bind>(id, default, parent, visible) {
    override fun parse(s: String) {
        value = JSON.decodeFromString(Bind.serializer(), s)
    }

    override fun save() = JSON.encodeToString(Bind.serializer(), value)
}

class StringSetting(id: String, default: String, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<String>(id, default, parent, visible) {
    override fun parse(s: String) {
        value = s
    }

    override fun save() = value
}

/** How a group shows: a collapsible row ([DEFAULT], [ALWAYS] behaves the same) or a row of its booleans as chips. */
enum class GroupMode { DEFAULT, ALWAYS, BOOLS }

/** A group's value; its children point at this object. Groups start collapsed and that isn't saved. */
class Group(var opened: Boolean, var mode: GroupMode)

class GroupSetting(id: String, default: Group, visible: () -> Boolean = { true }) : Setting<Group>(id, default, null, visible) {
    override fun parse(s: String) {
        value = default
    }

    override fun save() = "group-$id"
}

class Notation(val text: String)

/** Read-only text in the settings list. */
class NotationSetting(id: String, default: Notation, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<Notation>(id, default, parent, visible) {
    override fun parse(s: String) {}

    override fun save() = "notation"
}

class Action(val run: () -> Unit)

/** A button in the settings list. */
class ActionSetting(id: String, default: Action, parent: Group? = null, visible: () -> Boolean = { true }) :
    Setting<Action>(id, default, parent, visible) {
    override fun parse(s: String) {}

    override fun save() = "action"
}

/** A list of registry entries saved as their description ids; edited in place by the picker popups. */
abstract class ListSetting<E : Any>(id: String, default: MutableList<E>, parent: Group?, visible: () -> Boolean,
                                    private val registry: () -> Registry<E>, private val descriptionId: (E) -> String) :
    Setting<MutableList<E>>(id, default, parent, visible) {
    override fun parse(s: String) {
        val ids = JSON.decodeFromString(ListSerializer(String.serializer()), s)
        val all = registry()
        value = ids.mapNotNull { want -> all.firstOrNull { descriptionId(it) == want } }.toMutableList()
    }

    override fun save(): String = JSON.encodeToString(ListSerializer(String.serializer()), value.map(descriptionId))
}

class EntitiesSetting(id: String, default: MutableList<EntityType<*>> = ArrayList(), parent: Group? = null, visible: () -> Boolean = { true }) :
    ListSetting<EntityType<*>>(id, default, parent, visible, { BuiltInRegistries.ENTITY_TYPE }, { it.descriptionId })

class BlocksSetting(id: String, default: MutableList<Block> = ArrayList(), parent: Group? = null, visible: () -> Boolean = { true }) :
    ListSetting<Block>(id, default, parent, visible, { BuiltInRegistries.BLOCK }, { it.descriptionId })

class ItemsSetting(id: String, default: MutableList<Item> = ArrayList(), parent: Group? = null, visible: () -> Boolean = { true }) :
    ListSetting<Item>(id, default, parent, visible, { BuiltInRegistries.ITEM }, { it.descriptionId })

/** Value labels: a setting id + value shown as a word instead of the number (Aura's fov 1 = "trigger"). */
object ValueLabels {
    private class Label(val id: String, val value: Number, val text: String)

    private val labels = ArrayList<Label>()

    fun register(id: String, value: Number, text: String) {
        labels += Label(id, value, text)
    }

    fun get(id: String, value: Number, fallback: String): String =
        labels.firstOrNull { it.id == id && it.value == value }?.text ?: fallback
}
