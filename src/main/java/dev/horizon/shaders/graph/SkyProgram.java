package dev.horizon.shaders.graph;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The pack's sky program: a scene program for the pipeline "sulkan:pipeline/sky", drawn as one full-screen triangle
 * right after the opaque world (terrain and solid entities) and before anything translucent. It only touches pixels
 * where nothing opaque was drawn (the depth is still the cleared far value), so water, glass, translucent entities,
 * clouds and weather all blend over the pack's own sky. While a pack has one, vanilla's sky isn't drawn.
 *
 * <p>The pack supplies its vertex shader too (a full-screen triangle from gl_VertexIndex, at depth 0) and can read the
 * SulkanFrame block and its own textures like any scene program.
 */
public final class SkyProgram {
    public static final String LOCATION = "sulkan:pipeline/sky";
    static final RenderPipeline BASE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("sulkan", "pipeline/sky"))
        .withVertexShader(Identifier.fromNamespaceAndPath("sulkan", "pipeline/sky_vertex"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("sulkan", "pipeline/sky_fragment"))
        .withColorTargetState(ColorTargetState.DEFAULT)
        // Reversed depth: the world clears to 0, so only uncovered pixels pass, and the sky never writes depth.
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build();

    static {
        GraphShaderSources.put(
            BASE,
            "#version 450\nvoid main() {\n\tvec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n}\n",
            "#version 450\nlayout(location=0) out vec4 color;\nvoid main() { discard; }\n"
        );
    }

    private SkyProgram() {
    }
}
