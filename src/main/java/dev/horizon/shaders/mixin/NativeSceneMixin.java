package dev.horizon.shaders.mixin;

import dev.horizon.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class NativeSceneMixin {
    @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
    private void sulkan$nativeTransparency(CallbackInfoReturnable<Boolean> ci) {
        if (NativePackRuntime.usesNativeTransparency()) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void sulkan$beginNativeScene(CallbackInfo ci) {
        NativePackRuntime.scope(true);
    }

    @Inject(
        method = "render3dHud",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"
        )
    )
    private void sulkan$preserveWorldDepthBeforeHand(CallbackInfo ci) {
        NativePackRuntime.captureWorldDepth();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void sulkan$finishNativeScene(CallbackInfo ci) {
        NativePackRuntime.finishScene();
    }
}
