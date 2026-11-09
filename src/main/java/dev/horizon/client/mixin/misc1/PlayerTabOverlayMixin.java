package dev.horizon.client.mixin.misc1;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.PlayerListSizeEvent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The tab list's 80-player limit, liftable to 1000 through PlayerListSizeEvent. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	@ModifyArg(method = "getPlayerInfos", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;limit(J)Ljava/util/stream/Stream;"))
	private long horizon$playerListSize(long max) {
		PlayerListSizeEvent.INSTANCE.setCancelled(false);
		return EventBus.INSTANCE.post(PlayerListSizeEvent.INSTANCE).getCancelled() ? 1000L : max;
	}
}
