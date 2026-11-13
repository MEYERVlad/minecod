package io.github.meyervlad.minecod;

import io.github.meyervlad.minecod.command.MinecodCommands;
import io.github.meyervlad.minecod.session.Sessions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

public final class Minecod implements ModInitializer {
	public static final String MOD_ID = "minecod";

	/** A chat message starting with this is run as code instead of being sent. */
	public static final String CHAT_PREFIX = ">";

	private static final Sessions SESSIONS = new Sessions();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static Sessions sessions() {
		return SESSIONS;
	}

	@Override
	public void onInitialize() {
		HandMining.init();

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> MinecodCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			HandMining.tick(server);
			SESSIONS.tick();
		});

		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> SESSIONS.close(listener.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> SESSIONS.closeAll());

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent();

			if (!text.startsWith(CHAT_PREFIX)) {
				return true;
			}

			String code = text.substring(CHAT_PREFIX.length()).strip();
			MinecraftServer server = sender.level().getServer();
			// Sessions belong to the server thread.
			server.execute(() -> SESSIONS.run(sender, code));
			return false;
		});
	}
}
