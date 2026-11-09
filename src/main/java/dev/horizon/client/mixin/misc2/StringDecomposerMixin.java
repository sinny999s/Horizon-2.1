package dev.horizon.client.mixin.misc2;

import dev.horizon.client.module.misc.NameProtect;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Name protect on every formatted text piece the vanilla font lays out or measures. */
@Mixin(StringDecomposer.class)
public abstract class StringDecomposerMixin {
	@ModifyVariable(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
		at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private static String horizon$protect(String text) {
		return NameProtect.protect(text);
	}
}
