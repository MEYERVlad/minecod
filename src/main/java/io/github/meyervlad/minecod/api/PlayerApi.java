package io.github.meyervlad.minecod.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.meyervlad.minecod.Messages;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The Lua functions a player's scripts can call.
 *
 * <p>To add one: write a method, register it in {@link #install} and describe it under
 * {@code minecod.help.<name>} in the language files. The body of a function runs on the
 * script thread, so anything that touches the game goes through {@link Script#await}.
 */
public final class PlayerApi {
	private static final int TICKS_PER_SECOND = 20;

	private final Supplier<ServerPlayer> playerLookup;
	private final List<String> signatures = new ArrayList<>();

	private PlayerApi(Supplier<ServerPlayer> playerLookup) {
		this.playerLookup = playerLookup;
	}

	/**
	 * @param playerLookup finds the player the scripts act for; returns {@code null} while they
	 *                     are offline. Called on the server thread.
	 */
	public static void install(Globals globals, Supplier<ServerPlayer> playerLookup) {
		PlayerApi api = new PlayerApi(playerLookup);
		api.add(globals, "print", "print(...)", api::print);
		api.add(globals, "sleep", "sleep(seconds)", api::sleep);
		api.add(globals, "pos", "pos() -> x, y, z", api::pos);
		api.add(globals, "inspect", "inspect() -> id, x, y, z", api::inspect);
		api.add(globals, "mine", "mine() -> true, id | false, reason", api::mine);
		api.add(globals, "help", "help()", api::help);
	}

	private void add(Globals globals, String name, String signature, Function<Varargs, Varargs> body) {
		signatures.add(signature);
		globals.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return body.apply(args);
			}
		});
	}

	private Varargs print(Varargs args) {
		StringBuilder line = new StringBuilder();

		for (int i = 1; i <= args.narg(); i++) {
			if (i > 1) {
				line.append(' ');
			}

			line.append(Script.display(args.arg(i)));
		}

		Component message = Messages.output(line.toString());

		// A whole tick's budget per line, so a runaway loop cannot flood the chat.
		return Script.await(() -> {
			player().sendSystemMessage(message);
			return LuaValue.NONE;
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	private Varargs sleep(Varargs args) {
		long ticks = Math.max(1, (long) Math.ceil(args.checkdouble(1) * TICKS_PER_SECOND));
		long[] waited = {0};
		return Script.await(() -> waited[0]++ < ticks ? null : LuaValue.NONE);
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
			String id = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(target).getBlock()).toString();
			return LuaValue.varargsOf(LuaValue.valueOf(id), coordinates(target));
		});
	}

	private Varargs mine(Varargs args) {
		return Script.await(new MineTask(playerLookup));
	}

	private Varargs help(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			player.sendSystemMessage(Component.translatable("minecod.help.title"));

			for (String signature : signatures) {
				String name = signature.substring(0, signature.indexOf('('));
				player.sendSystemMessage(Component.literal("  " + signature + " ").withStyle(ChatFormatting.YELLOW)
						.append(Component.translatable("minecod.help." + name).withStyle(ChatFormatting.GRAY)));
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

	private static Varargs coordinates(BlockPos pos) {
		return LuaValue.varargsOf(LuaValue.valueOf(pos.getX()), LuaValue.valueOf(pos.getY()), LuaValue.valueOf(pos.getZ()));
	}
}
