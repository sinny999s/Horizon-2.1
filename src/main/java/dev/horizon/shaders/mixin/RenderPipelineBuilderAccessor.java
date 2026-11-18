package dev.horizon.shaders.mixin;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Builder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Builder.class)
public interface RenderPipelineBuilderAccessor {
    @Accessor("colorTargetStates")
    ColorTargetState[] sulkan$colorTargetStates();

    @Accessor("activeColorTargetStateCount")
    void sulkan$setActiveColorTargetStateCount(int var1);
}
