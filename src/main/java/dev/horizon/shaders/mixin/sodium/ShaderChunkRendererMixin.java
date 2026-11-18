package dev.horizon.shaders.mixin.sodium;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.horizon.shaders.render.shadow.DirectionalShadowRenderer;
import dev.horizon.shaders.render.shadow.SodiumTerrainShadowPipelines;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
    // The terrain pipelines, one per vertex format (packs with material ids use a different chunk vertex), Sodium's
    // shader parameters (its spherical fog option) and pass, shared by every chunk renderer: Sodium makes a new
    // renderer for each world, and fresh pipeline objects there would miss Sulkan's replacement caches and recompile
    // the pack's terrain programs (a second-long freeze on every world change, e.g. a server sending you back to its
    // lobby).
    @Unique
    private static final Map<VertexFormat, Map<Object, Map<TerrainRenderPass, RenderPipeline>>> sulkan$programs = new IdentityHashMap<>();
    // Sodium's createShader(pass, parameters). Its parameters type is a protected record, so the factory is reached
    // by reflection and the parameters are passed through as they came.
    @Unique
    private static Method sulkan$createShader;
    @Shadow
    @Final
    protected VertexFormat vertexFormat;

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void sulkan$useTerrainShadowPipeline(
        TerrainRenderPass pass, OitStage stage, @Coerce Object parameters, CallbackInfoReturnable<RenderPipeline> cir
    ) {
        if (!ShaderRuntime.resourceReloading()) {
            if (DirectionalShadowRenderer.isRenderingShadowMap()) {
                cir.setReturnValue(SodiumTerrainShadowPipelines.caster(pass, this.vertexFormat));
            } else if (stage == null) {
                cir.setReturnValue(
                    sulkan$programs.computeIfAbsent(this.vertexFormat, format -> new HashMap<>())
                        .computeIfAbsent(parameters, key -> new IdentityHashMap<>())
                        .computeIfAbsent(pass, key -> this.sulkan$createShader(key, parameters))
                );
            }
        }
    }

    @Unique
    private RenderPipeline sulkan$createShader(TerrainRenderPass pass, Object parameters) {
        try {
            Method factory = sulkan$createShader;
            if (factory == null) {
                factory = ShaderChunkRenderer.class.getDeclaredMethod("createShader", TerrainRenderPass.class, parameters.getClass());
                factory.setAccessible(true);
                sulkan$createShader = factory;
            }
            return (RenderPipeline)factory.invoke(this, pass, parameters);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sodium's terrain shader factory changed", e);
        }
    }
}
