package dev.horizon.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.horizon.shaders.mixin.GraphDeviceAccessor;
import dev.horizon.shaders.mixin.VulkanDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Properties;
import org.slf4j.Logger;

public record GpuCapabilities(
    String deviceName, int apiVersion, boolean apple, boolean float16, boolean demote, int subgroupSize, int subgroupOperations, int maxStageSamplers
) {
    public static final int SUBGROUP_BASIC = 1;
    public static final int SUBGROUP_VOTE = 2;
    public static final int SUBGROUP_ARITHMETIC = 4;
    public static final int SUBGROUP_BALLOT = 8;
    public static final int SUBGROUP_SHUFFLE = 16;
    public static final int SUBGROUP_SHUFFLE_RELATIVE = 32;
    public static final int SUBGROUP_CLUSTERED = 64;
    public static final int SUBGROUP_QUAD = 128;
    public static final GpuCapabilities NONE = new GpuCapabilities("", VK12.VK_API_VERSION_1_2, false, false, false, 0, 0, Integer.MAX_VALUE);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int APPLE_VENDOR_ID = 4203;
    private static final int METAL_STAGE_SAMPLERS = 16;
    private static final int SUBGROUP_STAGES = 48;
    private static volatile GpuCapabilities current;

    public static GpuCapabilities current() {
        GpuCapabilities capabilities = current;
        if (capabilities != null) {
            return capabilities;
        } else {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device != null && ((GraphDeviceAccessor)device).sulkan$backend() instanceof VulkanDevice vulkan) {
                capabilities = detect(vulkan);
                current = capabilities;
                LOGGER.info(
                    "Sulkan GPU: {} (Vulkan {}.{}, apple={}, float16={}, demote={}, subgroupSize={}, subgroupOps=0x{}, stageSamplers={})",
                    new Object[]{
                        capabilities.deviceName,
                        VK10.VK_API_VERSION_MAJOR(capabilities.apiVersion),
                        VK10.VK_API_VERSION_MINOR(capabilities.apiVersion),
                        capabilities.apple,
                        capabilities.float16,
                        capabilities.demote,
                        capabilities.subgroupSize,
                        Integer.toHexString(capabilities.subgroupOperations),
                        capabilities.maxStageSamplers
                    }
                );
                return capabilities;
            } else {
                return NONE;
            }
        }
    }

    private static GpuCapabilities detect(VulkanDevice device) {
        VkPhysicalDevice physical = device.vkDevice().getPhysicalDevice();
        FeatureSet enabled = ((VulkanDeviceAccessor)device).sulkan$enabledFeatures();
        MemoryStack stack = MemoryStack.stackPush();

        GpuCapabilities var10;
        try {
            VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Properties vulkan11 = VkPhysicalDeviceVulkan11Properties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver).pNext(vulkan11);
            VK11.vkGetPhysicalDeviceProperties2(physical, properties);
            boolean apple = driver.driverID() == 14 || properties.properties().vendorID() == 4203;
            boolean subgroups = (vulkan11.subgroupSupportedStages() & 48) == 48;
            int stageSamplers = Math.min(
                properties.properties().limits().maxPerStageDescriptorSamplers(), properties.properties().limits().maxPerStageDescriptorSampledImages()
            );
            var10 = new GpuCapabilities(
                properties.properties().deviceNameString(),
                properties.properties().apiVersion(),
                apple,
                SulkanFeatureSets.enabled(enabled, SulkanFeatureSets.FLOAT16),
                SulkanFeatureSets.enabled(enabled, SulkanFeatureSets.DEMOTE),
                subgroups ? vulkan11.subgroupSize() : 0,
                subgroups ? vulkan11.subgroupSupportedOperations() : 0,
                apple ? Math.min(stageSamplers, 16) : stageSamplers
            );
        } catch (Throwable var12) {
            if (stack != null) {
                try {
                    stack.close();
                } catch (Throwable var11) {
                    var12.addSuppressed(var11);
                }
            }

            throw var12;
        }

        if (stack != null) {
            stack.close();
        }

        return var10;
    }

    public boolean atLeast(int major, int minor) {
        return VK10.VK_API_VERSION_MAJOR(this.apiVersion) > major
            || VK10.VK_API_VERSION_MAJOR(this.apiVersion) == major && VK10.VK_API_VERSION_MINOR(this.apiVersion) >= minor;
    }

    public String shaderHeader() {
        StringBuilder header = new StringBuilder();
        if (this.float16) {
            header.append("#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable\n");
        }

        if (this.demote) {
            header.append("#extension GL_EXT_demote_to_helper_invocation : enable\n");
        }

        String[][] subgroupExtensions = new String[][]{
            {"BASIC", "basic"},
            {"VOTE", "vote"},
            {"ARITHMETIC", "arithmetic"},
            {"BALLOT", "ballot"},
            {"SHUFFLE", "shuffle"},
            {"SHUFFLE_RELATIVE", "shuffle_relative"},
            {"CLUSTERED", "clustered"},
            {"QUAD", "quad"}
        };

        for (int i = 0; i < subgroupExtensions.length; i++) {
            if ((this.subgroupOperations & 1 << i) != 0) {
                header.append("#extension GL_KHR_shader_subgroup_").append(subgroupExtensions[i][1]).append(" : enable\n");
                header.append("#define SULKAN_SUBGROUP_").append(subgroupExtensions[i][0]).append(" 1\n");
            }
        }

        header.append("#define SULKAN_VULKAN_VERSION ")
            .append(VK10.VK_API_VERSION_MAJOR(this.apiVersion) * 100 + VK10.VK_API_VERSION_MINOR(this.apiVersion) * 10)
            .append('\n');
        header.append("#define SULKAN_SUBGROUP_SIZE ").append(this.subgroupSize).append('\n');
        if (this.apple) {
            header.append("#define SULKAN_APPLE 1\n");
        }

        if (this.float16) {
            header.append("#define SULKAN_FLOAT16 1\n")
                .append("#define shalf float16_t\n#define shalf2 f16vec2\n#define shalf3 f16vec3\n#define shalf4 f16vec4\n");
        } else {
            header.append("#define shalf float\n#define shalf2 vec2\n#define shalf3 vec3\n#define shalf4 vec4\n");
        }

        if (this.demote) {
            header.append("#define SULKAN_DEMOTE 1\n#define sulkan_discard demote\n");
        } else {
            header.append("#define sulkan_discard discard\n");
        }

        return header.toString();
    }
}
