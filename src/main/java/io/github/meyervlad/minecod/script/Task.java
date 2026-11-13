package io.github.meyervlad.minecod.script;

import org.luaj.vm2.Varargs;

/**
 * Work a script has asked the game to do, e.g. "break this block" or "wait two seconds".
 *
 * <p>Scripts run on their own thread and must never touch the world directly. Instead a Lua
 * function hands a task to {@link Script#await}, and the server thread runs it.
 */
@FunctionalInterface
public interface Task {
	/**
	 * Called on the server thread, once per tick, until it returns a result.
	 *
	 * @return the values to hand back to Lua, or {@code null} to be called again next tick
	 * @throws org.luaj.vm2.LuaError to raise an error inside the script
	 */
	Varargs tick();

	/**
	 * Called on the server thread if the script is stopped while this task is still working.
	 * Undo anything half-done here.
	 */
	default void cancel() {
	}
}
