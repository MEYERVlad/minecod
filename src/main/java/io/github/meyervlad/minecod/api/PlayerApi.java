package io.github.meyervlad.minecod.api;

import java.util.function.Supplier;

import io.github.meyervlad.minecod.Messages;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The Lua functions available to code a player types in chat. They act through the player:
 * {@code mine()} breaks what the player is looking at.
 *
 * <p>To add one: write a method, register it in {@link #install} and describe it under
 * {@code minecod.help.<name>} in the language files.
 */
public final class PlayerApi extends LuaApi {
	private final Supplier<ServerPlayer> playerLookup;

	private PlayerApi(Supplier<ServerPlayer> playerLookup) {
		this.playerLookup = playerLookup;
	}

	/**
	 * @param playerLookup finds the player the scripts act for; returns {@code null} while they
	 *                     are offline. Called on the server thread.
	 */
	public static void install(Globals globals, Supplier<ServerPlayer> playerLookup) {
		PlayerApi api = new PlayerApi(playerLookup);
		api.add(globals, "print(...)", api::print);
		api.add(globals, "sleep(seconds)", LuaApi::sleep);
		api.add(globals, "pos() -> x, y, z", api::pos);
		api.add(globals, "inspect() -> id, x, y, z", api::inspect);
		api.add(globals, "mine() -> true, id | false, reason", api::mine);
		api.add(globals, "help()", api::help);
	}

	private Varargs print(Varargs args) {
		Component message = Messages.output(line(args));

		// A whole tick's budget per line, so a runaway loop cannot flood the chat.
		return Script.await(() -> {
			player().sendSystemMessage(message);
			return LuaValue.NONE;
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	private Varargs pos(Varargs args) {
		return Script.await(() -> coordinates(player().blockPosition()));
	}

	private Varargs inspect(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			BlockHitResult hit = Targeting.block(player);

			if (hit == null) {
				return LuaValue.NIL;
			}

			BlockPos target = hit.getBlockPos();
			return LuaValue.varargsOf(LuaValue.valueOf(id(player.level().getBlockState(target))), coordinates(target));
		});
	}

	private Varargs mine(Varargs args) {
		return Script.await(new MineTask(playerLookup));
	}

	private Varargs help(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			player.sendSystemMessage(Component.translatable("minecod.help.title"));

			for (String signature : signatures()) {
				player.sendSystemMessage(Component.literal("  " + signature + " ").withStyle(ChatFormatting.YELLOW)
						.append(Component.translatable("minecod.help." + nameOf(signature)).withStyle(ChatFormatting.GRAY)));
			}

			return LuaValue.NONE;
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	private ServerPlayer player() {
		ServerPlayer player = playerLookup.get();

		if (player == null) {
			throw new LuaError("player is offline");
		}

		return player;
	}
}
