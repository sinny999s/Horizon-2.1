package dev.horizon.client.mixin.vk;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.horizon.client.render.vk.Barriers;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A vertex format's attributes by name in a hash map: every new BufferBuilder (one each time a draw's vertices change)
 * looks up eight names, and Minecraft's map compares the strings one by one. Same answers.
 */
@Mixin(VertexFormat.class)
public abstract class VertexFormatMixin {
	@Shadow @Final private Map<String, VertexFormatElement> elements;
	@Unique private HashMap<String, VertexFormatElement> horizon$byName;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void horizon$index(List<VertexFormatElement> list, int vertexSize, int stepRate, CallbackInfo ci) {
		this.horizon$byName = new HashMap<>(this.elements);
	}

	/**
	 * @author Horizon
	 * @reason A hash lookup instead of a linear string scan (see the class doc); same result.
	 */
	@Overwrite
	public @Nullable VertexFormatElement getElement(final String attributeName) {
		return Barriers.fastVertexFormats ? this.horizon$byName.get(attributeName) : this.elements.get(attributeName);
	}

	/**
	 * @author Horizon
	 * @reason As {@link #getElement}.
	 */
	@Overwrite
	public boolean contains(final String attributeName) {
		return Barriers.fastVertexFormats ? this.horizon$byName.containsKey(attributeName) : this.elements.containsKey(attributeName);
	}
}
