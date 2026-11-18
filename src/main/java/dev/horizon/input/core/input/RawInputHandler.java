package dev.horizon.input.core.input;

import dev.horizon.input.core.Ixeris;
import dev.horizon.input.core.glfw.GlfwEventHandler;
import dev.horizon.input.core.input.win32.RawInputHandlerGlfwWin32;
import dev.horizon.input.core.util.PlatformHelper;

public interface RawInputHandler {
    void grab();
    void release();
    void pollEvents();
    void setCursorPos(double x, double y);
    boolean supported();

    static RawInputHandler create(long window) {
        if (PlatformHelper.isWindows() && Ixeris.getEventHandler() instanceof GlfwEventHandler) {
            return new RawInputHandlerGlfwWin32(window);
        }
        throw new IllegalStateException("Buffered raw input is not supported on current platform");
    }
}
