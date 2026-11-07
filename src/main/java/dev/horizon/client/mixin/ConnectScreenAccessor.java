package dev.horizon.client.mixin;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The connecting screen's status ("Connecting to the server...", "Logging in..."), for the client's {@code ConnectMenu}. */
@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {
	@Accessor("status")
	Component horizon_status();
}
