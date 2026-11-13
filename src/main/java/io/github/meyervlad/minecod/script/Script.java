package io.github.meyervlad.minecod.script;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One running Lua program.
 *
 * <p>The Lua code runs on its own thread, but never at the same time as the server thread:
 * the two pass control back and forth like a baton. {@link #tick()} hands the baton to the
 * script and blocks until the script gives it back, which happens when the script
 * <ul>
 *   <li>has used up its instruction budget for this tick,</li>
 *   <li>asks the game to do something ({@link #await}), or</li>
 *   <li>ends.</li>
 * </ul>
 * So a script can loop forever without freezing the game, and everything that touches the
 * world still happens on the server thread.
 */
public final class Script {
	/** How many Lua instructions a script may execute per server tick. */
	public static final int INSTRUCTIONS_PER_TICK = 20_000;
	/** Default price of one {@link #await} call, in instructions. */
	public static final int TASK_COST = 1_000;

	private static final int HOOK_INTERVAL = 100;
	private static final long KILL_WAIT_MILLIS = 200;
	/** How long the server waits for a script to hand control back before giving up on it. */
	private static final long HANG_TIMEOUT_MILLIS = 2_000;
	private static final long STACK_SIZE = 16L * 1024 * 1024;
	private static final Logger LOGGER = LoggerFactory.getLogger("minecod/script");
	private static final ThreadLocal<Script> CURRENT = new ThreadLocal<>();

	public enum State {
		RUNNING,
		/** Ran to the end; see {@link Script#results()}. */
		FINISHED,
		/** Stopped by a Lua error; see {@link Script#error()}. */
		FAILED,
		/** Stopped by {@link Script#kill()}. */
		KILLED,
		/** Did not give control back in time. Its thread may still be alive, so its Lua state must not be reused. */
		HUNG
	}

	/** What the server thread sends when it hands control to the script. */
	private record Resume(Varargs values, String error) {
		static final Resume EMPTY = new Resume(LuaValue.NONE, null);
	}

	/** What the script sends when it hands control back. */
	private sealed interface Signal {
		record Preempted() implements Signal { }

		record Await(Task task) implements Signal { }

		record Finished(List<String> results) implements Signal { }

		record Failed(String message) implements Signal { }
	}

	private final LuaValue chunk;
	private final Thread thread;
	private final long hangTimeoutMillis;
	private final BlockingQueue<Resume> toScript = new ArrayBlockingQueue<>(1);
	private final BlockingQueue<Signal> toServer = new ArrayBlockingQueue<>(1);

	// Server thread only.
	private State state = State.RUNNING;
	private Task pending;
	private List<String> results = List.of();
	private String error;

	// Owned by whichever thread currently holds the baton.
	private int budget;

	private volatile boolean killed;

	private Script(Globals globals, LuaValue chunk, String name, long hangTimeoutMillis) {
		this.chunk = chunk;
		this.hangTimeoutMillis = hangTimeoutMillis;

		// A fresh Lua thread gives every run clean debug state (call stack, hook).
		globals.running = new LuaThread(globals);
		globals.running.state.hookfunc = new Hook();
		globals.running.state.hookcount = HOOK_INTERVAL;

		this.thread = new Thread(null, this::threadMain, "Minecod script (" + name + ")", STACK_SIZE);
		this.thread.setDaemon(true);
	}

	/**
	 * Starts a script. Nothing runs until the first {@link #tick()}.
	 *
	 * @param globals the Lua state from {@link LuaSandbox}; at most one script may use it at a time
	 * @param chunk   compiled code, from {@link LuaSandbox#compile}
	 * @param name    for thread names and logs
	 */
	public static Script start(Globals globals, LuaValue chunk, String name) {
		return start(globals, chunk, name, HANG_TIMEOUT_MILLIS);
	}

	static Script start(Globals globals, LuaValue chunk, String name, long hangTimeoutMillis) {
		Script script = new Script(globals, chunk, name, hangTimeoutMillis);
		script.thread.start();
		return script;
	}

	// ---------------------------------------------------------------- server thread

	/** Lets the script run for up to one tick's worth of work. */
	public void tick() {
		if (state != State.RUNNING) {
			return;
		}

		Resume resume = Resume.EMPTY;

		if (pending != null) {
			resume = poll(pending);

			if (resume == null) {
				return;
			}

			pending = null;
		}

		budget = INSTRUCTIONS_PER_TICK;

		while (true) {
			Signal signal = exchange(resume);

			switch (signal) {
				case null -> {
					killed = true;
					thread.interrupt();
					state = State.HUNG;
					error = "script stopped responding";
					return;
				}
				case Signal.Preempted() -> {
					return;
				}
				case Signal.Await(Task task) -> {
					resume = poll(task);

					if (resume == null) {
						pending = task;
						return;
					}
				}
				case Signal.Finished(List<String> values) -> {
					state = State.FINISHED;
					results = values;
					return;
				}
				case Signal.Failed(String message) -> {
					state = State.FAILED;
					error = message;
					return;
				}
			}
		}
	}

	/**
	 * Stops the script. Safe to call at any time; does nothing if it already ended.
	 *
	 * <p>Afterwards the state is {@link State#KILLED} and the Lua state is free for the next
	 * script, or, should the thread refuse to die, {@link State#HUNG}.
	 */
	public void kill() {
		if (state != State.RUNNING) {
			return;
		}

		state = State.KILLED;
		killed = true;
		thread.interrupt();

		if (pending != null) {
			try {
				pending.cancel();
			} catch (RuntimeException e) {
				LOGGER.error("Task failed to cancel", e);
			}

			pending = null;
		}

		// The script is parked waiting for its turn, so it unwinds at once. Wait for that:
		// the caller may want to start another script on the same Lua state right away.
		try {
			thread.join(KILL_WAIT_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (thread.isAlive()) {
			state = State.HUNG;
		}
	}

	public State state() {
		return state;
	}

	public boolean isDone() {
		return state != State.RUNNING;
	}

	/** The values the script returned, already converted to text. */
	public List<String> results() {
		return results;
	}

	/** Why the script failed, or {@code null}. */
	public String error() {
		return error;
	}

	Thread thread() {
		return thread;
	}

	private Resume poll(Task task) {
		try {
			Varargs values = task.tick();
			return values == null ? null : new Resume(values, null);
		} catch (LuaError e) {
			return new Resume(null, LuaSandbox.describe(e));
		} catch (RuntimeException e) {
			LOGGER.error("Task failed", e);
			return new Resume(null, "internal error: " + e);
		}
	}

	/** Hands control to the script and waits for it to come back. Null means it did not. */
	private Signal exchange(Resume resume) {
		if (!toScript.offer(resume)) {
			return null;
		}

		try {
			return toServer.poll(hangTimeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	// ---------------------------------------------------------------- script thread

	/**
	 * Asks the server thread to run {@code task} and waits for its result. Call this from the
	 * Java side of a Lua function.
	 */
	public static Varargs await(Task task) {
		return await(task, TASK_COST);
	}

	/**
	 * @param cost how much of this tick's instruction budget the call uses up; pass
	 *             {@link #INSTRUCTIONS_PER_TICK} to allow at most one such call per tick
	 */
	public static Varargs await(Task task, int cost) {
		Script script = CURRENT.get();

		if (script == null) {
			throw new LuaError("this function only works inside a running script");
		}

		if (script.budget <= 0) {
			script.handBack(new Signal.Preempted());
		}

		script.budget -= cost;
		Resume resume = script.handBack(new Signal.Await(task));

		if (resume.error() != null) {
			throw new LuaError(resume.error());
		}

		return resume.values();
	}

	/** Converts a Lua value to text the way {@code tostring} does, honouring {@code __tostring}. */
	public static String display(LuaValue value) {
		LuaValue meta = value.metatag(LuaValue.TOSTRING);
		return meta.isnil() ? value.tojstring() : meta.call(value).tojstring();
	}

	private void threadMain() {
		CURRENT.set(this);
		Signal last;

		try {
			takeResume();
			Varargs values = chunk.invoke();
			List<String> texts = new ArrayList<>(values.narg());

			for (int i = 1; i <= values.narg(); i++) {
				texts.add(display(values.arg(i)));
			}

			last = new Signal.Finished(texts);
		} catch (ScriptKilled e) {
			return;
		} catch (LuaError e) {
			last = new Signal.Failed(LuaSandbox.describe(e));
		} catch (StackOverflowError e) {
			last = new Signal.Failed("stack overflow");
		} catch (Throwable t) {
			LOGGER.error("Script crashed", t);
			last = new Signal.Failed("internal error: " + t);
		}

		toServer.offer(last);
	}

	private Resume handBack(Signal signal) {
		if (!toServer.offer(signal)) {
			// The server gave up on us (see State.HUNG).
			throw new ScriptKilled();
		}

		return takeResume();
	}

	private Resume takeResume() {
		try {
			Resume resume = toScript.take();

			if (killed) {
				throw new ScriptKilled();
			}

			return resume;
		} catch (InterruptedException e) {
			throw new ScriptKilled();
		}
	}

	/** Called by the Lua VM every {@link #HOOK_INTERVAL} instructions. */
	private final class Hook extends ZeroArgFunction {
		@Override
		public LuaValue call() {
			if (killed) {
				throw new ScriptKilled();
			}

			budget -= HOOK_INTERVAL;

			if (budget <= 0) {
				handBack(new Signal.Preempted());
			}

			return NIL;
		}
	}
}
