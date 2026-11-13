package io.github.meyervlad.minecod.terminal;

import io.github.meyervlad.minecod.Minecod;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client to server: something the player did on a terminal's screen.
 *
 * @param program the text in the editor; only read for {@link Action#SAVE} and {@link Action#RUN}
 */
public record TerminalActionPayload(BlockPos pos, Action action, String program) implements CustomPacketPayload {
	public enum Action {
		/** Store the program without touching whatever is running. */
		SAVE,
		/** Store the program and start it from the beginning. */
		RUN,
		STOP,
		/** Just send back the current log and status. */
		REFRESH,
		/** Switch from the editor to the terminal's inventory. */
		INVENTORY;

		private static final Action[] VALUES = values();

		static Action byId(int id) {
			return id >= 0 && id < VALUES.length ? VALUES[id] : REFRESH;
		}
	}

	public static final Type<TerminalActionPayload> TYPE = new Type<>(Minecod.id("terminal_action"));

	public static final StreamCodec<ByteBuf, TerminalActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TerminalActionPayload::pos,
			ByteBufCodecs.VAR_INT.map(Action::byId, Action::ordinal), TerminalActionPayload::action,
			ByteBufCodecs.stringUtf8(TerminalBlockEntity.MAX_PROGRAM_LENGTH), TerminalActionPayload::program,
			TerminalActionPayload::new);

	/** How far beyond normal reach a player may be and still use the screen they opened. */
	private static final double REACH_LEEWAY = 4.0;

	@Override
	public Type<TerminalActionPayload> type() {
		return TYPE;
	}

	/** Server side. The payload comes from a client, so nothing in it is trusted. */
	static void handle(TerminalActionPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();

		// The range check also keeps this from loading far-away chunks.
		if (player.isSpectator() || !player.isWithinBlockInteractionRange(payload.pos(), REACH_LEEWAY)) {
			return;
		}

		if (!(player.level().getBlockEntity(payload.pos()) instanceof TerminalBlockEntity terminal)) {
			return;
		}

		switch (payload.action()) {
			case SAVE -> terminal.setProgram(payload.program());
			case RUN -> {
				terminal.setProgram(payload.program());
				terminal.run();
			}
			case STOP -> terminal.stop();
			case REFRESH -> { }
			case INVENTORY -> {
				player.openMenu(terminal);
				return;
			}
		}

		ServerPlayNetworking.send(player, terminal.state(false));
	}
}
