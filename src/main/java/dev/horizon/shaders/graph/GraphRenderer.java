package dev.horizon.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor.Attachment;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.Builder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.util.UncheckedAutoCloseable;
import dev.horizon.shaders.mixin.GraphDeviceAccessor;
import dev.horizon.shaders.render.shadow.ShadowService;
import dev.horizon.shaders.runtime.GpuCapabilities;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class GraphRenderer implements AutoCloseable {
    private static long generation;
    private static final String VERTEX = "#version 450\nlayout(location=0) out vec2 texCoord;\nvoid main() {\n\ttexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    private static final String PRESENT = "#version 450\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 color;\nuniform sampler2D Source;\nvoid main() { color = texture(Source, texCoord); }\n";
    private final PackGraph graph;
    private final MaterialTable materials;
    private final List<PackGraph.Pass> schedule;
    // Keyed by the scheduled pass objects themselves: a Pass is a record of maps and lists, too slow to hash per frame.
    private final Map<PackGraph.Pass, RenderPipeline> pipelines = new java.util.IdentityHashMap<>();
    private final Map<PackGraph.Pass, ComputeProgram> computePipelines = new java.util.IdentityHashMap<>();
    private final List<RenderPipeline> ownedPipelines = new ArrayList<>();
    private final Map<String, GraphRenderer.Image[]> images = new LinkedHashMap<>();
    private final GraphFrame frame = new GraphFrame();
    private final GpuSampler sampler;
    private GpuSampler linearSampler;
    private final RenderPipeline present;
    private int width;
    private int height;
    private int parity;
    private boolean closed;
    private long renderedFrames;
    private ScenePrograms scenePrograms;
    private GpuBufferSlice windUniforms;
    private final ByteBuffer windBytes = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
    private static final long WIND_START = System.nanoTime();
    private GpuBufferSlice sceneUniforms;
    private final Map<String, GraphRenderer.Image> customTextures = new LinkedHashMap<>();
    private final Map<String, GpuSampler> textureSamplers = new LinkedHashMap<>();
    private final Map<String, Integer> mipFilters = new HashMap<>();
    private long textureBytes;
    private GraphBuffers buffers;
    private GraphSceneCapture sceneCapture;
    private final Set<RenderPipeline> shadowPostPipelines = new HashSet<>();
    /**
     * With the pack's environment.hdr, the world draws into this 16-bit float target instead of Minecraft's 8-bit main
     * target (see {@link #sceneAttachments}): every pass that would draw into the main color draws here, with its
     * pipeline rebuilt for the float format. The pack reads it as $scene and its last pass writes the main target.
     */
    public static final GpuFormat HDR_FORMAT = GpuFormat.RGBA16_FLOAT;
    private GpuTexture hdrTexture;
    private GpuTextureView hdrView;

    public MaterialTable materials() {
        return this.materials;
    }

    public long renderedFrames() {
        return this.renderedFrames;
    }

    public boolean translucentShadows() {
        return this.graph.translucentShadowsActive();
    }

    public List<Integer> shadowSkipMaterials() {
        return this.graph.environment().shadowSkipMaterials();
    }

    public PackGraph.TranslucentShadows translucentShadowSettings() {
        return this.graph.environment().translucentShadows();
    }

    // Whether the pack draws the sky: asked for every sky draw, and the pack's rules don't change while it's loaded.
    private Boolean skyProgram;

    public boolean hasSkyProgram() {
        Boolean sky = this.skyProgram;
        if (sky == null) {
            sky = this.graph.scenePrograms().stream().anyMatch(rule -> rule.matches(SkyProgram.LOCATION));
            this.skyProgram = sky;
        }
        return sky;
    }

    public long sceneReplacementCount() {
        return this.scenePrograms.replacementCount();
    }

    public void invalidateHistory() {
        this.frame.reset();
    }

    public long terrainCaptures() {
        return this.sceneCapture.captures();
    }

    public void captureTerrain(RenderTarget main) {
        this.sceneCapture.capture(main, this.sceneColor(main));
    }

    public boolean hdr() {
        return this.graph.environment().hdr();
    }

    /** The texture the world's color goes to: the HDR target, or Minecraft's main color. */
    public GpuTexture sceneColor(RenderTarget main) {
        return this.hdrTexture != null ? this.hdrTexture : main.getColorTexture();
    }

    public boolean isHdrTexture(GpuTexture texture) {
        return texture != null && texture == this.hdrTexture;
    }

    public void captureWorldDepth(RenderTarget main) {
        this.sceneCapture.captureWorldDepth(main);
    }

    public GraphRenderer(PackGraph graph, PackFiles files, RenderTarget main) throws IOException {
        this.graph = graph;
        this.schedule = graph.schedule();
        this.frame.heldLighting = graph.options().getOrDefault("HELD_LIGHTING", 0.0) > 0.0;
        this.frame.biomeGroups(graph.environment().biomes());
        if (this.translucentShadows()) {
            files.feature("SULKAN_TRANSLUCENT_SHADOWS");
        }
        this.materials = MaterialTable.compile(graph.materials());
        this.materials.vegetationWind = graph.options().containsKey("VEGETATION_WIND");
        this.materials.faceData = graph.environment().terrainNormals();
        GpuDevice device = RenderSystem.getDevice();
        this.sampler = device.createSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()
        );

        try {
            this.linearSampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty()
            );
            if (graph.sceneTargets().size() + 1 > device.getDeviceInfo().limits().maxColorAttachments()) {
                throw new IOException("GPU cannot provide the pack's scene attachments");
            }

            this.sceneCapture = new GraphSceneCapture(graph, main);
            this.scenePrograms = new ScenePrograms(graph, files);

            for (PackGraph.Pass pass : this.schedule) {
                if (pass.isCompute()) {
                    this.computePipelines.put(pass, new ComputeProgram(graph, pass, files));
                } else {
                    if (pass.writes().size() > device.getDeviceInfo().limits().maxColorAttachments()) {
                        throw new IOException("Device cannot provide " + pass.writes().size() + " color attachments for " + pass.name());
                    }

                    List<GpuFormat> formats = pass.writes().stream().map(n -> graph.resources().get(n).format()).toList();
                    this.pipelines.put(pass, this.pipeline(pass.name(), files.shader(pass.fragment(), graph.options()), pass.reads().keySet(), formats, true));
                }
            }

            this.present = this.pipeline(
                "present",
                "#version 450\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 color;\nuniform sampler2D Source;\nvoid main() { color = texture(Source, texCoord); }\n",
                Set.of("Source"),
                List.of(main.getColorTexture().getFormat()),
                false
            );
            this.loadTextures(files);
            this.allocate(main.width, main.height);
            this.buffers = new GraphBuffers(graph);
        } catch (Exception failure) {
            this.close();
            throw new IOException("Cannot activate " + graph.name() + ": " + failure.getMessage(), failure);
        }
    }

    private RenderPipeline pipeline(String label, String fragment, Set<String> inputs, List<GpuFormat> formats, boolean frameUniform) throws IOException {
        String path = "native/g" + ++generation + "/" + label;
        Builder bindings = BindGroupLayout.builder();
        inputs.forEach(name -> bindings.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER));
        if (frameUniform) {
            bindings.withUniform("SulkanFrame", UniformType.UNIFORM_BUFFER);
        }

        boolean shadows = frameUniform && this.graph.shadowQuality() > 0 && fragment.contains("uniform SulkanShadowData");
        if (shadows) {
            ShadowService.layout(bindings, this.translucentShadows());
        }

        int samplers = inputs.size() + (shadows ? 6 : 0);
        int maxSamplers = GpuCapabilities.current().maxStageSamplers();
        if (samplers > maxSamplers) {
            throw new IOException("Pass " + label + " binds " + samplers + " textures but this GPU allows " + maxSamplers + " per shader");
        }

        com.mojang.renderpearl.api.pipeline.RenderPipeline.Builder builder = RenderPipeline.builder(new Snippet[]{RenderPipelines.POST_PROCESSING_SNIPPET})
            .withLocation(Identifier.fromNamespaceAndPath("sulkan", path))
            .withVertexShader(Identifier.fromNamespaceAndPath("sulkan", path + "_vertex"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("sulkan", path + "_fragment"))
            .withBindGroupLayout(bindings.build());

        for (int i = 0; i < formats.size(); i++) {
            builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), formats.get(i), 15));
        }

        RenderPipeline pipeline = builder.build();
        this.ownedPipelines.add(pipeline);
        if (shadows) {
            this.shadowPostPipelines.add(pipeline);
        }

        GraphShaderSources.put(
            pipeline,
            "#version 450\nlayout(location=0) out vec2 texCoord;\nvoid main() {\n\ttexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);\n}\n",
            fragment
        );
        if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
            throw new IOException("Shader compilation failed in pass " + label + "; see log for source diagnostics");
        } else {
            return pipeline;
        }
    }

    private void allocate(int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (this.width != width || this.height != height) {
            if (this.graph.allocationBytes(width, height)
                    + this.textureBytes
                    + this.sceneCapture.bytes(width, height)
                    + (this.hdr() ? (long)width * height * HDR_FORMAT.blockSize() : 0L)
                    + ShadowService.memoryBytes(this.graph.shadowQuality(), this.translucentShadows())
                > this.graph.budgetBytes()) {
                throw new IllegalArgumentException("Textures and render targets exceed pack memory budget");
            }

            Map<String, GraphRenderer.Image[]> fresh = new LinkedHashMap<>();
            GraphAllocations plan = GraphAllocations.plan(this.graph);
            Set<Integer> storageSlots = new HashSet<>();

            for (PackGraph.Pass pass : this.schedule) {
                if (pass.isCompute()) {
                    for (String output : pass.writes()) {
                        storageSlots.add(plan.resourceSlots().get(output));
                    }
                }
            }

            Map<Integer, GraphRenderer.Image[]> physical = new HashMap<>();

            try {
                GpuDevice device = RenderSystem.getDevice();

                for (String name : plan.resourceSlots().keySet()) {
                    PackGraph.Resource r = this.graph.resources().get(name);
                    if (r.mipmaps()) {
                        this.mipFilters.put(name, GraphMipmaps.filter(r.format()));
                    }

                    int slot = plan.resourceSlots().get(name);
                    if (!physical.containsKey(slot)) {
                        int w = r.width(width);
                        int h = r.height(height);
                        int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(r.format());
                        if (w > limit || h > limit) {
                            throw new IllegalArgumentException("Unsupported size/format for " + name + ": " + w + "x" + h);
                        }

                        long size = r.bytes(width, height);
                        if (size > device.getDeviceInfo().limits().maxMemoryAllocationSize()) {
                            throw new IllegalArgumentException("Allocation exceeds device limit: " + name);
                        }

                        GraphRenderer.Image[] pair = new GraphRenderer.Image[r.history() ? 2 : 1];
                        fresh.put(name, pair);
                        physical.put(slot, pair);

                        for (int i = 0; i < pair.length; i++) {
                            try (StorageImageScope scope = new StorageImageScope(storageSlots.contains(slot), r.depth())) {
                                GpuTexture texture = ((GraphDeviceAccessor)device)
                                    .sulkan$backend()
                                    .createTexture(
                                        "Sulkan " + name,
                                        (r.depth() == 1 ? 8 : 0)
                                            | 4
                                            | (!r.history() && !this.graph.sceneTargets().contains(name) && !r.mipmaps() ? 0 : 1)
                                            | (r.mipmaps() ? 2 : 0),
                                        r.format(),
                                        w,
                                        h,
                                        r.depth(),
                                        r.mipLevels(width, height)
                                    );

                                try {
                                    GpuTextureView sampled = device.createTextureView(texture);

                                    try {
                                        pair[i] = new GraphRenderer.Image(texture, sampled, r.mipmaps() ? device.createTextureView(texture, 0, 1) : sampled);
                                    } catch (Exception failure) {
                                        sampled.close();
                                        throw failure;
                                    }
                                } catch (Exception failure) {
                                    texture.close();
                                    throw failure;
                                }
                            }
                        }
                    } else {
                        fresh.put(name, physical.get(slot));
                    }
                }
            } catch (Exception failure) {
                destroy(fresh);
                throw failure;
            }

            try {
                this.sceneCapture.resize(width, height);
            } catch (Exception failure) {
                destroy(fresh);
                throw failure;
            }

            GpuTexture oldHdr = this.hdrTexture;
            GpuTextureView oldHdrView = this.hdrView;
            if (this.hdr()) {
                GpuDevice device = RenderSystem.getDevice();
                GpuTexture texture = device.createTexture("Sulkan HDR scene", 15, HDR_FORMAT, width, height, 1, 1);
                this.hdrTexture = texture;
                this.hdrView = device.createTextureView(texture);
            }

            if (oldHdr != null) {
                RenderSystem.queueFencedTask(() -> {
                    oldHdrView.close();
                    oldHdr.close();
                });
            }

            Map<String, GraphRenderer.Image[]> old = new LinkedHashMap<>(this.images);
            this.images.clear();
            this.images.putAll(fresh);
            if (!old.isEmpty()) {
                RenderSystem.queueFencedTask(() -> destroy(old));
            }

            this.width = width;
            this.height = height;
            this.parity = 0;
            this.frame.reset();
        }
    }

    public void render(RenderTarget main, CameraRenderState camera, Matrix4fc view, Object world) {
        if (this.closed) {
            throw new IllegalStateException("Graph is closed");
        }

        this.allocate(main.width, main.height);
        this.sceneCapture.captureWorldDepth(main);
        this.sceneCapture.clearMissedCapture();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        boolean reset = this.frame.discontinuity(camera, world, view);
        boolean resetGlobal = this.frame.globalDiscontinuity(camera, world);
        this.buffers.beginFrame(this.parity, reset);
        if (reset) {
            for (GraphRenderer.Image[] pair : this.images.values()) {
                if (pair.length == 2) {
                    for (GraphRenderer.Image image : pair) {
                        if (resetGlobal || image.texture.getWidth(0) != 1 || image.texture.getHeight(0) != 1 || image.texture.getDepthOrLayers() != 1) {
                            if (image.texture.getDepthOrLayers() > 1) {
                                ComputeProgram.clearVolume(image.texture);
                            } else {
                                encoder.clearColorTexture(image.texture, new Vector4f(0.0F));
                            }
                        }
                    }
                }
            }
        }

        GpuBufferSlice uniforms = this.frame.upload(encoder, camera, view, this.width, this.height, reset);
        boolean directPresent = canPresentDirectly(this.graph, this.schedule.getLast(), main.getColorTexture().getFormat(), this.width, this.height);
        this.graph.sceneTargets().forEach(this::generateMipmaps);

        for (PackGraph.Pass node : this.schedule) {
            if (node.isCompute()) {
                this.computePipelines
                    .get(node)
                    .dispatch(
                        uniforms,
                        resource -> node.writes().contains(resource) ? this.image(resource).attachment : this.resolve(resource, main),
                        this::sampler,
                        this.buffers
                    );
                node.writes().forEach(this::generateMipmaps);
            } else {
                com.mojang.renderpearl.api.commands.RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(
                    () -> this.graph.name() + "/" + node.name()
                );
                GpuTexture first = this.image(node.writes().getFirst()).texture;
                descriptor.withRenderArea(new RenderArea(0, 0, first.getWidth(0), first.getHeight(0)));
                boolean outputToMain = directPresent && node.equals(this.schedule.getLast());

                for (String output : node.writes()) {
                    descriptor.withColorAttachment(outputToMain ? main.getColorTextureView() : this.image(output).attachment, Optional.of(new Vector4f(0.0F)));
                }

                RenderPass pass = encoder.createRenderPass(descriptor.build());

                try {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(this.pipelines.get(node)));
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("SulkanFrame", uniforms);
                    if (this.shadowPostPipelines.contains(this.pipelines.get(node))) {
                        ShadowService.bindTerrain(pass, this.translucentShadows());
                    }

                    node.reads()
                        .forEach(
                            (binding, resource) -> pass.setUniform(
                                binding, this.resolve(resource, main), node.linearReads().contains(binding) ? this.linearSampler : this.sampler(resource)
                            )
                        );
                    pass.draw(3, 1, 0, 0);
                } catch (Throwable var21) {
                    if (pass != null) {
                        try {
                            pass.close();
                        } catch (Throwable var19) {
                            var21.addSuppressed(var19);
                        }
                    }

                    throw var21;
                }

                if (pass != null) {
                    pass.close();
                }

                node.writes().forEach(this::generateMipmaps);
            }
        }

        if (!directPresent) {
            RenderPass pass = encoder.createRenderPass(() -> "Sulkan present", main.getColorTextureView(), Optional.empty());

            try {
                pass.setPipeline(RenderSystem.getCompiledPipeline(this.present));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("Source", this.image(this.graph.present()).view, this.sampler);
                pass.draw(3, 1, 0, 0);
            } catch (Throwable var20) {
                if (pass != null) {
                    try {
                        pass.close();
                    } catch (Throwable var18) {
                        var20.addSuppressed(var18);
                    }
                }

                throw var20;
            }

            if (pass != null) {
                pass.close();
            }
        }

        this.parity ^= 1;
        this.frame.commit(camera, view, world);
        this.renderedFrames++;
    }

    static boolean canPresentDirectly(PackGraph graph, PackGraph.Pass last, GpuFormat format, int width, int height) {
        PackGraph.Resource output = graph.resources().get(graph.present());
        return !last.isCompute()
            && last.writes().equals(List.of(graph.present()))
            && !last.reads().containsValue("$scene")
            && !output.history()
            && !output.mipmaps()
            && output.depth() == 1
            && output.format() == format
            && output.width(width) == width
            && output.height(height) == height;
    }

    private GraphRenderer.Image image(String name) {
        GraphRenderer.Image[] pair = this.images.get(PackGraph.current(name));
        return pair[pair.length == 1 ? 0 : (PackGraph.previous(name) ? this.parity ^ 1 : this.parity)];
    }

    private void generateMipmaps(String name) {
        if (this.graph.resources().get(name).mipmaps()) {
            GraphMipmaps.generate(this.image(name).texture, this.mipFilters.get(name));
        }
    }

    private GpuTextureView resolve(String resource, RenderTarget main) {
        return switch (resource) {
            case "$scene" -> this.hdrView != null ? this.hdrView : main.getColorTextureView();
            case "$weather" -> this.sceneCapture.view("$weather");
            case "$depth" -> this.sceneCapture.view("$depth");
            case "$handDepth" -> main.getDepthTextureView();
            case "$terrainOpaque", "$terrainOpaqueDepth" -> this.sceneCapture.view(resource);
            default -> resource.startsWith("$texture/") ? this.customTextures.get(resource.substring(9)).view : this.image(resource).view;
        };
    }

    private GpuSampler sampler(String resource) {
        return resource.startsWith("$texture/") ? this.textureSamplers.get(resource.substring(9)) : this.sampler;
    }

    private void loadTextures(PackFiles files) throws IOException {
        Set<String> used = new HashSet<>();
        this.schedule.forEach(pass -> pass.reads().values().forEach(input -> {
            if (input.startsWith("$texture/")) {
                used.add(input.substring(9));
            }
        }));
        this.graph
            .activeScenePrograms()
            .forEach(program -> program.textures().values().stream().filter(asset -> !GraphSceneCapture.isInput(asset)).forEach(used::add));
        GpuDevice device = RenderSystem.getDevice();

        for (String name : used) {
            PackGraph.Texture definition = this.graph.textures().get(name);
            NativeImage stitched = null;
            byte[] png;
            switch (definition.source()) {
                case "$minecraft/clouds" -> png = minecraftPng("textures/environment/clouds.png");
                case "$minecraft/sun" -> png = celestialPng("textures/environment/celestial/sun.png");
                case "$minecraft/moon_phases" -> {
                    stitched = moonPhases();
                    png = pngHeader(stitched.getWidth(), stitched.getHeight());
                }
                default -> png = files.binary(definition.source());
            }

            ByteBuffer header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
            if (png.length >= 24 && header.getLong(0) == -8552249625308161526L && header.getInt(12) == 1229472850) {
                int w = header.getInt(16);
                int h = header.getInt(20);
                int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
                long bytes = (long)w * h * 4L;
                if (w > 0
                    && h > 0
                    && w <= limit
                    && h <= limit
                    && bytes <= device.getDeviceInfo().limits().maxMemoryAllocationSize()
                    && bytes <= this.graph.budgetBytes() - this.textureBytes) {
                    NativeImage image = stitched != null ? stitched : NativeImage.read(new ByteArrayInputStream(png));

                    try {
                        GpuTexture texture = device.createTexture("Sulkan texture " + name, 5, GpuFormat.RGBA8_UNORM, w, h, 1, 1);

                        try {
                            this.customTextures.put(name, new GraphRenderer.Image(texture, device.createTextureView(texture)));
                        } catch (Exception failure) {
                            texture.close();
                            throw failure;
                        }

                        device.createCommandEncoder().writeToTexture(texture, image);
                    } catch (Throwable var20) {
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Throwable var17) {
                                var20.addSuppressed(var17);
                            }
                        }

                        throw var20;
                    }

                    if (image != null) {
                        image.close();
                    }

                    AddressMode wrap = definition.repeat() ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
                    FilterMode filter = definition.linear() ? FilterMode.LINEAR : FilterMode.NEAREST;
                    this.textureSamplers.put(name, device.createSampler(wrap, wrap, filter, filter, 1, OptionalDouble.empty()));
                    this.textureBytes += bytes;
                    if (definition.source().equals("$minecraft/clouds")) {
                        this.frame.cloudTextureWidth(w);
                    }
                    continue;
                }

                if (stitched != null) {
                    stitched.close();
                }

                throw new IOException("Custom texture exceeds device or pack limits: " + name);
            }

            throw new IOException("Custom textures must be PNG: " + name);
        }
    }

    private static final String[] MOON_PHASES = {
        "full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous"
    };

    // A texture of Minecraft's (or the resource pack's), at most 8 MiB.
    private static byte[] minecraftPng(String path) throws IOException {
        try (InputStream stream = Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace(path))) {
            return limited(stream, path);
        }
    }

    // A sun or moon texture as the player's resource packs have it, else Minecraft's own: other mods' replacements are
    // passed over (Sulkan ships its own round sun and moon, which a pack drawing Minecraft's must not get).
    private static byte[] celestialPng(String path) throws IOException {
        List<Resource> stack = Minecraft.getInstance().getResourceManager().getResourceStack(Identifier.withDefaultNamespace(path));
        Resource chosen = null;
        for (int i = stack.size() - 1; i >= 0 && chosen == null; i--) {
            if (stack.get(i).sourcePackId().startsWith("file/")) {
                chosen = stack.get(i);
            }
        }

        for (int i = stack.size() - 1; i >= 0 && chosen == null; i--) {
            if (stack.get(i).sourcePackId().equals("vanilla")) {
                chosen = stack.get(i);
            }
        }

        if (chosen == null) {
            throw new IOException("Missing texture " + path);
        }

        try (InputStream stream = chosen.open()) {
            return limited(stream, path);
        }
    }

    private static byte[] limited(InputStream stream, String path) throws IOException {
        byte[] png = stream.readNBytes(8388609);
        if (png.length > 8388608) {
            throw new IOException("Texture " + path + " exceeds 8 MiB");
        }

        return png;
    }

    // Minecraft's eight moon phase textures side by side, four by two in MoonPhase order (index = x + 4 * y), the
    // layout moon_phases.png had before the phases became separate textures.
    private static NativeImage moonPhases() throws IOException {
        NativeImage atlas = null;

        try {
            for (int i = 0; i < MOON_PHASES.length; i++) {
                try (NativeImage phase = NativeImage.read(celestialPng("textures/environment/celestial/moon/" + MOON_PHASES[i] + ".png"))) {
                    if (atlas == null) {
                        atlas = new NativeImage(phase.getWidth() * 4, phase.getHeight() * 2, true);
                    }

                    int w = atlas.getWidth() / 4;
                    int h = atlas.getHeight() / 2;
                    if (phase.getWidth() != w || phase.getHeight() != h) {
                        throw new IOException("Moon phase textures differ in size");
                    }

                    phase.copyRect(atlas, 0, 0, i % 4 * w, i / 4 * h, w, h, false, false);
                }
            }

            return atlas;
        } catch (IOException | RuntimeException failure) {
            if (atlas != null) {
                atlas.close();
            }

            throw failure;
        }
    }

    // The start of a PNG (signature and IHDR size) for an image made here, so it passes the size checks above.
    private static byte[] pngHeader(int width, int height) {
        return ByteBuffer.allocate(24).putLong(-8552249625308161526L).putInt(13).putInt(1229472850).putInt(width).putInt(height).array();
    }

    public void beginScene(RenderTarget target, CameraRenderState camera, Matrix4fc view, Object world) {
        this.allocate(target.width, target.height);
        this.sceneCapture.beginFrame();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        for (String name : this.graph.sceneTargets()) {
            encoder.clearColorTexture(this.image(name).texture, new Vector4f(0.0F));
        }

        this.sceneUniforms = this.frame.upload(encoder, camera, view, this.width, this.height, this.frame.discontinuity(camera, world, view));
        this.windBytes.clear();
        this.windBytes
            .putFloat((float)(camera.pos.x - Math.floor(camera.pos.x / 4096.0) * 4096.0))
            .putFloat((float)(camera.pos.y - Math.floor(camera.pos.y / 4096.0) * 4096.0))
            .putFloat((float)(camera.pos.z - Math.floor(camera.pos.z / 4096.0) * 4096.0))
            .putFloat((float)(System.nanoTime() - WIND_START) * 1.0E-9F);
        this.windBytes.putFloat(this.graph.options().getOrDefault("VEGETATION_WIND", 0.0).floatValue()).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).flip();
        this.windUniforms = encoder.transientMemory()
            .uploadGpu(this.windBytes, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
    }

    public GpuTextureView weatherView() {
        return this.sceneCapture.weatherView();
    }

    public void handProjection(Matrix4fc projection) {
        this.frame.handProjection(projection);
    }

    public void projection(Matrix4fc projection) {
        this.frame.projection(projection);
    }

    public void environment(LevelRenderState state, ClientLevel level, float partialTick) {
        this.frame.environment(state, level, partialTick);
    }

    public boolean replacesClouds() {
        return this.graph.replacesClouds();
    }

    public PackGraph.Environment environment() {
        return this.graph.environment();
    }

    public int shadowQuality() {
        return this.graph.shadowQuality();
    }

    public int shadowDistance() {
        return this.graph.shadowDistance();
    }

    public boolean animatedShadowCasters() {
        return this.graph.options().getOrDefault("VEGETATION_WIND", 0.0) > 0.0;
    }

    public RenderPipeline scenePipeline(RenderPipeline original) {
        return this.scenePrograms.replace(original, !this.graph.sceneTargets().isEmpty(), false);
    }

    public RenderPipeline scenePipeline(RenderPipeline original, boolean attachments, boolean hdr) {
        return this.scenePrograms.replace(original, attachments, hdr);
    }

    public RenderPipeline sceneFallback(RenderPipeline original, boolean attachments, boolean hdr) {
        return this.scenePrograms.fallback(original, attachments, hdr);
    }

    /** Whether a render pass draws into the HDR scene target (its first color attachment). */
    public boolean isHdrPass(List<Attachment<Optional<Vector4fc>>> attachments) {
        return this.hdrTexture != null
            && !attachments.isEmpty()
            && attachments.getFirst() != null
            && attachments.getFirst().textureView().texture() == this.hdrTexture;
    }

    public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor, RenderTarget main) {
        if (descriptor.colorAttachments().size() != 1 || descriptor.colorAttachments().getFirst() == null) {
            return descriptor;
        }

        Attachment<Optional<Vector4fc>> first = (Attachment<Optional<Vector4fc>>)descriptor.colorAttachments().getFirst();
        if (first.textureView().texture() != main.getColorTexture()) {
            return descriptor;
        }

        if (this.hdrView != null) {
            first = new Attachment<>(this.hdrView, first.clearValue());
        }

        if (this.graph.sceneTargets().isEmpty() && this.hdrView == null) {
            return descriptor;
        }

        ArrayList<Attachment<Optional<Vector4fc>>> attachments = new ArrayList<>();
        attachments.add(first);

        for (String name : this.graph.sceneTargets()) {
            attachments.add(new Attachment(this.image(name).attachment, Optional.empty()));
        }

        return new RenderPassDescriptor(descriptor.label(), attachments, descriptor.depthAttachment(), descriptor.renderArea());
    }

    public boolean hasSceneAttachments(List<Attachment<Optional<Vector4fc>>> attachments) {
        return !this.graph.sceneTargets().isEmpty()
            && attachments.size() == this.graph.sceneTargets().size() + 1
            && attachments.get(1) != null
            && attachments.get(1).textureView() == this.image(this.graph.sceneTargets().getFirst()).attachment;
    }

    public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
        if (ScenePrograms.isReplacement(pipeline) && this.sceneUniforms != null && this.sceneUniforms.buffer().isClosed()) {
            throw new IllegalStateException("Scene uniforms expired before terrain rendering");
        }

        if (this.windUniforms != null
            && (ScenePrograms.isReplacement(pipeline) || pipeline.getShaderDefines().flags().contains("SULKAN_VEGETATION"))
            && this.windUniforms.buffer().isClosed()) {
            throw new IllegalStateException("Wind uniforms expired before geometry rendering");
        }

        if (this.windUniforms != null && (ScenePrograms.isReplacement(pipeline) || pipeline.getShaderDefines().flags().contains("SULKAN_VEGETATION"))) {
            pass.setUniform("SulkanWind", this.windUniforms);
        }

        if (ScenePrograms.isReplacement(pipeline) && this.sceneUniforms != null) {
            pass.setUniform("SulkanFrame", this.sceneUniforms);
        }

        if (ScenePrograms.isReplacement(pipeline) && this.graph.shadowQuality() > 0) {
            ShadowService.bindTerrain(pass, this.translucentShadows());
        }

        ScenePrograms.textures(pipeline)
            .forEach(
                (binding, asset) -> pass.setUniform(
                    binding,
                    GraphSceneCapture.isInput(asset) ? this.sceneCapture.view(asset) : this.customTextures.get(asset).view,
                    GraphSceneCapture.isInput(asset) ? this.sampler : this.textureSamplers.get(asset)
                )
            );
    }

    private static void destroy(Map<String, GraphRenderer.Image[]> targets) {
        Set<GraphRenderer.Image[]> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(targets.values());
        unique.forEach(pair -> {
            for (GraphRenderer.Image image : pair) {
                if (image != null) {
                    image.close();
                }
            }
        });
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.scenePrograms != null) {
                this.scenePrograms.close();
            }

            if (this.buffers != null) {
                this.buffers.close();
            }

            if (this.sceneCapture != null) {
                this.sceneCapture.close();
            }

            if (this.hdrTexture != null) {
                this.hdrView.close();
                this.hdrTexture.close();
                this.hdrTexture = null;
                this.hdrView = null;
            }

            this.computePipelines.values().forEach(ComputeProgram::close);
            this.computePipelines.clear();
            this.customTextures.values().forEach(GraphRenderer.Image::close);
            this.customTextures.clear();
            this.textureSamplers.values().forEach(UncheckedAutoCloseable::close);
            this.textureSamplers.clear();
            destroy(this.images);
            this.images.clear();
            this.sampler.close();
            if (this.linearSampler != null) {
                this.linearSampler.close();
            }

            this.ownedPipelines.forEach(GraphShaderSources::remove);
            this.ownedPipelines.clear();
        }
    }

    private record Image(GpuTexture texture, GpuTextureView view, GpuTextureView attachment) implements AutoCloseable {
        Image(GpuTexture texture, GpuTextureView view) {
            this(texture, view, view);
        }

        @Override
        public void close() {
            if (this.attachment != this.view) {
                this.attachment.close();
            }

            this.view.close();
            this.texture.close();
        }
    }
}
