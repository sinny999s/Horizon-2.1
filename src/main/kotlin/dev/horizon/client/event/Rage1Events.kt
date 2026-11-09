package dev.horizon.client.event

import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3

/** `WebBlock.entityInside` HEAD; cancelling skips the cobweb's slowdown. */
class CobWebEvent : Event()

/** `SweetBerryBushBlock.entityInside` HEAD; cancelling skips the bush's slowdown and damage. */
class SweetBerryBushCollisionEvent : Event()

/** `SlimeBlock.stepOn` HEAD; cancelling skips the slime's slowdown. */
class StepOnSlimeEvent : Event()

/** `Block.getFriction` HEAD; cancelling returns dirt's friction. */
class SlipperinessEvent(val block: Block) : Event()

/** `Block.getSpeedFactor` HEAD; cancelling returns dirt's speed factor. */
class VelocityMultiplierEvent(val block: Block) : Event()

/** `LocalPlayer.isMovingSlowly` HEAD; cancelling makes it false. */
class SlowDownEvent : Event()

/** `LocalPlayer.shouldStopRunSprinting` HEAD; cancelling makes it false. */
class ShouldStopSprintingEvent : Event()

/** `Player.wantsToStopRiding` HEAD; cancelling makes it false. */
class ShouldDismountEvent : Event()

/** `AbstractContainerMenu.clicked` HEAD; cancelling skips the client-side click (the packet then lists no changes). */
class ClearClickMapEvent : Event()

/** `Gui.setScreen` HEAD; cancelling keeps the current screen. */
class ScreenEvent(val screen: Screen?) : Event()

/** The local player's pose (`Entity.getPose` HEAD); cancelling returns [pose]. */
class SetPoseEvent : Event() {
    var pose: Pose = Pose.STANDING
}

/**
 * The local player's `LivingEntity.travel` HEAD with its velocity; cancelling skips travel and moves by [vec] instead.
 */
class PreElytraEvent(var vec: Vec3) : Event()

/** The local player's `LivingEntity.travel` RETURN; cancelling moves by [vec] once more. */
class AfterElytraEvent(var vec: Vec3) : Event()

/** A firework boosting the local player sets its velocity; cancelling sets [velocity] instead. */
class FireworkVelocityEvent(var velocity: Vec3) : Event()
