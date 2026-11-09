package dev.horizon.client.module.world

import dev.horizon.client.event.BossBarRenderEvent
import dev.horizon.client.event.DarknessEffectEvent
import dev.horizon.client.event.EntityAlphaEvent
import dev.horizon.client.event.GetFogBufferEvent
import dev.horizon.client.event.GetFogModifierEvent
import dev.horizon.client.event.HurtTiltEvent
import dev.horizon.client.event.ItemNameRenderEvent
import dev.horizon.client.event.MapRenderEvent
import dev.horizon.client.event.NauseaRenderEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.RenderArmorEvent
import dev.horizon.client.event.RenderEntityEvent
import dev.horizon.client.event.RenderFireOnEntityEvent
import dev.horizon.client.event.RenderFireOverlayEvent
import dev.horizon.client.event.RenderGuiBackgroundEvent
import dev.horizon.client.event.RenderInWallOverlayEvent
import dev.horizon.client.event.RenderNameTagEvent
import dev.horizon.client.event.RenderParticleEvent
import dev.horizon.client.event.RenderPortalOverlayEvent
import dev.horizon.client.event.RenderSpawnerEntityEvent
import dev.horizon.client.event.RenderUnderwaterOverlayEvent
import dev.horizon.client.event.RenderVignetteOverlayEvent
import dev.horizon.client.event.ScoreBoardRenderEvent
import dev.horizon.client.event.SignTextRenderEvent
import dev.horizon.client.event.StatusEffectsRenderEvent
import dev.horizon.client.event.WeatherRenderEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.particle.CampfireSmokeParticle
import net.minecraft.client.particle.ElderGuardianParticle
import net.minecraft.client.particle.ExplodeParticle
import net.minecraft.client.particle.FireworkParticles
import net.minecraft.client.particle.HugeExplosionParticle
import net.minecraft.client.particle.HugeExplosionSeedParticle
import net.minecraft.client.particle.TerrainParticle
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.entity.projectile.arrow.SpectralArrow
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle

/**
 * Every handler cancels one render event (posted by the `NoRender*Mixin` hooks) when its
 * setting is on.
 */
class NoRender : Module("no-render", Categories.WORLD) {
    val screen = group("screen", GroupMode.BOOLS)
    val hurtCam = bool("hurt-cam", true, parent = screen.value)
    val potions = bool("potions", false, parent = screen.value)
    val vignette = bool("vignette", true, parent = screen.value)
    val portal = bool("portal", true, parent = screen.value)
    val bossbar = bool("bossbar", false, parent = screen.value)
    val fireOverlay = bool("fire-overlay", false, parent = screen.value)
    val waterOverlay = bool("water-overlay", false, parent = screen.value)
    val blockOverlay = bool("block-overlay", false, parent = screen.value)
    val nausea = bool("nausea", false, parent = screen.value)
    val blindness = bool("blindness", false, parent = screen.value)
    val guiBackGround = bool("gui-back-ground", true, parent = screen.value)
    val noScoreBoard = bool("no-score-board", true, parent = screen.value)
    val hotbarItemName = bool("hotbar-item-name", false, parent = screen.value)
    val antiTitle = bool("anti-title", false, parent = screen.value)
    val entitiesSetting = group("entities", GroupMode.BOOLS)
    val xp = bool("xp", false, parent = entitiesSetting.value)
    val arrows = bool("arrows", false, parent = entitiesSetting.value)
    val eggs = bool("eggs", false, parent = entitiesSetting.value)
    val guardian = bool("guardian", false, parent = entitiesSetting.value)
    val armor = bool("armor", false, parent = entitiesSetting.value)
    val itemsSetting = bool("items", false, parent = entitiesSetting.value)
    val crystals = bool("crystals", false, parent = entitiesSetting.value)
    val fireworks = bool("fireworks", false, parent = entitiesSetting.value)
    val noArmorStands = bool("no-armor-stands", false, parent = entitiesSetting.value)
    val particles = group("particles", GroupMode.BOOLS)
    val explosions = bool("explosions", true, parent = particles.value)
    val campFire = bool("camp-fire", false, parent = particles.value)
    val breakParticles = bool("break-particles", true, parent = particles.value)
    val noWeather = bool("no-weather", false, parent = particles.value)
    val world = group("world", GroupMode.BOOLS)
    val maps = bool("maps", false, parent = world.value)
    val spawnerEntity = bool("spawner-entity", false, parent = world.value)
    val fireOnEntity = bool("fire-on-entity", true, parent = world.value)
    val signText = bool("sign-text", false, parent = world.value)
    val darkness = bool("darkness", false, parent = world.value)
    val fog = bool("fog", false, parent = world.value)
    val other = group("other", GroupMode.BOOLS)
    val antiPlayerCollision = bool("anti-player-collision", true, parent = other.value)

    init {
        on<ReceivePacketEvent> { e ->
            if ((e.packet is ClientboundSetTitleTextPacket || e.packet is ClientboundSetSubtitleTextPacket) && antiTitle.value) e.cancel()
        }
        // Every sign's text is hidden (on purpose): a crosshair check comparing the hit location (a Vec3) with the sign's
        // BlockPos would never match.
        on<SignTextRenderEvent> { e -> if (signText.value) e.cancel() }
        on<RenderArmorEvent> { e -> if (armor.value) e.cancel() }
        on<MapRenderEvent> { e -> if (maps.value) e.cancel() }
        on<WeatherRenderEvent> { e -> if (noWeather.value) e.cancel() }
        on<RenderGuiBackgroundEvent> { e -> if (guiBackGround.value) e.cancel() }
        on<DarknessEffectEvent> { e -> if (darkness.value) e.cancel() }
        on<RenderSpawnerEntityEvent> { e -> if (spawnerEntity.value) e.cancel() }
        on<NauseaRenderEvent> { e -> if (nausea.value) e.cancel() }
        on<StatusEffectsRenderEvent> { e -> if (potions.value) e.cancel() }
        on<RenderPortalOverlayEvent> { e -> if (portal.value) e.cancel() }
        on<RenderVignetteOverlayEvent> { e -> if (vignette.value) e.cancel() }
        on<ScoreBoardRenderEvent> { e -> if (noScoreBoard.value) e.cancel() }
        on<ItemNameRenderEvent> { e -> if (hotbarItemName.value) e.cancel() }
        on<RenderFireOnEntityEvent> { e -> if (fireOnEntity.value) e.cancel() }
        on<BossBarRenderEvent> { e -> if (bossbar.value) e.cancel() }
        on<RenderFireOverlayEvent> { e -> if (fireOverlay.value) e.cancel() }
        on<RenderUnderwaterOverlayEvent> { e -> if (waterOverlay.value) e.cancel() }
        on<RenderInWallOverlayEvent> { e -> if (blockOverlay.value) e.cancel() }
        on<GetFogModifierEvent> { e -> if (blindness.value) e.cancel() }
        on<HurtTiltEvent> { e ->
            if (!hurtCam.value) return@on
            val p = mc.player ?: return@on
            if (p.hurtTime - mc.deltaTracker.getGameTimeDeltaPartialTick(true) > 0f) e.cancel()
        }
        on<EntityAlphaEvent> { e ->
            if (!antiPlayerCollision.value) return@on
            val p = mc.player ?: return@on
            if (e.pos == p.position()) return@on
            e.alpha = Math.clamp((p.distanceToSqr(e.pos) / 3.0f).toFloat() + 0.2f, 0.0f, 1.0f)
            e.cancel()
        }
        on<RenderParticleEvent> { e -> if (hidden(e.particle)) e.cancel() }
        // RenderEntityEvent is posted at `EntityRenderDispatcher.shouldRender` HEAD (which only forwards to
        // `EntityRenderer.shouldRender`). [hidden] is false unless one of the entity settings below is on, so no gate is
        // needed.
        on<RenderEntityEvent> { e -> if (hidden(e.entity)) e.cancel() }
        on<RenderNameTagEvent> { e -> if (e.state is ArmorStandRenderState && noArmorStands.value) e.cancel() }
        on<GetFogBufferEvent> { e -> if (fog.value) e.cancel() }
    }

    /** The particle handler: the first matching setting cancels. */
    private fun hidden(p: net.minecraft.client.particle.Particle): Boolean = when {
        guardian.value && p is ElderGuardianParticle -> true
        explosions.value && (p is HugeExplosionParticle || p is HugeExplosionSeedParticle || p is ExplodeParticle) -> true
        campFire.value && p is CampfireSmokeParticle -> true
        breakParticles.value && p is TerrainParticle -> true
        fireworks.value && (p is FireworkParticles.Starter || p is FireworkParticles.OverlayParticle || p.javaClass.enclosingClass == FireworkParticles::class.java) -> true
        else -> false
    }

    /** Entities the entity settings hide. */
    private fun hidden(e: Entity): Boolean = when (e) {
        is EndCrystal -> crystals.value
        is ArmorStand -> noArmorStands.value
        is ItemEntity -> itemsSetting.value
        is ThrownEgg -> eggs.value
        is ThrownExperienceBottle, is ExperienceOrb -> xp.value
        is Arrow, is SpectralArrow -> arrows.value
        is FireworkRocketEntity -> fireworks.value
        else -> false
    }
}
