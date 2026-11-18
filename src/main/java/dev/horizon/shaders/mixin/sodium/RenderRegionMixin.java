package dev.horizon.shaders.mixin.sodium;

import dev.horizon.shaders.render.shadow.ShadowRegionState;
import dev.horizon.shaders.render.shadow.SodiumShadowTerrainPasses;
import dev.horizon.shaders.render.shadow.SodiumShadowTerrainRenderer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderRegion.class, remap = false)
public abstract class RenderRegionMixin implements ShadowRegionState {
    @Unique
    private final Map<TerrainRenderPass, MultiDrawBatch> sulkan$shadowBatches = new IdentityHashMap<>();
    @Unique
    private long sulkan$shadowRevision;

    @Inject(method = "getStorage", at = @At("HEAD"), cancellable = true)
    private void sulkan$shareSourceMeshStorage(TerrainRenderPass pass, CallbackInfoReturnable<SectionRenderDataStorage> cir) {
        if (SodiumShadowTerrainPasses.isCombined(pass)) {
            RenderRegion region = (RenderRegion)(Object)this;
            SectionRenderDataStorage solid = region.getStorage(DefaultTerrainRenderPasses.SOLID);
            cir.setReturnValue(solid != null ? solid : region.getStorage(DefaultTerrainRenderPasses.CUTOUT));
        } else {
            TerrainRenderPass source = SodiumShadowTerrainPasses.source(pass);
            if (source != pass) {
                cir.setReturnValue(((RenderRegion)(Object)this).getStorage(source));
            }
        }
    }

    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
    private void sulkan$useDedicatedShadowBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
        if (SodiumShadowTerrainPasses.source(pass) != pass) {
            cir.setReturnValue(
                this.sulkan$shadowBatches
                    .computeIfAbsent(
                        pass, ignored -> MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * 256 * (SodiumShadowTerrainPasses.isCombined(pass) ? 2 : 1) + 1)
                    )
            );
        }
    }

    @Inject(method = "clearCachedBatchFor", at = @At("TAIL"))
    private void sulkan$invalidateShadowBatches(TerrainRenderPass pass, CallbackInfo ci) {
        MultiDrawBatch directBatch = this.sulkan$shadowBatches.get(pass);
        if (directBatch != null) {
            directBatch.clear();
        }

        for (TerrainRenderPass shadowPass : SodiumShadowTerrainPasses.passesFor(pass)) {
            MultiDrawBatch shadowBatch = this.sulkan$shadowBatches.get(shadowPass);
            if (shadowBatch != null) {
                shadowBatch.clear();
            }
        }
    }

    @Inject(method = "setSectionRenderState", at = @At("TAIL"))
    private void sulkan$markShadowPlansDirty(int sectionIndex, BuiltSectionInfo info, CallbackInfo ci) {
        this.sulkan$clearShadowBatches();
        this.sulkan$shadowRevision++;
        this.sulkan$markSectionDirty(sectionIndex);
    }

    @Inject(method = "clearSectionRenderState", at = @At("TAIL"))
    private void sulkan$markClearedShadowPlansDirty(int sectionIndex, CallbackInfo ci) {
        this.sulkan$clearShadowBatches();
        this.sulkan$shadowRevision++;
        this.sulkan$markSectionDirty(sectionIndex);
    }

    @Unique
    private void sulkan$markSectionDirty(int sectionIndex) {
        RenderRegion region = (RenderRegion)(Object)this;
        SodiumShadowTerrainRenderer.markSectionDirty(
            region.getChunkX() + LocalSectionIndex.unpackX(sectionIndex),
            region.getChunkY() + LocalSectionIndex.unpackY(sectionIndex),
            region.getChunkZ() + LocalSectionIndex.unpackZ(sectionIndex)
        );
    }

    @Inject(method = {"onGeometrySegmentChange", "onIndexSegmentChange"}, at = @At("HEAD"))
    private void sulkan$clearResizedShadowBatches(CallbackInfo ci) {
        this.sulkan$clearShadowBatches();
    }

    // When the region's geometry buffer grows (onGeometryBufferChange) every section's data moves and Sodium drops its
    // own cached draw commands here; the shadow passes' dedicated ones (getCachedBatch above) must go with them, or the
    // shadow cascades keep drawing from the old offsets and blocks vanish from them (seen after a full chunk rebuild,
    // e.g. a pack or option change, mostly in the near cascades).
    @Inject(method = "clearAllCachedBatches", at = @At("TAIL"))
    private void sulkan$clearAllShadowBatches(CallbackInfo ci) {
        this.sulkan$clearShadowBatches();
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void sulkan$deleteShadowBatches(CallbackInfo ci) {
        SodiumShadowTerrainRenderer.markTerrainDirty();

        for (MultiDrawBatch batch : this.sulkan$shadowBatches.values()) {
            batch.delete();
        }

        this.sulkan$shadowBatches.clear();
    }

    @Unique
    private void sulkan$clearShadowBatches() {
        for (MultiDrawBatch batch : this.sulkan$shadowBatches.values()) {
            batch.clear();
        }
    }

    @Override
    public long sulkan$shadowRevision() {
        return this.sulkan$shadowRevision;
    }
}
