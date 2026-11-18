package dev.horizon.shaders.mixin.sodium;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.horizon.shaders.render.shadow.DirectionalShadowRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TerrainRenderPass.class, remap = false)
public abstract class TerrainRenderPassMixin {
    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true)
    private void sulkan$useShadowTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (DirectionalShadowRenderer.isRenderingShadowMap()) {
            RenderTarget target = DirectionalShadowRenderer.get().activeTarget();
            if (target != null) {
                cir.setReturnValue(target);
            }
        }
    }
}
