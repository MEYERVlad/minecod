package io.github.meyervlad.minecod.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.meyervlad.minecod.Messages;
import io.github.meyervlad.minecod.api.PlayerApi;
import io.github.meyervlad.minecod.notebook.Notebook;
import io.github.meyervlad.minecod.script.LuaSandbox;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * One player's Lua state and the script they are running, if any. The Lua state lives as long
 * as the player stays online, so variables carry over from one {@code /run} to the next.
 *
 * <p>Whatever is written in the notebooks the player carries is run first, once, and again
 * whenever it changes, so that the functions defined there can be called from chat.
 */
final class Session {
	private final UUID playerId;
	private final MinecraftServer server;
	private Globals globals;
	private Script script;
	/**
	 * The notebook texts that have been run in {@link #globals}; null if none have yet.
	 * Written by the script thread when it gets through them, hence volatile.
	 */
	private volatile List<String> loadedNotebooks;

	Session(UUID playerId, MinecraftServer server) {
		this.playerId = playerId;
		this.server = server;
	}

	/** Looked up every time: the player object is replaced on respawn. */
	private ServerPlayer player() {
		return server.getPlayerList().getPlayer(playerId);
	}

	boolean isRunning() {
		return script != null;
	}

	void run(ServerPlayer player, String code) {
		if (script != null) {
			player.sendSystemMessage(Messages.error(Component.translatable("minecod.run.busy")));
			return;
		}

		if (globals == null) {
			globals = LuaSandbox.create();
			loadedNotebooks = null;
			PlayerApi.install(globals, this::player);
		}

		player.sendSystemMessage(Messages.echo(code));
		LuaValue chunk;

		try {
			chunk = withNotebooks(Notebook.programs(player), LuaSandbox.compile(globals, code, "=run"));
		} catch (LuaError e) {
			player.sendSystemMessage(Messages.error(LuaSandbox.describe(e)));
			return;
		}

		script = Script.start(globals, chunk, player.getScoreboardName());
	}

	/**
	 * Puts the notebooks in front of the player's code, unless these very texts have already
	 * been run. An error in a notebook stops everything, and since the notebooks then do not
	 * count as loaded, the player hears about it on every run until it is fixed.
	 *
	 * @throws LuaError on a syntax error in a notebook
	 */
	private LuaValue withNotebooks(List<String> notebooks, LuaValue code) {
		if (notebooks.equals(loadedNotebooks)) {
			return code;
		}

		List<LuaValue> chunks = new ArrayList<>();

		for (String notebook : notebooks) {
			chunks.add(LuaSandbox.compileProgram(globals, notebook, "=notebook"));
		}

		return new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				for (LuaValue chunk : chunks) {
					chunk.invoke();
				}

				loadedNotebooks = notebooks;
				return code.invoke();
			}
		};
	}

	void tick() {
		if (script == null) {
			return;
		}

		script.tick();

		if (!script.isDone()) {
			return;
		}

		Script finished = script;
		script = null;
		ServerPlayer player = player();

		switch (finished.state()) {
			case FINISHED -> {
				if (player != null && !finished.results().isEmpty()) {
					player.sendSystemMessage(Messages.result(finished.results()));
				}
			}
			case FAILED -> {
				if (player != null) {
					player.sendSystemMessage(Messages.error(finished.error()));
				}
			}
			case HUNG -> {
				// Its thread may still be inside the Lua state; never touch that state again.
				globals = null;
				loadedNotebooks = null;

				if (player != null) {
					player.sendSystemMessage(Messages.error(Component.translatable("minecod.run.hung")));
				}
			}
			case KILLED, RUNNING -> { }
		}
	}

	/** @return whether there was a script to stop */
	boolean halt() {
		if (script == null) {
			return false;
		}

		script.kill();

		if (script.state() == Script.State.HUNG) {
			globals = null;
			loadedNotebooks = null;
		}

		script = null;
		return true;
	}
}
