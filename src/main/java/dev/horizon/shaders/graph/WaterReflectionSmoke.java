package dev.horizon.shaders.graph;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;

final class WaterReflectionSmoke {
    private static int phase = -1;
    private static int ticks;
    private static boolean pending;
    private static boolean finished;

    static void tick(Minecraft client) {
        if (!pending && !finished && client.level != null && client.gui.overlay() == null && !ShaderRuntime.resourceReloading()) {
            try {
                if (NativePackRuntime.failure() != null) {
                    throw new IllegalStateException(NativePackRuntime.failure());
                }

                if (phase < 0 && NativePackRuntime.renderedFrames() >= 120L) {
                    setup(client);
                    return;
                }

                if (phase >= 0 && client.player != null) {
                    client.player.setPos(216.0, 100.0, 657.0);
                    client.player.setYRot(180.0F);
                    client.player.setXRot(8.0F);
                    client.player.setDeltaMovement(0.0, 0.0, 0.0);
                }

                if (phase < 0 || ++ticks < 140) {
                    return;
                }

                pending = true;
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                    try {
                        NativeImage twrVar0$ = image;

                        try {
                            String name = switch (phase) {
                                case 0 -> "on";
                                case 1 -> "off";
                                case 2 -> "waves";
                                case 3 -> "no-clouds";
                                default -> "reload";
                            };
                            image.writeToFile(Path.of("../build/reports/smoke/sulkan-water-reflections-" + name + ".png"));
                            LogUtils.getLogger().info("SULKAN_WATER_REFLECTION_STEP {} frames={}", name, NativePackRuntime.renderedFrames());
                            ticks = 0;
                            pending = false;
                            switch (phase++) {
                                case 0:
                                    options(0, 0, 2);
                                    break;
                                case 1:
                                    options(3, 1, 2);
                                    break;
                                case 2:
                                    options(2, 0, 0);
                                    break;
                                case 3:
                                    options(1, 0, 2);
                                    pending = true;
                                    ShaderRuntime.applyConfig(client, ShaderRuntime.config(), true).whenComplete((ignored, error) -> {
                                        if (error != null) {
                                            fail(client, error);
                                        } else {
                                            pending = false;
                                        }
                                    });
                                    break;
                                default:
                                    finished = true;
                                    LogUtils.getLogger().info("SULKAN_WATER_REFLECTION_SMOKE_PASS");
                                    client.stop();
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
                });
            } catch (Throwable error) {
                fail(client, error);
            }
        }
    }

    private static void options(int quality, int waves, int clouds) throws Exception {
        NativePackRuntime.applyOptions(
            ShaderRuntime.config().selectedPackId(),
            Map.of("WATER_REFLECTION_QUALITY", (double)quality, "WATER_WAVES", (double)waves, "CLOUD_QUALITY", (double)clouds)
        );
    }

    private static void setup(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server != null && server.getWorldData().getLevelName().equals("Sulkan QA")) {
            pending = true;
            server.execute(
                () -> {
                    try {
                        CommandSourceStack source = server.createCommandSourceStack();

                        for (String command : new String[]{
                            "gamerule minecraft:advance_time false",
                            "time set 6000",
                            "weather clear",
                            "fill 198 94 618 234 104 662 minecraft:air",
                            "fill 198 105 618 234 115 662 minecraft:air",
                            "fill 200 94 620 232 98 660 minecraft:stone",
                            "fill 201 95 621 231 98 659 minecraft:water",
                            "fill 204 99 624 228 99 629 minecraft:smooth_quartz",
                            "fill 205 100 625 208 111 628 minecraft:red_concrete",
                            "fill 224 100 625 227 109 628 minecraft:blue_concrete",
                            "fill 213 106 626 219 108 627 minecraft:yellow_concrete",
                            "summon minecraft:pig 216 100 628 {NoAI:1b,NoGravity:1b,Invulnerable:1b}",
                            "gamemode spectator @p",
                            "tp @p 216 100 657 180 8"
                        }) {
                            server.getCommands().performPrefixedCommand(source, command);
                        }

                        client.execute(() -> {
                            try {
                                options(2, 0, 2);
                                phase = 0;
                                ticks = 0;
                                pending = false;
                            } catch (Exception error) {
                                fail(client, error);
                            }
                        });
                    } catch (Throwable error) {
                        client.execute(() -> fail(client, error));
                    }
                }
            );
        } else {
            throw new IllegalStateException("Requires disposable Sulkan QA world");
        }
    }

    private static void fail(Minecraft client, Throwable error) {
        finished = true;
        LogUtils.getLogger().error("SULKAN_WATER_REFLECTION_SMOKE_FAIL", error);
        client.stop();
    }
}
