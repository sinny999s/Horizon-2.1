package dev.horizon.client.mixin.basic;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.WaterPushEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every fluid flow vector goes through WaterPushEvent. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidBasicMixin {
	@Inject(method = "getFlow", at = @At("HEAD"), cancellable = true)
	private void horizon$flow(BlockGetter level, BlockPos pos, FluidState state, CallbackInfoReturnable<Vec3> cir) {
		WaterPushEvent e = EventBus.INSTANCE.post(new WaterPushEvent(pos, (Fluid) (Object) this, state));
		if (e.getCancelled()) cir.setReturnValue(e.getVec());
	}
}
