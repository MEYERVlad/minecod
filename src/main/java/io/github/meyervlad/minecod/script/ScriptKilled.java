package io.github.meyervlad.minecod.script;

/**
 * Thrown on the script thread to unwind a script that has been stopped.
 *
 * <p>Deliberately an {@link Error}: Lua's {@code pcall} only catches exceptions, so a script
 * cannot swallow its own termination.
 */
final class ScriptKilled extends Error {
	ScriptKilled() {
		super("script killed", null, false, false);
	}
}
