package dev.horizon.shaders.graph;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.horizon.shaders.render.shadow.SodiumShadowTerrainRenderer;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;

final class ReloadShadowSmoke {
    private static final String[] FIXTURE = new String[]{
        "weather clear",
        "time set 3000",
        "gamerule minecraft:advance_time false",
        "gamemode spectator @p",
        "tp @p 16 186 40 180 50",
        "fill 0 170 0 31 180 31 minecraft:air",
        "fill 0 170 0 31 170 31 minecraft:smooth_quartz",
        "fill 12 171 12 12 176 12 minecraft:stone",
        "fill 18 171 12 19 174 13 minecraft:stone_bricks",
        "fill 6 171 16 6 173 16 minecraft:glass",
        "summon minecraft:armor_stand 12 171 20 {Invulnerable:1b,NoGravity:1b}"
    };
    private static int phase;
    private static int ticks;
    private static boolean pending;
    private static boolean finished;
    private static double baseline = -1.0;

    static void tick(Minecraft client) {
        if (!finished && !pending && client.level != null && client.gui.overlay() == null && !ShaderRuntime.resourceReloading()) {
            try {
                if (NativePackRuntime.failure() != null) {
                    throw new IllegalStateException(NativePackRuntime.failure());
                }

                ticks++;
                if (phase == 0 && NativePackRuntime.renderedFrames() >= 20L) {
                    capture(client, "join", 1);
                } else if (phase == 1 && ticks >= 40) {
                    pending = true;
                    IntegratedServer server = client.getSingleplayerServer();
                    server.execute(() -> {
                        CommandSourceStack source = server.createCommandSourceStack();

                        for (String command : FIXTURE) {
                            server.getCommands().performPrefixedCommand(source, command);
                        }

                        client.execute(() -> advance(2));
                    });
                } else if (phase == 2 && ticks >= 400) {
                    capture(client, "before", 3);
                } else if (phase == 3) {
                    pending = true;
                    client.reloadResourcePacks().whenComplete((ignored, error) -> client.execute(() -> {
                        if (error != null) {
                            fail(client, error);
                        } else {
                            advance(4);
                        }
                    }));
                } else if (phase == 4 && ticks >= 200) {
                    capture(client, "after-reload", 5);
                } else if (phase == 5 && ticks >= 600) {
                    capture(client, "after-reload-late", 6);
                } else if (phase == 6) {
                    finished = true;
                    LogUtils.getLogger().info("SULKAN_RELOAD_SHADOW_SMOKE_DONE");
                    client.stop();
                }
            } catch (Throwable error) {
                fail(client, error);
            }
        }
    }

    private static void advance(int next) {
        pending = false;
        phase = next;
        ticks = 0;
    }

    private static void capture(Minecraft client, String name, int next) {
        pending = true;
        Screenshot.takeScreenshot(
            client.gameRenderer.mainRenderTarget(),
            image -> {
                try {
                    NativeImage twrVar0$ = image;

                    try {
                        int width = image.getWidth();
                        int height = image.getHeight();
                        double[] samples = new double[width / 4 * (height / 4)];
                        int count = 0;

                        for (int y = 0; y + 4 <= height; y += 4) {
                            for (int x = 0; x + 4 <= width; x += 4) {
                                int pixel = image.getPixel(x, y);
                                samples[count++] = ((pixel >> 16 & 0xFF) + (pixel >> 8 & 0xFF) + (pixel & 0xFF)) / 765.0;
                            }
                        }

                        double[] sorted = Arrays.copyOf(samples, count);
                        Arrays.sort(sorted);
                        double median = sorted[count / 2];
                        int dark = 0;

                        for (int i = 0; i < count; i++) {
                            if (samples[i] < median * 0.6) {
                                dark++;
                            }
                        }

                        double fraction = (double)dark / count;
                        if (name.equals("before")) {
                            baseline = fraction;
                        }

                        Path directory = Path.of(System.getProperty("sulkan.smokeOutput", "../build/reports/smoke"));
                        Files.createDirectories(directory);
                        image.writeToFile(directory.resolve("sulkan-reload-shadow-" + name + ".png"));
                        LogUtils.getLogger()
                            .info(
                                "SULKAN_RELOAD_SHADOW sample={} size={}x{} median={} shadowed={} baseline={} plans={}",
                                new Object[]{name, width, height, median, fraction, baseline, SodiumShadowTerrainRenderer.debugSummary()}
                            );
                        client.execute(() -> advance(next));
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

    private static void fail(Minecraft client, Throwable error) {
        finished = true;
        LogUtils.getLogger().error("SULKAN_RELOAD_SHADOW_SMOKE_FAIL", error);
        client.stop();
    }
}
