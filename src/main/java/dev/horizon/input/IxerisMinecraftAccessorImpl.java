package dev.horizon.input;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.horizon.input.core.EventHandler;
import dev.horizon.input.core.IxerisMinecraftAccessor;
import dev.horizon.input.mixins.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.CGL;
//? >=26.3 {
import dev.horizon.input.core.sdl.SdlEventHandler;
//? } else {
/*import dev.horizon.input.core.glfw.GlfwEventHandler;
*///? }

public class IxerisMinecraftAccessorImpl implements IxerisMinecraftAccessor {
    @Override
    public long getMinecraftWindow() {
        return VersionCompatUtils.getMinecraftWindow();
    }

    @Override
    public void setIgnoreFirstMouseMove() {
        Minecraft.getInstance().mouseHandler.setIgnoreFirstMove();
    }

    @Override
    public boolean isMouseInternallyGrabbed() {
        return ((MouseHandlerAccessor)Minecraft.getInstance().mouseHandler).isMouseGrabbed();
    }

    @Override
    public boolean isOnRenderThread() {
        return RenderSystem.isOnRenderThread();
    }

    @Override
    public void lockContext() {
        var context = IxerisMod.lockedContext;
        if (context != 0L) {
            CGL.CGLLockContext(context);
        }
    }

    @Override
    public void unlockContext() {
        var context = IxerisMod.lockedContext;
        if (context != 0L) {
            CGL.CGLUnlockContext(context);
        }
    }
}
