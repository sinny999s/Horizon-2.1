package dev.horizon.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.horizon.shaders.runtime.PipelineCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceCloseMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void sulkan$savePipelineCache(CallbackInfo ci) {
        PipelineCache.destroy((VulkanDevice)(Object)this);
    }
}
