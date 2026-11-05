package dev.horizon.client.module

import dev.horizon.client.config.SettingsHolder
import dev.horizon.client.event.Event
import dev.horizon.client.event.EventBus
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.setting.Action
import dev.horizon.client.setting.ActionSetting
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.BlocksSetting
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.ColorSetting
import dev.horizon.client.setting.EntitiesSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.FloatSetting
import dev.horizon.client.setting.Group
import dev.horizon.client.setting.GroupMode
import dev.horizon.client.setting.GroupSetting
import dev.horizon.client.setting.IntSetting
import dev.horizon.client.setting.ItemsSetting
import dev.horizon.client.setting.Notation
import dev.horizon.client.setting.NotationSetting
import dev.horizon.client.setting.Setting
import dev.horizon.client.setting.StringSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import java.awt.Color

/**
 * A module (the original's `_7`). Its settings are kept in declaration order, which is the order the ClickGUI shows them;
 * the first two are always "enabled" and "bind" (not shown as widgets).
 */
abstract class Module(override val id: String, val subCategory: SubCategory, val aliases: List<String> = emptyList(),
                      defaultBind: Bind = Bind()) : SettingsHolder {
    override val settings = ArrayList<Setting<*>>()
    val enabledSetting = bool("enabled", false)
    val bindSetting = bind("bind", defaultBind)

    /** The original's display name: each id part capitalised and followed by a space ("Auto Crystal "). */
    val name: String = id.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } + " " }

    val mc: Minecraft get() = Minecraft.getInstance()

    var enabled: Boolean
        get() = enabledSetting.value
        set(v) = if (v) enable() else disable()

    var bind: Bind
        get() = bindSetting.value
        set(v) {
            bindSetting.value = v
        }

    private class Handler(val type: Class<out Event>, val priority: Int, val fn: (Event) -> Unit)

    private val handlers = ArrayList<Handler>()

    @PublishedApi
    internal fun addHandler(type: Class<out Event>, priority: Int, fn: (Event) -> Unit) {
        handlers += Handler(type, priority, fn)
    }

    /** A handler that is on the event bus only while the module is enabled (the original's `@Flow`). */
    protected inline fun <reified E : Event> on(priority: Int = 0, noinline fn: (E) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        addHandler(E::class.java, priority, fn as (Event) -> Unit)
    }

    fun plug() {
        for (h in handlers) EventBus.register(this, h.type, h.priority, h.fn)
    }

    fun unplug() = EventBus.unregister(this)

    /** The original's `z()`: on, re-plugged on the bus, the enable hook, then the notification. */
    fun enable() {
        if (enabledSetting.value) return
        if (dev.horizon.client.module.legit.GhostMode.blocks(this)) {
            Notifications.post(name, "is blocked by Ghost Mode", 1f, NotificationType.WARNING)
            return
        }
        enabledSetting.value = true
        unplug()
        plug()
        onEnable()
        Notifications.post(name, "was enabled", 1f, NotificationType.ENABLED)
    }

    /** The original's `p()`: off, unplugged, the disable hook, then the notification. */
    fun disable() {
        if (!enabledSetting.value) return
        enabledSetting.value = false
        unplug()
        onDisable()
        Notifications.post(name, "was disabled", 1f, NotificationType.DISABLED)
    }

    fun toggle() = if (enabled) disable() else enable()

    open fun onEnable() {}

    open fun onDisable() {}

    // ---- setting builders (the original's z0): each appends to [settings] ----

    protected fun <S : Setting<*>> add(s: S): S = s.also { settings += it }

    protected fun bool(id: String, default: Boolean, parent: Group? = null, visible: () -> Boolean = { true }) =
        add(BoolSetting(id, default, parent, visible))

    protected fun int(id: String, default: Int, range: IntRange, parent: Group? = null, visible: () -> Boolean = { true }) =
        add(IntSetting(id, default, range, parent, visible))

    protected fun float(id: String, default: Float, range: ClosedFloatingPointRange<Float>, parent: Group? = null,
                        visible: () -> Boolean = { true }) = add(FloatSetting(id, default, range, parent, visible))

    protected fun <E : Enum<E>> enum(id: String, default: E, parent: Group? = null, visible: () -> Boolean = { true }) =
        add(EnumSetting(id, default, parent, visible))

    protected fun color(id: String, default: Color, parent: Group? = null, visible: () -> Boolean = { true }) =
        add(ColorSetting(id, default, parent, visible))

    protected fun bind(id: String, default: Bind = Bind(), parent: Group? = null, visible: () -> Boolean = { true }) =
        add(BindSetting(id, default, parent, visible))

    protected fun string(id: String, default: String, parent: Group? = null, visible: () -> Boolean = { true }) =
        add(StringSetting(id, default, parent, visible))

    /** A group; give its [Group] value as the children's parent. */
    protected fun group(id: String, mode: GroupMode = GroupMode.DEFAULT, visible: () -> Boolean = { true }) =
        add(GroupSetting(id, Group(false, mode), visible))

    protected fun notation(id: String, text: String, parent: Group? = null, visible: () -> Boolean = { true }) =
        add(NotationSetting(id, Notation(text), parent, visible))

    protected fun action(id: String, parent: Group? = null, visible: () -> Boolean = { true }, run: () -> Unit) =
        add(ActionSetting(id, Action(run), parent, visible))

    protected fun entities(id: String, default: MutableList<EntityType<*>> = ArrayList(), parent: Group? = null,
                           visible: () -> Boolean = { true }) = add(EntitiesSetting(id, default, parent, visible))

    protected fun blocks(id: String, default: MutableList<Block> = ArrayList(), parent: Group? = null, visible: () -> Boolean = { true }) =
        add(BlocksSetting(id, default, parent, visible))

    protected fun items(id: String, default: MutableList<Item> = ArrayList(), parent: Group? = null, visible: () -> Boolean = { true }) =
        add(ItemsSetting(id, default, parent, visible))

    // Default lists by description id ("block.minecraft.obsidian"), the way the original saves them.
    protected fun entityList(vararg ids: String): MutableList<EntityType<*>> =
        ids.mapNotNullTo(ArrayList()) { id -> BuiltInRegistries.ENTITY_TYPE.firstOrNull { it.descriptionId == id } }

    protected fun blockList(vararg ids: String): MutableList<Block> =
        ids.mapNotNullTo(ArrayList()) { id -> BuiltInRegistries.BLOCK.firstOrNull { it.descriptionId == id } }

    protected fun itemList(vararg ids: String): MutableList<Item> =
        ids.mapNotNullTo(ArrayList()) { id -> BuiltInRegistries.ITEM.firstOrNull { it.descriptionId == id } }
}
