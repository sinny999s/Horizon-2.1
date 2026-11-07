package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloFeatures;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Not in the original: body parts an Apollo server hides on a player (Apollo's limb module, HideBodyPartMessage), each
 * with its outer layer. The model is shared between players, so parts hidden for one are shown again for the next.
 */
@Mixin(PlayerModel.class)
public abstract class ApolloPlayerModelMixin {
	@Unique
	private boolean horizon$hid;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void horizon$apolloHideParts(AvatarRenderState state, CallbackInfo ci) {
		if (!horizon$hid && !ApolloFeatures.anyHiddenParts()) return;
		Set<Integer> parts = ApolloFeatures.partsHidden(state.id);
		PlayerModel m = (PlayerModel) (Object) this;
		m.head.visible = !parts.contains(1);
		m.body.visible = !parts.contains(2);
		m.leftArm.visible = !parts.contains(3);
		m.rightArm.visible = !parts.contains(4);
		m.leftLeg.visible = !parts.contains(5);
		m.rightLeg.visible = !parts.contains(6);
		if (parts.contains(1)) m.hat.visible = false;
		if (parts.contains(2)) m.jacket.visible = false;
		if (parts.contains(3)) m.leftSleeve.visible = false;
		if (parts.contains(4)) m.rightSleeve.visible = false;
		if (parts.contains(5)) m.leftPants.visible = false;
		if (parts.contains(6)) m.rightPants.visible = false;
		horizon$hid = !parts.isEmpty() || ApolloFeatures.anyHiddenParts();
	}
}
