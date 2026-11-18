package dev.horizon.shaders.graph;

/**
 * Terrain faces (a pack's {@code environment.terrainNormals}): what the chunk mesher knows about the quad it is writing,
 * handed to {@link MaterialChunkVertex} on the same thread, which packs it into the 4 bits Sodium's position words leave
 * free (see {@code sulkan/terrain-face.glsl}). Codes: bits 0-2 the face's direction (0 unknown, 1-6 down, up, north,
 * south, west, east as {@code Direction.ordinal() + 1}, 7 a slanted quad), bit 3 set when Minecraft shades the face as
 * if it pointed up (a model's {@code shade_direction_override: "up"}: plants, crops, torches, ladders, fire...).
 * A block quad brings its code ({@link #block}); a fluid only says it is one ({@link #fluid}): Sodium shades a fluid's
 * top and bottom as its top face. Anything else (translucent quads Sodium splits for sorting, other mods' geometry)
 * gets a code from its corners.
 */
public final class FaceContext {
    static final int NONE = 0;
    static final int BLOCK = 1;
    static final int FLUID = 2;
    public static final int SLANTED = 7;
    public static final int SHADED_UP = 8;
    private static final ThreadLocal<FaceContext.State> STATE = ThreadLocal.withInitial(FaceContext.State::new);

    private FaceContext() {
    }

    /** True while the active pack asked for terrain faces (so the hooks only work for packs that read them). */
    public static boolean wanted() {
        MaterialTable table = NativePackRuntime.materials();
        return table != null && table.enabled() && table.faceData;
    }

    /** A block quad's face code. */
    public static void block(int code) {
        FaceContext.State state = STATE.get();
        state.mode = BLOCK;
        state.code = code;
    }

    public static void fluid() {
        STATE.get().mode = FLUID;
    }

    public static void clear() {
        STATE.get().mode = NONE;
    }

    static FaceContext.State current() {
        return STATE.get();
    }

    /**
     * The face direction code (1-6) of a quad with this normal when it lies along the block grid, else {@link #SLANTED}.
     */
    public static int direction(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        float length = (float)Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0E-6F) {
            return SLANTED;
        }

        float largest = Math.max(ax, Math.max(ay, az));
        if (largest < length * 0.999F) {
            return SLANTED;
        }

        if (largest == ay) {
            return y < 0.0F ? 1 : 2;
        }

        if (largest == az) {
            return z < 0.0F ? 3 : 4;
        }

        return x < 0.0F ? 5 : 6;
    }

    static final class State {
        int mode;
        int code;
    }
}
