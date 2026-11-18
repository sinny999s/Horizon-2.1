package dev.horizon.shaders.mixin.sodium;

import dev.horizon.shaders.graph.MaterialChunkVertex;
import dev.horizon.shaders.graph.MaterialTable;
import dev.horizon.shaders.graph.NativePackRuntime;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkMeshFormats.class, remap = false)
public abstract class MaterialVertexFormatMixin {
    @Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true)
    private static void sulkan$materialFormat(CallbackInfoReturnable<ChunkVertexType> ci) {
        MaterialTable table = NativePackRuntime.materials();
        if (table != null && table.enabled()) {
            ci.setReturnValue(new MaterialChunkVertex(table));
        }
    }
}
