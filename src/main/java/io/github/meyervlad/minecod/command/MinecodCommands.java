package io.github.meyervlad.minecod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.meyervlad.minecod.Messages;
import io.github.meyervlad.minecod.Minecod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /run <code>} runs Lua for the player who typed it; {@code /halt} stops their script.
 * (Not {@code /stop}: that one shuts the server down.)
 */
public final class MinecodCommands {
	private MinecodCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("run")
				.then(Commands.argument("code", StringArgumentType.greedyString())
						.executes(context -> {
							Minecod.sessions().run(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "code"));
							return 1;
						})));

		dispatcher.register(Commands.literal("halt")
				.executes(context -> {
					CommandSourceStack source = context.getSource();

					if (!Minecod.sessions().halt(source.getPlayerOrException())) {
						source.sendFailure(Component.translatable("minecod.halt.idle"));
						return 0;
					}

					source.sendSuccess(() -> Messages.info(Component.translatable("minecod.halt.done")), false);
					return 1;
				}));
	}
}
