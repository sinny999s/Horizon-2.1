package dev.horizon.client.event

import net.minecraft.world.entity.Entity

/**
 * `ClientLevel.addEntity` HEAD, before the entity is in the level ([EntitySpawnEvent] is the one after). Cancelling
 * keeps the entity out of the level.
 */
class PreEntitySpawnEvent(val entity: Entity) : Event()
