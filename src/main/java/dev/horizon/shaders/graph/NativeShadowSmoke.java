package dev.horizon.shaders.graph;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.horizon.shaders.render.shadow.DirectionalShadowRenderer;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;

final class NativeShadowSmoke {
    private static int phase;
    private static int ticks;
    private static boolean pending;
    private static boolean finished;
    private static int stabilitySamples;
    private static double previousLuminance;
    private static boolean sawCachedTerrain;

    static void tick(Minecraft client) {
        if (!finished && !pending && client.level != null && client.gui.overlay() == null && !ShaderRuntime.resourceReloading()) {
            try {
                if (NativePackRuntime.failure() != null) {
                    throw new IllegalStateException(NativePackRuntime.failure());
                }

                ticks++;
                if (ticks > 1200) {
                    throw new IllegalStateException("Shadow smoke timed out in phase " + phase);
                }

                if (phase == 0 && NativePackRuntime.renderedFrames() >= 120L) {
                    IntegratedServer server = client.getSingleplayerServer();
                    if (server == null || !Set.of("Sulkan QA", "Aster QA").contains(server.getWorldData().getLevelName())) {
                        throw new IllegalStateException("Requires disposable QA world");
                    }

                    pending = true;
                    server.execute(
                        () -> {
                            try {
                                CommandSourceStack source = server.createCommandSourceStack();

                                for (String command : new String[]{
                                    "weather clear",
                                    "time set 3000",
                                    "gamerule minecraft:advance_time false",
                                    "fill 176 94 614 208 108 648 minecraft:air",
                                    "fill 176 94 614 208 95 648 minecraft:smooth_quartz",
                                    "fill 187 96 626 187 99 626 minecraft:oak_log",
                                    "fill 185 100 624 189 101 628 minecraft:oak_leaves[persistent=true]",
                                    "fill 195 96 623 196 101 624 minecraft:stone",
                                    "fill 190 100 632 195 100 635 minecraft:stone",
                                    "fill 178 95 618 182 95 623 minecraft:grass_block",
                                    "fill 178 96 618 182 96 623 minecraft:short_grass",
                                    "summon minecraft:pig 192 96 628 {NoAI:1b,Invulnerable:1b}",
                                    "gamemode spectator @p",
                                    "tp @p 204 103 643 135 25"
                                }) {
                                    server.getCommands().performPrefixedCommand(source, command);
                                }

                                if (Boolean.getBoolean("sulkan.iceSmoke")) {
                                    for (String command : new String[]{
                                        "fill 176 159 614 208 159 648 minecraft:smooth_quartz",
                                        "fill 176 160 614 183 160 648 minecraft:ice",
                                        "fill 184 160 614 191 160 648 minecraft:packed_ice",
                                        "fill 192 160 614 199 160 648 minecraft:blue_ice",
                                        "fill 200 160 614 208 160 648 minecraft:frosted_ice",
                                        "fill 181 161 619 183 166 621 minecraft:red_concrete",
                                        "fill 190 161 619 192 167 621 minecraft:gold_block",
                                        "fill 199 161 619 201 166 621 minecraft:oak_leaves[persistent=true]",
                                        "tp @p 193 164 644 180 14"
                                    }) {
                                        server.getCommands().performPrefixedCommand(source, command);
                                    }
                                }

                                client.execute(() -> {
                                    pending = false;

                                    try {
                                        select(2, 1);
                                    } catch (Exception error) {
                                        fail(client, error);
                                    }
                                });
                            } catch (Throwable error) {
                                client.execute(() -> fail(client, error));
                            }
                        }
                    );
                } else if (phase == 1 && ticks >= 200) {
                    checkMaps(3);
                    if (Boolean.getBoolean("sulkan.iceSmoke")) {
                        capture(client, "ice-ssr-on", () -> {
                            NativePackRuntime.applyOptions(ShaderRuntime.config().selectedPackId(), Map.of("WATER_REFLECTION_QUALITY", 0.0));
                            phase = 8;
                            ticks = 0;
                        });
                    } else {
                        capture(client, "on", () -> select(0, 2));
                    }
                } else if (phase == 8 && NativePackRuntime.renderedFrames() >= 180L) {
                    capture(client, "ice-ssr-off", () -> {
                        finished = true;
                        LogUtils.getLogger().info("SULKAN_ICE_SMOKE_PASS reflections/on/off");
                        client.stop();
                    });
                } else if (phase == 2 && NativePackRuntime.renderedFrames() >= 180L) {
                    if (NativePackRuntime.shadowQuality() != 0) {
                        throw new IllegalStateException("Shadows not disabled");
                    }

                    capture(client, "off", () -> select(1, 3));
                } else if (phase == 3 && NativePackRuntime.renderedFrames() >= 180L) {
                    checkMaps(1);
                    select(3, 4);
                } else if (phase == 4 && NativePackRuntime.renderedFrames() >= 180L) {
                    checkMaps(4);
                    pending = true;
                    ShaderRuntime.applyConfig(client, ShaderRuntime.config(), true).whenComplete((ignored, error) -> {
                        if (error != null) {
                            fail(client, error);
                        } else {
                            pending = false;
                            phase = 5;
                            ticks = 0;
                        }
                    });
                } else if (phase == 5 && NativePackRuntime.renderedFrames() >= 180L) {
                    checkMaps(4);
                    pending = true;
                    IntegratedServer server = client.getSingleplayerServer();
                    server.execute(
                        () -> {
                            CommandSourceStack source = server.createCommandSourceStack();

                            for (String command : new String[]{
                                "gamemode creative @p", "tp @p 201 96 637 135 12", "item replace entity @p weapon.mainhand with minecraft:torch"
                            }) {
                                server.getCommands().performPrefixedCommand(source, command);
                            }

                            client.execute(() -> {
                                pending = false;
                                phase = 6;
                                ticks = 0;
                            });
                        }
                    );
                } else if (phase == 6 && ticks >= 80 && ticks % 4 == 0) {
                    checkMaps(4);
                    if (HeldLight.emission(client.player.getMainHandItem()) == 0) {
                        throw new IllegalStateException("Held-light fixture missing");
                    }

                    captureStability(client);
                } else if (phase == 7 && NativePackRuntime.renderedFrames() >= 60L) {
                    sawCachedTerrain = sawCachedTerrain | !DirectionalShadowRenderer.get().shouldUpdateCascade(3);
                    if (NativePackRuntime.renderedFrames() >= 180L) {
                        if (!sawCachedTerrain) {
                            throw new IllegalStateException("Static shadow maps are never reused");
                        }

                        finished = true;
                        LogUtils.getLogger().info("SULKAN_NATIVE_SHADOW_SMOKE_PASS medium/off/low/high/reload/held-light/wind/static-cache");
                        client.stop();
                    }
                }
            } catch (Throwable error) {
                fail(client, error);
            }
        }
    }

    private static void captureStability(Minecraft client) {
        pending = true;
        Screenshot.takeScreenshot(
            client.gameRenderer.mainRenderTarget(),
            image -> {
                try {
                    NativeImage twrVar0$ = image;

                    try {
                        double total = 0.0;
                        int samples = 0;

                        for (int y = 40; y < image.getHeight() - 40; y += 8) {
                            for (int x = 40; x < image.getWidth() - 40; x += 8) {
                                int pixel = image.getPixel(x, y);
                                total += (pixel >> 16 & 0xFF) + (pixel >> 8 & 0xFF) + (pixel & 0xFF);
                                samples++;
                            }
                        }

                        double luminance = total / (samples * 765.0);
                        if (luminance < 0.02 || stabilitySamples > 0 && (luminance < previousLuminance * 0.65 || luminance > previousLuminance * 1.35)) {
                            throw new IllegalStateException("Abrupt whole-frame brightness change: " + previousLuminance + " -> " + luminance);
                        }

                        previousLuminance = luminance;
                        Path directory = Path.of(System.getProperty("sulkan.smokeOutput", "../build/reports/smoke"));
                        Files.createDirectories(directory);
                        image.writeToFile(directory.resolve("sulkan-shadow-stability-" + stabilitySamples + ".png"));
                        LogUtils.getLogger().info("SULKAN_SHADOW_STABILITY sample={} luminance={}", stabilitySamples, luminance);
                        pending = false;
                        if (++stabilitySamples == 8) {
                            NativePackRuntime.applyOptions(
                                ShaderRuntime.config().selectedPackId(), Map.of("SHADOW_QUALITY", 3.0, "SHADOW_DISTANCE", 128.0, "VEGETATION_WIND", 0.0)
                            );
                            phase = 7;
                            ticks = 0;
                        }
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
                } catch (Throwable error) {
                    fail(client, error);
                }
            }
        );
    }

    private static void checkMaps(int count) {
        DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
        if (!shadows.resourcesReady() || shadows.activeCascadeCount() != count) {
            throw new IllegalStateException("Incorrect cascade allocation");
        }

        if (shadows.entityTarget(0).getDepthTexture().getWidth(0) <= 1 || !shadows.shouldUpdateEntityCascade(0)) {
            throw new IllegalStateException("Near entity shadows missing");
        }

        if (shadows.entityTarget(1).getDepthTexture().getWidth(0) != 1 || shadows.shouldUpdateEntityCascade(1)) {
            throw new IllegalStateException("Distant entity shadows unexpectedly enabled");
        }

        if (NativePackRuntime.animatedShadowCasters() && !shadows.animatedCasters()) {
            throw new IllegalStateException("Wind caster reused an old shadow map");
        }

        if (Math.abs(shadows.shadowDistance() - 128.0F) > 0.1) {
            throw new IllegalStateException("Incorrect shadow coverage");
        }

        LogUtils.getLogger().info("SULKAN_NATIVE_SHADOW_MAPS_PASS cascades={} distance={}", count, shadows.shadowDistance());
    }

    private static void select(int quality, int next) throws Exception {
        NativePackRuntime.applyOptions(
            ShaderRuntime.config().selectedPackId(), Map.of("SHADOW_QUALITY", (double)quality, "SHADOW_DISTANCE", 128.0, "VEGETATION_WIND", 1.0)
        );
        phase = next;
        ticks = 0;
    }

    private static void capture(Minecraft client, String suffix, NativeShadowSmoke.CheckedAction next) {
        pending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try {
                NativeImage twrVar0$ = image;

                try {
                    Path directory = Path.of(System.getProperty("sulkan.smokeOutput", "../build/reports/smoke"));
                    Files.createDirectories(directory);
                    image.writeToFile(directory.resolve("sulkan-shadow-fixture-" + suffix + ".png"));
                    pending = false;
                    next.run();
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
            } catch (Throwable error) {
                fail(client, error);
            }
        });
    }

    private static void fail(Minecraft client, Throwable error) {
        finished = true;
        LogUtils.getLogger().error("SULKAN_NATIVE_SHADOW_SMOKE_FAIL", error);
        client.stop();
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
