package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.lunar.LunarNameTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Not in the original: Lunar's logo and badge on a Lunar player's vanilla name tag ([LunarNameTag]). The name line (not
 * the score line above it) gets room for them, and they're drawn in the same place and turn as its text.
 */
@Mixin(EntityRenderer.class)
public abstract class LunarNameTagMixin {
	@WrapOperation(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
		at = @At(value = "INVOKE", ordinal = 1,
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
	private void horizon$lunarNameTag(SubmitNodeCollector collector, PoseStack poseStack, Vec3 attachment, int y, Component name, boolean seeThrough,
	                                  int light, CameraRenderState camera, Operation<Void> original, @Local(argsOnly = true) EntityRenderState state) {
		LunarNameTag.Marks marks = null;
		if (state instanceof AvatarRenderState avatar && attachment != null && Minecraft.getInstance().level != null
			&& Minecraft.getInstance().level.getEntity(avatar.id) instanceof Player p) {
			// An Apollo server's own name tag lines for this player (bottom line where the name goes).
			java.util.List<String> lines = dev.horizon.client.lunar.ApolloFeatures.nametag(p.getUUID());
			if (lines != null && !lines.isEmpty()) {
				for (int i = 0; i < lines.size(); i++) {
					int up = (lines.size() - 1 - i) * 10;
					original.call(collector, poseStack, attachment, y - up, Component.literal(lines.get(i)), seeThrough, light, camera);
				}
				return;
			}
			marks = LunarNameTag.INSTANCE.marks(p.getUUID());
		}
		if (marks == null) {
			original.call(collector, poseStack, attachment, y, name, seeThrough, light, camera);
			return;
		}
		Component padded = LunarNameTag.padded(name, marks);
		original.call(collector, poseStack, attachment, y, padded, seeThrough, light, camera);
		LunarNameTag.submit(collector, poseStack, attachment, y, padded, marks, seeThrough, light, camera);
	}
}
