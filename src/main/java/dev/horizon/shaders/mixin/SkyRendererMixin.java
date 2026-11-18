package dev.horizon.shaders.mixin;

import dev.horizon.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    // A pack with its own sky program draws the whole sky after the opaque world, so vanilla's isn't needed.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sulkan$packSky(CallbackInfo ci) {
        if (NativePackRuntime.drawsSky()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void sulkan$renderCustomCelestialOrbit(CallbackInfo ci) {
        if (NativePackRuntime.replacesEnvironment(false)) {
            ci.cancel();
        }
    }
}
