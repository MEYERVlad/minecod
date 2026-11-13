package io.github.meyervlad.minecod;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** How the mod talks to a player in chat. */
public final class Messages {
	private static final int MAX_LENGTH = 1_000;

	private Messages() {
	}

	/** The code the player just entered. */
	public static Component echo(String code) {
		return Component.literal("> " + clip(code)).withStyle(ChatFormatting.GRAY);
	}

	/** A line written by {@code print}. */
	public static Component output(String text) {
		return Component.literal(clip(text));
	}

	/** The values a script returned. */
	public static Component result(List<String> values) {
		return Component.literal("= " + clip(String.join(", ", values))).withStyle(ChatFormatting.AQUA);
	}

	public static Component error(String text) {
		return Component.literal(clip(text)).withStyle(ChatFormatting.RED);
	}

	public static Component error(Component text) {
		return text.copy().withStyle(ChatFormatting.RED);
	}

	public static Component info(Component text) {
		return text.copy().withStyle(ChatFormatting.GRAY);
	}

	private static String clip(String text) {
		return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH) + "…";
	}
}
