package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.FinishUsingItemEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The local player finished using an item. */
@Mixin(ItemStack.class)
public abstract class ItemStackFinishMixin {
	@Inject(method = "finishUsingItem", at = @At("HEAD"))
	private void horizon$finishUsing(Level level, LivingEntity entity, CallbackInfoReturnable<ItemStack> cir) {
		if (entity == Minecraft.getInstance().player) EventBus.INSTANCE.post(new FinishUsingItemEvent((ItemStack) (Object) this));
	}
}
