package dev.horizon.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.InputSuggestorEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat input that starts with the client's command prefix is parsed and completed
 * against the client's own dispatcher (InputSuggestorEvent) instead of the server's commands.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
	@Shadow
	@Final
	EditBox input;
	@Shadow
	boolean keepSuggestions;
	@Shadow
	private ParseResults<ClientSuggestionProvider> currentParse;
	@Shadow
	private CompletableFuture<Suggestions> pendingSuggestions;
	@Shadow
	private CommandSuggestions.SuggestionsList suggestions;

	@Shadow
	protected abstract void updateUsageInfo(ParseResults<ClientSuggestionProvider> parse, Suggestions suggestions);

	@Inject(method = "updateCommandInfo", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;canRead()Z", remap = false), cancellable = true)
	private void horizon$clientCommands(CallbackInfo ci, @Local StringReader reader) {
		InputSuggestorEvent e = InputSuggestorEvent.INSTANCE;
		e.setCancelled(false);
		if (!EventBus.INSTANCE.post(e).getCancelled()) return;
		if (!reader.canRead(e.getPrefix().length()) || !reader.getString().startsWith(e.getPrefix(), reader.getCursor())) return;
		// Steps over one character whatever the prefix's length (on purpose).
		reader.setCursor(reader.getCursor() + 1);
		if (currentParse == null) currentParse = Objects.requireNonNull(e.getDispatcher()).parse(reader, Objects.requireNonNull(e.getSource()));
		int cursor = input.getCursorPosition();
		if (cursor >= 1 && (suggestions == null || !keepSuggestions)) {
			pendingSuggestions = Objects.requireNonNull(e.getDispatcher()).getCompletionSuggestions(currentParse, cursor);
			pendingSuggestions.thenAccept(s -> {
				if (pendingSuggestions.isDone()) updateUsageInfo(currentParse, s);
			});
		}
		ci.cancel();
	}
}
