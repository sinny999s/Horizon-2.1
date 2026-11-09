package dev.horizon.client.mixin.esp2;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.XRayBlockEvent;
import dev.horizon.client.event.XRayState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While x-ray is on, a face is drawn only when x-ray says so. */
@Mixin(Block.class)
public abstract class BlockMixin {
	@ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
	private static boolean horizon$xray(boolean original, BlockState state, BlockState neighbor, Direction direction) {
		if (!XRayState.active) return original;
		return EventBus.INSTANCE.post(new XRayBlockEvent(state.getBlock())).getCancelled();
	}
}
