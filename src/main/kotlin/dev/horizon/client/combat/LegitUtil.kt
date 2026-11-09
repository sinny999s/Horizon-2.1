package dev.horizon.client.combat

import com.mojang.blaze3d.systems.RenderSystem
import dev.horizon.client.gui.Keys
import dev.horizon.client.setting.Bind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen

/**
 * Whether a bind setting is physically held right now (polled; never off the render thread, for an
 * unset bind, or with chat open; any other screen doesn't matter).
 */
fun bindHeld(b: Bind): Boolean {
    if (!RenderSystem.isOnRenderThread() || b.key == -1 || Minecraft.getInstance().gui.screen() is ChatScreen) return false
    return if (b.mouse) b.key in Keys.heldMouse else Keys.down(b.key)
}
