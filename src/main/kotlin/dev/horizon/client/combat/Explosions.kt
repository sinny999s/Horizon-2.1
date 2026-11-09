package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PostTasksEvent
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.CombatRules
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** End-crystal explosion damage (power 6) with its own block raycast. */
object Explosions {
    private val mc get() = Minecraft.getInstance()

    /** Set by AutoCrystal's `assume-best-armor`: other players count as wearing Blast Protection IV everywhere. */
    var assumeBestArmor: () -> Boolean = { false }

    private const val EMPTY = 1
    private const val WEAK = 2
    private const val STRONG = 3
    private const val ENDER_CHEST = 4

    /** Block codes for this frame, by position (a block's code is its first lookup's). */
    private val codes = ConcurrentHashMap<Long, Int>()
    private val ENDER_CHEST_SHAPE: VoxelShape = net.minecraft.world.level.block.Block.column(14.0, 0.0, 14.0)

    private var source: DamageSource? = null

    /** Read block codes from the cache (on, except while the "test" module compares without it). */
    var useCache = true

    fun init() {
        EventBus.on<PostTasksEvent>(this) { codes.clear() }
    }

    /** Damage to [entity] from a crystal exploding at [center], with its box [ticks] ahead. */
    fun damage(center: Vec3, entity: LivingEntity, ticks: Int, terrainIgnore: Boolean, ghost: Collection<BlockPos>,
               solid: Collection<BlockPos>, power: Float = 6.0f): Float {
        val level = mc.level ?: return 0f
        val box = if (ticks > 0) Predictor.box(entity, ticks) else entity.boundingBox
        val doublePower = (power * 2.0)
        val dist = box.center.subtract(0.0, box.ysize / 2, 0.0).distanceTo(center) / doublePower
        if (level.difficulty == Difficulty.PEACEFUL || dist > 1.0 || (entity is Player && entity.isCreative)) return 0f
        val seen = exposure(center, box, terrainIgnore, ghost, solid)
        val d = (1.0 - dist) * seen
        var dmg = ((d * d + d) / 2.0 * 7.0 * doublePower + 1.0).toFloat()
        if (level.difficulty == Difficulty.EASY) dmg = min(dmg / 2f + 1f, dmg)
        if (level.difficulty == Difficulty.HARD) dmg *= 1.5f
        val src = source ?: level.damageSources().source(DamageTypes.EXPLOSION).also { source = it }
        dmg = CombatRules.getDamageAfterAbsorb(entity, dmg, src, entity.armorValue.toFloat(),
            entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS).toFloat())
        val resistance = entity.getEffect(MobEffects.RESISTANCE)
        if (resistance != null) dmg = max(dmg * (25 - (resistance.amplifier + 1) * 5) / 25f, 0f)
        if (dmg <= 0f) return 0f
        var epf = 0
        val armour = intArrayOf(39, 38, 37, 36)
        if (assumeBestArmor() && entity !== mc.player && entity is Player) {
            for (i in armour) if (!entity.inventory.getItem(i).isEmpty) epf += 8
        } else if (entity is Player) {
            val reg = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            val blast = reg.getOrThrow(Enchantments.BLAST_PROTECTION)
            val prot = reg.getOrThrow(Enchantments.PROTECTION)
            for (i in armour) {
                val s = entity.inventory.getItem(i)
                epf += 2 * EnchantmentHelper.getItemEnchantmentLevel(blast, s) + EnchantmentHelper.getItemEnchantmentLevel(prot, s)
            }
        }
        if (epf > 0) dmg = CombatRules.getDamageAfterMagicAbsorb(dmg, epf.toFloat())
        return dmg
    }

    /** The share of [box] the explosion at [center] reaches: corners first, then vanilla's grid. */
    private fun exposure(center: Vec3, box: AABB, terrainIgnore: Boolean, ghost: Collection<BlockPos>, solid: Collection<BlockPos>): Float {
        val sx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0)
        val sy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0)
        val sz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0)
        if (sx < 0 || sy < 0 || sz < 0) return 0f
        val offX = (1.0 - floor(1.0 / sx) * sx) / 2.0
        val offZ = (1.0 - floor(1.0 / sz) * sz) / 2.0
        var hits = 0
        var total = 0
        fun sample(a: Double, b: Double, c: Double) {
            val p = Vec3(Mth.lerp(a, box.minX, box.maxX) + offX, Mth.lerp(b, box.minY, box.maxY), Mth.lerp(c, box.minZ, box.maxZ) + offZ)
            if (raycast(p, center, terrainIgnore, ghost, solid) == null) hits++
            total++
        }
        for (a in 0..1) for (b in 0..1) for (c in 0..1) sample(a.toDouble(), b.toDouble(), c.toDouble())
        if (hits == total) return 1f
        if (hits == 0) return 0f
        var a = 0.0
        while (a <= 1.0) {
            var b = 0.0
            while (b <= 1.0) {
                var c = 0.0
                while (c <= 1.0) {
                    val corner = (a == 0.0 || a == 1.0) && (b == 0.0 || b == 1.0) && (c == 0.0 || c == 1.0)
                    if (!corner) sample(a, b, c)
                    c += sz
                }
                b += sy
            }
            a += sx
        }
        return hits.toFloat() / total
    }

    /**
     * The first block hit going from [from] to [to], or null. Blocks in [ghost] are air, blocks in
     * [solid] full; with [terrainIgnore], blocks an explosion breaks (resistance under 600) are air too.
     */
    fun raycast(from: Vec3, to: Vec3, terrainIgnore: Boolean, ghost: Collection<BlockPos>, solid: Collection<BlockPos>): BlockHitResult? {
        if (from == to) return null
        val level = mc.level ?: return null
        return traverse(from, to) { pos ->
            when {
                pos in ghost -> null
                pos in solid -> Shapes.block().clip(from, to, pos.immutable())
                else -> shapeFor(pos, terrainIgnore)?.clip(from, to, pos.immutable())
            }
        }
    }

    /** Vanilla's `BlockGetter.traverseBlocks`: the blocks on the line, the start block first, until [fn] returns a hit. */
    private inline fun traverse(from: Vec3, to: Vec3, fn: (BlockPos) -> BlockHitResult?): BlockHitResult? {
        val tx = Mth.lerp(-1.0E-7, to.x, from.x)
        val ty = Mth.lerp(-1.0E-7, to.y, from.y)
        val tz = Mth.lerp(-1.0E-7, to.z, from.z)
        val fx = Mth.lerp(-1.0E-7, from.x, to.x)
        val fy = Mth.lerp(-1.0E-7, from.y, to.y)
        val fz = Mth.lerp(-1.0E-7, from.z, to.z)
        var x = Mth.floor(fx)
        var y = Mth.floor(fy)
        var z = Mth.floor(fz)
        val pos = BlockPos.MutableBlockPos(x, y, z)
        fn(pos)?.let { return it }
        val dx = tx - fx
        val dy = ty - fy
        val dz = tz - fz
        val sx = Mth.sign(dx)
        val sy = Mth.sign(dy)
        val sz = Mth.sign(dz)
        val ddx = if (sx == 0) Double.MAX_VALUE else sx / dx
        val ddy = if (sy == 0) Double.MAX_VALUE else sy / dy
        val ddz = if (sz == 0) Double.MAX_VALUE else sz / dz
        var px = ddx * (if (sx > 0) 1.0 - Mth.frac(fx) else Mth.frac(fx))
        var py = ddy * (if (sy > 0) 1.0 - Mth.frac(fy) else Mth.frac(fy))
        var pz = ddz * (if (sz > 0) 1.0 - Mth.frac(fz) else Mth.frac(fz))
        while (px <= 1.0 || py <= 1.0 || pz <= 1.0) {
            if (px < py) {
                if (px < pz) {
                    x += sx
                    px += ddx
                } else {
                    z += sz
                    pz += ddz
                }
            } else if (py < pz) {
                y += sy
                py += ddy
            } else {
                z += sz
                pz += ddz
            }
            fn(pos.set(x, y, z))?.let { return it }
        }
        return null
    }

    /** The block's collision shape, or null where the ray goes through it. */
    private fun shapeFor(pos: BlockPos, terrainIgnore: Boolean): VoxelShape? {
        val level = mc.level ?: return null
        val key = pos.asLong()
        val code = codes[key]
        if (useCache && code != null) return when (code) {
            EMPTY -> null
            WEAK -> if (terrainIgnore) null else Shapes.block()
            STRONG -> Shapes.block()
            else -> ENDER_CHEST_SHAPE
        }
        val state = level.getBlockState(pos)
        val shape = state.getCollisionShape(level, pos)
        val block = state.block
        val resistance = if (block == Blocks.GLOWSTONE) 600f else block.explosionResistance
        when {
            shape.isEmpty -> codes[key] = EMPTY
            block == Blocks.ENDER_CHEST -> codes[key] = ENDER_CHEST
            shape == Shapes.block() -> codes[key] = if (block.explosionResistance < 600f) WEAK else STRONG
        }
        if (terrainIgnore && resistance < 600f) return null
        return if (shape.isEmpty) null else if (block == Blocks.ENDER_CHEST) ENDER_CHEST_SHAPE else shape
    }
}
