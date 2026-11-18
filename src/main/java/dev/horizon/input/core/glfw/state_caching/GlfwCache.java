package dev.horizon.input.core.glfw.state_caching;

import dev.horizon.input.core.Ixeris;

import java.util.concurrent.atomic.AtomicInteger;

public abstract class GlfwCache {
    protected AtomicInteger enabled;

    protected GlfwCache() {
        this.enabled = new AtomicInteger();
    }

    public boolean isCacheEnabled() {
        return enabled.get() > 0 && Ixeris.glfwInitialized;
    }

    public void enableCache() {
        enabled.getAndIncrement();
    }

    public void disableCache() {
        enabled.getAndDecrement();
    }
}
