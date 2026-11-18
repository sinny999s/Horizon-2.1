package dev.horizon.shaders;

import com.mojang.logging.LogUtils;
import dev.horizon.shaders.graph.GraphGpuSmoke;
import dev.horizon.shaders.runtime.BackendStatus;
import dev.horizon.shaders.runtime.ShaderRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStopping;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public final class SulkanShadersClient implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private boolean backendNoticeShown;

    public void onInitializeClient() {
        ShaderRuntime.init();
        ClientLifecycleEvents.CLIENT_STARTED.register(ShaderRuntime::bootstrap);
        ClientLifecycleEvents.CLIENT_STOPPING.register((ClientStopping)client -> ShaderRuntime.close());
        ClientTickEvents.END_CLIENT_TICK
            .register(
                (EndTick)client -> {
                    GraphGpuSmoke.tick(client);
                    if (!this.backendNoticeShown && client.gui.overlay() == null && client.gui.screen() instanceof TitleScreen) {
                        this.backendNoticeShown = true;
                        if (!BackendStatus.vulkanActive()) {
                            Screen parent = client.gui.screen();
                            client.setScreenAndShow(
                                new ConfirmScreen(
                                    retry -> {
                                        if (retry) {
                                            client.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
                                            client.options.save();
                                        }

                                        client.setScreenAndShow(parent);
                                    },
                                    Component.translatable("sulkan.backend.title"),
                                    BackendStatus.unavailableMessage(),
                                    Component.translatable("sulkan.backend.retry"),
                                    Component.translatable("sulkan.backend.continue")
                                )
                            );
                        }
                    }
                }
            );
        LOGGER.info("Sulkan Shaders initialized");
    }
}
