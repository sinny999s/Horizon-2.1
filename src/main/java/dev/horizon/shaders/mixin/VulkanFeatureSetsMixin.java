package dev.horizon.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.horizon.shaders.runtime.SulkanFeatureSets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanFeatureSets.class)
public abstract class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true)
    private static void sulkan$requestShaderFeatures(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        Set<FeatureSet> sets = new HashSet<>((Collection<? extends FeatureSet>)cir.getReturnValue());
        sets.addAll(SulkanFeatureSets.ALL);
        cir.setReturnValue(sets);
    }
}
