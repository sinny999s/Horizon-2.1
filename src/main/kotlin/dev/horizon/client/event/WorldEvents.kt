package dev.horizon.client.event

// Events added for the world-area modules (auto-bone-meal … zoom). Each section is owned by one module.

// ---- nuker ----

// ---- server-helper / pearl-chaser / auto-sign ----

/** `AbstractContainerScreen.extractSlot` HEAD: [slot] is about to be drawn into [graphics]. */
class SlotRenderEvent(val slot: net.minecraft.world.inventory.Slot, val graphics: net.minecraft.client.gui.GuiGraphicsExtractor) : Event()

// ---- no-render ----
// Render events that only no-render listens to. Each is posted by a mixin in dev.horizon.client.mixin.world
// (NoRender*Mixin); cancelling skips the vanilla drawing.

/** `AbstractSignRenderer.submitSignText` HEAD: a sign's text at [pos]. */
class SignTextRenderEvent(val pos: net.minecraft.core.BlockPos) : Event()

/** `HumanoidArmorLayer.renderArmorPiece` HEAD. */
class RenderArmorEvent : Event()

/** `MapRenderer.render` HEAD, only for maps in item frames (`active`). */
class MapRenderEvent : Event()

/** The weather pass: rain/snow and the world border (26.2's `addWeatherPass` drew both). */
class WeatherRenderEvent : Event()

/** `Screen.extractTransparentBackground` HEAD (the dimmed in-game background behind a screen). */
class RenderGuiBackgroundEvent : Event()

/** `LightmapRenderStateExtractor.calculateDarknessScale` HEAD; cancelling makes it 0. */
class DarknessEffectEvent : Event()

/** `SpawnerRenderer.submit` HEAD (the spinning mob inside a spawner). */
class RenderSpawnerEntityEvent : Event()

/** `Hud.extractConfusionOverlay` HEAD. */
class NauseaRenderEvent : Event()

/** `Hud.extractEffects` HEAD, posted only with effects active and no screen open. */
class StatusEffectsRenderEvent : Event()

/** `Hud.extractPortalOverlay` HEAD. */
class RenderPortalOverlayEvent : Event()

/** `Hud.extractVignette` HEAD. */
class RenderVignetteOverlayEvent : Event()

/** `Hud.displayScoreboardSidebar` HEAD. */
class ScoreBoardRenderEvent : Event()

/** `Hud.extractSelectedItemName` HEAD (the held item's name above the hotbar). */
class ItemNameRenderEvent : Event()

/** `EntityRenderDispatcher.submit` HEAD; cancelling clears the state's `displayFireAnimation`. */
class RenderFireOnEntityEvent : Event()

/** `BossHealthOverlay.extractRenderState` HEAD, posted only while a boss bar exists. */
class BossBarRenderEvent : Event()

/** `GameRenderer.bobHurt` HEAD (the camera tilt when hurt). */
class HurtTiltEvent : Event()

/**
 * Another player's model about to be submitted (`LivingEntityRenderer.submit` → `submitModel`), at its render position
 * [pos]; cancelling draws it white with [alpha] instead of vanilla's colour.
 */
class EntityAlphaEvent(val pos: net.minecraft.world.phys.Vec3) : Event() {
    var alpha = 1.0f
}

/** `ParticleEngine.add` HEAD; cancelling drops the particle. */
class RenderParticleEvent(val particle: net.minecraft.client.particle.Particle) : Event()

/** `FogRenderer.getBuffer` reading `fogEnabled`; cancelling makes it read false (no fog). */
class GetFogBufferEvent : Event()

/** `ScreenEffectRenderer.submitFire` HEAD (first-person burning flames overlay). */
class RenderFireOverlayEvent : Event()

/** `ScreenEffectRenderer.submitWater` HEAD (first-person underwater overlay). */
class RenderUnderwaterOverlayEvent : Event()

/** `ScreenEffectRenderer.submitBlockSprite` HEAD (first-person in-wall suffocation block overlay). */
class RenderInWallOverlayEvent : Event()

/** `MobEffectFogEnvironment.isApplicable` HEAD for blindness (and sky-blocking check). */
class GetFogModifierEvent : Event()

// ---- free-cam / zoom ----

/** `Camera.update` RETURN: cancelling marks the camera detached, so the player is drawn. */
class SetupTerrainEvent : Event()

/**
 * `Camera.extractRenderState` RETURN: cancelling rebuilds the level projection
 * from the values below. They keep their last value between frames ([fov] is set to the
 * camera's FOV before every post). Same definition as the cosmetic1 copy's (aspect uses it): keep one when merging.
 */
class BasicProjectionMatrixEvent : Event() {
    companion object {
        var ratio = 1f
        var zoom = 1f
        var zoomX = 1f
        var zoomY = 1f
        var fov = 1f
    }
}
