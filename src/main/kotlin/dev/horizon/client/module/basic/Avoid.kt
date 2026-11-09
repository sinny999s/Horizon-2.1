package dev.horizon.client.module.basic

import dev.horizon.client.event.CollisionEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.TurtleEggBlock

/**
 * The blocks it is set to avoid collide like dirt, so the player can't walk into them. This changes every collision
 * check while it is on (other entities too, and the integrated server's).
 */
class Avoid : Module("avoid", Categories.BASIC) {
    val unloaded = bool("unloaded", false)
    val voidAir = bool("void-air", false)
    val cactus = bool("cactus", false)
    val fire = bool("fire", false)
    val berryBush = bool("berry-bush", false)
    val powderSnow = bool("powder-snow", false)
    val lava = bool("lava", false)
    val trapString = bool("trap-string", false)
    val plate = bool("plate", false)
    val turtleEggs = bool("turtle-eggs", false)

    init {
        on<CollisionEvent> { e ->
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val state = e.state
            val block = state.block
            // The chunk the player stands in, from the position truncated to int (on purpose).
            val pos = p.position()
            val hit = (!level.hasChunk(pos.x.toInt() shr 4, pos.z.toInt() shr 4) && unloaded.value) ||
                (block == Blocks.FIRE || block == Blocks.SOUL_FIRE) && fire.value ||
                block == Blocks.CACTUS && cactus.value ||
                block == Blocks.LAVA && lava.value ||
                block == Blocks.SWEET_BERRY_BUSH && berryBush.value ||
                block == Blocks.POWDER_SNOW && powderSnow.value ||
                (block is PressurePlateBlock || block == Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE ||
                    block == Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE) && plate.value ||
                block is TripWireBlock && state.getValue(TripWireHookBlock.ATTACHED) && trapString.value ||
                e.pos.y < level.minY && voidAir.value ||
                block is TurtleEggBlock && turtleEggs.value
            if (hit) e.state = Blocks.DIRT.defaultBlockState()
        }
    }
}
