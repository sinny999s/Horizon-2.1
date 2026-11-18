package dev.horizon.shaders.graph;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Tells the player in game when a shader pack fails to load or pauses: a toast, and a chat line while in a world,
 * with the reason. A failed shader compile names the first compiler error, which otherwise is only in the log.
 */
public final class PackNotices {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);
    private static volatile String compileError;

    private PackNotices() {
    }

    /** Remembers the first line of a shader compiler error for the next notice. */
    public static void compileError(String shader, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String line = message.strip().lines().findFirst().orElse(message.strip());
        compileError = shader == null ? line : shader + ": " + line;
    }

    public static void failed(String title, Throwable problem) {
        StringBuilder reason = new StringBuilder();
        for (Throwable cause = problem; cause != null && reason.length() < 400; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && !message.isBlank() && reason.indexOf(message) < 0) {
                if (!reason.isEmpty()) {
                    reason.append(": ");
                }

                reason.append(message.strip());
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        String compile = compileError;
        compileError = null;
        if (compile != null) {
            reason.append(reason.isEmpty() ? "" : " (").append(compile).append(reason.isEmpty() ? "" : ")");
        }

        String text = reason.isEmpty() ? problem.getClass().getSimpleName() : reason.toString();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            String shortText = text.length() > 120 ? text.substring(0, 117) + "..." : text;
            SystemToast.addOrUpdate(client.gui.toastManager(), TOAST, Component.literal(title), Component.literal(shortText));
            if (client.player != null) {
                client.player.sendSystemMessage(
                    Component.literal("[Horizon Shaders] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(title + ": ").withStyle(ChatFormatting.RED))
                        .append(Component.literal(text).withStyle(ChatFormatting.GRAY))
                );
            }
        });
    }
}
