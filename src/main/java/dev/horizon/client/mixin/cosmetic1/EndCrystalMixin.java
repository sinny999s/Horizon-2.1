package dev.horizon.client.mixin.cosmetic1;

import dev.horizon.client.event.CrystalCreateEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A cancelled CrystalCreateEvent starts a new crystal's age at 0 instead of a random one. */
@Mixin(EndCrystal.class)
public abstract class EndCrystalMixin {
	@Shadow
	public int time;

	@Unique
	private float horizon$startAge = -1.0F;

	@Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"))
	private void horizon$create(EntityType<? extends EndCrystal> type, Level level, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new CrystalCreateEvent()).getCancelled() && horizon$startAge == -1.0F) {
			horizon$startAge = time;
			time = 0;
		}
	}
}
