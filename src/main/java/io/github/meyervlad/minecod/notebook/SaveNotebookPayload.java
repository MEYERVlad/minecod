package io.github.meyervlad.minecod.notebook;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.Programs;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Client to server: the player closed the notebook in this hand with this text in it. */
public record SaveNotebookPayload(InteractionHand hand, String program) implements CustomPacketPayload {
	public static final Type<SaveNotebookPayload> TYPE = new Type<>(Minecod.id("save_notebook"));

	public static final StreamCodec<ByteBuf, SaveNotebookPayload> CODEC = StreamCodec.composite(
			InteractionHand.STREAM_CODEC, SaveNotebookPayload::hand,
			ByteBufCodecs.stringUtf8(Programs.MAX_LENGTH), SaveNotebookPayload::program,
			SaveNotebookPayload::new);

	@Override
	public Type<SaveNotebookPayload> type() {
		return TYPE;
	}

	/** Server side. The payload comes from a client: it only counts if a notebook really is in that hand. */
	static void handle(SaveNotebookPayload payload, ServerPlayNetworking.Context context) {
		ItemStack stack = context.player().getItemInHand(payload.hand());

		if (stack.getItem() instanceof NotebookItem) {
			stack.set(Programs.COMPONENT, Programs.clip(payload.program()));
		}
	}
}
