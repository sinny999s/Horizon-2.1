package dev.horizon.input.core;

public interface EventHandler {
    boolean canPollEvents();
    void pollEvents();

    default void afterMainThreadTask() {}
}
