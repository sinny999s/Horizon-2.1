package dev.horizon.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.horizon.shaders.mixin.GraphDeviceAccessor;
import dev.horizon.shaders.runtime.NativeShaderCompiler;
import dev.horizon.shaders.runtime.PipelineCache;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map.Entry;
import java.util.function.Function;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkQueueFamilyProperties.Buffer;

final class ComputeProgram implements AutoCloseable {
    private final VulkanDevice device;
    private final PackGraph.Pass pass;
    private long setLayout;
    private long layout;
    private long pipeline;
    private final Deque<ComputeProgram.Descriptors> availableDescriptors = new ArrayDeque<>();
    private boolean closed;
    private boolean push;
    private final int[] groupLimits = new int[3];

    ComputeProgram(PackGraph graph, PackGraph.Pass pass, PackFiles files) throws IOException {
        this.pass = pass;
        if (((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend() instanceof VulkanDevice vulkan) {
            this.device = vulkan;

            try {
                MemoryStack stack = MemoryStack.stackPush();

                try {
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(this.device.vkDevice().getPhysicalDevice(), properties);
                    VkPhysicalDeviceLimits limits = properties.limits();
                    long invocations = 1L;

                    for (int axis = 0; axis < 3; axis++) {
                        int local = pass.localSize().get(axis);
                        invocations *= local;
                        if (local > limits.maxComputeWorkGroupSize(axis)) {
                            throw new IOException("Compute local size exceeds device limit in " + pass.name());
                        }

                        this.groupLimits[axis] = limits.maxComputeWorkGroupCount(axis);
                    }

                    if (invocations > limits.maxComputeWorkGroupInvocations()) {
                        throw new IOException("Compute workgroup is too large for this GPU: " + pass.name());
                    }

                    IntBuffer familyCount = stack.ints(0);
                    VK10.vkGetPhysicalDeviceQueueFamilyProperties(this.device.vkDevice().getPhysicalDevice(), familyCount, null);
                    Buffer families = VkQueueFamilyProperties.calloc(familyCount.get(0), stack);
                    VK10.vkGetPhysicalDeviceQueueFamilyProperties(this.device.vkDevice().getPhysicalDevice(), familyCount, families);
                    if ((((VkQueueFamilyProperties)families.get(this.device.graphicsQueue().queueFamilyIndex())).queueFlags() & 2) == 0) {
                        throw new IOException("Minecraft's graphics queue does not support compute");
                    }

                    for (String output : pass.writes()) {
                        GpuFormat format = graph.resources().get(output).format();
                        formatName(format);
                        PackGraph.Resource resource = graph.resources().get(output);
                        if (resource.depth() > 1
                            && (
                                resource.fixedWidth() > limits.maxImageDimension3D()
                                    || resource.fixedHeight() > limits.maxImageDimension3D()
                                    || resource.depth() > limits.maxImageDimension3D()
                            )) {
                            throw new IOException("Volume exceeds device 3D texture limit: " + output);
                        }

                        VkFormatProperties support = VkFormatProperties.calloc(stack);
                        VK10.vkGetPhysicalDeviceFormatProperties(this.device.vkDevice().getPhysicalDevice(), VulkanConst.toVk(format), support);
                        if ((support.optimalTilingFeatures() & 2) == 0) {
                            throw new IOException("GPU cannot write storage format " + format);
                        }
                    }

                    if (pass.buffers().size() > limits.maxPerStageDescriptorStorageBuffers()
                        || pass.buffers().size() > limits.maxDescriptorSetStorageBuffers()
                        || pass.reads().size() > limits.maxPerStageDescriptorSamplers()
                        || pass.reads().size() > limits.maxPerStageDescriptorSampledImages()
                        || pass.writes().size() > limits.maxPerStageDescriptorStorageImages()) {
                        throw new IOException("Pass exceeds GPU descriptor limits: " + pass.name());
                    }

                    int count = 1 + pass.reads().size() + pass.writes().size() + pass.buffers().size();
                    VkPhysicalDevicePushDescriptorPropertiesKHR pushProperties = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
                    VK11.vkGetPhysicalDeviceProperties2(
                        this.device.vkDevice().getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(pushProperties)
                    );
                    this.push = count <= pushProperties.maxPushDescriptors();
                    org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);

                    for (int i = 0; i < count; i++) {
                        ((VkDescriptorSetLayoutBinding)bindings.get(i)).binding(i).descriptorType(this.type(i)).descriptorCount(1).stageFlags(32);
                    }

                    LongBuffer handle = stack.mallocLong(1);
                    check(
                        VK10.vkCreateDescriptorSetLayout(
                            this.device.vkDevice(),
                            VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().flags(this.push ? 1 : 0).pBindings(bindings),
                            null,
                            handle
                        )
                    );
                    this.setLayout = handle.get(0);
                    check(
                        VK10.vkCreatePipelineLayout(
                            this.device.vkDevice(),
                            VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(this.setLayout)),
                            null,
                            handle
                        )
                    );
                    this.layout = handle.get(0);

                    try (
                        NativeShaderCompiler compiler = new NativeShaderCompiler();
                        NativeShaderCompiler.ComputeModule code = compiler.compileCompute(
                            pass.name(), source(graph, pass, files.shader(pass.compute(), graph.options()))
                        );
                    ) {
                        check(
                            VK10.vkCreateShaderModule(
                                this.device.vkDevice(), VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code.code()), null, handle
                            )
                        );
                        long module = handle.get(0);

                        try {
                            org.lwjgl.vulkan.VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                                .sType$Default()
                                .layout(this.layout);
                            info.stage().sType$Default().stage(32).module(module).pName(stack.UTF8("main"));
                            check(VK10.vkCreateComputePipelines(this.device.vkDevice(), PipelineCache.handle(this.device), info, null, handle));
                            this.pipeline = handle.get(0);
                        } finally {
                            VK10.vkDestroyShaderModule(this.device.vkDevice(), module, null);
                        }
                    }
                } catch (Throwable var36) {
                    if (stack != null) {
                        try {
                            stack.close();
                        } catch (Throwable var30) {
                            var36.addSuppressed(var30);
                        }
                    }

                    throw var36;
                }

                if (stack != null) {
                    stack.close();
                }
            } catch (Exception failure) {
                this.close();
                throw new IOException("Compute pass " + pass.name() + ": " + failure.getMessage(), failure);
            }
        } else {
            throw new IOException("Compute requires the Vulkan backend");
        }
    }

    static String source(PackGraph graph, PackGraph.Pass pass, String source) {
        StringBuilder declarations = new StringBuilder(
            "\nlayout(local_size_x="
                + pass.localSize().get(0)
                + ",local_size_y="
                + pass.localSize().get(1)
                + ",local_size_z="
                + pass.localSize().get(2)
                + ") in;\n"
        );
        declarations.append("layout(std140,set=0,binding=0) uniform SulkanFrame {\n")
            .append(GraphFrame.MEMBERS)
            .append("};\n");
        int binding = 1;

        for (Entry<String, String> entry : pass.reads().entrySet()) {
            GpuFormat format = entry.getValue().startsWith("$") ? GpuFormat.RGBA8_UNORM : graph.resources().get(PackGraph.current(entry.getValue())).format();
            boolean volume = !entry.getValue().startsWith("$") && graph.resources().get(PackGraph.current(entry.getValue())).depth() > 1;
            declarations.append("layout(set=0,binding=")
                .append(binding++)
                .append(") uniform ")
                .append(prefix(format))
                .append(volume ? "sampler3D " : "sampler2D ")
                .append(entry.getKey())
                .append(";\n");
        }

        for (int i = 0; i < pass.writes().size(); i++) {
            GpuFormat format = graph.resources().get(pass.writes().get(i)).format();
            declarations.append("layout(")
                .append(formatName(format))
                .append(",set=0,binding=")
                .append(binding++)
                .append(") writeonly uniform ")
                .append(prefix(format))
                .append(graph.resources().get(pass.writes().get(i)).depth() > 1 ? "image3D Output" : "image2D Output")
                .append(i)
                .append(";\n");
        }

        int marker = source.indexOf("\n#line 2 0\n");
        int end = marker >= 0 ? marker : source.indexOf(10);

        for (Entry<String, PackGraph.BufferBinding> entry : pass.buffers().entrySet()) {
            declarations.append("layout(std430,set=0,binding=")
                .append(binding++)
                .append(") ")
                .append(entry.getValue().write() ? "" : "readonly ")
                .append("buffer SulkanStorage_")
                .append(entry.getKey())
                .append(" { uint data[]; } ")
                .append(entry.getKey())
                .append(";\n");
        }

        return source.substring(0, end + 1) + declarations + source.substring(end + 1);
    }

    private static String prefix(GpuFormat format) {
        return format.name().endsWith("_UINT") ? "u" : (format.name().endsWith("_SINT") ? "i" : "");
    }

    private static String formatName(GpuFormat format) {
        return switch (format) {
            case RGBA8_UNORM -> "rgba8";
            case RGBA16_FLOAT -> "rgba16f";
            case RGBA32_FLOAT -> "rgba32f";
            case R32_FLOAT -> "r32f";
            case R32_UINT -> "r32ui";
            case R16_UINT -> "r16ui";
            case R8_UINT -> "r8ui";
            case R32_SINT -> "r32i";
            case RGBA32_UINT -> "rgba32ui";
            case RGBA32_SINT -> "rgba32i";
            default -> throw new IllegalArgumentException("Unsupported native storage format " + format);
        };
    }

    private int type(int binding) {
        return binding == 0 ? 6 : (binding <= this.pass.reads().size() ? 1 : (binding <= this.pass.reads().size() + this.pass.writes().size() ? 3 : 7));
    }

    void dispatch(GpuBufferSlice frame, Function<String, GpuTextureView> images, Function<String, GpuSampler> samplers, GraphBuffers buffers) {
        VulkanCommandEncoder encoder = this.device.createCommandEncoder();
        MemoryStack stack = MemoryStack.stackPush();

        try {
            GpuTexture first = this.pass.dispatch().isEmpty() ? images.apply(this.pass.writes().getFirst()).texture() : null;
            int x = first == null ? this.pass.dispatch().get(0) : first.getWidth(0);
            int y = first == null ? this.pass.dispatch().get(1) : first.getHeight(0);
            int z = first == null ? this.pass.dispatch().get(2) : first.getDepthOrLayers();
            int groupsX = (x + this.pass.localSize().get(0) - 1) / this.pass.localSize().get(0);
            int groupsY = (y + this.pass.localSize().get(1) - 1) / this.pass.localSize().get(1);
            int groupsZ = (z + this.pass.localSize().get(2) - 1) / this.pass.localSize().get(2);
            if (groupsX > this.groupLimits[0] || groupsY > this.groupLimits[1] || groupsZ > this.groupLimits[2]) {
                throw new IllegalArgumentException("Compute dispatch exceeds device workgroup count");
            }

            long set = 0L;
            if (!this.push) {
                ComputeProgram.Descriptors descriptors = this.availableDescriptors.pollFirst();
                if (descriptors == null) {
                    descriptors = this.allocateDescriptors(stack);
                }

                ComputeProgram.Descriptors leased = descriptors;
                encoder.queueForDestroy(() -> {
                    if (this.closed) {
                        VK10.vkDestroyDescriptorPool(this.device.vkDevice(), leased.pool, null);
                    } else {
                        this.availableDescriptors.addLast(leased);
                    }
                });
                set = descriptors.set;
            }

            int count = 1 + this.pass.reads().size() + this.pass.writes().size() + this.pass.buffers().size();
            org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);

            for (int i = 0; i < count; i++) {
                ((VkWriteDescriptorSet)writes.get(i)).sType$Default().dstSet(set).dstBinding(i).descriptorType(this.type(i)).descriptorCount(1);
            }

            ((VkWriteDescriptorSet)writes.get(0))
                .pBufferInfo(
                    VkDescriptorBufferInfo.calloc(1, stack).buffer(((VulkanGpuBuffer)frame.buffer()).vkBuffer()).offset(frame.offset()).range(frame.length())
                );
            int index = 1;

            for (String input : this.pass.reads().values()) {
                ((VkWriteDescriptorSet)writes.get(index++))
                    .pImageInfo(
                        VkDescriptorImageInfo.calloc(1, stack)
                            .imageLayout(1)
                            .imageView(((VulkanGpuTextureView)images.apply(input)).vkImageView())
                            .sampler(((VulkanGpuSampler)samplers.apply(input)).vkSampler())
                    );
            }

            for (String output : this.pass.writes()) {
                ((VkWriteDescriptorSet)writes.get(index++))
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageLayout(1).imageView(((VulkanGpuTextureView)images.apply(output)).vkImageView()));
            }

            for (PackGraph.BufferBinding binding : this.pass.buffers().values()) {
                GraphBuffers.Buffer buffer = buffers.resolve(binding.resource());
                ((VkWriteDescriptorSet)writes.get(index++))
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.handle()).offset(0L).range(buffer.bytes()));
            }

            if (!this.push) {
                VK10.vkUpdateDescriptorSets(this.device.vkDevice(), writes, null);
            }

            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            barrier(commands, stack, 65536, 2048, 65536, 96);
            VK10.vkCmdBindPipeline(commands, 1, this.pipeline);
            if (this.push) {
                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commands, 1, this.layout, 0, writes);
            } else {
                VK10.vkCmdBindDescriptorSets(commands, 1, this.layout, 0, stack.longs(set), null);
            }

            VK10.vkCmdDispatch(commands, groupsX, groupsY, groupsZ);
            barrier(commands, stack, 2048, 65536, 64, 98304);
            check(VK10.vkEndCommandBuffer(commands));
            encoder.execute(commands);
        } catch (Throwable var23) {
            if (stack != null) {
                try {
                    stack.close();
                } catch (Throwable var22) {
                    var23.addSuppressed(var22);
                }
            }

            throw var23;
        }

        if (stack != null) {
            stack.close();
        }
    }

    private ComputeProgram.Descriptors allocateDescriptors(MemoryStack stack) {
        org.lwjgl.vulkan.VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(
            1 + (this.pass.reads().isEmpty() ? 0 : 1) + (this.pass.writes().isEmpty() ? 0 : 1) + (this.pass.buffers().isEmpty() ? 0 : 1), stack
        );
        ((VkDescriptorPoolSize)sizes.get(0)).type(6).descriptorCount(1);
        int poolIndex = 1;
        if (!this.pass.writes().isEmpty()) {
            ((VkDescriptorPoolSize)sizes.get(poolIndex++)).type(3).descriptorCount(this.pass.writes().size());
        }

        if (!this.pass.reads().isEmpty()) {
            ((VkDescriptorPoolSize)sizes.get(poolIndex++)).type(1).descriptorCount(this.pass.reads().size());
        }

        if (!this.pass.buffers().isEmpty()) {
            ((VkDescriptorPoolSize)sizes.get(poolIndex)).type(7).descriptorCount(this.pass.buffers().size());
        }

        LongBuffer handle = stack.mallocLong(1);
        check(
            VK10.vkCreateDescriptorPool(
                this.device.vkDevice(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handle
            )
        );
        long pool = handle.get(0);

        try {
            check(
                VK10.vkAllocateDescriptorSets(
                    this.device.vkDevice(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(this.setLayout)),
                    handle
                )
            );
            return new ComputeProgram.Descriptors(pool, handle.get(0));
        } catch (Exception failure) {
            VK10.vkDestroyDescriptorPool(this.device.vkDevice(), pool, null);
            throw failure;
        }
    }

    static void barrier(VkCommandBuffer buffer, MemoryStack stack, int from, int to, int writes, int reads) {
        VK10.vkCmdPipelineBarrier(buffer, from, to, 0, VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(writes).dstAccessMask(reads), null, null);
    }

    static void clearVolume(GpuTexture texture) {
        VulkanDevice device = (VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        MemoryStack stack = MemoryStack.stackPush();

        try {
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            barrier(commands, stack, 65536, 4096, 65536, 4096);
            VK10.vkCmdClearColorImage(
                commands,
                ((VulkanGpuTexture)texture).vkImage(),
                1,
                VkClearColorValue.calloc(stack),
                VkImageSubresourceRange.calloc(1, stack).aspectMask(1).baseMipLevel(0).levelCount(texture.getMipLevels()).baseArrayLayer(0).layerCount(1)
            );
            barrier(commands, stack, 4096, 65536, 4096, 98304);
            check(VK10.vkEndCommandBuffer(commands));
            encoder.execute(commands);
        } catch (Throwable var7) {
            if (stack != null) {
                try {
                    stack.close();
                } catch (Throwable var6) {
                    var7.addSuppressed(var6);
                }
            }

            throw var7;
        }

        if (stack != null) {
            stack.close();
        }
    }

    private static void check(int result) {
        if (result != 0) {
            throw new IllegalStateException("Vulkan compute operation failed: " + result);
        }
    }

    @Override
    public void close() {
        this.closed = true;

        for (ComputeProgram.Descriptors descriptors : this.availableDescriptors) {
            VK10.vkDestroyDescriptorPool(this.device.vkDevice(), descriptors.pool, null);
        }

        this.availableDescriptors.clear();
        if (this.pipeline != 0L) {
            VK10.vkDestroyPipeline(this.device.vkDevice(), this.pipeline, null);
        }

        if (this.layout != 0L) {
            VK10.vkDestroyPipelineLayout(this.device.vkDevice(), this.layout, null);
        }

        if (this.setLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(this.device.vkDevice(), this.setLayout, null);
        }

        this.pipeline = this.layout = this.setLayout = 0L;
    }

    private record Descriptors(long pool, long set) {
    }
}
