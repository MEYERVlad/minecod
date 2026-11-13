package io.github.meyervlad.minecod.client;

import io.github.meyervlad.minecod.Programs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** A screen built around a box for editing Lua: what terminals and notebooks have in common. */
abstract class CodeScreen extends Screen {
	protected static final int MARGIN = 8;
	protected static final int TITLE_HEIGHT = 20;
	protected static final int BUTTON_WIDTH = 70;
	protected static final int BUTTON_HEIGHT = 20;
	protected static final int GAP = 4;
	protected static final int WHITE = 0xFFFFFFFF;

	private static final int INDENT = 2;

	/** The text being edited, kept up to date as the player types. */
	protected String program;
	private MultiLineEditBox editor;

	protected CodeScreen(Component title, String program) {
		super(title);
		this.program = program;
	}

	/** Where the row of buttons along the bottom of the screen starts. */
	protected int buttonsTop() {
		return height - MARGIN - BUTTON_HEIGHT;
	}

	/**
	 * Adds the editor, as wide as the screen, from under the title down to {@code bottom}.
	 * Call from {@code init()}.
	 */
	protected void addEditor(int bottom, Component placeholder) {
		editor = MultiLineEditBox.builder()
				.setX(MARGIN)
				.setY(TITLE_HEIGHT)
				.setPlaceholder(placeholder)
				.build(font, width - 2 * MARGIN, bottom - TITLE_HEIGHT, Component.translatable("minecod.terminal.program"));
		editor.setCharacterLimit(Programs.MAX_LENGTH);
		// init() runs again when the window is resized; keep what was typed.
		editor.setValue(program);
		editor.setValueListener(text -> program = text);
		addRenderableWidget(editor);
		setInitialFocus(editor);
	}

	/** Tab indents instead of jumping to the buttons. Shift+Tab still moves the focus. */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isCycleFocus() && !event.hasShiftDown() && editor.isFocused()) {
			for (int i = 0; i < INDENT; i++) {
				editor.charTyped(new CharacterEvent(' '));
			}

			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, (TITLE_HEIGHT - font.lineHeight) / 2, WHITE);
	}

	/** The world keeps running while you type. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
