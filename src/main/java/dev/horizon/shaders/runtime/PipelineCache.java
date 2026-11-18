package dev.horizon.shaders.runtime;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.slf4j.Logger;

public final class PipelineCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int HEADER_BYTES = 32;
    private static VulkanDevice owner;
    private static long handle;

    private PipelineCache() {
    }

    public static synchronized long handle(VulkanDevice device) {
        if (owner == device) {
            return handle;
        }

        if (owner != null) {
            destroy(owner);
        }

        owner = device;
        handle = create(device);
        return handle;
    }

    private static long create(VulkanDevice device) {
        MemoryStack stack = MemoryStack.stackPush();

        long var14;
        label102: {
            try {
                ByteBuffer initial = load(device);

                try {
                    VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
                    if (initial != null) {
                        info.pInitialData(initial);
                    }

                    LongBuffer created = stack.mallocLong(1);
                    if (VK10.vkCreatePipelineCache(device.vkDevice(), info, null, created) != 0
                        && (initial == null || VK10.vkCreatePipelineCache(device.vkDevice(), info.pInitialData(null), null, created) != 0)) {
                        var14 = 0L;
                        break label102;
                    }

                    var14 = created.get(0);
                } finally {
                    MemoryUtil.memFree(initial);
                }
            } catch (Throwable var13) {
                if (stack != null) {
                    try {
                        stack.close();
                    } catch (Throwable var11) {
                        var13.addSuppressed(var11);
                    }
                }

                throw var13;
            }

            if (stack != null) {
                stack.close();
            }

            return var14;
        }

        if (stack != null) {
            stack.close();
        }

        return var14;
    }

    private static ByteBuffer load(VulkanDevice device) {
        Path file = file();
        ByteBuffer data = null;

        try {
            if (!Files.isRegularFile(file)) {
                return null;
            } else {
                byte[] bytes = Files.readAllBytes(file);
                data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
                if (!matchesDevice(device, data)) {
                    LOGGER.info("Sulkan pipeline cache is from another GPU or driver, starting fresh");
                    MemoryUtil.memFree(data);
                    return null;
                } else {
                    LOGGER.info("Sulkan pipeline cache loaded ({} KiB)", bytes.length / 1024);
                    return data;
                }
            }
        } catch (IOException | RuntimeException failure) {
            MemoryUtil.memFree(data);
            LOGGER.warn("Could not read Sulkan pipeline cache", failure);
            return null;
        }
    }

    private static boolean matchesDevice(VulkanDevice device, ByteBuffer data) {
        if (data.remaining() < 32) {
            return false;
        }

        ByteBuffer header = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        MemoryStack stack = MemoryStack.stackPush();

        boolean var7;
        label77: {
            boolean var11;
            label78: {
                boolean uuid;
                try {
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
                    if (header.getInt(0) >= 32
                        && header.getInt(4) == 1
                        && header.getInt(8) == properties.vendorID()
                        && header.getInt(12) == properties.deviceID()) {
                        ByteBuffer uuidx = properties.pipelineCacheUUID();

                        for (int i = 0; i < 16; i++) {
                            if (header.get(16 + i) != uuidx.get(i)) {
                                var7 = false;
                                break label77;
                            }
                        }

                        var11 = true;
                        break label78;
                    }

                    uuid = false;
                } catch (Throwable var9) {
                    if (stack != null) {
                        try {
                            stack.close();
                        } catch (Throwable var8) {
                            var9.addSuppressed(var8);
                        }
                    }

                    throw var9;
                }

                if (stack != null) {
                    stack.close();
                }

                return uuid;
            }

            if (stack != null) {
                stack.close();
            }

            return var11;
        }

        if (stack != null) {
            stack.close();
        }

        return var7;
    }

    public static synchronized void save() {
        if (owner != null && handle != 0L) {
            ByteBuffer data = null;

            try {
                MemoryStack stack = MemoryStack.stackPush();

                label144: {
                    label145: {
                        try {
                            PointerBuffer size = stack.mallocPointer(1);
                            if (VK10.vkGetPipelineCacheData(owner.vkDevice(), handle, size, null) == 0 && size.get(0) != 0L) {
                                data = MemoryUtil.memAlloc((int)size.get(0));
                                if (VK10.vkGetPipelineCacheData(owner.vkDevice(), handle, size, data) != 0) {
                                    break label144;
                                }

                                byte[] bytes = new byte[(int)size.get(0)];
                                data.get(0, bytes);
                                Path file = file();
                                Files.createDirectories(file.getParent());
                                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                                Files.write(temp, bytes);
                                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                                break label145;
                            }
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

                        return;
                    }

                    if (stack != null) {
                        stack.close();
                    }

                    return;
                }

                if (stack != null) {
                    stack.close();
                }
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not save Sulkan pipeline cache", failure);
                return;
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    public static synchronized void destroy(VulkanDevice device) {
        if (owner == device) {
            save();
            if (handle != 0L) {
                VK10.vkDestroyPipelineCache(device.vkDevice(), handle, null);
            }

            owner = null;
            handle = 0L;
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sulkan").resolve("pipeline-cache.bin");
    }
}
