package dev.horizon.shaders.mixin.sodium;

import dev.horizon.shaders.graph.FaceContext;
import dev.horizon.shaders.graph.MaterialContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FluidRendererImpl.class, remap = false)
public abstract class FluidMaterialMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void sulkan$fluidMaterial(
        LevelSlice level,
        BlockState block,
        FluidState fluid,
        BlockPos pos,
        BlockPos origin,
        TranslucentGeometryCollector collector,
        ChunkBuildBuffers buffers,
        CallbackInfo ci
    ) {
        MaterialContext.enter(fluid.createLegacyBlock());
        if (FaceContext.wanted()) {
            FaceContext.fluid();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void sulkan$clearFluidMaterial(CallbackInfo ci) {
        MaterialContext.leave();
        FaceContext.clear();
    }
}
