package io.github.meyervlad.minecod.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

class ScriptTest {
	private static final Duration LIMIT = Duration.ofSeconds(10);

	private Globals globals;
	private final List<String> printed = new ArrayList<>();

	@BeforeEach
	void setUp() {
		globals = LuaSandbox.create();
		globals.set("print", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				String text = Script.display(args.arg1());
				return Script.await(() -> {
					printed.add(text);
					return LuaValue.NONE;
				});
			}
		});
	}

	private Script start(String code) {
		return Script.start(globals, LuaSandbox.compile(globals, code, "=test"), "test");
	}

	/** Ticks until the script ends; fails the test if it takes more than {@code maxTicks}. */
	private Script run(String code, int maxTicks) {
		Script script = start(code);

		for (int i = 0; i < maxTicks && !script.isDone(); i++) {
			script.tick();
		}

		assertTrue(script.isDone(), "script still running after " + maxTicks + " ticks");
		return script;
	}

	private static void assertThreadEnds(Script script) throws InterruptedException {
		script.thread().join(5_000);
		assertFalse(script.thread().isAlive(), "script thread is still alive");
	}

	// ------------------------------------------------------------ running code

	@Test
	void expressionYieldsItsValue() {
		Script script = run("2 + 2", 1);
		assertEquals(Script.State.FINISHED, script.state());
		assertEquals(List.of("4"), script.results());
	}

	@Test
	void statementsRunAndGlobalsPersistBetweenScripts() {
		assertEquals(List.of(), run("x = 5", 1).results());
		assertEquals(List.of("10"), run("x * 2", 1).results());
	}

	@Test
	void multipleResultsAndToStringMetamethod() {
		Script script = run("return 1, 'a', setmetatable({}, {__tostring = function() return 'custom' end})", 1);
		assertEquals(List.of("1", "a", "custom"), script.results());
	}

	@Test
	void syntaxErrorIsThrownByCompile() {
		LuaError error = assertThrows(LuaError.class, () -> LuaSandbox.compile(globals, "for for", "=test"));
		assertTrue(error.getMessage().contains("test:1"), error.getMessage());
	}

	@Test
	void runtimeErrorFailsTheScript() {
		Script script = run("local t = nil\nreturn t.x", 1);
		assertEquals(Script.State.FAILED, script.state());
		assertTrue(script.error().contains("test:2"), script.error());

		script = run("error('boom')", 1);
		assertEquals(Script.State.FAILED, script.state());
		assertTrue(script.error().contains("boom"), script.error());
	}

	@Test
	void deepRecursionFailsCleanly() {
		Script script = assertTimeoutPreemptively(LIMIT, () -> run("local function f(n) return 1 + f(n + 1) end return f(1)", 100_000));
		assertEquals(Script.State.FAILED, script.state());
	}

	// ------------------------------------------------------------ time slicing

	@Test
	void infiniteLoopIsSlicedIntoTicks() throws InterruptedException {
		Script script = start("n = 0 while true do n = n + 1 end");

		assertTimeoutPreemptively(LIMIT, script::tick);
		int afterOne = globals.get("n").toint();
		assertTimeoutPreemptively(LIMIT, script::tick);
		int afterTwo = globals.get("n").toint();

		assertEquals(Script.State.RUNNING, script.state());
		assertTrue(afterOne > 0 && afterOne <= Script.INSTRUCTIONS_PER_TICK, "iterations in one tick: " + afterOne);
		assertTrue(afterTwo > afterOne, "no progress on the second tick");
		assertTrue(afterTwo <= 2 * Script.INSTRUCTIONS_PER_TICK, "iterations in two ticks: " + afterTwo);

		script.kill();
		assertEquals(Script.State.KILLED, script.state());
		assertThreadEnds(script);
	}

	@Test
	void pcallCannotSwallowKill() throws InterruptedException {
		Script script = start("while true do pcall(function() while true do end end) end");
		assertTimeoutPreemptively(LIMIT, script::tick);
		assertEquals(Script.State.RUNNING, script.state());

		script.kill();
		assertThreadEnds(script);
	}

	@Test
	void luaStateIsReusableRightAfterKill() {
		Script looping = start("while true do end");
		looping.tick();
		looping.kill();
		assertFalse(looping.thread().isAlive(), "kill() returned before the script thread ended");

		assertEquals(List.of("2"), run("1 + 1", 1).results());
	}

	@Test
	void killCancelsThePendingTask() {
		AtomicInteger cancelled = new AtomicInteger();
		globals.set("forever", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return Script.await(new Task() {
					@Override
					public Varargs tick() {
						return null;
					}

					@Override
					public void cancel() {
						cancelled.incrementAndGet();
					}
				});
			}
		});

		Script script = start("forever()");
		script.tick();
		script.tick();
		assertEquals(0, cancelled.get());
		script.kill();
		script.kill();
		assertEquals(1, cancelled.get());
	}

	@Test
	void killBeforeFirstTick() throws InterruptedException {
		Script script = start("return 1");
		script.kill();
		script.tick();
		assertEquals(Script.State.KILLED, script.state());
		assertThreadEnds(script);
	}

	@Test
	void finishedScriptThreadEnds() throws InterruptedException {
		assertThreadEnds(run("return 1", 1));
	}

	// ------------------------------------------------------------ tasks

	@Test
	void tasksRunOnTheTickingThread() {
		AtomicReference<Thread> taskThread = new AtomicReference<>();
		AtomicReference<Thread> luaThread = new AtomicReference<>();
		globals.set("probe", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				luaThread.set(Thread.currentThread());
				return Script.await(() -> {
					taskThread.set(Thread.currentThread());
					return LuaValue.valueOf(42);
				});
			}
		});

		assertEquals(List.of("42"), run("probe()", 1).results());
		assertSame(Thread.currentThread(), taskThread.get());
		assertTrue(luaThread.get() != Thread.currentThread());
	}

	@Test
	void slowTaskSuspendsTheScriptAcrossTicks() {
		AtomicInteger calls = new AtomicInteger();
		globals.set("slow", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return Script.await(() -> calls.incrementAndGet() < 4 ? null : LuaValue.valueOf("done"));
			}
		});

		Script script = start("print('before') local r = slow() print(r)");
		script.tick();
		assertEquals(List.of("before"), printed);
		script.tick();
		script.tick();
		assertEquals(Script.State.RUNNING, script.state());
		assertEquals(3, calls.get());
		script.tick();
		assertEquals(Script.State.FINISHED, script.state());
		assertEquals(List.of("before", "done"), printed);
	}

	@Test
	void taskErrorBecomesALuaError() {
		globals.set("fail", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return Script.await(() -> {
					throw new LuaError("nope");
				});
			}
		});

		assertEquals(List.of("false", "nope"), run("return pcall(fail)", 1).results());

		Script script = run("fail()", 1);
		assertEquals(Script.State.FAILED, script.state());
		assertTrue(script.error().contains("nope"), script.error());
	}

	@Test
	void expensiveTaskRunsOncePerTick() {
		AtomicInteger calls = new AtomicInteger();
		globals.set("costly", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return Script.await(() -> LuaValue.valueOf(calls.incrementAndGet()), Script.INSTRUCTIONS_PER_TICK);
			}
		});

		Script script = start("while true do costly() end");

		for (int tick = 1; tick <= 5; tick++) {
			script.tick();
			assertEquals(tick, calls.get());
		}

		script.kill();
	}

	@Test
	void cheapTasksAreStillLimitedPerTick() {
		AtomicInteger calls = new AtomicInteger();
		globals.set("cheap", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return Script.await(() -> LuaValue.valueOf(calls.incrementAndGet()));
			}
		});

		Script script = start("while true do cheap() end");
		script.tick();
		int limit = Script.INSTRUCTIONS_PER_TICK / Script.TASK_COST;
		assertTrue(calls.get() > 0 && calls.get() <= limit, "calls in one tick: " + calls.get());
		script.kill();
	}

	@Test
	void awaitOutsideAScriptIsAnError() {
		assertThrows(LuaError.class, () -> Script.await(() -> LuaValue.NONE));
	}

	@Test
	void scriptThatBlocksIsAbandoned() throws InterruptedException {
		globals.set("block", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException e) {
					// Interrupted by the kill; carry on so the instruction hook can end us.
				}

				return NONE;
			}
		});

		Script script = Script.start(globals, LuaSandbox.compile(globals, "block() while true do end", "=test"), "test", 100);
		assertTimeoutPreemptively(LIMIT, script::tick);
		assertEquals(Script.State.HUNG, script.state());
		assertThreadEnds(script);
	}

	// ------------------------------------------------------------ sandbox

	@Test
	void dangerousGlobalsAreGone() {
		for (String name : List.of("io", "os", "luajava", "require", "package", "debug", "load", "loadstring", "dofile", "loadfile", "collectgarbage", "coroutine")) {
			assertEquals(List.of("nil"), run("return type(" + name + ")", 1).results(), name);
		}
	}

	@Test
	void standardLibrariesWork() {
		assertEquals(List.of("3"), run("return math.max(1, 3)", 1).results());
		assertEquals(List.of("abab"), run("return ('ab'):rep(2)", 1).results());
		assertEquals(List.of("1,2"), run("return table.concat({1, 2}, ',')", 1).results());
		assertEquals(List.of("7"), run("return bit32.bor(3, 4)", 1).results());
		assertEquals(List.of("true", "caught"), run("return pcall(function() return 'caught' end)", 1).results());
	}

	@Test
	void sharedStringMetatableCannotBeModified() {
		assertEquals(Script.State.FAILED, run("getmetatable('').__index.rep = nil", 1).state());
		assertEquals(Script.State.FAILED, run("getmetatable('').__index = {}", 1).state());
		assertEquals(List.of("xx"), run("return ('x'):rep(2)", 1).results());
	}
}
