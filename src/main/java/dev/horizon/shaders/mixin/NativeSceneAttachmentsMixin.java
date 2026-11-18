package dev.horizon.shaders.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.graph.SceneRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FrontendCommandEncoder.class)
public abstract class NativeSceneAttachmentsMixin {
    @ModifyVariable(
        method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private RenderPassDescriptor sulkan$sceneTargets(RenderPassDescriptor descriptor) {
        return NativePackRuntime.sceneAttachments(descriptor);
    }

    @Inject(
        method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void sulkan$splitScenePass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
        cir.setReturnValue(SceneRenderPass.wrap((RenderPass)cir.getReturnValue(), descriptor));
    }

    // With an HDR scene target, the world's clear of Minecraft's main color clears the HDR target instead.
    @ModifyVariable(
        method = {
            "clearColorTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;)V",
            "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
            "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V"
        },
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private GpuTexture sulkan$hdrSceneClear(GpuTexture texture) {
        return NativePackRuntime.sceneClearTarget(texture);
    }
}
