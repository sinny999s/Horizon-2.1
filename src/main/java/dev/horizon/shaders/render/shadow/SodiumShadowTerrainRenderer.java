package dev.horizon.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.horizon.shaders.mixin.sodium.RenderSectionManagerAccessor;
import dev.horizon.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Map.Entry;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Vector3f;

public final class SodiumShadowTerrainRenderer {
    private static final int CASCADE_COUNT = 4;
    private static final double[] PLAN_MOVEMENT_LIMITS = new double[]{4.0, 8.0, 16.0, 32.0};
    private static final float PLAN_DIRECTION_DOT = 0.9925F;
    private static final SodiumShadowTerrainRenderer.CascadePlan[] CACHED_PLANS = new SodiumShadowTerrainRenderer.CascadePlan[4];
    private static long terrainRevision;
    private static TerrainRenderPass[] preparingPasses;
    private static TerrainRenderPass[] drawingPasses;

    public static TerrainRenderPass[] preparingPasses() {
        return preparingPasses;
    }

    public static int shadowPassIndex(TerrainRenderPass pass) {
        if (drawingPasses != null) {
            for (int i = 0; i < drawingPasses.length; i++) {
                if (drawingPasses[i] == pass) {
                    return i;
                }
            }
        }

        return -1;
    }

    private SodiumShadowTerrainRenderer() {
    }

    public static void renderCascade(
        SodiumWorldRenderer renderer,
        DirectionalShadowRenderer shadows,
        int cascade,
        TerrainRenderPass[] passes,
        ChunkRenderMatrices matrices,
        double cameraX,
        double cameraY,
        double cameraZ,
        GpuSampler sampler
    ) {
        SodiumWorldRendererAccessor rendererAccess = (SodiumWorldRendererAccessor)renderer;
        RenderSectionManager sectionManager = rendererAccess.sulkan$renderSectionManager();
        if (sectionManager != null) {
            Iterable<RenderRegion> regions = ((RenderSectionManagerAccessor)sectionManager).sulkan$regions().getLoadedRegions();
            SodiumShadowTerrainRenderer.CascadePlan plan = planFor(regions, shadows, cascade, cameraX, cameraY, cameraZ);
            if (!plan.lists.isEmpty()) {
                UniformBufferManager uniforms = rendererAccess.sulkan$uniformBufferManager();
                uniforms.update(matrices, FogParameters.NONE, new CameraTransform(cameraX, cameraY, cameraZ));
                boolean blockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
                SodiumClientMod.options().performance.useBlockFaceCulling = false;

                try {
                    if (cascade == 0) {
                        for (TerrainRenderPass pass : passes) {
                            renderPass(
                                sectionManager.getChunkRenderer(),
                                matrices,
                                plan.renderLists,
                                pass,
                                cameraX,
                                cameraY,
                                cameraZ,
                                sampler,
                                uniforms.getUniformBuffer(),
                                uniforms.getSectionTimeInfo()
                            );
                        }
                    } else {
                        renderPasses(
                            sectionManager.getChunkRenderer(),
                            matrices,
                            plan.renderLists,
                            passes,
                            cameraX,
                            cameraY,
                            cameraZ,
                            sampler,
                            uniforms.getUniformBuffer(),
                            uniforms.getSectionTimeInfo()
                        );
                    }
                } finally {
                    SodiumClientMod.options().performance.useBlockFaceCulling = blockFaceCulling;
                }
            }
        }
    }

    public static void markTerrainDirty() {
        terrainRevision++;
        DirectionalShadowRenderer.markAllTerrainDirty();
    }

    public static void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        terrainRevision++;
        DirectionalShadowRenderer.markSectionDirty(sectionX, sectionY, sectionZ);
    }

    public static String debugSummary() {
        StringBuilder summary = new StringBuilder();

        for (int cascade = 0; cascade < CACHED_PLANS.length; cascade++) {
            SodiumShadowTerrainRenderer.CascadePlan plan = CACHED_PLANS[cascade];
            int sections = 0;
            if (plan != null) {
                for (ChunkRenderList list : plan.lists) {
                    sections += list.getSectionsWithGeometryCount();
                }
            }

            summary.append(cascade == 0 ? "" : " ").append(cascade).append(':').append(plan == null ? "none" : plan.lists.size() + "r/" + sections + "s");
        }

        return summary.toString();
    }

    public static long terrainRevision() {
        return terrainRevision;
    }

    public static void close() {
        for (int cascade = 0; cascade < CACHED_PLANS.length; cascade++) {
            clearPlanBatches(CACHED_PLANS[cascade], cascade);
            CACHED_PLANS[cascade] = null;
        }

        terrainRevision++;
        DirectionalShadowRenderer.markAllTerrainDirty();
    }

    private static SodiumShadowTerrainRenderer.CascadePlan planFor(
        Iterable<RenderRegion> regions, DirectionalShadowRenderer shadows, int cascade, double cameraX, double cameraY, double cameraZ
    ) {
        SodiumShadowTerrainRenderer.CascadePlan cached = CACHED_PLANS[cascade];
        long layoutVersion = shadows.cascadeLayoutVersion(cascade);
        Vector3f lightDirection = shadows.lightDirection(new Vector3f());
        Vector3f cameraForward = shadows.cameraForward(new Vector3f());
        if (cached != null
            && cached.layoutVersion == layoutVersion
            && cached.matches(
                terrainRevision, cameraX, cameraY, cameraZ, lightDirection, cameraForward, shadows.cascadeEnd(cascade), PLAN_MOVEMENT_LIMITS[cascade]
            )) {
            return cached;
        }

        boolean spatiallyReusable = cached != null
            && cached.layoutVersion == layoutVersion
            && cached.spatiallyMatches(cameraX, cameraY, cameraZ, lightDirection, cameraForward, shadows.cascadeEnd(cascade), PLAN_MOVEMENT_LIMITS[cascade]);
        SodiumShadowTerrainRenderer.PlanContents contents = buildRenderLists(regions, shadows, cascade, cached, spatiallyReusable);
        SodiumShadowTerrainRenderer.CascadePlan next = new SodiumShadowTerrainRenderer.CascadePlan(
            contents.lists,
            new SodiumShadowTerrainRenderer.ShadowRenderLists(contents.lists),
            contents.regions,
            layoutVersion,
            terrainRevision,
            cameraX,
            cameraY,
            cameraZ,
            lightDirection,
            cameraForward,
            shadows.cascadeEnd(cascade)
        );
        clearChangedPlanBatches(cached, next, cascade);
        CACHED_PLANS[cascade] = next;
        return next;
    }

    static void renderPass(
        ChunkRenderer renderer,
        ChunkRenderMatrices matrices,
        ChunkRenderListIterable renderLists,
        TerrainRenderPass pass,
        double cameraX,
        double cameraY,
        double cameraZ,
        GpuSampler sampler,
        GpuBufferSlice uniformBuffer,
        GpuBuffer sectionTimeInfo
    ) {
        renderPasses(renderer, matrices, renderLists, new TerrainRenderPass[]{pass}, cameraX, cameraY, cameraZ, sampler, uniformBuffer, sectionTimeInfo);
    }

    private static void renderPasses(
        ChunkRenderer renderer,
        ChunkRenderMatrices matrices,
        ChunkRenderListIterable renderLists,
        TerrainRenderPass[] passes,
        double cameraX,
        double cameraY,
        double cameraZ,
        GpuSampler sampler,
        GpuBufferSlice uniformBuffer,
        GpuBuffer sectionTimeInfo
    ) {
        boolean[] flags = ((ShadowChunkState)renderer).sulkan$drawFlags();
        boolean[] savedFlags = (boolean[])flags.clone();

        try {
            preparingPasses = passes;
            drawingPasses = passes;
            // Sodium keeps sorted translucent geometry behind each region's own index buffer: draw it indexed.
            boolean indexed = passes.length > 0 && passes[0].isTranslucent();
            renderer.prepare(renderLists, new CameraTransform(cameraX, cameraY, cameraZ), indexed);
            preparingPasses = null;
            RenderTarget target = DirectionalShadowRenderer.get().activeTarget();
            RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Sulkan shadow terrain");
            if (target.getDepthTextureView() != null) {
                descriptor.withDepthAttachment(target.getDepthTextureView(), OptionalDouble.empty());
            }

            if (target.getColorTextureView() != null) {
                descriptor.withColorAttachment(target.getColorTextureView(), Optional.empty());
            }

            RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build());

            try {
                for (TerrainRenderPass pass : passes) {
                    renderer.render(
                        matrices,
                        renderLists,
                        pass,
                        new CameraTransform(cameraX, cameraY, cameraZ),
                        FogParameters.NONE,
                        indexed,
                        renderPass,
                        sampler,
                        uniformBuffer,
                        sectionTimeInfo,
                        null
                    );
                }
            } catch (Throwable var26) {
                if (renderPass != null) {
                    try {
                        renderPass.close();
                    } catch (Throwable var25) {
                        var26.addSuppressed(var25);
                    }
                }

                throw var26;
            }

            if (renderPass != null) {
                renderPass.close();
            }
        } finally {
            preparingPasses = null;
            drawingPasses = null;
            System.arraycopy(savedFlags, 0, flags, 0, flags.length);
        }
    }

    private static SodiumShadowTerrainRenderer.PlanContents buildRenderLists(
        Iterable<RenderRegion> regions,
        DirectionalShadowRenderer shadows,
        int cascade,
        SodiumShadowTerrainRenderer.CascadePlan previous,
        boolean spatiallyReusable
    ) {
        List<ChunkRenderList> result = new ArrayList<>();
        Map<RenderRegion, SodiumShadowTerrainRenderer.RegionPlan> nextRegions = new IdentityHashMap<>();

        for (RenderRegion region : regions) {
            long revision = ((ShadowRegionState)region).sulkan$shadowRevision();
            SodiumShadowTerrainRenderer.RegionPlan priorRegion = previous == null ? null : previous.regions.get(region);
            SodiumShadowTerrainRenderer.RegionPlan nextRegion;
            if (spatiallyReusable && priorRegion != null && priorRegion.revision == revision) {
                nextRegion = priorRegion;
            } else {
                nextRegion = buildRegionPlan(region, shadows, cascade, revision);
            }

            nextRegions.put(region, nextRegion);
            if (!nextRegion.sections.isEmpty()) {
                result.add(nextRegion.list);
            }
        }

        return new SodiumShadowTerrainRenderer.PlanContents(List.copyOf(result), nextRegions);
    }

    private static SodiumShadowTerrainRenderer.RegionPlan buildRegionPlan(RenderRegion region, DirectionalShadowRenderer shadows, int cascade, long revision) {
        ChunkRenderList list = new ChunkRenderList(region);
        BitSet sections = new BitSet(256);
        list.reset(0);

        for (int index = 0; index < 256; index++) {
            int flags = region.getSectionFlags(index);
            if ((flags & 1) != 0) {
                int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(index);
                int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(index);
                int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
                if (shadows.sectionIntersectsCascade(cascade, sectionX << 4, sectionY << 4, sectionZ << 4)) {
                    list.add(index);
                    sections.set(index);
                }
            }
        }

        return new SodiumShadowTerrainRenderer.RegionPlan(list, sections, revision);
    }

    private static void clearChangedPlanBatches(SodiumShadowTerrainRenderer.CascadePlan previous, SodiumShadowTerrainRenderer.CascadePlan next, int cascade) {
        if (previous != null) {
            for (Entry<RenderRegion, SodiumShadowTerrainRenderer.RegionPlan> entry : previous.regions.entrySet()) {
                SodiumShadowTerrainRenderer.RegionPlan replacement = next.regions.get(entry.getKey());
                SodiumShadowTerrainRenderer.RegionPlan prior = entry.getValue();
                if (replacement == null || prior.revision != replacement.revision || !prior.sections.equals(replacement.sections)) {
                    clearRegionBatches(entry.getKey(), cascade);
                }
            }

            for (Entry<RenderRegion, SodiumShadowTerrainRenderer.RegionPlan> entry : next.regions.entrySet()) {
                if (!previous.regions.containsKey(entry.getKey()) && !entry.getValue().sections.isEmpty()) {
                    clearRegionBatches(entry.getKey(), cascade);
                }
            }
        }
    }

    private static void clearRegionBatches(RenderRegion region, int cascade) {
        region.clearCachedBatchFor(SodiumShadowTerrainPasses.solid(cascade));
        region.clearCachedBatchFor(SodiumShadowTerrainPasses.cutout(cascade));
        region.clearCachedBatchFor(SodiumShadowTerrainPasses.combined(cascade));
        region.clearCachedBatchFor(SodiumShadowTerrainPasses.translucent(cascade));
    }

    private static void clearPlanBatches(SodiumShadowTerrainRenderer.CascadePlan plan, int cascade) {
        if (plan != null) {
            for (ChunkRenderList list : plan.lists) {
                RenderRegion region = list.getRegion();
                clearRegionBatches(region, cascade);
            }
        }
    }

    private record CascadePlan(
        List<ChunkRenderList> lists,
        SodiumShadowTerrainRenderer.ShadowRenderLists renderLists,
        Map<RenderRegion, SodiumShadowTerrainRenderer.RegionPlan> regions,
        long layoutVersion,
        long revision,
        double cameraX,
        double cameraY,
        double cameraZ,
        Vector3f lightDirection,
        Vector3f cameraForward,
        float cascadeEnd
    ) {
        private boolean matches(
            long currentRevision,
            double currentCameraX,
            double currentCameraY,
            double currentCameraZ,
            Vector3f currentLightDirection,
            Vector3f currentCameraForward,
            float currentCascadeEnd,
            double movementLimit
        ) {
            return this.revision == currentRevision
                && this.spatiallyMatches(
                    currentCameraX, currentCameraY, currentCameraZ, currentLightDirection, currentCameraForward, currentCascadeEnd, movementLimit
                );
        }

        private boolean spatiallyMatches(
            double currentCameraX,
            double currentCameraY,
            double currentCameraZ,
            Vector3f currentLightDirection,
            Vector3f currentCameraForward,
            float currentCascadeEnd,
            double movementLimit
        ) {
            double dx = currentCameraX - this.cameraX;
            double dy = currentCameraY - this.cameraY;
            double dz = currentCameraZ - this.cameraZ;
            return dx * dx + dy * dy + dz * dz <= movementLimit * movementLimit
                && this.lightDirection.dot(currentLightDirection) >= 0.9925F
                && this.cameraForward.dot(currentCameraForward) >= 0.9925F
                && Math.abs(this.cascadeEnd - currentCascadeEnd) < 0.5F;
        }
    }

    private record PlanContents(List<ChunkRenderList> lists, Map<RenderRegion, SodiumShadowTerrainRenderer.RegionPlan> regions) {
    }

    private record RegionPlan(ChunkRenderList list, BitSet sections, long revision) {
    }

    private record ShadowRenderLists(List<ChunkRenderList> lists) implements ChunkRenderListIterable {
        public Iterator<ChunkRenderList> iterator(boolean reverse) {
            if (!reverse) {
                return this.lists.iterator();
            }

            final ListIterator<ChunkRenderList> iterator = this.lists.listIterator(this.lists.size());
            return new Iterator<ChunkRenderList>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasPrevious();
                }

                public ChunkRenderList next() {
                    return iterator.previous();
                }
            };
        }
    }
}
