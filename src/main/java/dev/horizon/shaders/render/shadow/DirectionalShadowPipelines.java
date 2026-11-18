package dev.horizon.shaders.render.shadow;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Builder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import dev.horizon.shaders.graph.GraphShaderSources;
import dev.horizon.shaders.mixin.RenderPipelineBuilderAccessor;
import dev.horizon.shaders.mixin.RenderPipelinesAccessor;
import dev.horizon.shaders.runtime.ShaderResourceIds;
import java.util.Arrays;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

public final class DirectionalShadowPipelines {
    public static RenderPipeline ENTITY_DEPTH_SOLID;
    public static RenderPipeline ENTITY_DEPTH_CUTOUT;
    public static RenderPipeline ENTITY_DEPTH_TRANSLUCENT;

    private DirectionalShadowPipelines() {
    }

    public static void ensureInitialized() {
        if (ENTITY_DEPTH_SOLID == null) {
            close();
            DepthStencilState depthWrite = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0F, 0.0F);
            ENTITY_DEPTH_SOLID = entity("pipeline/shadow/entity_depth_solid", "entity_shadow_depth")
                .withDepthStencilState(depthWrite)
                .withCull(false)
                .withBindGroupLayout(casterLayout())
                .build();
            ENTITY_DEPTH_CUTOUT = entity("pipeline/shadow/entity_depth_cutout", "entity_shadow_depth")
                .withDepthStencilState(depthWrite)
                .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                .withCull(false)
                .withBindGroupLayout(casterLayout())
                .build();
            ENTITY_DEPTH_TRANSLUCENT = entity("pipeline/shadow/entity_depth_translucent", "entity_shadow_depth")
                .withDepthStencilState(depthWrite)
                .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                .withCull(false)
                .withBindGroupLayout(casterLayout())
                .build();
        }
    }

    public static void close() {
        for (RenderPipeline pipeline : new RenderPipeline[]{ENTITY_DEPTH_SOLID, ENTITY_DEPTH_CUTOUT, ENTITY_DEPTH_TRANSLUCENT}) {
            if (pipeline != null) {
                GraphShaderSources.remove(pipeline);
            }
        }

        ENTITY_DEPTH_SOLID = null;
        ENTITY_DEPTH_CUTOUT = null;
        ENTITY_DEPTH_TRANSLUCENT = null;
    }

    public static RenderPipeline entityDepthPipeline(RenderPipeline pipeline) {
        if (pipeline == RenderPipelines.ENTITY_SOLID || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD) {
            return ENTITY_DEPTH_SOLID;
        } else if (pipeline != RenderPipelines.ENTITY_CUTOUT
            && pipeline != RenderPipelines.ENTITY_CUTOUT_CULL
            && pipeline != RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
            && pipeline != RenderPipelines.ENTITY_CUTOUT_DISSOLVE
            && pipeline != RenderPipelines.ARMOR_CUTOUT_NO_CULL
            && pipeline != RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
            return pipeline != RenderPipelines.ENTITY_TRANSLUCENT && pipeline != RenderPipelines.ENTITY_TRANSLUCENT_CULL ? null : ENTITY_DEPTH_TRANSLUCENT;
        } else {
            return ENTITY_DEPTH_CUTOUT;
        }
    }

    private static Builder entity(String location, String shaderName) {
        return depthOnly(RenderPipeline.builder(new Snippet[]{RenderPipelinesAccessor.sulkan$entitySnippet()}))
            .withLocation(ShaderResourceIds.id(location))
            .withVertexShader(ShaderResourceIds.coreShader(shaderName))
            .withFragmentShader(ShaderResourceIds.coreShader(shaderName))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1);
    }

    private static Builder depthOnly(Builder builder) {
        RenderPipelineBuilderAccessor accessor = (RenderPipelineBuilderAccessor)builder;
        Arrays.fill(accessor.sulkan$colorTargetStates(), null);
        accessor.sulkan$setActiveColorTargetStateCount(0);
        return builder;
    }

    private static BindGroupLayout casterLayout() {
        return BindGroupLayout.builder().withUniform("SulkanCascade", UniformType.UNIFORM_BUFFER).build();
    }
}
