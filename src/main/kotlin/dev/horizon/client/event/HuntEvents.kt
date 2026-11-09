package dev.horizon.client.event

import net.minecraft.world.phys.Vec3

/**
 * A base-hunting module announced something new at [pos]. [kind] is "base", "stash", "stasis",
 * "portal", "island" or "clue" (signs, item frames, entities). Elytra Autopilot can circle bases, stashes and stasis
 * chambers when it hears one.
 */
class FindEvent(val kind: String, val pos: Vec3) : Event()
