package dev.horizon.shaders.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.Builder;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.horizon.shaders.config.ShaderQualityPreset;
import dev.horizon.shaders.graph.NativePackRuntime;

public final class ShadowService {
    public static final int SAMPLER_COUNT = 6;

    private ShadowService() {
    }

    public static boolean enabled() {
        return NativePackRuntime.shadowQuality() > 0 && NativePackRuntime.shadowFrameReady();
    }

    public static boolean entities() {
        return enabled();
    }

    public static ShaderQualityPreset quality() {
        return NativePackRuntime.shadowQuality() > 0 ? ShaderQualityPreset.values()[NativePackRuntime.shadowQuality()] : ShaderQualityPreset.OFF;
    }

    public static float distance() {
        return NativePackRuntime.shadowQuality() > 0 ? NativePackRuntime.shadowDistance() : 384.0F;
    }

    public static long memoryBytes(int quality, boolean translucent) {
        if (quality == 0) {
            return 0L;
        }

        long colored = 0L;
        if (translucent) {
            for (int size : DirectionalShadowRenderer.targetSizes(ShaderQualityPreset.values()[quality], Integer.MAX_VALUE)) {
                int colorSize = DirectionalShadowRenderer.translucentSize(size);
                colored += (long)colorSize * colorSize * 8L;
            }
        }

        long bytes = 560L;
        ShaderQualityPreset preset = ShaderQualityPreset.values()[quality];
        int[] sizes = DirectionalShadowRenderer.targetSizes(preset, Integer.MAX_VALUE);

        for (int size : sizes) {
            bytes += (long)size * size * 4L;
        }

        return bytes + (long)sizes[0] * sizes[0] * 4L + 4L + colored;
    }

    public static void layout(Builder layout, boolean translucent) {
        layout.withUniform("SulkanShadowData", UniformType.UNIFORM_BUFFER);

        for (int i = 0; i < 4; i++) {
            layout.withUniform("SulkanShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
        }

        for (int i = 0; i < 2; i++) {
            layout.withUniform("SulkanEntityShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
        }

        if (translucent) {
            for (int i = 0; i < 4; i++) {
                layout.withUniform("SulkanShadowColor" + i, UniformType.COMBINED_IMAGE_SAMPLER);
            }
        }
    }

    public static void bindTerrain(RenderPass pass, boolean translucent) {
        DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
        if (!shadows.resourcesReady()) {
            throw new IllegalStateException("Shadow producer did not run before native terrain");
        }

        if (shadows.shadowDataSlice().buffer().isClosed()) {
            throw new IllegalStateException("Shadow uniforms expired before their receiver pass");
        }

        pass.setUniform("SulkanShadowData", shadows.shadowDataSlice());
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        for (int i = 0; i < 4; i++) {
            pass.setUniform("SulkanShadowMap" + i, shadows.target(i).getDepthTextureView(), sampler);
        }

        for (int i = 0; i < 2; i++) {
            pass.setUniform("SulkanEntityShadowMap" + i, shadows.entityTarget(i).getDepthTextureView(), sampler);
        }

        if (translucent) {
            if (!shadows.translucentReady()) {
                throw new IllegalStateException("Colored shadow maps were not prepared before their receiver pass");
            }

            for (int i = 0; i < 4; i++) {
                pass.setUniform("SulkanShadowColor" + i, shadows.translucentTarget(i).getColorTextureView(), sampler);
            }
        }
    }
}
