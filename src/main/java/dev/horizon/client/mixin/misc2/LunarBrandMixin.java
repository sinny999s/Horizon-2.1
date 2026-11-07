package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.horizon.client.module.misc.LunarSpoof;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Not in the original: Lunar Spoof's brand. The brand the client sends when login finishes (vanilla's "vanilla", which
 * Fabric makes "fabric") is "lunarclient:v&lt;version&gt;" while it's on, as Lunar Client's own, except in singleplayer and on
 * versions Lunar doesn't run on (Moonlight's FMLCommonHandlerMixin).
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class LunarBrandMixin {
	@Shadow
	@Final
	private Connection connection;

	@ModifyExpressionValue(method = "handleLoginFinished",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ClientBrandRetriever;getClientModName()Ljava/lang/String;"))
	private String horizon$lunarBrand(String brand) {
		String lunar = LunarSpoof.brandFor(connection);
		return lunar != null ? lunar : brand;
	}
}
