package dev.horizon.client.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Better Chat: a font's glyph provider, to make one with the client's font as its default (not in the original). */
@Mixin(Font.class)
public interface FontAccessor {
	@Accessor("provider")
	Font.Provider horizon_provider();
}
