package dev.horizon.client.module.player

import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * Records the look and the movement / jump / sneak / use / attack keys every tick,
 * and plays them back by setting them again, from the start once the end is reached (or back and forth with Rewind
 * On Done). Recording and playback keep going while the module is on; nothing is cleared when it's turned off.
 */
class ActionRecorder : Module("action-recorder", Categories.PLAYER) {
    /** One tick of input. */
    private class Frame(val yaw: Float, val pitch: Float, val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean,
                        val shift: Boolean, val jump: Boolean, val use: Boolean, val attack: Boolean)

    /** Set while a recording plays back (hides Record). */
    var playing = false
    /** Set while recording (hides Play). */
    var recording = false

    private val frames = HashMap<Int, Frame>()
    private var index = 0
    private var rewinding = false

    // Starting to record or to play goes back to frame 0.
    val recordStop = action("record-/-stop", visible = { !playing }) {
        recording = !recording
        if (recording) index = 0
    }
    val playStop = action("play-/-stop", visible = { !recording }) {
        playing = !playing
        if (playing) index = 0
    }
    val reset = action("reset") {
        frames.clear()
        recording = false
        playing = false
        index = 0
        rewinding = false
    }
    val rewindOnDone = bool("rewind-on-done", false)

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            val o = mc.options
            if (recording) {
                frames[index] = Frame(p.yRot, p.xRot, o.keyUp.isDown, o.keyDown.isDown, o.keyLeft.isDown, o.keyRight.isDown,
                    o.keyShift.isDown, o.keyJump.isDown, o.keyUse.isDown, o.keyAttack.isDown)
                index++
            }
            if (!playing) return@on
            if (index >= frames.size) {
                rewinding = true
                index = frames.size
            }
            if (index < 0) {
                rewinding = false
                index = 0
            }
            val f = frames[index]
            if (f != null) {
                p.forceSetRotation(f.yaw, false, f.pitch, false)
                o.keyUp.isDown = f.up
                o.keyDown.isDown = f.down
                o.keyLeft.isDown = f.left
                o.keyRight.isDown = f.right
                o.keyShift.isDown = f.shift
                o.keyJump.isDown = f.jump
                o.keyUse.isDown = f.use
                o.keyAttack.isDown = f.attack
            }
            step()
        }
    }

    /** The next frame; past the end, back to 0 (or walk back down with Rewind On Done). */
    private fun step() {
        if (rewindOnDone.value) {
            if (rewinding) index-- else index++
            return
        }
        index++
        if (!rewinding) return
        index = 0
        rewinding = false
    }
}
