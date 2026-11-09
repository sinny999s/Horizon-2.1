package dev.horizon.client.module.misc

import dev.horizon.client.event.TooltipImageEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ShulkerBoxBlock

/**
 * Hovering a shulker box (or any item holding container contents) in an inventory or container shows
 * its slots as a grid in the tooltip, tinted with the box's colour; with [hideList] vanilla's text list goes.
 */
class ShulkerView : Module("shulker-view", Categories.MISC) {
    val hideList = bool("hide-list", true)

    /** The tooltip data: the item's slots, nine to a row, and the box's colour. */
    class Contents(val slots: List<ItemStack>, val color: DyeColor) : TooltipComponent

    /** Draws [Contents] as 18 px slots, like a container's. */
    class Grid(private val c: Contents) : ClientTooltipComponent {
        private val rows = c.slots.size / 9

        override fun getHeight(font: Font) = rows * 18 + 2

        override fun getWidth(font: Font) = 9 * 18

        override fun extractImage(font: Font, x: Int, y: Int, w: Int, h: Int, graphics: GuiGraphicsExtractor) {
            graphics.fill(x, y, x + 9 * 18, y + rows * 18, (c.color.textureDiffuseColor and 0xFFFFFF) or 0x55000000)
            for ((i, stack) in c.slots.withIndex()) {
                val sx = x + i % 9 * 18
                val sy = y + i / 9 * 18
                graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0x40000000)
                if (stack.isEmpty) continue
                graphics.item(stack, sx + 1, sy + 1)
                graphics.itemDecorations(font, stack, sx + 1, sy + 1)
            }
        }
    }

    init {
        ClientTooltipComponentCallback.EVENT.register { data -> if (data is Contents) Grid(data) else null }
        on<TooltipImageEvent> { e ->
            val contents = e.stack.get(DataComponents.CONTAINER) ?: return@on
            if (contents.nonEmptyItemCopyStream().findAny().isEmpty) return@on
            val slots = NonNullList.withSize(maxOf(27, (contents.size() + 8) / 9 * 9), ItemStack.EMPTY)
            contents.copyInto(slots)
            val block = (e.stack.item as? BlockItem)?.block as? ShulkerBoxBlock
            e.image = Contents(slots, block?.color ?: DyeColor.PURPLE)
            e.hideList = hideList.value
            // A long custom name would stretch the tooltip far past the grid.
            e.maxLineWidth = 9 * 18
        }
    }
}
