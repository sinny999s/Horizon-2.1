package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.Bind
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper

/**
 * Swaps between the elytra and a chestplate, on enable (then turns itself off) or on
 * [switchButton]; with [startFireWork] it then jumps until gliding and uses a firework once gliding starts.
 */
class ElytraSwap : Module("elytra-swap", Categories.EQUIP) {
    enum class Mode { ENABLE, BIND }
    enum class FireWorkMode { SILENT, NORMAL }
    enum class SwapMode { DEFAULT, ALT }
    enum class HotbarSwapMode { DEFAULT, V1_17 }

    val mode = enum("mode", Mode.ENABLE)
    val switchButton = bind("switch-button", Bind()) { mode.value == Mode.BIND }
    val fireWorkButton = bind("fire-work-button", Bind()) { mode.value == Mode.BIND }
    val startFireWork = bool("start-fire-work", false) { mode.value == Mode.BIND }
    val fireWorkMode = enum("fire-work-mode", FireWorkMode.SILENT) { mode.value == Mode.BIND }
    val swapMode = enum("swap-mode", SwapMode.ALT)
    val hotbarSwapMode = enum("hotbar-swap-mode", HotbarSwapMode.DEFAULT)
    val swapOnLand = bool("swap-on-land", false) { mode.value == Mode.BIND }

    /** Don't start a firework on the next START_FALL_FLYING (set by other elytra modules). */
    @Volatile
    var skipFirework = false

    private var wasGliding = false
    private var jumpStart = 0

    init {
        on<PlayerUpdateEvent> {
            if (!swapOnLand.value) return@on
            val p = mc.player ?: return@on
            if (PlayerState.elytraTicks > 15) wasGliding = true
            if (!p.isFallFlying && wasGliding) {
                if (p.inventory.getItem(38).`is`(Items.ELYTRA)) scheduleSwap(false, true)
                wasGliding = false
            }
        }
        on<AfterSendPacketEvent> { e ->
            val pk = e.packet as? ServerboundPlayerCommandPacket ?: return@on
            if (pk.action != ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) return@on
            if (mode.value == Mode.BIND && startFireWork.value && !skipFirework) Scheduler.ticks(0, -10) { firework() }
            skipFirework = false
        }
        on<InputEvent> { e ->
            if (mode.value != Mode.BIND || mc.gui.screen() != null || e.action != InputEvent.Action.Press) return@on
            if (pressed(e, switchButton.value)) scheduleSwap(false, false)
            if (fireWorkButton.value.key != -1 && pressed(e, fireWorkButton.value) && mc.player?.isFallFlying == true) {
                Scheduler.ticks(0, -10) { firework() }
            }
        }
    }

    override fun onEnable() {
        if (mc.player == null || mode.value != Mode.ENABLE) return
        scheduleSwap(true, false)
    }

    /** The device must match too, as 26.3's key and button codes overlap. */
    private fun pressed(e: InputEvent, b: Bind) = e.key == b.key && (e.device == InputEvent.Device.Mouse) == b.mouse

    private fun scheduleSwap(disable: Boolean, onlyToChestplate: Boolean) = Scheduler.ticks(0, -10) { swap(disable, onlyToChestplate) }

    private fun swap(disable: Boolean, onlyToChestplate: Boolean) {
        val p = mc.player ?: return
        if (p.inventory.getItem(38).`is`(Items.ELYTRA)) {
            val slot = chestplate()
            if (slot != -1) {
                Notifications.post(name, "Swapped to chestplate!", 2f, NotificationType.SUCCESS,
                    p.inventory.getItem(if (slot >= 36) slot - 36 else slot).copy())
                equip(slot, false)
                Notifications.silent = true
                if (disable) disable()
                Notifications.silent = false
            } else {
                say("You don't have a chestplate!", disable)
            }
        } else if (!onlyToChestplate) {
            val slot = elytra()
            if (slot != -1) {
                Notifications.post(name, "Swapped to elytra!", 2f, NotificationType.SUCCESS,
                    p.inventory.getItem(if (slot >= 36) slot - 36 else slot).copy())
                equip(slot, true)
                Notifications.silent = true
                if (disable) disable()
                Notifications.silent = false
            } else {
                say("You don't have an elytra!", disable)
            }
        }
    }

    /** Off, then the message, or just the message. */
    private fun say(text: String, disable: Boolean) {
        if (disable) disable()
        moduleMessage(name.trim(), text)
    }

    /** Menu slot [slot] onto the chest. */
    private fun equip(slot: Int, toElytra: Boolean) {
        val p = mc.player ?: return
        if (slot in 36 until 45) {
            if (hotbarSwapMode.value == HotbarSwapMode.DEFAULT) Inventory.swap(6, slot - 36, true) else EquipUtil.useInstant(slot - 36, 1)
        } else if (swapMode.value == SwapMode.DEFAULT) {
            EquipUtil.click(slot)
            EquipUtil.click(6)
            EquipUtil.click(slot, close = true)
        } else {
            EquipUtil.click(slot, 7, ContainerInput.SWAP)
            EquipUtil.click(6, 7, ContainerInput.SWAP)
            EquipUtil.click(slot, 7, ContainerInput.SWAP, true)
        }
        if (toElytra && startFireWork.value) {
            jumpStart = p.tickCount
            Scheduler.ticks(-1, -10) { jump() }
        }
    }

    /** Jump every other tick until gliding (or while standing without the elytra on). */
    private fun jump() {
        val p = mc.player ?: return
        val jumpKey = mc.options.keyJump
        if (p.inventory.getItem(38).`is`(Items.ELYTRA)) {
            if (p.isFallFlying) {
                jumpKey.isDown = false
            } else {
                jumpKey.isDown = (p.tickCount - jumpStart) % 2 == 0
                Scheduler.ticks(-1, -10) { jump() }
            }
        } else if (p.onGround()) {
            jumpKey.isDown = true
            Scheduler.ticks(-1, -10) { jump() }
        }
    }

    /** A firework from the hotbar, or swapped in from the inventory (and back in silent mode). */
    private fun firework() {
        val p = mc.player ?: return
        val hotbar = Inventory.hotbarFind(Items.FIREWORK_ROCKET)
        val inv = Inventory.find(Items.FIREWORK_ROCKET)
        Inventory.saveAndSelect(Inventory.selected)
        if (hotbar != null) {
            Inventory.selectHotbar(hotbar.slot)
        } else {
            if (inv == null) {
                moduleMessage(name.trim(), "You've got no fireworks!")
                return
            }
            Inventory.swap(inv.slot, p.inventory.selectedSlot, true)
        }
        // No swing packet after the use; 26.3 has none.
        EquipUtil.use()
        if (fireWorkMode.value == FireWorkMode.SILENT) {
            Inventory.restore()
            if (hotbar == null) Inventory.swap(inv!!.slot, p.inventory.selectedSlot, true)
        }
    }

    /** The best chestplate material found (inventory index 36 down to 0), as a menu slot. */
    private fun chestplate(): Int {
        for (item in CHESTPLATES) Inventory.find(item)?.let { return it.slot }
        return -1
    }

    /** The usable elytra with the most enchantment levels (inventory 0..36), as a menu slot. */
    private fun elytra(): Int {
        val inv = mc.player?.inventory ?: return -1
        var slot = -1
        var best = -1
        for (i in 0 until 37) {
            val s = inv.getItem(i)
            if (!s.`is`(Items.ELYTRA) || s.damageValue >= s.maxDamage - 1 || !s.has(DataComponents.GLIDER)) continue
            val ench = EnchantmentHelper.getEnchantmentsForCrafting(s)
            var levels = 0
            for (h in ench.keySet()) levels += ench.getLevel(h)
            if (levels > best) {
                best = levels
                slot = if (i < 9) i + 36 else i
            }
        }
        return slot
    }

    private companion object {
        val CHESTPLATES = listOf(
            Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE, Items.LEATHER_CHESTPLATE,
        )
    }
}
