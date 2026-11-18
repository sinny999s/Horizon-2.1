package dev.horizon.shaders.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.graph.SceneRenderPass;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class NativeWeatherMixin {
    @Unique
    private boolean sulkan$renderingWeather;

    @Inject(
        method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void sulkan$weatherLayer(WeatherRenderState state, RenderPass original, CallbackInfo ci) {
        GpuTextureView weather = NativePackRuntime.weatherView();
        if (weather != null && !this.sulkan$renderingWeather) {
            ci.cancel();
            SceneRenderPass.outside(
                () -> {
                    GpuTextureView depth = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();

                    try {
                        RenderPass pass = RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(() -> "Sulkan weather", weather, Optional.empty(), depth, OptionalDouble.empty());

                        try {
                            this.sulkan$renderingWeather = true;
                            ((WeatherEffectRenderer)(Object)this).render(state, pass);
                        } catch (Throwable t$) {
                            if (pass != null) {
                                try {
                                    pass.close();
                                } catch (Throwable x2) {
                                    t$.addSuppressed(x2);
                                }
                            }

                            throw t$;
                        }

                        if (pass != null) {
                            pass.close();
                        }
                    } finally {
                        this.sulkan$renderingWeather = false;
                    }
                }
            );
        }
    }
}
