package io.github.meyervlad.minecod.notebook;

import io.github.meyervlad.minecod.Minecod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

/** Server to client: open the editor for the notebook in this hand. */
public record OpenNotebookPayload(InteractionHand hand) implements CustomPacketPayload {
	public static final Type<OpenNotebookPayload> TYPE = new Type<>(Minecod.id("open_notebook"));

	public static final StreamCodec<ByteBuf, OpenNotebookPayload> CODEC =
			StreamCodec.composite(InteractionHand.STREAM_CODEC, OpenNotebookPayload::hand, OpenNotebookPayload::new);

	@Override
	public Type<OpenNotebookPayload> type() {
		return TYPE;
	}
}
