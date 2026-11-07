package dev.horizon.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Better Chat: the messages, newest first, to see when a new one comes in (not in the original). */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("allMessages")
	List<GuiMessage> horizon_allMessages();
}
