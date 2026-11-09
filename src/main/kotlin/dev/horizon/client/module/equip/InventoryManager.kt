package dev.horizon.client.module.equip

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.config.Storage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.gui.popup.KitPopup
import dev.horizon.client.gui.popup.PopupScreen
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import java.nio.file.Files
import kotlin.io.path.extension

/**
 * Every tick (after [actionDelay]) up to [clickPerAction] pickup clicks move the
 * open menu's inventory towards the current kit's layout; the cleaner throws out [cleanerList] items, one per
 * [cleanerDelay] ms, and sends a close packet [cleanerDelay] + 100 ms after the last throw.
 */
class InventoryManager : Module("inventory-manager", Categories.EQUIP) {
    enum class Cleaner { INVENTORY, ALWAYS, NOT_IN_INVENTORY, OFF }

    val openManager = action("open-manager") { PopupScreen.popup = KitPopup() }
    val cleanerList = items("cleaner-list")
    val actionDelay = int("action-delay", 0, 0..10)
    val clickPerAction = int("click-per-action", 1, 1..3)
    val cleaner = enum("cleaner", Cleaner.OFF)
    val cleanerDelay = int("cleaner-delay", 50, 0..500) { cleaner.value != Cleaner.OFF }
    val cleanChests = bool("clean-chests", false) { cleaner.value != Cleaner.OFF }

    private var wait = 0
    private var thrown = false
    private val throwTimer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (wait > 0) {
                wait--
            } else if (Kits.current.items.isNotEmpty()) {
                var n = 0
                for (slot in plan(p.containerMenu)) {
                    EquipUtil.click(slot)
                    if (++n >= clickPerAction.value) break
                }
                wait = actionDelay.value
            }
        }
        on<Render3DEvent> {
            if (cleaner.value == Cleaner.OFF) return@on
            val p = mc.player ?: return@on
            val inInventory = mc.gui.screen() is InventoryScreen
            val menu = p.containerMenu
            if (menu is ChestMenu && cleanChests.value) {
                // Reads the title of a screen it assumes is open.
                val title = mc.gui.screen()?.title?.string ?: return@on
                for (i in 0 until menu.container.containerSize) {
                    val slot = menu.getSlot(i)
                    if (slot.hasItem() && cleanerList.value.contains(slot.item.item) && SKIP_TITLES.none { title.contains(it) }) throwOut(i)
                }
            }
            if (cleaner.value == Cleaner.INVENTORY && !inInventory) return@on
            if (cleaner.value == Cleaner.NOT_IN_INVENTORY && inInventory) return@on
            for (i in 0 until 36) if (cleanerList.value.contains(p.inventory.getItem(i).item)) throwOut(if (i < 9) i + 36 else i)
            if (thrown && throwTimer.passed((cleanerDelay.value + 100).toLong())) {
                Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
                thrown = false
            }
        }
    }

    /** Throw the stack in [slot] if [cleanerDelay] has passed. */
    private fun throwOut(slot: Int) {
        if (!throwTimer.passedAndReset(cleanerDelay.value.toLong())) return
        EquipUtil.click(slot, 1, ContainerInput.THROW)
        thrown = true
    }

    /** The clicks that bring [menu]'s inventory part closer to the kit (only for 46/63/90-slot menus). */
    private fun plan(menu: AbstractContainerMenu): List<Int> {
        val out = ArrayList<Int>()
        val size = menu.slots.size
        if (size != 46 && size != 63 && size != 90) return out
        val kit = Kits.current.items
        for (index in kit.keys) {
            val item = kit[index]
            if (item == Items.AIR) continue
            val target = index + inventoryPart(size).first
            if (menu.slots[target].item.item == item) continue
            val from = source(item!!, menu, searchRange(size))
            if (from == -2) {
                out += target
            } else if (from != -1) {
                out += from
                out += target
                if (menu.slots[target].item.item != Items.AIR) out += from
            }
        }
        return out
    }

    /** -2 when the cursor holds [item], else the first slot in [range] with it that isn't already in place. */
    private fun source(item: Item, menu: AbstractContainerMenu, range: IntRange): Int {
        if (menu.carried.item == item) return -2
        val part = inventoryPart(menu.slots.size)
        for (i in range) {
            if (menu.getSlot(i).item.item != item) continue
            if (Kits.current.items[i - range.first - (if (range.first == 0) part.first else 0)] != item) return i
        }
        return -1
    }

    private fun searchRange(size: Int) = when (size) {
        46 -> 9..44
        63 -> 0..62
        else -> 0..89
    }

    /** Where the player's inventory (main, then hotbar) sits in the menu. */
    private fun inventoryPart(size: Int) = when (size) {
        46 -> 9..44
        63 -> 27..62
        else -> 54..89
    }

    /** A kit named [name] from the open menu's slots 9..44. */
    fun snapshot(name: String): Kit {
        val items = LinkedHashMap<Int, Item>()
        val p = mc.player
        if (p != null) for (i in 9 until 45) items[i - 9] = p.containerMenu.slots[i].item.item
        return Kit(name, items)
    }

    private companion object {
        /** An auction-screen filter, mis-encoded ("Аукцион", "покупки"), so it never matches (on purpose). */
        val SKIP_TITLES = listOf(
            "РђСѓРєС†РёРѕРЅ",
            "РїРѕРєСѓРїРєРё",
        )
    }
}

/** An item saved by its description id (unknown ids load as air). */
object ItemByDescriptionId : KSerializer<Item> {
    override val descriptor = PrimitiveSerialDescriptor("Item", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Item) = encoder.encodeString(value.descriptionId)
    override fun deserialize(decoder: Decoder): Item {
        val id = decoder.decodeString()
        return BuiltInRegistries.ITEM.firstOrNull { it.descriptionId == id } ?: Items.AIR
    }
}

/** A kit: slot 0..26 main inventory, 27..35 hotbar. */
@Serializable
class Kit(var id: String, var items: MutableMap<Int, @Serializable(with = ItemByDescriptionId::class) Item>)

/**
 * The kit store: `inventories/<id>.hzn`, read once at start-up and written with the other configs. The
 * current kit is the one named in shared.hzn (created empty if missing); choosing another doesn't change that name.
 */
object Kits {
    val list = ArrayList<Kit>()
    lateinit var current: Kit

    private val dir get() = Storage.root.resolve("inventories")

    fun load() {
        list.clear()
        try {
            Files.createDirectories(dir)
            val files = Files.list(dir).use { s -> s.filter { it.extension == "hzn" }.sorted().toList() }
            for (f in files) list += Storage.json.decodeFromString(Kit.serializer(), Files.readString(f))
        } catch (e: Exception) {
            list.clear()
            HorizonClient.LOG.warn("Inventories are not loaded from file for some reasons.")
        }
        val name = Storage.shared.inventoriesConfig
        current = list.firstOrNull { it.id == name } ?: Kit(name, LinkedHashMap()).also { list += it }
    }

    fun save() {
        for (k in list) Storage.write(dir.resolve(k.id + ".hzn"), Kit.serializer(), k)
    }
}
