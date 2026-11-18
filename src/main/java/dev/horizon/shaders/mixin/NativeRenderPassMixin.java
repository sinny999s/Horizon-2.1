package dev.horizon.shaders.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor.Attachment;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.horizon.shaders.graph.GraphShaderSources;
import dev.horizon.shaders.graph.NativePackRuntime;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public abstract class NativeRenderPassMixin {
    @Shadow
    @Final
    private List<Attachment<Optional<Vector4fc>>> colorAttachments;
    @Unique
    private RenderPipeline sulkan$pipeline;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline sulkan$nativeScenePipeline(CompiledRenderPipeline compiled) {
        RenderPipeline original = GraphShaderSources.original(compiled);
        this.sulkan$pipeline = original == null ? null : NativePackRuntime.scenePipeline(original, this.colorAttachments);
        return this.sulkan$pipeline != null && this.sulkan$pipeline != original ? RenderSystem.getCompiledPipeline(this.sulkan$pipeline) : compiled;
    }

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void sulkan$nativeFrameUniforms(CompiledRenderPipeline pipeline, CallbackInfo ci) {
        if (this.sulkan$pipeline != null) {
            NativePackRuntime.bindSceneUniforms((RenderPass)(Object)this, this.sulkan$pipeline);
        }
    }
}
