package dev.horizon.input.core.glfw.state_caching.window;

import dev.horizon.input.core.glfw.state_caching.GlfwCache;

public abstract class GlfwWindowCache extends GlfwCache {
    protected long window;

    protected GlfwWindowCache(long window) {
        super();
        this.window = window;
    }
}
