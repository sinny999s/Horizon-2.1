package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.widgets.KeybindsWidget
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Modules
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.FloatSetting
import dev.horizon.client.setting.IntSetting
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.nio.file.Files

sealed interface Macro {
    var id: String
    var bind: Bind
    var enabled: Boolean
}

/** Sends [message] (a command when it starts with "/") on press. */
@Serializable
data class ChatMacro(override var id: String, override var bind: Bind, override var enabled: Boolean, var message: String) : Macro

/** Uses [item] (a block or item id without namespace) on press; [legit] only switches to it and right-clicks. */
@Serializable
data class ItemMacro(override var id: String, override var bind: Bind, override var enabled: Boolean, var item: String, var legit: Boolean) : Macro

/** Sets a module setting on press (and back on release with [hold]). */
@Serializable
data class SettingMacro(
    override var id: String,
    override var bind: Bind,
    override var enabled: Boolean,
    var module: String,
    var setting: String,
    var pressValue: String,
    var releaseValue: String,
    var hold: Boolean,
) : Macro

@Serializable
private data class MacrosFile(
    val settingMacros: List<SettingMacro> = emptyList(),
    val chatMacros: List<ChatMacro> = emptyList(),
    val itemMacros: List<ItemMacro> = emptyList(),
)

/**
 * Macros, saved in `macro.hzn` (loaded chat, item, setting, so they regroup by type on restart).
 * They fire with no screen open, on press and release of their bind, in list order.
 */
object Macros {
    val list = ArrayList<Macro>()
    private val file get() = Storage.root.resolve("macro.hzn")
    private val items = HashMap<String, Item>()
    private val pending = ArrayList<() -> Unit>()
    private val mc get() = Minecraft.getInstance()

    fun load() {
        list.clear()
        try {
            if (Files.exists(file) && Files.size(file) > 0) {
                val f = Storage.json.decodeFromString(MacrosFile.serializer(), Files.readString(file))
                list.addAll(f.chatMacros)
                list.addAll(f.itemMacros)
                list.addAll(f.settingMacros)
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Macros are not loaded from file for some reasons.")
        }
    }

    fun save() = Storage.write(file, MacrosFile.serializer(), MacrosFile(
        list.filterIsInstance<SettingMacro>(), list.filterIsInstance<ChatMacro>(), list.filterIsInstance<ItemMacro>(),
    ))

    /** A block (as its item) or item whose id is [name]; hits are cached. */
    fun item(name: String): Item? {
        items[name]?.let { return it }
        val found = BuiltInRegistries.BLOCK.firstOrNull { it.descriptionId == "block.minecraft.$name" }?.asItem()
            ?: BuiltInRegistries.ITEM.firstOrNull { it.descriptionId == "item.minecraft.$name" }
            ?: return null
        items[name] = found
        return found
    }

    /** A key or mouse button event. The device must match too, as 26.3 codes overlap. */
    fun input(key: Int, mouse: Boolean, press: Boolean) {
        if (mc.level == null || mc.player == null || mc.gui.screen() != null) return
        for (m in list.toList()) {
            if (!m.enabled || m.bind.key != key || m.bind.mouse != mouse) continue
            when (m) {
                is ChatMacro -> if (press) chat(m)
                is ItemMacro -> if (press) pending += { use(m) }
                is SettingMacro -> setting(m, press)
            }
        }
    }

    /** Every player tick: item macros pressed since the last tick. */
    fun tick() {
        val run = pending.toList()
        pending.clear()
        for (r in run) r()
    }

    private fun chat(m: ChatMacro) {
        val c = mc.connection ?: return
        if (m.message.startsWith("/")) c.sendCommand(m.message.replaceFirst("/", "")) else c.sendChat(m.message)
    }

    private fun use(m: ItemMacro) {
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        val item = item(m.item) ?: return
        val inv = p.inventory
        val hotbar = (0 until 9).firstOrNull { inv.getItem(it).`is`(item) }
        if (hotbar != null) {
            if (m.legit) {
                inv.selectedSlot = hotbar
                if (item != Items.SHIELD) pending += { pending += { (mc as MinecraftAccessor).horizon_startUseItem() } }
                return
            }
            val previous = inv.selectedSlot
            inv.selectedSlot = hotbar
            useHeld(item)
            inv.selectedSlot = previous
            return
        }
        val slot = (36 downTo 0).firstOrNull { inv.getItem(it).`is`(item) } ?: return
        val menu = p.inventoryMenu
        gm.handleContainerInput(menu.containerId, slot, inv.selectedSlot, ContainerInput.SWAP, p)
        if (m.legit) return
        useHeld(item)
        gm.handleContainerInput(menu.containerId, slot, inv.selectedSlot, ContainerInput.SWAP, p)
    }

    /** Rage use: on the looked-at block for block items, else in the air; then a swing. */
    private fun useHeld(item: Item) {
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        val hit = mc.hitResult
        if (item is BlockItem && hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) gm.useItemOn(p, InteractionHand.MAIN_HAND, hit)
        else gm.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
    }

    private fun setting(m: SettingMacro, press: Boolean) {
        val module = Modules.all.firstOrNull { it.id == m.module } ?: return
        val s = module.settings.firstOrNull { it.id == m.setting } ?: return
        fun set(v: String) {
            when (s) {
                is IntSetting -> s.value = v.toInt()
                is FloatSetting -> s.value = v.toFloat()
                is BoolSetting -> s.value = v.toBoolean()
                is EnumSetting<*> -> s.constants.firstOrNull { it.name.equals(v.replace(" ", "_"), ignoreCase = true) }?.let { s.select(it) }
                else -> {}
            }
        }
        val keyName = Keys.name(m.bind)
        val extra = "${module.name.trim()} -> ${s.displayName} -> ${m.pressValue}"
        fun addEntry() {
            KeybindsWidget.rows += KeybindsWidget.KeyBind(m.id, keyName, extra)
        }
        fun removeEntry() {
            KeybindsWidget.rows.filter { it.name == m.id && it.extra == extra }.forEach { it.removing = true }
        }
        if (m.hold) {
            if (press) {
                set(m.pressValue)
                addEntry()
            } else {
                set(m.releaseValue)
                removeEntry()
            }
        } else if (press) {
            if (s.save() == m.pressValue) {
                set(m.releaseValue)
                removeEntry()
            } else {
                set(m.pressValue)
                addEntry()
            }
        }
    }
}
