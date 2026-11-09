package dev.horizon.client.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.vertex.VertexFormat
import dev.horizon.client.HorizonClient
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * The client's shaders (assets/horizon/shaders/core) as 26.3 pipelines. Each quad pipeline takes Position, UV0 (the corner,
 * 0..1 across the quad) and Color; the per-shape values (size, radius, border...) come in the "UBO" block, one per draw.
 */
object Pipelines {
    private val UBO = BindGroupLayout.builder().withUniform("UBO", UniformType.UNIFORM_BUFFER).build()
    // Samplers before uniform blocks, as vanilla's post chains declare them.
    private val UBO_SAMPLER = BindGroupLayout.builder()
        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("UBO", UniformType.UNIFORM_BUFFER)
        .build()

    private fun id(path: String) = Identifier.fromNamespaceAndPath(HorizonClient.ID, path)

    private fun quad(name: String, format: VertexFormat, extra: BindGroupLayout?, shader: String = name,
                     topology: PrimitiveTopology = PrimitiveTopology.QUADS, blend: BlendFunction? = BlendFunction.TRANSLUCENT,
                     depth: DepthStencilState? = null, cull: Boolean = false, define: String? = null): RenderPipeline {
        val b = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/$name"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(id("core/$shader"))
            .withFragmentShader(id("core/$shader"))
            .withColorTargetState(if (blend == null) ColorTargetState.DEFAULT else ColorTargetState(blend))
            .withVertexBinding(0, format)
            .withPrimitiveTopology(topology)
            // These pipelines don't cull; the glass rects are wound the other way round.
            .withCull(cull)
        if (extra != null) b.withBindGroupLayout(extra)
        if (depth != null) b.withDepthStencilState(depth)
        if (define != null) b.withShaderDefine(define)
        return RenderPipelines.registerOptional(b.build())
    }

    // Not registered: registered pipelines with no vertex input get preloaded at startup, before mod resources exist.
    // Like vanilla's post chains, these compile on first use.
    private fun post(name: String, fragment: String, blend: BlendFunction? = null): RenderPipeline = (
        RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/$name"))
            .withVertexShader(id("core/kawase"))
            .withFragmentShader(id("core/$fragment"))
            .withBindGroupLayout(UBO_SAMPLER)
            .withColorTargetState(if (blend == null) ColorTargetState.DEFAULT else ColorTargetState(blend))
            .build()
    )

    val RECT = quad("rect", DefaultVertexFormat.POSITION_TEX_COLOR, UBO)

    /** [Canvas.box]'s rounded rect. */
    val BOX = quad("box", DefaultVertexFormat.POSITION_TEX_COLOR, UBO)

    /** [Canvas.box] with a gradient, dithered. */
    val BOX_DITHER = quad("box_dither", DefaultVertexFormat.POSITION_TEX_COLOR, UBO, shader = "box",
        blend = BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA, define = "DITHER")

    /** [Canvas.glow] and [Canvas.radial]. */
    val GLOW = quad("glow", DefaultVertexFormat.POSITION_TEX_COLOR, UBO, blend = BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
    val TEXTURED_RECT = quad("textured_rect", DefaultVertexFormat.POSITION_TEX_COLOR, UBO_SAMPLER)
    val BLURRED_RECT = quad("blurred_rect", DefaultVertexFormat.POSITION_TEX_COLOR, UBO_SAMPLER)

    /** [Canvas.blurBox]'s blurred rounded rect. */
    val BLUR_BOX = quad("blur_box", DefaultVertexFormat.POSITION_TEX_COLOR, UBO_SAMPLER)
    private val SAMPLER = BindGroupLayout.builder().withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build()
    val COLORED_TEXTURE = quad("colored_texture", DefaultVertexFormat.POSITION_TEX_COLOR, SAMPLER)

    /** Additive (SRC_ALPHA, ONE), for the atlas sprites. */
    val COLORED_TEXTURE_ADDITIVE = quad("colored_texture_additive", DefaultVertexFormat.POSITION_TEX_COLOR, SAMPLER,
        shader = "colored_texture", blend = BlendFunction.LIGHTNING)
    val POS_COLOR = quad("pos_color", DefaultVertexFormat.POSITION_COLOR, null)

    /** GL lines, 1 px wide, no anti-aliasing. */
    val LINES = quad("pos_color_lines", DefaultVertexFormat.POSITION_COLOR, null, shader = "pos_color",
        topology = PrimitiveTopology.DEBUG_LINES)
    val POS_COLOR_TRIANGLES: RenderPipeline = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/pos_color_triangles"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(id("core/pos_color"))
            .withFragmentShader(id("core/pos_color"))
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build()
    )
    // ---- world (reversed-Z, so GEQUAL is the normal depth test) ----

    private val DEPTH_TEST = DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)

    /** Shader boxes into their own target, the last face drawn wins. */
    val WORLD_SHADER_FILL = quad("world_shader_fill", DefaultVertexFormat.POSITION_COLOR, null, shader = "pos_color", blend = null)

    /** Box fills, through walls. */
    val WORLD_FILL = quad("world_fill", DefaultVertexFormat.POSITION_COLOR, null, shader = "pos_color")

    /** Depth-tested additive lines. */
    val WORLD_DEPTH_LINES = quad("world_depth_lines", DefaultVertexFormat.POSITION_COLOR, null, shader = "pos_color",
        topology = PrimitiveTopology.DEBUG_LINES, blend = BlendFunction.LIGHTNING, depth = DEPTH_TEST)

    /** Fills hidden behind the level (Breadcrumbs' shader mask with through-walls off). */
    val WORLD_DEPTH_FILL = quad("world_depth_fill", DefaultVertexFormat.POSITION_COLOR, null, shader = "pos_color", depth = DEPTH_TEST)

    /** Additive sprites, depth-tested. */
    val COLORED_TEXTURE_ADDITIVE_DEPTH = quad("colored_texture_additive_depth", DefaultVertexFormat.POSITION_TEX_COLOR, SAMPLER,
        shader = "colored_texture", blend = BlendFunction.LIGHTNING, depth = DEPTH_TEST)

    /** The back-track ghost, depth-tested and writing depth (no cull). */
    val GHOST_DEPTH = quad("ghost_depth", DefaultVertexFormat.POSITION_TEX_COLOR, SAMPLER, shader = "colored_texture",
        depth = DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))

    /** The ghost through walls, back faces culled. */
    val GHOST = quad("ghost", DefaultVertexFormat.POSITION_TEX_COLOR, SAMPLER, shader = "colored_texture", cull = true)

    /** The world targets onto the screen, blended. */
    val POST_MULTIDEFAULT = post("multidefault", "multidefault", BlendFunction.TRANSLUCENT)
    val POST_LINES = post("lines_post", "lines_post", BlendFunction.TRANSLUCENT)

    val KAWASE_DOWN = post("kawase_down", "kawase_down")
    val KAWASE_UP = post("kawase_up", "kawase_up")

    /** The menus' animated backdrop ([Backdrop]), a vanilla GUI element over the whole screen. */
    val MENU_BACKGROUND = quad("menu_background", DefaultVertexFormat.POSITION_TEX_COLOR, null)

    /** Loads the class (registering the pipelines) before the first resource reload. */
    fun init() {}
}
