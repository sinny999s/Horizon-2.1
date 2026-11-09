package dev.horizon.client.module.attack

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Predictor
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Modules
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock

/**
 * Cobwebs into an enemy's feet block (and/or the one above), where their movement says
 * they'll be by the time the server gets the packet.
 */
class AutoWeb : PlacerModule("auto-web", Categories.ATTACK, 132) {
    enum class TargetMode { AUTO, ALL }
    enum class Face { OFF, ON, ONLY }

    val targetMode = enum("target-mode", TargetMode.AUTO)
    val face = enum("face", Face.ON)

    override fun blocks(): List<Block> = listOf(Blocks.COBWEB)

    /** No entity check at all: webs go into anyone's hitbox. */
    override val ignoreMode: IgnoreMode get() = IgnoreMode.NONE

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    private fun tick() {
        val self = mc.player ?: return
        val level = mc.level ?: return
        val range = range.value
        val fallback = TargetFinder.find(range.toDouble(), TargetFinder.Sort.FOV, false, false)
        if (targetMode.value == TargetMode.AUTO) {
            var t: Player? = fallback
            val aura = Modules.get<Aura>()
            val auraTarget = aura.target
            if (aura.enabled && auraTarget is Player) t = auraTarget
            val crystal = Modules.get<AutoCrystal>()
            if (crystal.enabled && crystal.target != null) t = crystal.target
            if (t == null || webbed(t, face.value != Face.OFF)) return
            webTarget(t)
            return
        }
        for (p in level.players()) {
            if (p === self || Friends.isFriend(p.gameProfile.name)) continue
            if (p.distanceTo(self) - range > 0) continue
            if (webbed(p, face.value != Face.OFF)) continue
            webTarget(p)
        }
    }

    /** A web in the feet block (and, with face on, the one above). */
    private fun webbed(p: Player, faceOn: Boolean): Boolean {
        val level = mc.level ?: return false
        val f = BlockPos.containing(p.position())
        if (level.getBlockState(f).block != Blocks.COBWEB) return false
        if (!faceOn) return true
        return level.getBlockState(f.above()).block == Blocks.COBWEB
    }

    /** The predicted feet block, then the one above (per `face`), never our own feet or eyes. */
    private fun webTarget(p: Player) {
        val self = mc.player ?: return
        val level = mc.level ?: return
        val feet = BlockPos.containing(Predictor.linear(p, Predictor.linearTicks(p)))
        val list = ArrayList<BlockPos>(2)
        if (face.value != Face.ONLY) {
            if (level.getBlockState(feet).block !is LiquidBlock && feet != BlockPos.containing(self.position())) list += feet
        }
        if (face.value != Face.OFF) {
            val head = feet.above()
            if (level.getBlockState(head).block !is LiquidBlock && head != BlockPos.containing(self.eyePosition)) list += head
        }
        placeList(list, false, false, listOf(Blocks.COBWEB), false)
    }
}
