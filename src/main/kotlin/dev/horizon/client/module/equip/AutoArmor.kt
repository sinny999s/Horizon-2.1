package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.ElytraRecast
import dev.horizon.client.module.rage.ElytraPlus
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Every [delay] ticks, scores the worn armour and every main-inventory/hotbar piece
 * (protection or blast protection doubled per slot setting, armour and toughness modifiers, elytra priority, cursed
 * pieces at -999) and puts on the first better piece found: right-click from the hotbar, else three pickup clicks.
 */
class AutoArmor : Module("auto-armor", Categories.EQUIP) {
    enum class Head { BLAST, PROTECTION }
    enum class ElytraPriority { NONE, ALWAYS, ELYTRA_PLUS, IGNORE }

    val head = enum("head", Head.PROTECTION)
    val body = enum("body", Head.PROTECTION)
    val tights = enum("tights", Head.PROTECTION)
    val feet = enum("feet", Head.PROTECTION)
    val elytraPriority = enum("elytra-priority", ElytraPriority.IGNORE)
    val delay = int("delay", 1, 0..10)
    val oldVersion = bool("old-version", false)
    val pauseInventory = bool("pause-inventory", false)
    val noMove = bool("no-move", false)
    val ignoreCurse = bool("ignore-curse", true)
    val facePlaceBait = bool("face-place-bait", false)

    /** Ticks since the last equip (read by other modules). */
    val delayTimer = TickTimer()
    /** Ticks since face-place-bait took the helmet off. */
    private val baitTimer = TickTimer()

    /** An armour slot, its inventory index, the worn piece's score and the best candidate this tick. */
    private class ArmorSlot(val slot: EquipmentSlot, val index: Int) {
        var worn = -1
        var best = -1
        var bestScore = -1
    }

    private val slots = listOf(
        ArmorSlot(EquipmentSlot.FEET, 36), ArmorSlot(EquipmentSlot.LEGS, 37),
        ArmorSlot(EquipmentSlot.CHEST, 38), ArmorSlot(EquipmentSlot.HEAD, 39),
    )

    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            val screen = mc.gui.screen()
            if (screen != null && pauseInventory.value && screen !is ChatScreen) return@on
            if (!delayTimer.passed(delay.value)) return@on
            for (s in slots) {
                s.worn = score(p.inventory.getItem(s.index))
                s.best = -1
                s.bestScore = -1
            }
            if (p.tickCount % 10 == 0 && facePlaceBait.value) {
                EquipUtil.click(5, 0, ContainerInput.QUICK_MOVE, true)
                baitTimer.reset()
            }
            for (i in 0 until 36) {
                val stack = p.inventory.getItem(i)
                val score = score(stack)
                if (score <= 0) continue
                for (s in slots) {
                    if ((!facePlaceBait.value || baitTimer.passed(1) || s.slot != EquipmentSlot.HEAD) &&
                        s.slot == (if (isArmor(stack)) slotOf(stack) else EquipmentSlot.CHEST) && score > s.worn && score > s.bestScore) {
                        s.best = i
                        s.bestScore = score
                    }
                }
            }
            for (s in slots) {
                val i = s.best
                if (i == -1) continue
                if ((s.worn == -1 || !oldVersion.value) && i < 9) {
                    // No swing packet after the use; 26.3 has none.
                    Inventory.saveAndSelect(i)
                    EquipUtil.use()
                    Inventory.restore()
                } else {
                    val mv = p.input.moveVector
                    if ((mv.y != 0f || mv.x != 0f) && noMove.value) return@on
                    val from = if (i < 9) 36 + i else i
                    EquipUtil.click(from)
                    EquipUtil.click(s.index - 34 + (39 - s.index) * 2, close = s.worn == -1)
                    if (s.worn != -1) EquipUtil.click(from, close = true)
                }
                delayTimer.reset()
                return@on
            }
        }
    }

    private fun isArmor(s: ItemStack) =
        s.`is`(ItemTags.FOOT_ARMOR) || s.`is`(ItemTags.LEG_ARMOR) || s.`is`(ItemTags.CHEST_ARMOR) || s.`is`(ItemTags.HEAD_ARMOR)

    /** Gliders go on the chest, everything else where its equippable component says. */
    private fun slotOf(s: ItemStack): EquipmentSlot {
        if (s.has(DataComponents.GLIDER)) return EquipmentSlot.CHEST
        return when (s.get(DataComponents.EQUIPPABLE)!!.slot().index) {
            0 -> EquipmentSlot.FEET
            1 -> EquipmentSlot.LEGS
            2 -> EquipmentSlot.CHEST
            else -> EquipmentSlot.HEAD
        }
    }

    /** -1 for an empty slot, 0 for anything that isn't armour or an elytra. */
    private fun score(s: ItemStack): Int {
        if (!isArmor(s) && !s.`is`(Items.ELYTRA)) return if (s.isEmpty) -1 else 0
        var score = 0
        val slot = slotOf(s)
        if (s.`is`(Items.ELYTRA)) {
            // A glider with at least two uses left.
            if (s.damageValue >= s.maxDamage - 1 || !s.has(DataComponents.GLIDER)) return 0
            val plus = elytraPriority.value == ElytraPriority.ELYTRA_PLUS &&
                (Modules.get<ElytraRecast>().enabled || Modules.get<ElytraPlus>().enabled)
            val ignore = elytraPriority.value == ElytraPriority.IGNORE && mc.player?.inventory?.getItem(38)?.`is`(Items.ELYTRA) == true
            if (plus || ignore || elytraPriority.value == ElytraPriority.ALWAYS) score = 999
        }
        var blastMul = 1
        var protMul = 1
        // "body" matches EquipmentSlot.BODY (animal armour), so chestplates get no multiplier (on purpose).
        val setting = when (slot) {
            EquipmentSlot.HEAD -> head
            EquipmentSlot.BODY -> body
            EquipmentSlot.LEGS -> tights
            EquipmentSlot.FEET -> feet
            else -> null
        }
        if (setting != null) if (setting.value == Head.PROTECTION) protMul *= 2 else blastMul *= 2
        val level = mc.level
        if (s.isEnchanted && level != null) {
            val ench = EnchantmentHelper.getEnchantmentsForCrafting(s)
            val reg = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            val prot = reg.getOrThrow(Enchantments.PROTECTION)
            val blast = reg.getOrThrow(Enchantments.BLAST_PROTECTION)
            val binding = reg.getOrThrow(Enchantments.BINDING_CURSE)
            if (ench.keySet().contains(prot)) score += ench.getLevel(prot) * protMul
            if (ench.keySet().contains(blast)) score += ench.getLevel(blast) * blastMul
            if (ench.keySet().contains(binding) && ignoreCurse.value) score = -999
        }
        if (s.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            val p = mc.player
            for (e in s.get(DataComponents.ATTRIBUTE_MODIFIERS)?.modifiers() ?: emptyList()) {
                if (e.attribute() != Attributes.ARMOR && e.attribute() != Attributes.ARMOR_TOUGHNESS) continue
                val amount = e.modifier().amount()
                score += when (e.modifier().operation()) {
                    AttributeModifier.Operation.ADD_VALUE -> amount
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE -> amount * (p?.getAttributeBaseValue(e.attribute()) ?: 0.0)
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL -> amount * score
                }.toInt()
            }
        }
        return score
    }
}
