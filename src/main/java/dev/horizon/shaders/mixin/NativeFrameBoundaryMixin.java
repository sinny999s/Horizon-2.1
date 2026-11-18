package dev.horizon.shaders.mixin;

import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.render.shadow.DirectionalShadowRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class NativeFrameBoundaryMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void sulkan$rebuildAtFrameBoundary(CallbackInfo ci) {
        NativePackRuntime.flushGeometryRebuild();
        DirectionalShadowRenderer.retireUnused();
    }
}
