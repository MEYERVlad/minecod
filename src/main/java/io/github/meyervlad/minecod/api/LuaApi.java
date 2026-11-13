package io.github.meyervlad.minecod.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.meyervlad.minecod.script.Script;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A set of Lua functions for one kind of script host: {@link PlayerApi} for code a player
 * types in chat, {@code TerminalApi} for programs stored in a terminal block.
 *
 * <p>The body of a function runs on the script thread, so anything that touches the game
 * goes through {@link Script#await}.
 */
public abstract class LuaApi {
	private static final int TICKS_PER_SECOND = 20;

	private final List<String> signatures = new ArrayList<>();

	/**
	 * Defines a global function.
	 *
	 * @param signature shown by {@code help()}, e.g. {@code "pos() -> x, y, z"}; the part before
	 *                  the bracket is the function's name
	 */
	protected final void add(Globals globals, String signature, Function<Varargs, Varargs> body) {
		signatures.add(signature);
		globals.set(nameOf(signature), new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return body.apply(args);
			}
		});
	}

	/** Every function added so far, in order. */
	protected final List<String> signatures() {
		return signatures;
	}

	protected static String nameOf(String signature) {
		return signature.substring(0, signature.indexOf('('));
	}

	/** The arguments of a {@code print} call as one line of text. Script thread. */
	protected static String line(Varargs args) {
		StringBuilder line = new StringBuilder();

		for (int i = 1; i <= args.narg(); i++) {
			if (i > 1) {
				line.append(' ');
			}

			line.append(Script.display(args.arg(i)));
		}

		return line.toString();
	}

	/** {@code sleep(seconds)}: the same for every host. */
	protected static Varargs sleep(Varargs args) {
		long ticks = Math.max(1, (long) Math.ceil(args.checkdouble(1) * TICKS_PER_SECOND));
		long[] waited = {0};
		return Script.await(() -> waited[0]++ < ticks ? null : LuaValue.NONE);
	}

	protected static Varargs coordinates(BlockPos pos) {
		return LuaValue.varargsOf(LuaValue.valueOf(pos.getX()), LuaValue.valueOf(pos.getY()), LuaValue.valueOf(pos.getZ()));
	}

	/** A block's id as scripts see it, e.g. {@code minecraft:oak_log}. */
	public static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
