package dev.horizon.shaders.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class ShaderConfig {
    public static final String BUILTIN_PACK_ID = "__builtin__";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "sulkan-shaders.json";
    private boolean enabled = true;
    private String selectedPackId = "__builtin__";

    public ShaderConfig() {
    }

    public ShaderConfig(boolean enabled, String selectedPackId) {
        this.enabled = enabled;
        this.selectedPackId = selectedPackId != null && !selectedPackId.isBlank() ? selectedPackId : "__builtin__";
    }

    public boolean enabled() {
        return this.enabled;
    }

    public String selectedPackId() {
        return this.selectedPackId;
    }

    public ShaderConfig withSelection(boolean enabled, String id) {
        return new ShaderConfig(enabled, id);
    }

    public static ShaderConfig load() {
        Path configPath = configPath();
        if (!Files.isRegularFile(configPath)) {
            return new ShaderConfig();
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            ShaderConfig loaded = (ShaderConfig)GSON.fromJson(reader, ShaderConfig.class);
            if (loaded != null) {
                return new ShaderConfig(loaded.enabled, loaded.selectedPackId);
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to load Sulkan shader settings from {}", configPath, exception);
        }

        return new ShaderConfig();
    }

    public void save() {
        Path configPath = configPath();

        try {
            Files.createDirectories(configPath.getParent());

            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to save Sulkan shader settings to {}", configPath, exception);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve("sulkan-shaders.json");
    }
}
