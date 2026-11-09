package dev.horizon.client.mixin.world;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SlotRenderEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SlotRenderEvent: a container slot about to be drawn. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSlotRenderMixin {
	@Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V", at = @At("HEAD"))
	private void horizon$slot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		EventBus.INSTANCE.post(new SlotRenderEvent(slot, graphics));
	}
}
