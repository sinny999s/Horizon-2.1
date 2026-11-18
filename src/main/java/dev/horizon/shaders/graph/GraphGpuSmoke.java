package dev.horizon.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.RenderTargetDescriptor.TextureProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor.Builder;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

public final class GraphGpuSmoke {
    private static int step;
    private static boolean pending;
    private static boolean worldSetup;
    private static boolean worldReload;
    private static GraphRenderer renderer;
    private static RenderTarget target;
    private static GpuBuffer sceneVertices;
    private static RenderPipeline scene;
    private static final CameraRenderState CAMERA = new CameraRenderState();

    private GraphGpuSmoke() {
    }

    public static void tick(Minecraft client) {
        if (Boolean.getBoolean("sulkan.reflectionSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            WaterReflectionSmoke.tick(client);
        } else if (Boolean.getBoolean("sulkan.environmentSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            NativeEnvironmentSmoke.tick(client);
        } else if (Boolean.getBoolean("sulkan.shadowSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            NativeShadowSmoke.tick(client);
        } else if (Boolean.getBoolean("sulkan.reloadShadowSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ReloadShadowSmoke.tick(client);
        } else if (Boolean.getBoolean("sulkan.switchSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            PackSwitchSmoke.tick(client);
        } else if (Boolean.getBoolean("sulkan.worldSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment() && !pending) {
            if (!worldSetup && NativePackRuntime.renderedFrames() >= 40L) {
                worldSetup = true;
                setupDemoScene(client);
            }

            if (!worldReload && Boolean.getBoolean("sulkan.worldSmokeReload") && NativePackRuntime.renderedFrames() >= 160L) {
                worldReload = true;
                ShaderRuntime.applyConfig(client, ShaderRuntime.config(), true).whenComplete((ignored, error) -> {
                    if (error != null) {
                        LogUtils.getLogger().error("SULKAN_WORLD_SMOKE_FAIL reload", error);
                        pending = true;
                        client.stop();
                    } else {
                        LogUtils.getLogger().info("SULKAN_WORLD_RELOAD_PASS");
                    }
                });
            } else if (NativePackRuntime.failure() != null) {
                LogUtils.getLogger().error("SULKAN_WORLD_SMOKE_FAIL {}", NativePackRuntime.failure());
                pending = true;
                client.stop();
            } else if (NativePackRuntime.renderedFrames() >= Integer.getInteger("sulkan.worldSmokeFrames", 120).intValue()) {
                pending = true;
                String screenshot = System.getProperty("sulkan.worldSmokeImage");
                if (screenshot == null) {
                    finishWorldSmoke(client);
                } else {
                    Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                        try {
                            NativeImage twrVar0$ = image;

                            try {
                                Path path = Path.of(screenshot).toAbsolutePath();
                                Files.createDirectories(path.getParent());
                                image.writeToFile(path);
                                finishWorldSmoke(client);
                            } catch (Throwable t$) {
                                if (twrVar0$ != null) {
                                    try {
                                        twrVar0$.close();
                                    } catch (Throwable x2) {
                                        t$.addSuppressed(x2);
                                    }
                                }

                                throw t$;
                            }

                            if (twrVar0$ != null) {
                                twrVar0$.close();
                            }
                        } catch (Exception failure) {
                            LogUtils.getLogger().error("SULKAN_WORLD_SMOKE_FAIL screenshot", failure);
                            client.stop();
                        }
                    });
                }
            }
        } else {
            String source = System.getProperty("sulkan.graphSmoke");
            if (source != null && FabricLoader.getInstance().isDevelopmentEnvironment() && client.gui.overlay() == null && !pending) {
                try {
                    if (step == 0 || step == 2) {
                        if (step == 0) {
                            checkMaterialEncoding();
                        }

                        if (target != null) {
                            target.destroyBuffers();
                        }

                        int size = step == 0 ? 16 : 32;
                        RenderTargetDescriptor descriptor = new RenderTargetDescriptor(
                            size, size, new TextureProperties(new Vector4f(0.0F), GpuFormat.RGBA8_UNORM), TextureProperties.DEFAULT_DEPTH
                        );
                        target = descriptor.allocate();
                        descriptor.prepare(target);
                        if (renderer == null) {
                            PackFiles files = PackFiles.read(Path.of(source));
                            renderer = new GraphRenderer(PackGraph.parse(files.text("sulkan.json")), files, target);
                            scene = RenderPipeline.builder(new Snippet[]{RenderPipelines.POST_PROCESSING_SNIPPET})
                                .withColorTargetState(ColorTargetState.DEFAULT)
                                .withVertexBinding(0, new MaterialChunkVertex(renderer.materials()).getVertexFormat())
                                .withLocation(Identifier.fromNamespaceAndPath("sulkan_smoke", "scene"))
                                .withVertexShader("unused")
                                .withFragmentShader("unused")
                                .build();
                            NativePackRuntime.activate(renderer, "__gpu_conformance__");
                        }

                        CAMERA.pos = Vec3.ZERO;
                        CAMERA.projectionMatrix = new Matrix4f();
                    }

                    CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                    encoder.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0.25F, 0.5F, 0.75F, 1.0F), target.getDepthTexture(), 1.0);
                    CAMERA.pos = new Vec3(step & 1, 0.0, 0.0);
                    renderer.beginScene(target, CAMERA, new Matrix4f(), GraphGpuSmoke.class);
                    if (step < 4 && (step & 1) == 0) {
                        encoder.clearDepthTexture(target.getDepthTexture(), 0.25);
                        renderer.captureTerrain(target);
                        encoder.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(1.0F, 0.0F, 0.0F, 1.0F), target.getDepthTexture(), 1.0);
                        renderer.captureTerrain(target);
                    }

                    if (step < 4) {
                        Builder sceneDescriptor = RenderPassDescriptor.builder(() -> "Native scene conformance")
                            .withColorAttachment(target.getColorTextureView())
                            .withRenderArea(new RenderArea(0, 0, target.width, target.height));
                        RenderPassDescriptor scenePass = renderer.sceneAttachments(sceneDescriptor.build(), target);
                        NativePackRuntime.scope(true);

                        try {
                            RenderPass pass = encoder.createRenderPass(scenePass);

                            try {
                                pass.setPipeline(RenderSystem.getCompiledPipeline(renderer.scenePipeline(scene)));
                                RenderSystem.bindDefaultUniforms(pass);
                                pass.setVertexBuffer(0, sceneVertices.slice());
                                pass.draw(3, 1, 0, 0);
                            } catch (Throwable var14) {
                                if (pass != null) {
                                    try {
                                        pass.close();
                                    } catch (Throwable var13) {
                                        var14.addSuppressed(var13);
                                    }
                                }

                                throw var14;
                            }

                            if (pass != null) {
                                pass.close();
                            }
                        } finally {
                            NativePackRuntime.scope(false);
                        }

                        encoder.clearColorTexture(target.getColorTexture(), new Vector4f(1.0F, 0.0F, 0.0F, 1.0F));
                    }

                    encoder.clearDepthTexture(target.getDepthTexture(), 0.375);
                    renderer.captureWorldDepth(target);
                    encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
                    renderer.render(target, CAMERA, new Matrix4f(), GraphGpuSmoke.class);
                    GpuBuffer readback = RenderSystem.getDevice().createBuffer(() -> "Graph smoke readback", 9, target.width * target.height * 4L);
                    pending = true;
                    encoder.copyTextureToBuffer(
                        target.getColorTexture(),
                        readback,
                        0L,
                        () -> {
                            try {
                                MappedView mapped = readback.map(true, false);

                                try {
                                    ByteBuffer bytes = mapped.data();
                                    int[] expected = new int[]{64, 128, 191, 255};

                                    for (int pixel = 0; pixel < bytes.remaining() / 4; pixel++) {
                                        for (int channel = 0; channel < 4; channel++) {
                                            int actual = Byte.toUnsignedInt(bytes.get(pixel * 4 + channel));
                                            if (Math.abs(actual - expected[channel]) > 2) {
                                                throw new IllegalStateException(
                                                    "RGBA="
                                                        + List.<Integer>of(
                                                            Byte.toUnsignedInt(bytes.get(pixel * 4)),
                                                            Byte.toUnsignedInt(bytes.get(pixel * 4 + 1)),
                                                            Byte.toUnsignedInt(bytes.get(pixel * 4 + 2)),
                                                            Byte.toUnsignedInt(bytes.get(pixel * 4 + 3))
                                                        )
                                                        + " Pixel "
                                                        + pixel
                                                        + " channel "
                                                        + channel
                                                        + " expected "
                                                        + expected[channel]
                                                        + " got "
                                                        + actual
                                                );
                                            }
                                        }
                                    }

                                    LogUtils.getLogger().info("SULKAN_GRAPH_SMOKE_PASS frame={} extent={}", step, target.width);
                                    step++;
                                } catch (Throwable t$) {
                                    if (mapped != null) {
                                        try {
                                            mapped.close();
                                        } catch (Throwable x2) {
                                            t$.addSuppressed(x2);
                                        }
                                    }

                                    throw t$;
                                }

                                if (mapped != null) {
                                    mapped.close();
                                }
                            } catch (Exception failure) {
                                LogUtils.getLogger().error("SULKAN_GRAPH_SMOKE_FAIL", failure);
                                step = 6;
                            } finally {
                                readback.close();
                                pending = false;
                            }

                            if (step >= 6) {
                                renderer.close();
                                sceneVertices.close();
                                target.destroyBuffers();
                                client.stop();
                                pending = true;
                            }
                        },
                        0
                    );
                    encoder.submit();
                } catch (Exception failure) {
                    LogUtils.getLogger().error("SULKAN_GRAPH_SMOKE_FAIL", failure);
                    pending = true;
                    client.stop();
                }
            }
        }
    }

    private static void finishWorldSmoke(Minecraft client) {
        LogUtils.getLogger()
            .info(
                "SULKAN_WORLD_SMOKE_PASS frames={} terrainCaptures={} scenePipelines={}",
                new Object[]{NativePackRuntime.renderedFrames(), NativePackRuntime.terrainCaptures(), NativePackRuntime.sceneReplacementCount()}
            );
        client.stop();
    }

    private static void setupDemoScene(Minecraft client) {
        String preset = System.getProperty("sulkan.demoScene");
        if (preset != null) {
            IntegratedServer server = client.getSingleplayerServer();
            if (server != null && server.getWorldData().getLevelName().equals("Sulkan QA")) {
                server.execute(() -> {
                    Commands commands = server.getCommands();
                    CommandSourceStack source = server.createCommandSourceStack();
                    commands.performPrefixedCommand(source, "weather clear");
                    commands.performPrefixedCommand(source, "time set " + (preset.equals("night") ? "18000" : "6000"));
                    commands.performPrefixedCommand(source, "gamemode spectator @a");
                    if (preset.equals("land")) {
                        commands.performPrefixedCommand(source, "execute as @a at @s run tp @s ~ ~6 ~ ~180 15");
                    }

                    if (preset.equals("underwater")) {
                        commands.performPrefixedCommand(source, "execute as @a at @s run tp @s ^ ^-3 ^20");
                    }
                });
            } else {
                LogUtils.getLogger().error("SULKAN_WORLD_SMOKE_FAIL demo setup requires Sulkan QA world");
                pending = true;
                client.stop();
            }
        }
    }

    private static void checkMaterialEncoding() {
        MaterialTable table = MaterialTable.compile(Map.of("minecraft:stone", 123, "minecraft:grass_block", 4, "minecraft:grass_block[snowy=true]", 5));
        if (table.id((BlockState)Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true)) != 5) {
            throw new IllegalStateException("Material state selector was not applied");
        }

        Vertex[] vertices = Vertex.uninitializedQuad();

        for (int i = 0; i < 4; i++) {
            vertices[i].x = i;
            vertices[i].y = i + 1;
            vertices[i].z = i + 2;
            vertices[i].color = -8359872 + i;
            vertices[i].ao = 1.0F;
            vertices[i].u = i * 0.1F;
            vertices[i].v = i * 0.2F;
            vertices[i].light = 15728880;
        }

        long compact = MemoryUtil.nmemCalloc(1L, 80L);
        long extended = MemoryUtil.nmemCalloc(1L, 104L);

        try {
            new CompactChunkVertex().getEncoder().write(compact, 3, vertices, 7);
            MaterialContext.enter(Blocks.STONE.defaultBlockState());
            MemoryUtil.memPutLong(extended + 96L, 81985529216486895L);
            if (new MaterialChunkVertex(table).getEncoder().write(extended, 3, vertices, 7) != extended + 96L) {
                throw new IllegalStateException("Incorrect material vertex stride");
            }

            for (int vertex = 0; vertex < 4; vertex++) {
                for (int word = 0; word < 5; word++) {
                    if (MemoryUtil.memGetInt(compact + vertex * 20L + word * 4L) != MemoryUtil.memGetInt(extended + vertex * 24L + word * 4L)) {
                        throw new IllegalStateException("Material encoding corrupted compact attributes");
                    }
                }

                if (MemoryUtil.memGetInt(extended + vertex * 24L + 20L) != 123) {
                    throw new IllegalStateException("Incorrect encoded material ID");
                }
            }

            if (MemoryUtil.memGetLong(extended + 96L) != 81985529216486895L) {
                throw new IllegalStateException("Material encoding exceeded allocated vertices");
            }

            sceneVertices = RenderSystem.getDevice().createBuffer(() -> "Material conformance vertices", 32, MemoryUtil.memByteBuffer(extended, 96));
            LogUtils.getLogger().info("SULKAN_MATERIAL_ENCODING_PASS");
        } finally {
            MaterialContext.leave();
            MemoryUtil.nmemFree(compact);
            MemoryUtil.nmemFree(extended);
        }
    }
}
