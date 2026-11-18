package dev.horizon.shaders.mixin.sodium;

import dev.horizon.shaders.graph.FaceContext;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain faces: before Sodium writes a block quad, its face code (FaceContext): the direction of Sodium's face normal
 * when it lies along the block grid (slanted otherwise), and whether the model shades it as if it pointed up.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockFaceMixin {
    @Inject(method = "bufferQuad", at = @At("HEAD"))
    private void sulkan$face(MutableQuadViewImpl quad, float[] brightnesses, Material material, CallbackInfo ci) {
        if (!FaceContext.wanted()) {
            return;
        }

        int normal = quad.sodium$getFaceNormal();
        int code = FaceContext.direction(NormI8.unpackX(normal), NormI8.unpackY(normal), NormI8.unpackZ(normal));
        if (code != FaceContext.SLANTED && quad.shadeDirectionOverride() == Direction.UP) {
            code |= FaceContext.SHADED_UP;
        }

        FaceContext.block(code);
    }

    @Inject(method = "bufferQuad", at = @At("RETURN"))
    private void sulkan$clearFace(MutableQuadViewImpl quad, float[] brightnesses, Material material, CallbackInfo ci) {
        FaceContext.clear();
    }
}
