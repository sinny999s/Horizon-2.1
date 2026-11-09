package dev.horizon.client.event

import net.minecraft.client.renderer.entity.state.EntityRenderState

/** Vanilla is about to draw an entity's name tag (`EntityRenderer`/`AvatarRenderer.submitNameDisplay` HEAD); cancelling hides it. */
class RenderNameTagEvent(val state: EntityRenderState) : Event()

/** `MouseHandler.onButton` HEAD: [action] 1 = press, 0 = release. */
class MouseButtonEvent(val button: Int, val action: Int) : Event()
