package dev.horizon.client.mixin.cosmetic1;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What drawing the first-person hands needs. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("hudProjection")
	Projection horizon_hudProjection();

	@Accessor("hud3dProjectionMatrixBuffer")
	ProjectionMatrixBuffer horizon_hud3dProjectionMatrixBuffer();

	@Invoker("bobHurt")
	void horizon_bobHurt(CameraRenderState cameraState, PoseStack poseStack);

	@Invoker("bobView")
	void horizon_bobView(CameraRenderState cameraState, PoseStack poseStack);
}
