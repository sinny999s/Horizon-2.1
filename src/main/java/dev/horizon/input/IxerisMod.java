package dev.horizon.input;

import com.mojang.blaze3d.systems.RenderSystem;

public final class IxerisMod {
    public static final String MOD_ID = "horizon";

    public static Thread renderThread;

    public static volatile long lockedContext;

    public static void init() {
    }

    public static boolean isOnRenderThread() {
        if (renderThread == null) {
            return RenderSystem.isOnRenderThread();
        }
        else {
            return Thread.currentThread() == renderThread;
        }
    }
}