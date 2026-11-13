package io.github.meyervlad.minecod.client;

import io.github.meyervlad.minecod.notebook.SaveNotebookPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/** A notebook's editor. Closing it saves the text onto the item. */
public final class NotebookScreen extends CodeScreen {
	private final InteractionHand hand;

	public NotebookScreen(InteractionHand hand, String program) {
		super(Component.translatable("item.minecod.notebook"), program);
		this.hand = hand;
	}

	@Override
	protected void init() {
		addEditor(buttonsTop() - GAP, Component.translatable("minecod.notebook.placeholder"));
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(width - MARGIN - BUTTON_WIDTH, buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	@Override
	public void onClose() {
		ClientPlayNetworking.send(new SaveNotebookPayload(hand, program));
		super.onClose();
	}
}
