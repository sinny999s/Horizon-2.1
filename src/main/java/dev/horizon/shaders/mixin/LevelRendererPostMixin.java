package dev.horizon.shaders.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererPostMixin {
    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("HEAD"))
    private void sulkan$prepareVolumetricCloudFrame(
        GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline,
        CameraRenderState cameraState,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        boolean consistentDepthRequired,
        CallbackInfo ci
    ) {
        if (!ShaderRuntime.resourceReloading()) {
            NativePackRuntime.beginScene(
                cameraState,
                cameraState.viewRotationMatrix,
                this.levelRenderState,
                Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)
            );
        }
    }

    // The pack's sky program (if any) fills the pixels the opaque world left uncovered, before translucents draw.
    @Inject(method = "executeSolid", at = @At("RETURN"))
    private void sulkan$drawPackSky(ChunkSectionsToRender chunks, FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, CallbackInfo ci) {
        NativePackRuntime.drawSky(pass);
    }
}
