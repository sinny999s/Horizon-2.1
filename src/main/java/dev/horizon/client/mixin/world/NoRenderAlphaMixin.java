package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EntityAlphaEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Another player's model colour. A cancelled EntityAlphaEvent replaces
 * the colour with white at the event's alpha (vanilla's tint is dropped).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class NoRenderAlphaMixin {
	private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

	@Unique
	private LivingEntityRenderState horizon$prevState;

	@Inject(method = SUBMIT, at = @At("HEAD"))
	private void horizon$state(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		horizon$prevState = state;
	}

	@ModifyArg(method = SUBMIT, index = 6, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
	private int horizon$alpha(int color) {
		Minecraft mc = Minecraft.getInstance();
		if (horizon$prevState instanceof AvatarRenderState avatar && mc.player != null && avatar.id != mc.player.getId()) {
			EntityAlphaEvent e = EventBus.INSTANCE.post(new EntityAlphaEvent(new Vec3(avatar.x, avatar.y, avatar.z)));
			if (e.getCancelled()) return (int) (255.0F * e.getAlpha()) << 24 | 0xFFFFFF;
		}
		return color;
	}
}
