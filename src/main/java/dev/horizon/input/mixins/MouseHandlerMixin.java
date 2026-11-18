package dev.horizon.input.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.horizon.input.VersionCompatUtils;
import dev.horizon.input.core.Ixeris;
import dev.horizon.input.core.glfw.GlfwEventHandler;
import dev.horizon.input.core.glfw.callback_dispatcher.CursorPosCallbackDispatcher;
import dev.horizon.input.core.threading.MainThreadDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;
    @Shadow
    private boolean mouseGrabbed;

    @WrapMethod(method = "grabMouse")
    private void ixeris$wrapGrabMouse(Operation<Void> original) {
        if (!(Ixeris.getEventHandler() instanceof GlfwEventHandler glfwEventHandler)) {
            original.call();
            return;
        }
        var shouldGrab = this.minecraft.isWindowActive() && !this.mouseGrabbed;
        var dispatcher = CursorPosCallbackDispatcher.get(VersionCompatUtils.getMinecraftWindow());
        if (shouldGrab) {
            dispatcher.suppressCallbacks();
        }

        original.call();

        if (shouldGrab) {
            glfwEventHandler.clearQueuedCursorPosCallbacks();
            MainThreadDispatcher.run(dispatcher::unsuppressCallbacks);
        }
    }

    @WrapMethod(method = "releaseMouse")
    private void ixeris$wrapReleaseMouse(Operation<Void> original) {
        if (!(Ixeris.getEventHandler() instanceof GlfwEventHandler glfwEventHandler)) {
            original.call();
            return;
        }
        var shouldRelease = this.mouseGrabbed;
        var dispatcher = CursorPosCallbackDispatcher.get(VersionCompatUtils.getMinecraftWindow());
        if (shouldRelease) {
            dispatcher.suppressCallbacks();
        }

        original.call();

        if (shouldRelease) {
            glfwEventHandler.clearQueuedCursorPosCallbacks();
            MainThreadDispatcher.run(dispatcher::unsuppressCallbacks);
        }
    }
}
