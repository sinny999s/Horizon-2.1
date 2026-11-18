package dev.horizon.shaders;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/**
 * Whether Horizon's shaders (this package, built from Sulkan) run: they draw through Sodium's terrain renderer, so
 * Sodium must be installed and at least {@link #SODIUM} (its chunk uniforms changed in 0.9.3). Without it nothing here
 * is loaded and none of its mixins apply ({@link ShadersMixinPlugin}).
 */
public final class ShadersGate {
    /** The oldest Sodium the shaders and their packs are written for. */
    public static final String SODIUM = ">=0.9.3-beta.5";

    private static Boolean enabled;
    private static String reason = "";

    private ShadersGate() {
    }

    public static boolean enabled() {
        if (enabled == null) {
            enabled = compute();
            System.out.println("[Horizon] Shaders " + (enabled ? "on" : "off") + (reason.isEmpty() ? "" : ": " + reason));
        }
        return enabled;
    }

    /** Why the shaders are off, or empty. */
    public static String reason() {
        enabled();
        return reason;
    }

    private static boolean compute() {
        var sodium = FabricLoader.getInstance().getModContainer("sodium").orElse(null);
        if (sodium == null) {
            reason = "Sodium isn't installed";
            return false;
        }
        try {
            if (!VersionPredicate.parse(SODIUM).test(sodium.getMetadata().getVersion())) {
                reason = "Sodium " + sodium.getMetadata().getVersion().getFriendlyString() + " is older than " + SODIUM.substring(2);
                return false;
            }
        } catch (VersionParsingException e) {
            reason = "Sodium's version couldn't be read";
            return false;
        }
        return true;
    }
}
