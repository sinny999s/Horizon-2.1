package dev.horizon.shaders.graph;

public final class StorageImageScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 1);
    private final boolean previous = ACTIVE.get();
    private final int previousDepth = DEPTH.get();

    public StorageImageScope(boolean active, int depth) {
        ACTIVE.set(active);
        DEPTH.set(depth);
    }

    public static boolean active() {
        return ACTIVE.get();
    }

    public static int depth() {
        return DEPTH.get();
    }

    @Override
    public void close() {
        ACTIVE.set(this.previous);
        DEPTH.set(this.previousDepth);
    }
}
