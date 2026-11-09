package dev.horizon.client.mixin.rage2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.TridentUseEvent;
import dev.horizon.client.event.TridentWeatherEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Riptide release and use go through TridentUseEvent / TridentWeatherEvent. */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
	@Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
	private void horizon$release(ItemStack stack, Level level, LivingEntity entity, int remainingTime, CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player && EnchantmentHelper.getTridentSpinAttackStrength(stack, mc.player) > 0.0F
			&& EventBus.INSTANCE.post(new TridentUseEvent()).getCancelled()) {
			cir.setReturnValue(true);
		}
	}

	// Any player (the integrated server's too), on purpose.
	@Inject(method = "use", at = @At("HEAD"), cancellable = true)
	private void horizon$use(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		ItemStack stack = player.getItemInHand(hand);
		if (EnchantmentHelper.getTridentSpinAttackStrength(stack, player) > 0.0F && !player.isInWaterOrRain()
			&& EventBus.INSTANCE.post(new TridentWeatherEvent()).getCancelled()) {
			player.startUsingItem(hand);
			cir.setReturnValue(InteractionResult.CONSUME);
		}
	}
}
