package dev.horizon.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.Builder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.horizon.shaders.render.shadow.ShadowService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

final class ScenePrograms implements AutoCloseable {
    private final List<ScenePrograms.Sources> rules = new ArrayList<>();
    private final Map<RenderPipeline, RenderPipeline> cache = new IdentityHashMap<>();
    private final Map<RenderPipeline, RenderPipeline> targetCache = new IdentityHashMap<>();
    private final Map<RenderPipeline, RenderPipeline> fallbackCache = new IdentityHashMap<>();
    // The same for passes into the HDR scene target (its first color target in GraphRenderer.HDR_FORMAT).
    private final Map<RenderPipeline, RenderPipeline> hdrCache = new IdentityHashMap<>();
    private final Map<RenderPipeline, RenderPipeline> hdrTargetCache = new IdentityHashMap<>();
    private final Map<RenderPipeline, RenderPipeline> hdrFallbackCache = new IdentityHashMap<>();
    private final PackGraph graph;
    private static long sequence;

    ScenePrograms(PackGraph graph, PackFiles files) throws IOException {
        this.graph = graph;

        try {
            for (PackGraph.SceneProgram rule : graph.activeScenePrograms()) {
                this.rules.add(new ScenePrograms.Sources(rule, files.shader(rule.vertex(), graph.options()), files.shader(rule.fragment(), graph.options())));
            }

            for (RenderPipeline pipeline : Stream.of(RenderPipelines.requiredPipelines(), RenderPipelines.optionalPipelines(), List.of(SkyProgram.BASE))
                .flatMap(List::stream)
                .toList()) {
                if (this.rules.stream().anyMatch(rule -> rule.rule.matches(pipeline.getLocation().toString()))) {
                    this.replace(pipeline, !graph.sceneTargets().isEmpty(), graph.environment().hdr());
                }
            }
        } catch (Exception failure) {
            this.close();
            throw new IOException("Invalid scene program: " + failure.getMessage(), failure);
        }
    }

    RenderPipeline replace(RenderPipeline base, boolean sceneTargets, boolean hdr) {
        if (base instanceof ScenePrograms.ScenePipeline) {
            return base;
        }

        Map<RenderPipeline, RenderPipeline> cache = hdr ? (sceneTargets ? this.hdrTargetCache : this.hdrCache) : (sceneTargets ? this.targetCache : this.cache);
        RenderPipeline cached = cache.get(base);
        if (cached != null) {
            return cached;
        }

        ScenePrograms.Sources selected = null;

        for (ScenePrograms.Sources rule : this.rules) {
            if (rule.rule.matches(base.getLocation().toString())) {
                if (selected != null) {
                    throw new IllegalArgumentException("Overlapping scene program selectors for " + base.getLocation());
                }

                selected = rule;
            }
        }

        if (selected != null && !selected.rule.writes().isEmpty() && !sceneTargets) {
            selected = null;
        }

        if (selected == null && !sceneTargets && !hdr) {
            cache.put(base, base);
            return base;
        }

        Identifier id = replacementLocation(base.getLocation(), sequence++);
        RenderPipeline replacement = new ScenePrograms.ScenePipeline(
            base,
            id,
            selected != null,
            this.targets(base, selected, sceneTargets, hdr),
            selected == null ? Map.of() : selected.rule.textures(),
            this.graph.shadowQuality() > 0,
            this.graph.translucentShadowsActive()
        );
        GraphShaderSources.put(replacement, selected == null ? null : selected.vertex, selected == null ? null : selected.fragment);

        try {
            if (RenderSystem.getCompiledPipelineNullable(replacement) == null) {
                throw new IllegalArgumentException("Scene shader is incompatible with " + base.getLocation());
            }
        } catch (RuntimeException failure) {
            GraphShaderSources.remove(replacement);
            throw failure;
        }

        cache.put(base, replacement);
        return replacement;
    }

    private ColorTargetState[] targets(RenderPipeline base, ScenePrograms.Sources selected, boolean enabled, boolean hdr) {
        ColorTargetState[] targets = !enabled
            ? base.getColorTargetStates().toArray(ColorTargetState[]::new)
            : attachmentStates(base, this.graph, selected == null ? List.of() : selected.rule.writes());
        if (hdr && targets.length > 0 && targets[0] != null) {
            targets[0] = new ColorTargetState(targets[0].blendFunction(), GraphRenderer.HDR_FORMAT, targets[0].writeMask());
        }

        return targets;
    }

    static ColorTargetState[] attachmentStates(RenderPipeline base, PackGraph graph, List<String> writes) {
        ColorTargetState[] targets = new ColorTargetState[graph.sceneTargets().size() + 1];
        targets[0] = (ColorTargetState)base.getColorTargetStates().getFirst();

        for (int i = 1; i < targets.length; i++) {
            String name = graph.sceneTargets().get(i - 1);
            boolean enabled = writes.contains(name);
            targets[i] = new ColorTargetState(Optional.empty(), graph.resources().get(name).format(), enabled ? 15 : 0);
        }

        return targets;
    }

    RenderPipeline fallback(RenderPipeline base, boolean sceneTargets, boolean hdr) {
        return !sceneTargets && !hdr ? base : (hdr ? this.hdrFallbackCache : this.fallbackCache).computeIfAbsent(base, original -> {
            Identifier id = replacementLocation(original.getLocation(), sequence++);
            RenderPipeline pipeline = new ScenePrograms.ScenePipeline(
                original, id, false, this.targets(original, null, sceneTargets, hdr), Map.of(), false, false
            );
            GraphShaderSources.put(pipeline, null, null);
            if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
                GraphShaderSources.remove(pipeline);
                throw new IllegalStateException("Cannot restore scene pipeline " + base.getLocation());
            } else {
                return pipeline;
            }
        });
    }

    static Identifier replacementLocation(Identifier original, long generation) {
        String namespace = original.getNamespace().contains("sodium") ? "sulkan_sodium" : "sulkan";
        return Identifier.fromNamespaceAndPath(namespace, "native_geometry/" + generation);
    }

    static boolean isReplacement(RenderPipeline pipeline) {
        return pipeline instanceof ScenePrograms.ScenePipeline scene && scene.nativeShader;
    }

    long replacementCount() {
        return Stream.of(this.cache, this.targetCache, this.hdrCache, this.hdrTargetCache)
            .flatMap(map -> map.values().stream())
            .filter(ScenePrograms::isReplacement)
            .distinct()
            .count();
    }

    static Map<String, String> textures(RenderPipeline pipeline) {
        return pipeline instanceof ScenePrograms.ScenePipeline scene ? scene.textures : Map.of();
    }

    @Override
    public void close() {
        for (Map<RenderPipeline, RenderPipeline> entries : List.of(
            this.cache, this.targetCache, this.fallbackCache, this.hdrCache, this.hdrTargetCache, this.hdrFallbackCache
        )) {
            entries.forEach((base, replacement) -> {
                if (base != replacement) {
                    GraphShaderSources.remove(replacement);
                }
            });
            entries.clear();
        }
    }

    private static final class ScenePipeline extends RenderPipeline {
        private final boolean nativeShader;
        private final Map<String, String> textures;

        ScenePipeline(
            RenderPipeline base,
            Identifier id,
            boolean nativeShader,
            ColorTargetState[] targets,
            Map<String, String> textures,
            boolean shadows,
            boolean translucentShadows
        ) {
            super(
                id,
                nativeShader ? Map.of(ShaderType.VERTEX, id.withSuffix("_vertex"), ShaderType.FRAGMENT, id.withSuffix("_fragment")) : base.getShaders(),
                defines(base, nativeShader),
                nativeShader ? layouts(base, textures.keySet(), shadows, translucentShadows) : base.getBindGroupLayouts(),
                targets,
                base.getDepthStencilState(),
                base.getPolygonMode(),
                base.isCull(),
                base.getVertexFormatBindings().toArray(VertexFormat[]::new),
                base.getPrimitiveTopology(),
                base.pushConstantSize(),
                base.getSortKey()
            );
            this.nativeShader = nativeShader;
            this.textures = textures;
        }

        private static ShaderDefines defines(RenderPipeline base, boolean nativeShader) {
            return nativeShader && base.getLocation().getPath().equals("pipeline/translucent_terrain")
                ? ShaderDefines.builder().define("SULKAN_TRANSLUCENT").build().withOverrides(base.getShaderDefines())
                : base.getShaderDefines();
        }

        private static List<BindGroupLayout> layouts(RenderPipeline base, Set<String> textures, boolean shadows, boolean translucentShadows) {
            List<BindGroupLayout> layouts = new ArrayList<>(base.getBindGroupLayouts());
            BindGroupLayout.Builder extra = BindGroupLayout.builder()
                .withUniform("SulkanFrame", UniformType.UNIFORM_BUFFER)
                .withUniform("SulkanWind", UniformType.UNIFORM_BUFFER);
            if (shadows) {
                ShadowService.layout(extra, translucentShadows);
            }

            for (String texture : textures) {
                if (BindGroupLayout.flattenUniforms(base.getBindGroupLayouts()).stream().anyMatch(uniform -> uniform.name().equals(texture))) {
                    throw new IllegalArgumentException("Scene texture conflicts with Minecraft binding " + texture);
                }

                extra.withUniform(texture, UniformType.COMBINED_IMAGE_SAMPLER);
            }

            layouts.add(extra.build());
            return List.copyOf(layouts);
        }
    }

    private record Sources(PackGraph.SceneProgram rule, String vertex, String fragment) {
    }
}
