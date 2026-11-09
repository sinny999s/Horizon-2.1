package dev.horizon.client.module.world

import dev.horizon.client.combat.Scheduler
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.SlotRenderEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.pow

/**
 * Helpers for Russian anarchy servers: auc-helper tints auction items by how close
 * their unit price is to the cheapest one of the same kind; structure-cooldowns shows a countdown over pistons
 * (netherite scrap, 15 s) and anvil-built structures (dried kelp, 20 s / 60 s).
 */
class ServerHelper : Module("server-helper", Categories.WORLD) {
    val aucHelper = bool("auc-helper", true)
    val structureCooldowns = bool("structure-cooldowns", true)

    /** A countdown over [pos] until [end] (ms), drawn with [icon]. */
    private class Cooldown(val pos: Vec3, val end: Long, val icon: Item)

    /** One auction slot: its unit [price], the cheapest unit price of that item, and its menu slot. */
    private class Lot(val item: Item, val price: Int, val min: Int, val index: Int)

    private val cooldowns = ArrayList<Cooldown>()
    private val lots = ArrayList<Lot>()

    init {
        on<ReceivePacketEvent> { e ->
            val p = e.packet
            if (!structureCooldowns.value || p !is ClientboundSoundPacket) return@on
            val sound = p.sound.value()
            // The scheduler isn't thread-safe, so the task is added on the game thread (before the next tick).
            if (sound == SoundEvents.PISTON_CONTRACT || sound == SoundEvents.PISTON_EXTEND) {
                mc.execute {
                    Scheduler.ticks(0) { cooldowns += Cooldown(Vec3(p.x, p.y, p.z), System.currentTimeMillis() + 15000L, Items.NETHERITE_SCRAP) }
                }
            } else if (sound == SoundEvents.ANVIL_PLACE) {
                val pos = BlockPos.containing(p.x, p.y, p.z)
                mc.execute { Scheduler.ticks(5) { anvil(pos) } }
            }
        }

        on<Render2DEvent> { e ->
            if (!structureCooldowns.value) return@on
            cooldowns.removeIf { System.currentTimeMillis() - it.end >= 0L }
            val g = e.graphics
            val font = Fonts.medium75
            for (c in cooldowns) {
                val ticks = ((c.end - System.currentTimeMillis()).toDouble() / 50.0f).toInt()
                val text = (ticks / 1200).toString() + ":" + String.format("%02d", ticks % 1200 / 20)
                val w = font.width(text) + 22.0f
                val s = World3D.project(c.pos) ?: continue
                val x = s[0] - w / 2
                val y = s[1]
                Canvas.glass(x, y, w, 16.0f, 3.0f, 1.5f, 0.0f, 1.0f, Theme.widgetBlur, Theme.outline)
                g.pose().pushMatrix()
                g.pose().translate(x + 2.0f, y + 2.0f)
                g.pose().scale(0.7f)
                g.item(c.icon.defaultInstance, 0, 0)
                g.pose().popMatrix()
                Canvas.quad(x + 14, y + 4.0f, 0.5f, 9.0f, 1157627903)
                font.draw(text, x + 18.0f, y + 3.0f, Theme.text)
            }
        }

        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val menu = player.containerMenu as? ChestMenu ?: return@on
            if (!aucHelper.value) return@on
            val title = mc.gui.screen()?.title?.string ?: return@on
            if (!isAuction(title)) return@on
            lots.clear()
            val mins = LinkedHashMap<String, Int>()
            var i = 0
            for (stack in menu.items) {
                if (i <= 44) {
                    var price = price(stack)
                    if (stack.count > 1) price /= stack.count
                    mins[key(stack)] = minOf(mins.getOrDefault(key(stack), 999999999), price)
                    i++
                }
            }
            i = 0
            for (stack in menu.items) {
                if (mins[key(stack)] != null) {
                    var price = price(stack)
                    if (stack.count > 1) price /= stack.count
                    lots += Lot(stack.item, price, mins[key(stack)] ?: 99999999, i)
                }
                i++
            }
        }

        on<SlotRenderEvent> { e ->
            val slot = e.slot
            if (mc.player?.containerMenu !is ChestMenu) return@on
            val title = mc.gui.screen()?.title?.string ?: return@on
            if (!isAuction(title)) return@on
            for (lot in lots) {
                if (lot.index == slot.index && slot.index <= 44 && !slot.item.isEmpty) {
                    val ratio = lot.min.toDouble().pow(4).toFloat() / lot.price.toDouble().pow(4).toFloat()
                    val c = Color(Color.GREEN.red, Color.GREEN.green, Color.GREEN.blue, (255.0f * Math.clamp(ratio, 0.0f, 1.0f)).toInt())
                    e.graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, c.rgb)
                    return@on
                }
            }
        }
    }

    /**
     * An anvil was placed. The nearest cobblestone more than 2 blocks from it (within ±4) is taken as the
     * structure's centre; 8 andesite around it makes it a 60 s one (drawn half a block lower), else 20 s.
     */
    private fun anvil(pos: BlockPos) {
        val level = mc.level ?: return
        val centre = Vec3.atCenterOf(pos)
        val core = cube(pos, 4, 4).filter {
            Vec3.atCenterOf(it).distanceTo(centre) > 2.0 && level.getBlockState(it).block == Blocks.COBBLESTONE
        }.minByOrNull { Vec3.atCenterOf(it).distanceTo(centre) } ?: return
        val andesite = cube(core, 1, 0).count { level.getBlockState(it).block == Blocks.ANDESITE }
        val time = if (andesite == 8) 60000 else 20000
        cooldowns += Cooldown(Vec3.atCenterOf(core).add(0.0, if (andesite == 8) -0.5 else 0.0, 0.0),
            System.currentTimeMillis() + time - 250, Items.DRIED_KELP)
    }

    /** The box ±[r] in x and z and ±[ry] in y around [pos], x outermost, then z, then y. */
    private fun cube(pos: BlockPos, r: Int, ry: Int): List<BlockPos> {
        val list = ArrayList<BlockPos>()
        for (x in pos.x - r..pos.x + r) for (z in pos.z - r..pos.z + r) for (y in pos.y - ry..pos.y + ry) list += BlockPos(x, y, z)
        return list
    }

    private fun key(stack: ItemStack): String = stack.item.descriptionId

    /**
     * The price in an item's lore. On holyworld the first sibling with 2+ digits of the "price per unit"
     * line; elsewhere the "price" line itself. 999999999 without lore, 9999999 when nothing parses.
     */
    private fun price(stack: ItemStack): Int {
        val lore = stack.components.get(DataComponents.LORE) ?: return 999999999
        try {
            val ip = mc.connection?.serverData?.ip ?: "singleplayer"
            if (ip.contains("holyworld")) {
                for (line in lore.lines()) {
                    if (!line.string.contains(PER_UNIT)) continue
                    for (sib in line.siblings) {
                        val s = sib.string
                        if (s.count { Character.isDigit(it) } > 1) return Integer.parseInt(s.replace(" ", "").replace(CURRENCY, ""))
                    }
                }
            } else {
                for (line in lore.lines()) {
                    if (line.string.contains(PRICE)) return Integer.parseInt(line.string.replace(PRICE_LABEL, "").replace(",", ""))
                }
            }
        } catch (_: Exception) {
        }
        return 9999999
    }

    private fun isAuction(title: String) = title.contains(AUCTION) || title.contains(SEARCH)

    private companion object {
        // These strings are UTF-8 Russian read as CP1251 ("Аукцион", "Поиск", "Цена за 1 ед.",
        // "¤", "Цена" with a Latin a), so they never match a real title or lore; kept like this on purpose.
        const val AUCTION = "РђСѓРєС†РёРѕРЅ"
        const val SEARCH = "РџРѕРёСЃРє"
        const val PER_UNIT = "Р¦РµРЅР° Р·Р° 1 РµРґ."
        const val CURRENCY = "В¤"
        const val PRICE = "Р¦РµРЅa"
        const val PRICE_LABEL = "$ Р¦РµРЅa $"
    }
}
