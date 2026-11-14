package dev.horizon.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.horizon.client.render.Overlay;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the client's GUI after vanilla's: the main GuiRenderer gets two more GuiRenderers (each with its
 * own render state) for vanilla things the client GUI submits, one for the HUD's layer and one for the screens' layer
 * above it, and at the end of each vanilla GUI frame the client GUI is drawn on top (see {@link Overlay}).
 * While a screen is open, the HUD's layer (ESP, chams, nametags, widgets) goes first instead, so the screen
 * (an inventory, a chest) covers it as it covers vanilla's HUD.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@Unique
	private static boolean horizon$constructing;
	@Unique
	private boolean horizon$auxiliary;
	@Unique
	private GuiRenderState horizon$state;
	@Unique
	private GuiRenderer horizon$renderer;
	@Unique
	private GuiRenderState horizon$screenState;
	@Unique
	private GuiRenderer horizon$screenRenderer;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void horizon$init(GuiRenderState renderState, FeatureRenderDispatcher dispatcher, List<PictureInPictureRenderer<?>> pip, CallbackInfo ci) {
		if (horizon$constructing) {
			horizon$auxiliary = true;
			return;
		}
		horizon$state = new GuiRenderState();
		horizon$screenState = new GuiRenderState();
		horizon$constructing = true;
		try {
			horizon$renderer = new GuiRenderer(horizon$state, dispatcher, pip);
			horizon$screenRenderer = new GuiRenderer(horizon$screenState, dispatcher, pip);
		} finally {
			horizon$constructing = false;
		}
	}

	/** The HUD's layer was drawn before vanilla's GUI this frame (a screen is open). */
	@Unique
	private boolean horizon$worldDrawn;

	@Inject(method = "render", at = @At("HEAD"))
	private void horizon$beforeVanilla(CallbackInfo ci) {
		horizon$worldDrawn = false;
		// Under a screen, and under the loading screen, so that covers it.
		if (horizon$auxiliary || Minecraft.getInstance().gui.screen() == null && !(Minecraft.getInstance().gui.overlay() instanceof LoadingOverlay)) return;
		horizon$world();
		// Vanilla's GUI starts from a clear depth buffer, as it would have.
		RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture(), 1.0);
		horizon$worldDrawn = true;
	}

	/** The HUD's layer: the world overlays, nametags and widgets, then their late draws. */
	@Unique
	private void horizon$world() {
		Minecraft mc = Minecraft.getInstance();
		int mouseX = (int) mc.mouseHandler.getScaledXPos(mc.getWindow());
		int mouseY = (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
		GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(mc, horizon$state, mouseX, mouseY);
		RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(mc.gameRenderer.mainRenderTarget().getDepthTexture(), 1.0);
		Overlay.INSTANCE.render(graphics, mouseX, mouseY, mc.getDeltaTracker().getGameTimeDeltaTicks());
		horizon$renderer.render();
		horizon$renderer.endFrame();
		Overlay.INSTANCE.renderLate();
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void horizon$render(CallbackInfo ci) {
		if (horizon$auxiliary) return;
		if (!horizon$worldDrawn) horizon$world();
		Minecraft mc = Minecraft.getInstance();
		int mouseX = (int) mc.mouseHandler.getScaledXPos(mc.getWindow());
		int mouseY = (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
		GuiGraphicsExtractor screens = new GuiGraphicsExtractor(mc, horizon$screenState, mouseX, mouseY);
		RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(mc.gameRenderer.mainRenderTarget().getDepthTexture(), 1.0);
		Overlay.INSTANCE.renderScreens(screens, mouseX, mouseY);
		horizon$screenRenderer.render();
		horizon$screenRenderer.endFrame();
		Overlay.INSTANCE.renderScreensLate();
	}
}
