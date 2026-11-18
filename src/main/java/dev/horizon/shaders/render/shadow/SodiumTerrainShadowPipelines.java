package dev.horizon.shaders.render.shadow;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Builder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.horizon.shaders.graph.GraphShaderSources;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.graph.PackGraph;
import dev.horizon.shaders.runtime.ShaderResourceIds;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.Identifier;

public final class SodiumTerrainShadowPipelines {
    // Casters per vertex format and settings: the layer (0 solid, 1 cutout, 2 translucent into the colored shadow
    // map), the pack's materials that cast no shadow, and the colored maps' alpha curve.
    private static final Map<VertexFormat, Map<SodiumTerrainShadowPipelines.Key, RenderPipeline>> CASTERS = new IdentityHashMap<>();
    private static final BlendFunction NEAREST_AND_DARKEST = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MIN);

    private SodiumTerrainShadowPipelines() {
    }

    public static RenderPipeline caster(TerrainRenderPass pass, VertexFormat vertexFormat) {
        int mode = pass.isTranslucent() ? 2 : (pass.supportsFragmentDiscard() ? 1 : 0);
        PackGraph.TranslucentShadows colored = NativePackRuntime.translucentShadows();
        SodiumTerrainShadowPipelines.Key key = new SodiumTerrainShadowPipelines.Key(
            mode, NativePackRuntime.shadowSkipMaterials(), mode == 2 && colored != null ? colored.alphaPower() : 1.0F
        );
        return CASTERS.computeIfAbsent(vertexFormat, ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> build(key, vertexFormat));
    }

    public static void close() {
        CASTERS.values().forEach(pipelines -> pipelines.values().forEach(GraphShaderSources::remove));
        CASTERS.clear();
    }

    // Solid and cutout casters write depth only. Translucent ones write each caster's transmittance (rgb) and depth
    // (alpha) into the cascade's colored shadow map: MIN blending keeps the nearest caster's depth and the darkest color
    // where casters overlap, so that map needs no depth buffer.
    private static RenderPipeline build(SodiumTerrainShadowPipelines.Key key, VertexFormat vertexFormat) {
        String layer = key.mode == 2 ? "colored" : (key.mode == 1 ? "cutout" : "solid");
        String shader = "sodium_terrain_shadow";
        Builder builder = RenderPipeline.builder(new Snippet[0])
            .withBindGroupLayout(sodiumLayout())
            .withLocation(Identifier.fromNamespaceAndPath("sulkan_sodium", "pipeline/shadow/" + shader + "_" + layer + "_" + Integer.toHexString(key.hashCode())))
            .withVertexShader(ShaderResourceIds.coreShader(shader))
            .withFragmentShader(ShaderResourceIds.coreShader(shader))
            .withPushConstantSize(20)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, vertexFormat)
            .withCull(false);
        if (key.mode == 2) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.01F)
                .withShaderDefine("SULKAN_SHADOW_COLOR")
                .withShaderDefine("SULKAN_SHADOW_ALPHA_POWER", key.alphaPower)
                .withColorTargetState(new ColorTargetState(Optional.of(NEAREST_AND_DARKEST), DirectionalShadowRenderer.COLORED_FORMAT, ColorTargetState.WRITE_ALL));
        } else {
            builder.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0F, 0.0F));
            if (key.mode == 1) {
                builder.withShaderDefine("ALPHA_CUTOUT", 0.5F);
            }
        }

        if (vertexFormat.getElements().stream().anyMatch(element -> element.name().equals("a_SulkanMaterial"))) {
            builder.withShaderDefine("SULKAN_VEGETATION");
            for (int i = 0; i < key.skip.size(); i++) {
                builder.withShaderDefine("SULKAN_SHADOW_SKIP" + i, key.skip.get(i));
            }
        }

        return builder.build();
    }

    private static BindGroupLayout sodiumLayout() {
        return BindGroupLayout.builder()
            .withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
            .withUniform("SulkanWind", UniformType.UNIFORM_BUFFER)
            .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
            .build();
    }

    private record Key(int mode, List<Integer> skip, float alphaPower) {
    }
}
