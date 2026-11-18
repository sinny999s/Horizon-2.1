package dev.horizon.input.core.mixins.sdl.threading;

import dev.horizon.input.core.Ixeris;
import dev.horizon.input.core.sdl.SdlEventHandler;
import dev.horizon.input.core.threading.MainThreadDispatcher;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.*;
import java.nio.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SDLEvents.class, remap = false)
public final class SDLEventsMixin {    
    @Inject(method = "SDL_PumpEvents", at = @At("HEAD"), cancellable = true)
    private static void ixeris$SDL_PumpEvents(CallbackInfo ci) {
        if (!Ixeris.isOnMainThread()) {
            ci.cancel(); MainThreadDispatcher.run(() -> SDLEvents.SDL_PumpEvents());
        }
    }

    @Inject(method = "nSDL_WaitEvent", at = @At("HEAD"), cancellable = true)
    private static void ixeris$nSDL_WaitEvent(long event, CallbackInfoReturnable<Boolean> cir) {
        if (!Ixeris.isOnMainThread()) {
            cir.setReturnValue(MainThreadDispatcher.query(() -> SDLEvents.nSDL_WaitEvent(event)));
        }
    }
    
    @Inject(method = "nSDL_WaitEventTimeout", at = @At("HEAD"), cancellable = true)
    private static void ixeris$nSDL_WaitEventTimeout(long event, int timeoutMS, CallbackInfoReturnable<Boolean> cir) {
        if (!Ixeris.isOnMainThread()) {
            cir.setReturnValue(MainThreadDispatcher.query(() -> SDLEvents.nSDL_WaitEventTimeout(event, timeoutMS)));
        }
    }
}
