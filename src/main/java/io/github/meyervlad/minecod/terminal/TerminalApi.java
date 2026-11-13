package io.github.meyervlad.minecod.terminal;

import io.github.meyervlad.minecod.api.LuaApi;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The Lua functions available to a terminal's program. They act through the block: it mines,
 * places and inspects in front of its screen, and talks to wires and redstone.
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
		api.add(globals, "place([id]) -> true, id | false, reason", api::place);
		api.add(globals, "tool() -> id, uses left", api::tool);
		api.add(globals, "count([id]) -> number", api::count);
		api.add(globals, "push() -> number moved", api::push);
		api.add(globals, "emit(number)", api::emit);
		api.add(globals, "signal() -> number", api::signal);
		api.add(globals, "redstone() -> 0..15", api::redstone);
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

	/** Places a block from storage in front of the screen: a given kind, or the first one found. */
	private Varargs place(Varargs args) {
		String id = args.optjstring(1, null);
		return Script.await(() -> {
			Item wanted = id == null ? null : item(id);
			int slot = terminal.findStored(stack -> stack.getItem() instanceof BlockItem && (wanted == null || stack.is(wanted)));

			if (slot < 0) {
				return failure(wanted == null ? "nothing to place" : "no such block in storage");
			}

			ItemStack stack = terminal.getItem(slot);
			// As if set down on the ground in front: logs stand upright, saplings check their soil.
			DirectionalPlaceContext context = new DirectionalPlaceContext(terminal.serverLevel(), terminal.frontPos(), terminal.facing(), stack, Direction.UP);

			if (!context.canPlace()) {
				return failure("blocked");
			}

			String placed = id(stack);

			if (!((BlockItem) stack.getItem()).place(context).consumesAction()) {
				return failure("cannot place");
			}

			terminal.setChanged();
			return LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(placed));
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	/** The tool in the tool slot and how many more blocks it will last, or nil for bare hands. */
	private Varargs tool(Varargs args) {
		return Script.await(() -> {
			ItemStack tool = terminal.tool();

			if (tool.isEmpty()) {
				return LuaValue.NIL;
			}

			return LuaValue.varargsOf(LuaValue.valueOf(id(tool)), LuaValue.valueOf(tool.getMaxDamage() - tool.getDamageValue()));
		});
	}

	/** How many items are in storage: of the given kind, or of every kind. */
	private Varargs count(Varargs args) {
		String id = args.optjstring(1, null);
		return Script.await(() -> LuaValue.valueOf(terminal.stored(id == null ? null : item(id))));
	}

	/** Unloads storage into the containers touching the terminal. */
	private Varargs push(Varargs args) {
		return Script.await(() -> LuaValue.valueOf(terminal.pushToNeighbors()));
	}

	/** Accepts any number or, for convenience, true and false for full redstone power and none. */
	private Varargs emit(Varargs args) {
		LuaValue value = args.arg1();
		double signal = value.isboolean() ? (value.toboolean() ? FULL_POWER : 0) : args.checkdouble(1);
		return Script.await(() -> {
			terminal.setOutput(signal);
			return LuaValue.NONE;
		});
	}

	/** What other terminals emit onto this terminal's wires. */
	private Varargs signal(Varargs args) {
		return Script.await(() -> LuaValue.valueOf(terminal.wireSignal()));
	}

	/**
	 * Redstone is the slow way to talk: reading it takes the rest of the tick, where reading
	 * a wire costs next to nothing.
	 */
	private Varargs redstone(Varargs args) {
		return Script.await(() -> LuaValue.valueOf(terminal.redstoneInput()), Script.INSTRUCTIONS_PER_TICK);
	}

	private Varargs help(Varargs args) {
		return Script.await(() -> {
			signatures().forEach(terminal::log);
			return LuaValue.NONE;
		});
	}
}
