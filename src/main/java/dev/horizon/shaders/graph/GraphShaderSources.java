package dev.horizon.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline.Pending;
import com.mojang.renderpearl.api.pipeline.ShaderSource.CachedIncludeSource;
import dev.horizon.shaders.runtime.NativeShaderPreprocessor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;

public final class GraphShaderSources {
    private static final Map<RenderPipeline, GraphShaderSources.Source> SOURCES = new IdentityHashMap<>();
    private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();
    private static final Map<CompiledRenderPipeline, RenderPipeline> ORIGINAL = new WeakHashMap<>();
    private static final Map<RenderPipeline, Runnable> RETIRE = new IdentityHashMap<>();
    // Whether a pipeline names a sulkan: shader (fixed per pipeline), and the last compiled -> original pairs looked up:
    // these run on every pipeline switch of every draw, so they must not stream over shader ids or hash into the weak
    // map each time. One immutable pair per slot, so a slot never mixes two lookups.
    private static final Map<RenderPipeline, Boolean> SULKAN_SHADERS = new IdentityHashMap<>();
    private static final GraphShaderSources.Pair[] RECENT = new GraphShaderSources.Pair[1024];

    private GraphShaderSources() {
    }

    public static void put(RenderPipeline pipeline, String vertex, String fragment) {
        SOURCES.put(pipeline, new GraphShaderSources.Source(vertex, fragment));
    }

    public static String get(RenderPipeline pipeline, ShaderType type) {
        GraphShaderSources.Source source = SOURCES.get(pipeline);
        return source == null ? null : (type == ShaderType.VERTEX ? source.vertex : source.fragment);
    }

    public static boolean contains(RenderPipeline pipeline) {
        return SOURCES.containsKey(pipeline);
    }

    public static boolean isNative(RenderPipeline pipeline) {
        if (contains(pipeline)) {
            return true;
        }

        Boolean sulkan = SULKAN_SHADERS.get(pipeline);
        if (sulkan == null) {
            sulkan = pipeline.getShaders().values().stream().anyMatch(id -> id.getNamespace().equals("sulkan"));
            SULKAN_SHADERS.put(pipeline, sulkan);
        }

        return sulkan;
    }

    public static void remember(RenderPipeline pipeline, CompiledRenderPipeline compiled) {
        if (compiled != null) {
            int slot = System.identityHashCode(compiled) & RECENT.length - 1;
            GraphShaderSources.Pair recent = RECENT[slot];
            if (recent == null || recent.compiled != compiled || recent.pipeline != pipeline) {
                ORIGINAL.put(compiled, pipeline);
                RECENT[slot] = new GraphShaderSources.Pair(compiled, pipeline);
            }
        }
    }

    public static RenderPipeline original(CompiledRenderPipeline compiled) {
        int slot = System.identityHashCode(compiled) & RECENT.length - 1;
        GraphShaderSources.Pair recent = RECENT[slot];
        if (recent != null && recent.compiled == compiled) {
            return recent.pipeline;
        }

        RenderPipeline original = ORIGINAL.get(compiled);
        if (original != null) {
            RECENT[slot] = new GraphShaderSources.Pair(compiled, original);
        }

        return original;
    }

    private record Pair(CompiledRenderPipeline compiled, RenderPipeline pipeline) {
    }

    public static CompiledRenderPipeline compile(RenderPipeline pipeline, ShaderSource fallback) {
        CompiledRenderPipeline cached = COMPILED.get(pipeline);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }

        GraphShaderSources.NativeSources sources = new GraphShaderSources.NativeSources(pipeline, fallback);

        CompiledRenderPipeline var5;
        try {
            CompiledRenderPipeline compiled = ((Pending)RenderSystem.getDevice().compilePipeline(pipeline, sources, Runnable::run).join()).finishCompile();
            if (compiled == null) {
                throw new IllegalStateException("Failed to compile native pipeline " + pipeline.getLocation());
            }

            COMPILED.put(pipeline, compiled);
            remember(pipeline, compiled);
            var5 = compiled;
        } catch (Throwable var7) {
            try {
                sources.close();
            } catch (Throwable var6) {
                var7.addSuppressed(var6);
            }

            throw var7;
        }

        sources.close();
        return var5;
    }

    public static void onRetire(RenderPipeline pipeline, Runnable callback) {
        RETIRE.put(pipeline, callback);
    }

    public static void remove(RenderPipeline pipeline) {
        SOURCES.remove(pipeline);
        SULKAN_SHADERS.remove(pipeline);
        CompiledRenderPipeline compiled = COMPILED.remove(pipeline);
        if (compiled != null) {
            ORIGINAL.remove(compiled);
            int slot = System.identityHashCode(compiled) & RECENT.length - 1;
            if (RECENT[slot] != null && RECENT[slot].compiled == compiled) {
                RECENT[slot] = null;
            }

            compiled.close();
        }

        Runnable retire = RETIRE.remove(pipeline);
        if (retire != null) {
            retire.run();
        }
    }

    public static final class NativeSources implements ShaderSource {
        private final RenderPipeline pipeline;
        private final ShaderSource fallback;
        private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();

        NativeSources(RenderPipeline pipeline, ShaderSource fallback) {
            this.pipeline = pipeline;
            this.fallback = fallback;
        }

        public boolean isNative(ShaderType type) {
            return GraphShaderSources.get(this.pipeline, type) != null || ((Identifier)this.pipeline.getShaders().get(type)).getNamespace().equals("sulkan");
        }

        public String getShader(Identifier id, ShaderType type) {
            String custom = GraphShaderSources.get(this.pipeline, type);
            if (custom != null) {
                return custom;
            }

            if (!id.getNamespace().equals("sulkan")) {
                return this.fallback.getShader(id, type);
            }

            String source = text("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.FRAGMENT ? ".fsh" : ".vsh"));
            if (source == null) {
                return null;
            }

            final HashSet<String> imported = new HashSet<>();
            return String.join("", (new NativeShaderPreprocessor() {
                @Override
                public String applyImport(boolean local, String name) {
                    if (!imported.add(name)) {
                        return null;
                    } else {
                        Identifier include = Identifier.parse(name);
                        String body = GraphShaderSources.NativeSources.text("assets/" + include.getNamespace() + "/shaders/include/" + include.getPath());
                        if (body == null) {
                            throw new IllegalArgumentException("Missing shader include " + name);
                        } else {
                            return body;
                        }
                    }
                }
            }).process(source));
        }

        public CachedIncludeSource getInclude(Identifier id) {
            return !id.getNamespace().equals("sulkan")
                ? this.fallback.getInclude(id)
                : this.includes
                    .computeIfAbsent(id, key -> CachedIncludeSource.create(key, Objects.requireNonNull(text("assets/sulkan/shaders/include/" + key.getPath()))));
        }

        public void close() {
            this.includes.values().forEach(CachedIncludeSource::close);
        }

        private static String text(String path) {
            try (InputStream stream = GraphShaderSources.class.getClassLoader().getResourceAsStream(path)) {
                return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    private record Source(String vertex, String fragment) {
    }
}
