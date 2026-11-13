package io.github.meyervlad.minecod.terminal;

import io.github.meyervlad.minecod.api.LuaApi;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The Lua functions available to a terminal's program. They act through the block: it mines
 * and inspects what is in front of its screen, and talks to redstone.
 */
final class TerminalApi extends LuaApi {
	private static final int FULL_POWER = 15;

	private final TerminalBlockEntity terminal;

	private TerminalApi(TerminalBlockEntity terminal) {
		this.terminal = terminal;
	}

	static void install(Globals globals, TerminalBlockEntity terminal) {
		TerminalApi api = new TerminalApi(terminal);
		api.add(globals, "print(...)", api::print);
		api.add(globals, "sleep(seconds)", LuaApi::sleep);
		api.add(globals, "pos() -> x, y, z", api::pos);
		api.add(globals, "inspect() -> id", api::inspect);
		api.add(globals, "mine() -> true, id | false, reason", api::mine);
		api.add(globals, "redstone() -> 0..15", api::redstone);
		api.add(globals, "emit(0..15)", api::emit);
		api.add(globals, "help()", api::help);
	}

	private Varargs print(Varargs args) {
		String line = line(args);
		return Script.await(() -> {
			terminal.log(line);
			return LuaValue.NONE;
		});
	}

	private Varargs pos(Varargs args) {
		return Script.await(() -> coordinates(terminal.getBlockPos()));
	}

	/** The block in front, or nil for air. */
	private Varargs inspect(Varargs args) {
		return Script.await(() -> {
			BlockState front = terminal.serverLevel().getBlockState(terminal.frontPos());
			return front.isAir() ? LuaValue.NIL : LuaValue.valueOf(id(front));
		});
	}

	private Varargs mine(Varargs args) {
		return Script.await(new TerminalMineTask(terminal));
	}

	private Varargs redstone(Varargs args) {
		return Script.await(() -> LuaValue.valueOf(terminal.input()));
	}

	/** Accepts a power level or, for convenience, true and false. */
	private Varargs emit(Varargs args) {
		LuaValue value = args.arg1();
		int power = value.isboolean() ? (value.toboolean() ? FULL_POWER : 0) : args.checkint(1);
		return Script.await(() -> {
			terminal.setPower(power);
			return LuaValue.NONE;
		});
	}

	private Varargs help(Varargs args) {
		return Script.await(() -> {
			signatures().forEach(terminal::log);
			return LuaValue.NONE;
		});
	}
}
