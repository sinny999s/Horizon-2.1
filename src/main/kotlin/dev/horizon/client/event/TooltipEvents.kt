package dev.horizon.client.event

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * `ItemStack.getTooltipImage` RETURN when the item has no image of its own (and the container text
 * lines, which are dropped when [hideList] is set with an image, and the tooltip lines, cut to [maxLineWidth] px when
 * it isn't 0). A handler may supply [image]. One shared instance.
 */
object TooltipImageEvent : Event() {
    var stack: ItemStack = ItemStack.EMPTY
    var image: TooltipComponent? = null
    var hideList = false
    var maxLineWidth = 0

    /** [lines], each cut to [maxLineWidth] with an ellipsis if wider (styles kept). */
    fun fit(lines: List<Component>): List<Component> {
        val font = Minecraft.getInstance().font
        return lines.map { line ->
            if (font.width(line) <= maxLineWidth) return@map line
            val out = Component.empty()
            font.substrByWidth(line, maxLineWidth - font.width("…")).visit({ style, s ->
                out.append(Component.literal(s).withStyle(style))
                Optional.empty<Unit>()
            }, Style.EMPTY)
            out.append(Component.literal("…").withStyle(line.style))
        }
    }
}
