package dev.horizon.shaders.runtime;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderDemoteToHelperInvocationFeaturesEXT;

public final class SulkanFeatureSets {
    private static final VulkanPNextStruct DEMOTE_STRUCT = new VulkanPNextStruct(VkPhysicalDeviceShaderDemoteToHelperInvocationFeaturesEXT.class);
    public static final FeatureSet FLOAT16 = new FeatureSet(
        "Sulkan float16 arithmetic", Set.of(), Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"))
    );
    public static final FeatureSet DEMOTE = new FeatureSet(
        "Sulkan demote to helper invocation",
        Set.of("VK_EXT_shader_demote_to_helper_invocation"),
        Set.of(new VulkanFeature(DEMOTE_STRUCT, "shaderDemoteToHelperInvocation"))
    );
    public static final Set<FeatureSet> ALL = Set.of(FLOAT16, DEMOTE);

    private SulkanFeatureSets() {
    }

    static boolean enabled(FeatureSet enabled, FeatureSet wanted) {
        return enabled != null && enabled.extensions().containsAll(wanted.extensions()) && enabled.features().containsAll(wanted.features());
    }
}
