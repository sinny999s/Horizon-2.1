package dev.horizon.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.shaders.render.shadow.ShadowService;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeStorage.class)
public abstract class SubmitNodeStorageMixin {
    @Inject(method = "submitShadow", at = @At("HEAD"), cancellable = true)
    private void sulkan$disableVanillaBlobShadows(PoseStack poseStack, float shadowRadius, List<ShadowPiece> shadowPieces, CallbackInfo ci) {
        if (!ShaderRuntime.resourceReloading() && ShadowService.entities()) {
            ci.cancel();
        }
    }
}
