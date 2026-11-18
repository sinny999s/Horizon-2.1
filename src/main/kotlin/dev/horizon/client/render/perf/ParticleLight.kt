package dev.horizon.client.render.perf

import dev.horizon.client.config.Storage

/**
 * Options > Horizon > Performance > Particle Light Cache. Minecraft looks up every particle's world light (the block
 * and sky light where it is) every frame, for every particle; in a scene full of campfire smoke that was about 7% of
 * the render thread. A particle's light only changes when it moves into another block or the world's light changes,
 * so each particle keeps its value until one of those happens (`mixin/perf/ParticleLightMixin`), and [epoch] moves on
 * whenever the client applies light changes, and every game tick (`mixin/perf/ClientLevelLightMixin`). Particles that
 * brighten as they age (flames) still do it every frame: only the world lookup underneath is kept.
 */
object ParticleLight {
    /** Moves on whenever the world's light may have changed; a particle's kept light is good for one epoch. Never 0. */
    @JvmField
    var epoch = 1

    /** Test hook override: null follows the option. */
    private var forced: Boolean? = null

    @JvmStatic
    fun enabled(): Boolean = forced ?: Storage.shared.particleLight

    @JvmStatic
    fun changed() {
        if (++epoch == 0) epoch = 1
    }

    /** Test hook (`verify true`): every kept light is checked against a fresh lookup; [report] prints the counts. */
    @JvmField
    var verify = false
    private var hits = 0L
    private var misses = 0L
    private var wrong = 0L

    @JvmStatic
    fun checked(kept: Int, fresh: Int) {
        hits++
        if (kept != fresh) wrong++
    }

    @JvmStatic
    fun missed() {
        misses++
    }

    /** Test hook: `call dev.horizon.client.render.perf.ParticleLight verify true|false` (turning it on clears the counts). */
    @JvmStatic
    fun verify(value: String) {
        verify = value.trim().toBoolean()
        if (verify) {
            hits = 0
            misses = 0
            wrong = 0
        }
    }

    /** Test hook: logs "PARTICLELIGHT kept=<n> looked-up=<n> wrong=<n>" for the time [verify] was on. */
    @JvmStatic
    fun report() {
        println("PARTICLELIGHT kept=$hits looked-up=$misses wrong=$wrong")
    }

    /** Test hook: `call dev.horizon.client.render.perf.ParticleLight set true|false|option`. */
    @JvmStatic
    fun set(value: String) {
        forced = when (value.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
