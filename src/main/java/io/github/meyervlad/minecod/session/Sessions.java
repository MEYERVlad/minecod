package io.github.meyervlad.minecod.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/** Every player's {@link Session}. Server thread only. */
public final class Sessions {
	private final Map<UUID, Session> sessions = new HashMap<>();

	/** Compiles {@code code} and starts running it for the player. Feedback goes to their chat. */
	public void run(ServerPlayer player, String code) {
		sessions.computeIfAbsent(player.getUUID(), id -> new Session(id, player.level().getServer())).run(player, code);
	}

	/** @return whether the player had a script running */
	public boolean halt(ServerPlayer player) {
		Session session = sessions.get(player.getUUID());
		return session != null && session.halt();
	}

	public boolean isRunning(ServerPlayer player) {
		Session session = sessions.get(player.getUUID());
		return session != null && session.isRunning();
	}

	/** Advances every running script by one tick. */
	public void tick() {
		for (Session session : sessions.values()) {
			session.tick();
		}
	}

	/** Forgets a player who left, stopping their script. */
	public void close(UUID playerId) {
		Session session = sessions.remove(playerId);

		if (session != null) {
			session.halt();
		}
	}

	public void closeAll() {
		for (Session session : sessions.values()) {
			session.halt();
		}

		sessions.clear();
	}
}
