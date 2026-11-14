package dev.horizon.client.module.attack

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Aura's `follow-mouse`: how the look only the server gets (LEGIT `silent`) shares the turning with your own hand, kept
 * free of the game so it can be simulated in tests. Left to itself that look is turned by the simulated hand alone, so
 * when your own flick beats the hand to the target, the hits still wait for the hand. With this:
 * - what you turn towards the aim the hidden look turns too, on top of the hand's own turn, up to the aim and not past
 *   it, and the hand's movement under way is shortened by as much ([HumanAim.credit]). So it is never behind your own
 *   aim. What you turn away from the target it leaves alone;
 * - once your own crosshair has stayed on the target for [DWELL] (someone running through it doesn't count) you lead:
 *   the hidden look takes all of your turn, closes what is left between it and the camera as the hand makes any other
 *   movement ([HumanAim.onto]), and within [LOCK] of the camera it rides with it, so what the server gets is your own
 *   aim. The hand takes over again the moment your crosshair is off the target.
 */
class FollowMouse(private val hand: HumanAim) {
    /** The hidden look, as the caller keeps it. */
    interface Look {
        val yaw: Double
        val pitch: Double

        /** Turns it by exactly this much (your own turn, which is whole mouse counts already). */
        fun add(dYaw: Double, dPitch: Double)

        /** Turns it by this much in whole mouse counts, carrying the rest over to the next turn. */
        fun turn(dYaw: Double, dPitch: Double)

        /** Turns it by the whole mouse counts nearest to this much, and drops the rest. */
        fun settle(dYaw: Double, dPitch: Double)
    }

    /** Your own crosshair has stayed on the target: the hidden look goes with the camera. */
    var leading = false
        private set

    /** When your crosshair came onto the target (NaN: it isn't on it). */
    private var onSince = Double.NaN

    fun reset() {
        leading = false
        onSince = Double.NaN
    }

    /**
     * One frame, before the hand's own. [now] is a clock in seconds; [moveYaw], [movePitch] how far your own hand turned
     * the camera since the last frame, and [camYaw], [camPitch] where it looks now; [inner] whether your crosshair is
     * well on the target and [outer] whether it is on it at all; [errYaw], [errPitch] how far off the aim the hidden look
     * is; [size] the target's width in degrees.
     *
     * Returns whether you lead this frame: the hidden look has been turned for it, and the hand must leave it alone.
     */
    fun frame(
        look: Look, now: Double, dt: Double, moveYaw: Double, movePitch: Double, camYaw: Double, camPitch: Double,
        inner: Boolean, outer: Boolean, errYaw: Double, errPitch: Double, size: Double, p: HumanAim.Params,
    ): Boolean {
        if (!outer) reset()
        else if (onSince.isNaN()) {
            if (inner) onSince = now
        } else if (now - onSince >= DWELL) leading = true

        if (!leading) {
            val yaw = toward(moveYaw, errYaw)
            val pitch = toward(movePitch, errPitch)
            if (yaw != 0.0 || pitch != 0.0) {
                look.turn(yaw, pitch)
                hand.credit(yaw, pitch)
            }
            return false
        }
        look.add(moveYaw, movePitch)
        val gapYaw = HumanAim.wrap(camYaw - look.yaw)
        val gapPitch = camPitch - look.pitch
        if (hypot(gapYaw, gapPitch) <= LOCK) {
            look.settle(gapYaw, gapPitch)
            hand.rest()
        } else {
            val d = hand.onto(dt, gapYaw, gapPitch, size, p)
            look.turn(d[0], d[1])
        }
        return true
    }

    companion object {
        /** How long your crosshair has to stay on the target before you lead (seconds). */
        const val DWELL = 0.15

        /** Within this of the camera (degrees) the hidden look rides with it. */
        const val LOCK = 0.5

        /**
         * The part of your own turn [moved] that goes towards an aim [err] away: none of a turn away from it, and no
         * more than is left to it.
         */
        fun toward(moved: Double, err: Double): Double =
            if (moved * err <= 0.0) 0.0 else if (abs(moved) < abs(err)) moved else err
    }
}
