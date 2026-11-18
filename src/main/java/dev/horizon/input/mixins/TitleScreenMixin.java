package dev.horizon.input.mixins;

import dev.horizon.input.VersionCompatUtils;
import dev.horizon.input.core.Ixeris;
import dev.horizon.input.core.glfw.GlfwEventHandler;
import dev.horizon.input.core.glfw.callback_dispatcher.CallbackDispatchers;
import dev.horizon.input.core.glfw.callback_dispatcher._334.CallbackDispatchers_334;
import dev.horizon.input.core.threading.MainThreadDispatcher;
import dev.horizon.input.core.util.LWJGLVersionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Inject(method = "init", at = @At("HEAD"))
    private void ixeris$init(CallbackInfo ci) {
        if (Ixeris.getEventHandler() instanceof GlfwEventHandler) {
            MainThreadDispatcher.runLater(() -> CallbackDispatchers.validateAll(VersionCompatUtils.getMinecraftWindow()));
            if (LWJGLVersionHelper.is334OrGreater()) {
                MainThreadDispatcher.runLater(() -> CallbackDispatchers_334.validateAll(VersionCompatUtils.getMinecraftWindow()));
            }
        }

        Ixeris.inEarlyDisplay = false;
    }
}
