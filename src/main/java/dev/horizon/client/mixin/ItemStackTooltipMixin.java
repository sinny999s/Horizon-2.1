package dev.horizon.client.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.TooltipImageEvent;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tooltip images for items without one, dropping the container text lines under them and cutting over-wide lines
 * (Shulker View).
 */
@Mixin(ItemStack.class)
public abstract class ItemStackTooltipMixin {
	@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
	private void horizon$tooltipImage(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		if (cir.getReturnValue().isPresent()) return;
		TooltipImageEvent e = horizon$ask((ItemStack) (Object) this);
		if (e.getImage() != null) cir.setReturnValue(Optional.of(e.getImage()));
	}

	@Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
	private void horizon$fitLines(Item.TooltipContext context, @Nullable Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
		TooltipImageEvent e = horizon$ask((ItemStack) (Object) this);
		if (e.getImage() != null && e.getMaxLineWidth() > 0) cir.setReturnValue(e.fit(cir.getReturnValue()));
	}

	@WrapWithCondition(method = "addDetailsToTooltip", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/item/ItemStack;addToTooltip(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V"))
	private boolean horizon$containerList(ItemStack stack, DataComponentType<?> type, Item.TooltipContext context, TooltipDisplay display,
			Consumer<Component> consumer, TooltipFlag flag) {
		if (type != DataComponents.CONTAINER) return true;
		TooltipImageEvent e = horizon$ask(stack);
		return e.getImage() == null || !e.getHideList();
	}

	private static TooltipImageEvent horizon$ask(ItemStack stack) {
		TooltipImageEvent e = TooltipImageEvent.INSTANCE;
		e.setCancelled(false);
		e.setStack(stack);
		e.setImage(null);
		e.setHideList(false);
		e.setMaxLineWidth(0);
		return EventBus.INSTANCE.post(e);
	}
}
