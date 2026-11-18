package dev.horizon.input.core;

import dev.horizon.input.core.util.PlatformHelper;

public class FlexibleThreadingManager {
    public static final Object CLIPBOARD_LOCK = new Object();
    public static final Object MONITOR_LOCK = new Object();
    public static final Object WINDOW_LOCK = new Object();

    public static boolean canUseFlexibleClipboard() {
        return PlatformHelper.isWindows();
    }
}
