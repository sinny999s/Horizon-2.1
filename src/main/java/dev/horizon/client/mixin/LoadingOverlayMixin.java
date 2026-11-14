package dev.horizon.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.horizon.client.gui.menu.LoadingScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

/**
 * The loading screen in Horizon's look ([LoadingScreen]): its background, logo and progress bar are swapped; its
 * timing and fades stay vanilla's.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
	@Shadow private float currentProgress;

	/** The red (or black) Mojang Studios background, for the fills and the clear colour alike. */
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
	private int horizon$background(IntSupplier supplier, Operation<Integer> original) {
		return LoadingScreen.BACKGROUND;
	}

	/** The logo's left half: the wordmark instead. */
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
	private void horizon$logo(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
			int w, int h, int srcW, int srcH, int texW, int texH, int color, Operation<Void> original) {
		LoadingScreen.INSTANCE.logo(graphics, (color >>> 24) / 255f);
	}

	/** The logo's right half: nothing (the wordmark is whole). */
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
	private void horizon$logoRight(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
			int w, int h, int srcW, int srcH, int texW, int texH, int color, Operation<Void> original) {
	}

	/** The boxed bar: the wordmark's horizon line filling up instead. */
	@Inject(method = "extractProgressBar", at = @At("HEAD"), cancellable = true)
	private void horizon$progress(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, float fade, CallbackInfo ci) {
		LoadingScreen.INSTANCE.progress(graphics, currentProgress, fade);
		ci.cancel();
	}
}
