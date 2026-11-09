package dev.horizon.client.event


/** `GameRenderer.renderItemInHand` HEAD: cancelling skips vanilla's first-person hands. */
class RenderHandEvent : Event()

/** Right after vanilla drew (or skipped) the first-person hands. */
class PostRenderHandEvent : Event()

/** `EndCrystalRenderer.submit` HEAD: cancelling hides the crystal. */
class CrystalRenderEvent : Event()

/** Before the crystal's first `scale`: cancelling scales the crystal by [scale] first. */
class SetCrystalScaleEvent(val age: Float) : Event() {
    var scale = 1f
}

/** `EndCrystalRenderer.getY` HEAD: cancelling makes [offset] the crystal's bob height. */
class CrystalYOffsetEvent(val age: Float) : Event() {
    var offset = 0f
}

/** End crystal constructor TAIL, client and server: cancelling starts its age at 0. */
class CrystalCreateEvent : Event()
