package dev.horizon.client.event

import net.minecraft.world.entity.Entity

/**
 * Whether [entity] should glow (Minecraft's outline, seen through walls) and in what colour, asked by
 * `Minecraft.shouldEntityAppearGlowing` (when vanilla says no) and `Entity.getTeamColor`. A handler sets [glow] and
 * [color] (RGB). Asked for every entity every frame, so it's one shared instance.
 */
object GlowEvent : Event() {
    var entity: Entity? = null
    var glow = false
    var color = 0

    fun ask(e: Entity): GlowEvent {
        cancelled = false
        entity = e
        glow = false
        color = 0
        return EventBus.post(this)
    }
}
