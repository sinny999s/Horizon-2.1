package dev.horizon.client.huntmap

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor

/**
 * Turns a loaded chunk into the hunt map's colours, the way vanilla's filled maps do (the top block's map
 * colour, darker or lighter by its height against the block north of it, water by its depth). Under a roof (the nether)
 * it looks below the roof instead: down through it to the first open space, then to the floor of that space.
 */
object ChunkColors {
    /** The highest Y the nether scan starts from, just under the bedrock roof. */
    const val ROOF_START = 122

    /**
     * Records [chunk] into [region] and returns each column's ground height (index x + z * 16, [Int.MIN_VALUE] where
     * there's none): the top solid block ignoring water, plants, logs and leaves, or the nether floor.
     */
    fun record(level: ClientLevel, chunk: LevelChunk, region: MapRegion, ceiling: Boolean): IntArray {
        val ground = IntArray(256) { Int.MIN_VALUE }
        val tops = IntArray(256)
        val colors = arrayOfNulls<MapColor>(256)
        val depths = IntArray(256)
        val pos = BlockPos.MutableBlockPos()
        val bx = chunk.pos.minBlockX
        val bz = chunk.pos.minBlockZ
        val minY = level.minY
        for (lz in 0..15) for (lx in 0..15) {
            val i = lx + lz * 16
            val col = scan(level, chunk, bx + lx, bz + lz, ceiling, pos)
            tops[i] = col.top
            colors[i] = col.color
            depths[i] = col.depth
            if (col.ground > minY) ground[i] = col.ground
        }
        // The row north of this chunk, for row 0's shading.
        val northChunk = level.chunkSource.getChunkNow(chunk.pos.x(), chunk.pos.z() - 1)
        val north = IntArray(16) { lx ->
            if (northChunk == null) tops[lx] else scan(level, northChunk, bx + lx, bz - 1, ceiling, pos).top
        }
        for (lz in 0..15) for (lx in 0..15) {
            val i = lx + lz * 16
            val color = colors[i]
            val packed = if (color == null || color == MapColor.NONE) {
                0
            } else {
                val brightness = if (color == MapColor.WATER) {
                    val f = depths[i] * 0.1 + ((lx + lz) and 1) * 0.2
                    if (f < 0.5) MapColor.Brightness.HIGH else if (f > 0.9) MapColor.Brightness.LOW else MapColor.Brightness.NORMAL
                } else {
                    val above = if (lz == 0) north[lx] else tops[i - 16]
                    val f = (tops[i] - above) * 0.8 + (((lx + lz) and 1) - 0.5) * 0.4
                    if (f > 0.6) MapColor.Brightness.HIGH else if (f < -0.6) MapColor.Brightness.LOW else MapColor.Brightness.NORMAL
                }
                color.getPackedId(brightness).toInt() and 255
            }
            region.colors[((bz + lz) and 511) * 512 + ((bx + lx) and 511)] = packed.toByte()
        }
        return ground
    }

    private class Column(val top: Int, val color: MapColor, val depth: Int, val ground: Int)

    private fun scan(level: ClientLevel, chunk: LevelChunk, x: Int, z: Int, ceiling: Boolean, pos: BlockPos.MutableBlockPos): Column {
        val minY = level.minY
        if (ceiling) {
            var y = minOf(ROOF_START, level.maxY)
            var state = at(chunk, pos, x, y, z)
            while (y > minY && !open(state)) state = at(chunk, pos, x, --y, z)
            while (y > minY && open(state)) state = at(chunk, pos, x, --y, z)
            return Column(y, state.getMapColor(level, pos), 0, y)
        }
        var y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15) + 1
        var state: BlockState
        var color: MapColor
        do {
            y--
            state = at(chunk, pos, x, y, z)
            color = state.getMapColor(level, pos)
        } while (color == MapColor.NONE && y > minY)
        val top = y
        var depth = 0
        if (!state.fluidState.isEmpty) {
            var yy = y - 1
            do {
                depth++
            } while (yy > minY && !at(chunk, pos, x, yy--, z).fluidState.isEmpty)
        }
        var g = y
        var s = state
        while (g > minY && !solidGround(s)) s = at(chunk, pos, x, --g, z)
        return Column(top, color, depth, g)
    }

    private fun at(chunk: LevelChunk, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): BlockState =
        chunk.getBlockState(pos.set(x, y, z))

    /** Space you could fly through under the nether roof. */
    private fun open(s: BlockState) = s.isAir || !s.isSolid && s.fluidState.isEmpty

    private fun solidGround(s: BlockState) = s.isSolid && !s.`is`(BlockTags.LEAVES) && !s.`is`(BlockTags.LOGS)
}
