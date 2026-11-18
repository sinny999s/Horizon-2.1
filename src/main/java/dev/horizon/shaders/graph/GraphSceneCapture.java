package dev.horizon.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.joml.Vector4f;

final class GraphSceneCapture implements AutoCloseable {
    static final String COLOR = "$terrainOpaque";
    static final String DEPTH = "$terrainOpaqueDepth";
    static final String WORLD_DEPTH = "$depth";
    static final String WEATHER = "$weather";
    private final Map<String, GpuFormat> formats = new LinkedHashMap<>();
    private Map<String, GraphSceneCapture.Image> images = new LinkedHashMap<>();
    private int width;
    private int height;
    private boolean captured;
    private boolean worldDepthCaptured;
    private long captures;

    static boolean isInput(String name) {
        return "$terrainOpaque".equals(name) || "$terrainOpaqueDepth".equals(name);
    }

    GraphSceneCapture(PackGraph graph, RenderTarget main) {
        Set<String> used = new HashSet<>();
        graph.schedule().forEach(pass -> used.addAll(pass.reads().values()));
        graph.activeScenePrograms().forEach(program -> used.addAll(program.textures().values()));
        if (used.contains("$weather")) {
            this.formats.put("$weather", main.getColorTexture().getFormat());
        }

        if (used.contains("$terrainOpaque")) {
            this.formats.put("$terrainOpaque", graph.environment().hdr() ? GraphRenderer.HDR_FORMAT : main.getColorTexture().getFormat());
        }

        if (used.contains("$terrainOpaqueDepth")) {
            if (main.getDepthTexture() == null) {
                throw new IllegalArgumentException("Terrain depth capture requires a depth attachment");
            }

            this.formats.put("$terrainOpaqueDepth", main.getDepthTexture().getFormat());
        }

        if (used.contains("$depth")) {
            if (main.getDepthTexture() == null) {
                throw new IllegalArgumentException("World depth capture requires a depth attachment");
            }

            this.formats.put("$depth", main.getDepthTexture().getFormat());
        }
    }

    long bytes(int width, int height) {
        return this.formats.values().stream().mapToLong(format -> (long)width * height * format.blockSize()).sum();
    }

    long captures() {
        return this.captures;
    }

    void resize(int width, int height) {
        if (this.width != width || this.height != height) {
            Map<String, GraphSceneCapture.Image> fresh = new LinkedHashMap<>();
            GpuDevice device = RenderSystem.getDevice();

            try {
                for (Entry<String, GpuFormat> entry : this.formats.entrySet()) {
                    GpuFormat format = entry.getValue();
                    if (Math.max(width, height) > device.getDeviceInfo().limits().maxTextureSizeForFormat(format)
                        || (long)width * height * format.blockSize() > device.getDeviceInfo().limits().maxMemoryAllocationSize()) {
                        throw new IllegalArgumentException("Terrain snapshot exceeds device limits: " + entry.getKey());
                    }

                    GpuTexture texture = device.createTexture("Sulkan " + entry.getKey(), 13, format, width, height, 1, 1);

                    try {
                        fresh.put(entry.getKey(), new GraphSceneCapture.Image(texture, device.createTextureView(texture)));
                    } catch (Exception failure) {
                        texture.close();
                        throw failure;
                    }
                }
            } catch (Exception failure) {
                fresh.values().forEach(GraphSceneCapture.Image::close);
                throw failure;
            }

            Map<String, GraphSceneCapture.Image> old = this.images;
            this.images = fresh;
            if (!old.isEmpty()) {
                RenderSystem.queueFencedTask(() -> old.values().forEach(GraphSceneCapture.Image::close));
            }

            this.width = width;
            this.height = height;
            this.captured = false;
            this.worldDepthCaptured = false;
        }
    }

    void beginFrame() {
        this.captured = false;
        this.worldDepthCaptured = false;
        GraphSceneCapture.Image weather = this.images.get("$weather");
        if (weather != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(weather.texture, new Vector4f(0.0F));
        }
    }

    void clearMissedCapture() {
        if (!this.captured) {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            GraphSceneCapture.Image color = this.images.get("$terrainOpaque");
            GraphSceneCapture.Image depth = this.images.get("$terrainOpaqueDepth");
            if (color != null) {
                encoder.clearColorTexture(color.texture, new Vector4f(0.0F));
            }

            if (depth != null) {
                encoder.clearDepthTexture(depth.texture, 1.0);
            }
        }
    }

    void capture(RenderTarget source, GpuTexture color) {
        if (!this.captured && (this.images.containsKey("$terrainOpaque") || this.images.containsKey("$terrainOpaqueDepth"))) {
            if (source.width != this.width || source.height != this.height) {
                throw new IllegalStateException("Terrain snapshot size changed during scene rendering");
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            this.images
                .forEach(
                    (name, image) -> {
                        if (name.equals("$terrainOpaque") || name.equals("$terrainOpaqueDepth")) {
                            encoder.copyTextureToTexture(
                                name.equals("$terrainOpaqueDepth") ? source.getDepthTexture() : color,
                                image.texture,
                                0,
                                0,
                                0,
                                0,
                                0,
                                this.width,
                                this.height
                            );
                        }
                    }
                );
            this.captured = true;
            this.captures++;
        }
    }

    void captureWorldDepth(RenderTarget source) {
        GraphSceneCapture.Image image = this.images.get("$depth");
        if (!this.worldDepthCaptured && image != null) {
            if (source.width != this.width || source.height != this.height) {
                throw new IllegalStateException("World depth size changed during scene rendering");
            }

            RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToTexture(source.getDepthTexture(), image.texture, 0, 0, 0, 0, 0, this.width, this.height);
            this.worldDepthCaptured = true;
        }
    }

    GpuTextureView weatherView() {
        GraphSceneCapture.Image image = this.images.get("$weather");
        return image == null ? null : image.view;
    }

    GpuTextureView view(String name) {
        return this.images.get(name).view;
    }

    @Override
    public void close() {
        this.images.values().forEach(GraphSceneCapture.Image::close);
        this.images.clear();
    }

    private record Image(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override
        public void close() {
            this.view.close();
            this.texture.close();
        }
    }
}
