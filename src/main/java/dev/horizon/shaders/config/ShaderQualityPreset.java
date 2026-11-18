package dev.horizon.shaders.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;

public enum ShaderQualityPreset {
    OFF("off", 0),
    LOW("low", 1),
    MEDIUM("medium", 2),
    HIGH("high", 3),
    ULTRA("ultra", 4);

    private final String serializedName;
    private final int tier;

    ShaderQualityPreset(String serializedName, int tier) {
        this.serializedName = serializedName;
        this.tier = tier;
    }

    public String serializedName() {
        return this.serializedName;
    }

    public int tier() {
        return this.tier;
    }

    public boolean enabled() {
        return this != OFF;
    }

    public Component displayName() {
        return Component.literal(switch (this) {
            case OFF -> "Off";
            case LOW -> "Low";
            case MEDIUM -> "Medium";
            case HIGH -> "High";
            case ULTRA -> "Ultra";
        });
    }

    public static ShaderQualityPreset fromSerializedName(String serializedName) {
        if (serializedName != null && !serializedName.isBlank()) {
            String normalized = serializedName.toLowerCase(Locale.ROOT);

            for (ShaderQualityPreset preset : values()) {
                if (preset.serializedName.equals(normalized)) {
                    return preset;
                }
            }

            return MEDIUM;
        } else {
            return MEDIUM;
        }
    }
}
