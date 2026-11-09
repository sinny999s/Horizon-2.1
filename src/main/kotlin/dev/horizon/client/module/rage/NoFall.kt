package dev.horizon.client.module.rage

import dev.horizon.client.combat.Found
import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.mixin.rage1.MovePacketAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Interact
import dev.horizon.client.module.Module
import dev.horizon.client.module.PlaceMode
import dev.horizon.client.module.Switch2
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation

/**
 * Once a fall would hurt (Calc: by health, Custom: past `fall-distance-val`; GrimOld past 3),
 * Rubberband reports on-ground, Items catches the fall (water bucket, pearl, cobweb or twisting vines, looking down),
 * MatrixOffGround/Vanilla/GRIM_NEW mark every later movement packet off-ground (GRIM_NEW flips it for 11 packets, then
 * jumps once with the keys released), GrimOld sends a position 1e-9 up off-ground and resets the fall.
 */
class NoFall : Module("no-fall", Categories.RAGE) {
    @Suppress("EnumEntryName")
    enum class Mode { Rubberband, Items, MatrixOffGround, Vanilla, GrimOld, GRIM_NEW }
    @Suppress("EnumEntryName")
    enum class FallDistance { Calc, Custom }

    val mode = enum("mode", Mode.Rubberband)
    val fallDistance = enum("fall-distance", FallDistance.Calc)
    val fallDistanceVal = int("fall-distance-val", 10, 2..100) { fallDistance.value == FallDistance.Custom }
    val waterBucket = bool("water-bucket", true) { mode.value == Mode.Items }
    val retrieve = bool("retrieve", true) { mode.value == Mode.Items && waterBucket.value }
    val enderPearl = bool("ender-pearl", true) { mode.value == Mode.Items }
    val cobweb = bool("cobweb", true) { mode.value == Mode.Items }
    val twistingVines = bool("twisting-vines", true) { mode.value == Mode.Items }
    val grim = bool("grim", true) { mode.value == Mode.Items }

    /** GRIM_NEW jump steps, spoof the on-ground flag, GRIM_NEW flipped packets, pick the water up. */
    private var jumpStage = 0
    private var spoof = false
    private var flipped = 0
    private var pickUpWater = false

    /** Since the last pearl / placement / successful use. */
    private val useTimer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (p.tickCount < 5) flipped = 0
            when (jumpStage) {
                1 -> {
                    jumpStage = 2
                    releaseKeys()
                }
                2 -> {
                    p.jumpFromGround()
                    jumpStage = 3
                    releaseKeys()
                }
                3 -> {
                    spoof = false
                    jumpStage = 0
                    releaseKeys()
                }
                else -> if (shouldCatch()) {
                    when (mode.value) {
                        Mode.Rubberband -> Packets.send(ServerboundMovePlayerPacket.StatusOnly(true, p.horizontalCollision))
                        Mode.Items -> items()
                        Mode.MatrixOffGround, Mode.Vanilla -> spoof = true
                        Mode.GRIM_NEW -> {
                            spoof = true
                            releaseKeys()
                        }
                        Mode.GrimOld -> {
                            Packets.send(ServerboundMovePlayerPacket.PosRot(p.x, p.y + 1.0E-9, p.z, p.yRot, p.xRot, false, p.horizontalCollision))
                            p.resetFallDistance()
                        }
                    }
                } else if (pickUpWater) {
                    Inventory.hotbarFind(Items.BUCKET)?.let { use(it) }
                    pickUpWater = false
                }
            }
        }
        on<SendPacketEvent> { e ->
            val pk = e.packet
            if (pk !is ServerboundMovePlayerPacket || !spoof) return@on
            val p = mc.player ?: return@on
            val grimFlip = mode.value == Mode.GRIM_NEW && flipped < 11
            if (grimFlip) {
                (pk as MovePacketAccessor).horizon_setOnGround(!p.onGround())
                flipped++
            } else {
                (pk as MovePacketAccessor).horizon_setOnGround(false)
            }
            if (p.onGround() && jumpStage == 0 && mode.value == Mode.GRIM_NEW && !grimFlip) jumpStage = 1
        }
    }

    override fun onEnable() {
        spoof = false
    }

    private fun shouldCatch(): Boolean {
        val p = mc.player ?: return false
        if (p.isFallFlying || p.abilities.flying) return false
        val fd = PlayerState.fallDistance
        if (mode.value == Mode.GrimOld) return fd > 3.0
        return when (fallDistance.value) {
            FallDistance.Custom -> fd > fallDistanceVal.value
            FallDistance.Calc -> (fd - 3) / 2.0f + 3.5f > p.health / 3.0f
        }
    }

    private fun items() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        var pos = BlockPos.containing(p.position())
        for (i in 0 until 5) {
            val b = pos.below(i)
            if (!level.getBlockState(b).canBeReplaced()) pos = b.above()
        }
        val pearl = Inventory.hotbarFind(Items.ENDER_PEARL)
        val web = Inventory.hotbarFind(Items.COBWEB)
        val vines = Inventory.hotbarFind(Items.TWISTING_VINES)
        val water = Inventory.hotbarFind(Items.WATER_BUCKET)
        if (water != null && waterBucket.value && useTimer.passed(500)) {
            use(water)
            pickUpWater = retrieve.value
        } else if (pearl != null && enderPearl.value && useTimer.passed(500)) {
            use(pearl)
            useTimer.reset()
        } else if (web != null && cobweb.value) {
            place(web, pos)
        } else if (vines != null && twistingVines.value) {
            place(vines, pos)
        }
    }

    /** Look straight down, then use the item from its hotbar slot and switch back. */
    private fun use(f: Found) {
        val p = mc.player ?: return
        RotationManager.queue(RotationRequest(Rotation(p.yRot, 90.0f), 150) {
            val pl = mc.player ?: return@RotationRequest
            Inventory.saveAndSelect(f.slot)
            val r = mc.gameMode?.useItem(pl, InteractionHand.MAIN_HAND)
            if (r != InteractionResult.PASS) useTimer.reset()
            pl.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
            Inventory.restore()
        })
    }

    /** Place the block at [pos] (priority 200, silent switch). */
    private fun place(f: Found, pos: BlockPos) {
        val p = mc.player ?: return
        val req = Placer.place(pos, f.slot, if (grim.value) Interact.GRIM else Interact.Strict, PlaceMode.Normal, IgnoreMode.ALL,
            p.blockInteractionRange().toFloat(), 0.0f, 200, Switch2.Silent) ?: return
        RotationManager.queue(req)
        useTimer.reset()
    }

    /** Stop sprinting and let go of the movement and jump keys. */
    private fun releaseKeys() {
        mc.player?.isSprinting = false
        val o = mc.options
        o.keyUp.isDown = false
        o.keyDown.isDown = false
        o.keyLeft.isDown = false
        o.keyRight.isDown = false
        o.keyJump.isDown = false
    }
}
