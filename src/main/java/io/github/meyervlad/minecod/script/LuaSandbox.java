package io.github.meyervlad.minecod.script;

import java.util.regex.Pattern;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseMathLib;

/**
 * Builds Lua states that are safe to hand to players: plain Lua 5.2 with {@code string},
 * {@code table}, {@code math} and {@code bit32}, and no way to reach files, the OS or Java.
 *
 * <p>Memory use is not limited; do not expose this to players you do not trust.
 */
public final class LuaSandbox {
	/**
	 * Globals that would let a script escape. {@code load} is on the list because code loaded
	 * with a custom environment would run without the instruction hook, i.e. without limits.
	 */
	private static final String[] REMOVED = {
		"debug", "package", "require", "dofile", "loadfile", "load", "loadstring", "collectgarbage", "print"
	};

	private static final Pattern POSITION = Pattern.compile("^=?([^\\s:]+:\\d+):?\\s+");

	private LuaSandbox() {
	}

	public static Globals create() {
		Globals globals = new Globals();
		globals.load(new BaseLib());
		globals.load(new PackageLib());
		globals.load(new Bit32Lib());
		globals.load(new TableLib());
		globals.load(new StringLib());
		globals.load(new JseMathLib());
		// Must stay loaded: it drives the instruction hook that Script relies on.
		globals.load(new DebugLib());
		LuaC.install(globals);

		for (String name : REMOVED) {
			globals.set(name, LuaValue.NIL);
		}

		// The string metatable is shared by every Lua state in the JVM.
		LuaString.s_metatable = new ReadOnlyTable(LuaString.s_metatable);
		return globals;
	}

	/**
	 * Compiles player input. Like a REPL, it first tries the input as an expression, so that
	 * {@code 2 + 2} yields a result.
	 *
	 * @throws LuaError on a syntax error
	 */
	public static LuaValue compile(Globals globals, String code, String chunkName) {
		try {
			return globals.load("return " + code, chunkName);
		} catch (LuaError notAnExpression) {
			return globals.load(code, chunkName);
		}
	}

	/**
	 * A one-line, player-friendly version of a Lua error: {@code run:2: attempt to call nil}.
	 * LuaJ's own message may carry a stack traceback and writes positions as {@code =run:2 ...}.
	 */
	public static String describe(LuaError error) {
		String message = error.getMessage();

		if (message == null) {
			return "error";
		}

		String firstLine = message.lines().findFirst().orElse("").strip();
		return POSITION.matcher(firstLine).replaceFirst("$1: ");
	}

	private static final class ReadOnlyTable extends LuaTable {
		ReadOnlyTable(LuaValue source) {
			for (Varargs entry = source.next(NIL); !entry.arg1().isnil(); entry = source.next(entry.arg1())) {
				LuaValue value = entry.arg(2);
				super.rawset(entry.arg1(), value.istable() ? new ReadOnlyTable(value) : value);
			}
		}

		@Override
		public LuaValue setmetatable(LuaValue metatable) {
			return error("table is read-only");
		}

		@Override
		public void set(int key, LuaValue value) {
			error("table is read-only");
		}

		@Override
		public void rawset(int key, LuaValue value) {
			error("table is read-only");
		}

		@Override
		public void rawset(LuaValue key, LuaValue value) {
			error("table is read-only");
		}

		@Override
		public LuaValue remove(int pos) {
			return error("table is read-only");
		}
	}
}
