package dev.horizon.shaders.render.sky;

import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3f;

/**
 * The sun and moon direction for a celestial angle. The path's yaw turns the rising point around the vertical axis
 * (-90 is vanilla's due east, Sulkan's default -45 the north-east) and its rotation tilts the path out of the vertical
 * plane, as a shader pack's sunPathRotation does. The active pack sets both (its "environment" block).
 */
public final class CustomCelestials {
    public static final float DEFAULT_YAW_DEGREES = -45.0F;
    public static final float DEFAULT_ROTATION_DEGREES = 0.0F;
    private static volatile float sinYaw;
    private static volatile float cosYaw;
    private static volatile float sinRotation;
    private static volatile float cosRotation;

    static {
        reset();
    }

    private CustomCelestials() {
    }

    public static void configure(float yawDegrees, float rotationDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double rotation = Math.toRadians(rotationDegrees);
        sinYaw = (float)Math.sin(yaw);
        cosYaw = (float)Math.cos(yaw);
        sinRotation = (float)Math.sin(rotation);
        cosRotation = (float)Math.cos(rotation);
    }

    public static void reset() {
        configure(DEFAULT_YAW_DEGREES, DEFAULT_ROTATION_DEGREES);
    }

    /**
     * The sun's angle for lighting and shadows. The End has no day: its light stays where noon's sun would be (straight
     * up, turned by the path's rotation), as shader packs on Iris see it, instead of following the Overworld's clock.
     */
    public static float sunAngle(SkyRenderState sky) {
        return sky.skybox == DimensionType.Skybox.END ? 0.0F : sky.sunAngle;
    }

    public static float moonAngle(SkyRenderState sky) {
        return sky.skybox == DimensionType.Skybox.END ? (float)Math.PI : sky.moonAngle;
    }

    public static void setCelestialDirection(float angleRadians, Vector3f output) {
        float horizontal = (float)Math.sin(angleRadians);
        float vertical = (float)Math.cos(angleRadians);
        output.set(
                horizontal * sinYaw - vertical * sinRotation * cosYaw,
                vertical * cosRotation,
                horizontal * cosYaw + vertical * sinRotation * sinYaw
            )
            .normalize();
    }
}
