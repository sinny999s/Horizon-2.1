package dev.horizon.input.core.glfw.state_caching;

import it.unimi.dsi.fastutil.longs.Long2ReferenceArrayMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMaps;
import dev.horizon.input.core.Ixeris;

import java.util.function.LongFunction;

public class GlfwCacheManager {
    private static final GlfwGlobalCacheManager globalCache = new GlfwGlobalCacheManager();
    private static final Long2ReferenceMap<GlfwWindowCacheManager> windowCaches = Long2ReferenceMaps.synchronize(new Long2ReferenceArrayMap<>(1));

    public static GlfwGlobalCacheManager getGlobalCache() {
        return globalCache;
    }

    public static boolean hasWindowCache(long window) {
        return window != 0L && (window == Ixeris.accessor.getMinecraftWindow() || windowCaches.containsKey(window));
    }

    public static GlfwWindowCacheManager getWindowCache(long window) {
        if (window == Ixeris.accessor.getMinecraftWindow()) {
            // Use LongFunction instead of Long2ReferenceFunction for compatibility with older fastutil
            return windowCaches.computeIfAbsent(window, (LongFunction<? extends GlfwWindowCacheManager>) GlfwWindowCacheManager::new);
        }
        return windowCaches.get(window);
    }

    public static void destroyWindowCache(long window) {
        windowCaches.remove(window);
    }
}
