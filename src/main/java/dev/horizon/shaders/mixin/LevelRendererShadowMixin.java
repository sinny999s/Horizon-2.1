package dev.horizon.shaders.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import dev.horizon.shaders.render.shadow.DirectionalShadowPipelines;
import dev.horizon.shaders.render.shadow.DirectionalShadowRenderer;
import dev.horizon.shaders.render.shadow.DirectionalShadowSubmitFilter;
import dev.horizon.shaders.render.shadow.ShadowService;
import dev.horizon.shaders.render.shadow.SodiumShadowTerrainPasses;
import dev.horizon.shaders.render.shadow.SodiumShadowTerrainRenderer;
import dev.horizon.shaders.runtime.ShaderRuntime;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererShadowMixin {
    @Shadow
    private GpuSampler chunkLayerSampler;
    @Shadow
    @Final
    private LevelRenderState levelRenderState;
    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;
    @Shadow
    @Final
    private GameRenderer gameRenderer;
    @Shadow
    @Final
    private ModelManager modelManager;
    @Shadow
    @Final
    private AtlasManager atlasManager;
    @Unique
    private final SubmitNodeStorage sulkan$nearShadowSubmitStorage = new SubmitNodeStorage();
    @Unique
    private DirectionalShadowSubmitFilter sulkan$shadowSubmitFilter;
    @Unique
    private RenderBuffers sulkan$shadowRenderBuffers;
    @Unique
    private FeatureRenderDispatcher sulkan$shadowFeatureDispatcher;
    @Unique
    private boolean sulkan$hasNearShadowEntitySubmits;

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"
        )
    )
    private void sulkan$collectDirectionalShadowEntities(
        GraphicsResourceAllocator allocator,
        boolean renderBlockOutline,
        CameraRenderState cameraRenderState,
        GpuBufferSlice fogParameters,
        Vector4f fogColor,
        boolean renderOutline,
        boolean consistentDepthRequired,
        CallbackInfo ci
    ) {
        DeltaTracker deltaTracker = Minecraft.getInstance().getDeltaTracker();
        this.sulkan$nearShadowSubmitStorage.getSubmitsPerOrder().clear();
        this.sulkan$hasNearShadowEntitySubmits = false;
        if (!ShaderRuntime.resourceReloading() && ShadowService.entities()) {
            DirectionalShadowPipelines.ensureInitialized();
            DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
            this.sulkan$shadowSubmitFilter = new DirectionalShadowSubmitFilter(
                this.sulkan$nearShadowSubmitStorage, Math.max(24.0F, shadows.entityCascadeEnd(0))
            );
            boolean hadEntityOutlines = this.levelRenderState.shouldShowEntityOutlines;
            this.levelRenderState.shouldShowEntityOutlines = false;

            try {
                this.sulkan$submitNativeShadowEntities(deltaTracker, cameraRenderState);
                this.sulkan$hasNearShadowEntitySubmits = this.sulkan$hasShadowSubmits(this.sulkan$nearShadowSubmitStorage);
            } finally {
                this.levelRenderState.shouldShowEntityOutlines = hadEntityOutlines;
            }
        }
    }

    @Inject(method = "addMainPass", at = @At("HEAD"))
    private void sulkan$addDirectionalShadowPass(
        FrameGraphBuilder builder,
        PreparedFrame preparedFrame,
        GpuBufferSlice viewPositions,
        ChunkSectionsToRender chunkSectionsToRender,
        boolean consistentDepthRequired,
        CallbackInfo ci
    ) {
        if (!ShaderRuntime.resourceReloading() && ShadowService.enabled() && chunkSectionsToRender != null) {
            FramePass pass = builder.addPass("sulkan:directional_shadow_maps");
            pass.disableCulling();
            pass.executes(
                () -> {
                    dev.horizon.shaders.graph.GpuProfiler.mark(dev.horizon.shaders.graph.GpuProfiler.SHADOWS_START);
                    DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
                    shadows.prepare(this.levelRenderState);
                    SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
                    GpuSampler sampler = this.chunkLayerSampler != null
                        ? this.chunkLayerSampler
                        : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                    CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

                    try {
                        boolean terrain = sodiumRenderer != null && this.levelRenderState.cameraRenderState != null;

                        for (int cascade = 0; cascade < shadows.activeCascadeCount(); cascade++) {
                            if (cascade == 0) {
                                this.sulkan$renderNearCascade(shadows, terrain ? sodiumRenderer : null, encoder, sampler);
                            } else if (shadows.shouldUpdateCascade(cascade)) {
                                shadows.uploadCascade(encoder, cascade);
                                shadows.clearCascade(encoder, cascade);
                                DirectionalShadowRenderer.beginCascade(cascade);

                                try {
                                    if (terrain) {
                                        this.sulkan$renderSodiumTerrainShadow(
                                            sodiumRenderer,
                                            shadows,
                                            cascade,
                                            sampler,
                                            SodiumShadowTerrainPasses.terrainPasses(cascade, shadows.activeCascadeCount())
                                        );
                                    }
                                } finally {
                                    DirectionalShadowRenderer.endCascade();
                                }
                            }

                            this.sulkan$renderColoredShadow(shadows, terrain ? sodiumRenderer : null, encoder, sampler, cascade);
                        }

                        if (sodiumRenderer != null) {
                            ((SodiumWorldRendererAccessor)sodiumRenderer).sulkan$uniformBufferManager().prepareFrame();
                        }

                        if (ShadowService.enabled()) {
                            shadows.uploadShadowData(encoder);
                        }
                    } catch (RuntimeException failure) {
                        NativePackRuntime.failScene(failure);
                    } finally {
                        if (this.sulkan$shadowRenderBuffers != null) {
                            this.sulkan$shadowRenderBuffers.endFrame();
                        }

                        dev.horizon.shaders.graph.GpuProfiler.mark(dev.horizon.shaders.graph.GpuProfiler.SHADOWS_END);
                    }
                }
            );
        }
    }

    @Unique
    private void sulkan$renderNearCascade(DirectionalShadowRenderer shadows, SodiumWorldRenderer renderer, CommandEncoder encoder, GpuSampler sampler) {
        boolean staticUpdate = shadows.shouldUpdateCascade(0);
        boolean animated = renderer != null && shadows.animatedCasters();
        boolean entities = this.sulkan$hasNearShadowEntitySubmits && shadows.shouldUpdateEntityCascade(0);
        boolean composite = shadows.needsComposite(staticUpdate, animated || entities);
        if (staticUpdate || composite) {
            shadows.uploadCascade(encoder, 0);
        }

        if (staticUpdate) {
            shadows.clearStaticCache(encoder);
            DirectionalShadowRenderer.beginStaticCache(0);

            try {
                if (renderer != null) {
                    TerrainRenderPass[] passes = shadows.animatedCasters()
                        ? new TerrainRenderPass[]{SodiumShadowTerrainPasses.solid(0)}
                        : new TerrainRenderPass[]{SodiumShadowTerrainPasses.solid(0), SodiumShadowTerrainPasses.cutout(0)};
                    this.sulkan$renderSodiumTerrainShadow(renderer, shadows, 0, sampler, passes);
                }
            } finally {
                DirectionalShadowRenderer.endCascade();
            }

            shadows.markStaticCacheRendered();
        }

        if (composite) {
            shadows.copyStaticCache(encoder);
            DirectionalShadowRenderer.beginCascade(0);

            try {
                if (animated) {
                    this.sulkan$renderSodiumTerrainShadow(renderer, shadows, 0, sampler, new TerrainRenderPass[]{SodiumShadowTerrainPasses.cutout(0)});
                }

                if (entities) {
                    this.sulkan$prepareAndRenderShadowEntities(this.sulkan$nearShadowSubmitStorage);
                }
            } finally {
                DirectionalShadowRenderer.endCascade();
            }

            shadows.markComposited(animated || entities);
        }
    }

    // Translucent terrain into the cascade's colored shadow map, when the pack wants colored shadows.
    @Unique
    private void sulkan$renderColoredShadow(
        DirectionalShadowRenderer shadows, SodiumWorldRenderer renderer, CommandEncoder encoder, GpuSampler sampler, int cascade
    ) {
        if (shadows.shouldUpdateTranslucent(cascade)) {
            shadows.clearTranslucent(encoder, cascade);
            if (renderer != null) {
                shadows.uploadCascade(encoder, cascade);
                DirectionalShadowRenderer.beginTranslucent(cascade);

                try {
                    this.sulkan$renderSodiumTerrainShadow(
                        renderer, shadows, cascade, sampler, new TerrainRenderPass[]{SodiumShadowTerrainPasses.translucent(cascade)}
                    );
                } finally {
                    DirectionalShadowRenderer.endCascade();
                }
            }
        }
    }

    @Unique
    private void sulkan$renderSodiumTerrainShadow(
        SodiumWorldRenderer renderer, DirectionalShadowRenderer shadows, int cascade, GpuSampler sampler, TerrainRenderPass[] passes
    ) {
        Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
        if (cameraPos != null) {
            ((SodiumWorldRendererAccessor)renderer).sulkan$uniformBufferManager().prepareFrame();
            ChunkRenderMatrices matrices = new ChunkRenderMatrices(new Matrix4f(shadows.cascadeMatrix(cascade)), new Matrix4f());
            SodiumShadowTerrainRenderer.renderCascade(renderer, shadows, cascade, passes, matrices, cameraPos.x, cameraPos.y, cameraPos.z, sampler);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void sulkan$closeDirectionalShadowFeatureDispatcher(CallbackInfo ci) {
        if (this.sulkan$shadowFeatureDispatcher != null) {
            this.sulkan$shadowFeatureDispatcher.close();
            this.sulkan$shadowFeatureDispatcher = null;
        }

        if (this.sulkan$shadowRenderBuffers != null) {
            this.sulkan$shadowRenderBuffers.close();
            this.sulkan$shadowRenderBuffers = null;
        }
    }

    @Unique
    private void sulkan$renderShadowEntityFrame(PreparedFrame frame) {
        RenderTarget target = DirectionalShadowRenderer.get().activeTarget();
        RenderPass pass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                RenderPassDescriptor.builder(() -> "Sulkan shadow entities").withDepthAttachment(target.getDepthTextureView(), OptionalDouble.empty()).build()
            );

        try {
            frame.executeSolid(pass);
            frame.executeTranslucent(pass);
            frame.executeTranslucentAfterTerrain(pass);
            frame.executeAlwaysOnTop(pass);
        } catch (Throwable var7) {
            if (pass != null) {
                try {
                    pass.close();
                } catch (Throwable var6) {
                    var7.addSuppressed(var6);
                }
            }

            throw var7;
        }

        if (pass != null) {
            pass.close();
        }
    }

    @Unique
    private void sulkan$prepareAndRenderShadowEntities(SubmitNodeStorage storage) {
        PreparedFrame frame = this.sulkan$shadowFeatureDispatcher().prepareFrame(storage);

        try {
            this.sulkan$renderShadowEntityFrame(frame);
        } finally {
            frame.close();
        }
    }

    @Unique
    private FeatureRenderDispatcher sulkan$shadowFeatureDispatcher() {
        if (this.sulkan$shadowFeatureDispatcher == null) {
            this.sulkan$shadowRenderBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
            this.sulkan$shadowFeatureDispatcher = new FeatureRenderDispatcher(
                this.sulkan$shadowRenderBuffers, this.modelManager, this.atlasManager, Minecraft.getInstance().font, this.gameRenderer.gameRenderState()
            );
        }

        return this.sulkan$shadowFeatureDispatcher;
    }

    @Unique
    private void sulkan$submitNativeShadowEntities(DeltaTracker deltaTracker, CameraRenderState cameraState) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && cameraState != null && cameraState.pos != null) {
            double distance = Math.max(24.0F, DirectionalShadowRenderer.get().entityShadowDistance()) + 24.0;
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

            for (Entity entity : level.entitiesForRendering()) {
                double limit = distance + Math.max(entity.getBbWidth(), entity.getBbHeight()) * 4.0;
                if (!entity.isRemoved()
                    && !entity.isSpectator()
                    && !entity.isInvisible()
                    && !(entity.position().distanceToSqr(cameraState.pos) > limit * limit)) {
                    EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, partialTick);
                    this.entityRenderDispatcher
                        .submit(
                            state,
                            cameraState,
                            state.x - cameraState.pos.x,
                            state.y - cameraState.pos.y,
                            state.z - cameraState.pos.z,
                            new PoseStack(),
                            this.sulkan$shadowSubmitFilter
                        );
                }
            }
        }
    }

    @Unique
    private boolean sulkan$hasShadowSubmits(SubmitNodeStorage storage) {
        ObjectIterator var2 = storage.getSubmitsPerOrder().values().iterator();

        while (var2.hasNext()) {
            SubmitNodeCollection submitNodeCollection = (SubmitNodeCollection)var2.next();

            for (FeatureRenderPhase<?> phase : submitNodeCollection.allPhases()) {
                if (!phase.isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }
}
