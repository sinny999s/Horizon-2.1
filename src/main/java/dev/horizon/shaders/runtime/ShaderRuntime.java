package dev.horizon.shaders.runtime;

import com.mojang.logging.LogUtils;
import dev.horizon.shaders.config.ShaderConfig;
import dev.horizon.shaders.graph.GraphRenderer;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.graph.PackNotices;
import dev.horizon.shaders.pack.LegacyPackMigration;
import dev.horizon.shaders.pack.ShaderPackScanner;
import dev.horizon.shaders.render.shadow.DirectionalShadowPipelines;
import dev.horizon.shaders.render.shadow.DirectionalShadowRenderer;
import dev.horizon.shaders.render.shadow.SodiumShadowTerrainRenderer;
import dev.horizon.shaders.render.shadow.SodiumTerrainShadowPipelines;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class ShaderRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ShaderConfig config = new ShaderConfig();
    private static List<ShaderPackScanner.AvailableShaderPack> packs = List.of();
    private static volatile boolean resourceReloading;

    private ShaderRuntime() {
    }

    public static void init() {
        config = ShaderConfig.load();
        ShaderPackScanner.ensureShaderPackDirectory();
        packs = ShaderPackScanner.listAvailablePacks();
        if (!ShaderPackScanner.isKnownPackId(config.selectedPackId(), packs)) {
            config = config.withSelection(config.enabled(), "__builtin__");
            config.save();
        }
    }

    public static void bootstrap(Minecraft minecraft) {
        applyConfig(minecraft, config, true).whenComplete((ignored, failure) -> {
            if (failure != null) {
                LOGGER.error("Failed to load the selected Sulkan shader pack", failure);
            }
        });
        if (!BackendStatus.vulkanActive()) {
            LOGGER.warn(
                "Sulkan shaders paused: Minecraft is using a non-Vulkan backend. See Video Settings to select Vulkan and restart; unsupported devices can continue with OpenGL."
            );
        }

        LOGGER.info(
            "Sulkan Shaders bootstrap complete: enabled={}, selectedPack={}, externalPacks={}",
            new Object[]{config.enabled(), config.selectedPackId(), packs.size()}
        );
    }

    public static ShaderConfig config() {
        return config;
    }

    public static List<ShaderPackScanner.AvailableShaderPack> packs() {
        return packs;
    }

    public static boolean shadersEnabled() {
        return config.enabled() && BackendStatus.vulkanActive();
    }

    public static boolean resourceReloading() {
        return resourceReloading;
    }

    public static ShaderPackScanner.ScanResult reloadPacks() {
        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan();
        packs = scan.supportedPacks();
        return scan;
    }

    public static CompletableFuture<Void> applySelection(Minecraft minecraft, boolean enabled, String selectedPackId) {
        reloadPacks();
        String normalizedPackId = normalizePackId(selectedPackId);
        return applyConfig(minecraft, config.withSelection(enabled, normalizedPackId), true);
    }

    public static CompletableFuture<Void> applyConfig(Minecraft minecraft, ShaderConfig nextConfig, boolean forcePackRebuild) {
        if (resourceReloading) {
            return CompletableFuture.failedFuture(new IllegalStateException("A shader reload is already in progress"));
        }

        ShaderConfig previousConfig = config;
        GraphRenderer candidate = null;

        try {
            candidate = NativePackRuntime.prepare(nextConfig);
            GraphRenderer prepared = candidate;
            return applyConfigInternal(minecraft, nextConfig, forcePackRebuild).whenComplete((ignored, failurex) -> {
                if (failurex == null) {
                    NativePackRuntime.activate(prepared, nextConfig.selectedPackId());
                } else {
                    if (prepared != null) {
                        prepared.close();
                    }

                    config = previousConfig;
                    config.save();
                }
            });
        } catch (Exception failure) {
            if (candidate != null) {
                candidate.close();
            }

            resourceReloading = false;
            config = previousConfig;
            LOGGER.error("Failed to apply Sulkan shader settings", failure);
            PackNotices.failed("Shader pack failed to load", failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static CompletableFuture<Void> applyConfigInternal(Minecraft minecraft, ShaderConfig nextConfig, boolean forcePackRebuild) {
        config = nextConfig;
        boolean reloadRequired = LegacyPackMigration.detach(minecraft);
        reloadRequired |= forcePackRebuild && shadersEnabled();
        config.save();
        minecraft.options.save();
        if (!reloadRequired) {
            invalidateTerrainRenderer(minecraft);
            return CompletableFuture.completedFuture(null);
        } else {
            resourceReloading = true;
            closeReloadableResources();
            return minecraft.reloadResourcePacks().handleAsync((ignored, throwable) -> {
                try {
                    invalidateTerrainRenderer(minecraft);
                    if (throwable != null) {
                        throw new CompletionException(throwable);
                    } else {
                        return (Void)null;
                    }
                } finally {
                    resourceReloading = false;
                }
            }, minecraft);
        }
    }

    public static void close() {
        NativePackRuntime.close();
        DirectionalShadowPipelines.close();
        DirectionalShadowRenderer.close();
        SodiumShadowTerrainRenderer.close();
        SodiumTerrainShadowPipelines.close();
        packs = List.of();
    }

    private static void closeReloadableResources() {
        DirectionalShadowPipelines.close();
        DirectionalShadowRenderer.close();
        SodiumShadowTerrainRenderer.close();
        SodiumTerrainShadowPipelines.close();
    }

    private static void invalidateTerrainRenderer(Minecraft minecraft) {
        NativePackRuntime.requestGeometryRebuild();
    }

    private static String normalizePackId(String selectedPackId) {
        if (selectedPackId != null && !selectedPackId.isBlank() && !"__builtin__".equals(selectedPackId)) {
            return findPack(selectedPackId) == null ? "__builtin__" : selectedPackId;
        } else {
            return "__builtin__";
        }
    }

    private static ShaderPackScanner.AvailableShaderPack findPack(String selectedPackId) {
        for (ShaderPackScanner.AvailableShaderPack pack : packs) {
            if (pack.id().equals(selectedPackId)) {
                return pack;
            }
        }

        return null;
    }
}
