package io.github.meyervlad.minecod.terminal;

import java.util.List;

import io.github.meyervlad.minecod.Minecod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: what a terminal's screen should show.
 *
 * @param log        the lines the program printed, oldest first
 * @param openScreen true when the player just right-clicked the terminal; false for an update
 *                   to a screen that is already open
 */
public record TerminalStatePayload(BlockPos pos, String program, List<String> log, TerminalStatus status, boolean openScreen) implements CustomPacketPayload {
	public static final Type<TerminalStatePayload> TYPE = new Type<>(Minecod.id("terminal_state"));

	public static final StreamCodec<ByteBuf, TerminalStatePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TerminalStatePayload::pos,
			ByteBufCodecs.stringUtf8(TerminalBlockEntity.MAX_PROGRAM_LENGTH), TerminalStatePayload::program,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(TerminalBlockEntity.MAX_LOG_LINES)), TerminalStatePayload::log,
			ByteBufCodecs.VAR_INT.map(TerminalStatus::byId, TerminalStatus::ordinal), TerminalStatePayload::status,
			ByteBufCodecs.BOOL, TerminalStatePayload::openScreen,
			TerminalStatePayload::new);

	@Override
	public Type<TerminalStatePayload> type() {
		return TYPE;
	}
}
