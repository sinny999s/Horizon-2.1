package dev.horizon.client.mixin.world;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sign and the side a sign edit screen edits (26.3 has a slot, not isFrontText). */
@Mixin(AbstractSignEditScreen.class)
public interface AutoSignScreenAccessor {
	@Accessor("sign")
	SignBlockEntity horizon_sign();

	@Accessor("slot")
	SignTextSlot horizon_slot();
}
