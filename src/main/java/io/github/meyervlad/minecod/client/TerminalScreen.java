package io.github.meyervlad.minecod.client;

import java.util.List;

import io.github.meyervlad.minecod.terminal.TerminalActionPayload;
import io.github.meyervlad.minecod.terminal.TerminalActionPayload.Action;
import io.github.meyervlad.minecod.terminal.TerminalBlockEntity;
import io.github.meyervlad.minecod.terminal.TerminalStatePayload;
import io.github.meyervlad.minecod.terminal.TerminalStatus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * A terminal's editor: the program on top, what it printed below, and buttons to run and
 * stop it. Closing the screen saves the text.
 */
public final class TerminalScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int TITLE_HEIGHT = 20;
	private static final int LOG_LINES = 6;
	private static final int BUTTON_WIDTH = 70;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int LABEL_HEIGHT = 14;
	private static final int INDENT = 2;
	private static final int REFRESH_INTERVAL_TICKS = 10;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int AQUA = 0xFF55FFFF;
	private static final int BLACK = 0xFF000000;

	private final BlockPos pos;
	private String program;
	private List<String> log;
	private TerminalStatus status;

	private MultiLineEditBox editor;
	private int logTop;
	private int age;

	public TerminalScreen(TerminalStatePayload state) {
		super(Component.translatable("block.minecod.terminal"));
		this.pos = state.pos();
		this.program = state.program();
		this.log = state.log();
		this.status = state.status();
	}

	public BlockPos pos() {
		return pos;
	}

	/** New output or status from the server. The text being edited is left alone. */
	public void update(TerminalStatePayload state) {
		log = state.log();
		status = state.status();
	}

	@Override
	protected void init() {
		int logHeight = LOG_LINES * font.lineHeight + GAP;
		int buttonsTop = height - MARGIN - BUTTON_HEIGHT;
		logTop = buttonsTop - GAP - logHeight;
		// Between the editor and the log sits one line of text: the "Output" label on the left
		// and, drawn by the editor itself, the character counter on the right.
		int editorHeight = logTop - LABEL_HEIGHT - TITLE_HEIGHT;

		editor = MultiLineEditBox.builder()
				.setX(MARGIN)
				.setY(TITLE_HEIGHT)
				.setPlaceholder(Component.translatable("minecod.terminal.placeholder"))
				.build(font, width - 2 * MARGIN, editorHeight, Component.translatable("minecod.terminal.program"));
		editor.setCharacterLimit(TerminalBlockEntity.MAX_PROGRAM_LENGTH);
		// init() runs again when the window is resized; keep what was typed.
		editor.setValue(program);
		editor.setValueListener(text -> program = text);
		addRenderableWidget(editor);
		setInitialFocus(editor);

		int x = width - MARGIN - BUTTON_WIDTH;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		x -= BUTTON_WIDTH + GAP;
		addRenderableWidget(Button.builder(Component.translatable("minecod.terminal.stop"), button -> send(Action.STOP))
				.bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		x -= BUTTON_WIDTH + GAP;
		addRenderableWidget(Button.builder(Component.translatable("minecod.terminal.run"), button -> send(Action.RUN))
				.bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
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
	public void tick() {
		if (++age % REFRESH_INTERVAL_TICKS == 0) {
			send(Action.REFRESH);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, (TITLE_HEIGHT - font.lineHeight) / 2, WHITE);

		int logWidth = width - 2 * MARGIN;
		int logHeight = LOG_LINES * font.lineHeight + GAP;
		graphics.text(font, Component.translatable("minecod.terminal.output"), MARGIN, logTop - LABEL_HEIGHT + GAP, GRAY);
		graphics.fill(MARGIN, logTop, MARGIN + logWidth, logTop + logHeight, BLACK);
		graphics.outline(MARGIN, logTop, logWidth, logHeight, GRAY);

		// The newest lines, with the last one at the bottom.
		int first = Math.max(0, log.size() - LOG_LINES);

		for (int i = first; i < log.size(); i++) {
			String line = log.get(i);
			int y = logTop + GAP / 2 + (i - first) * font.lineHeight;
			graphics.text(font, font.plainSubstrByWidth(line, logWidth - 2 * GAP), MARGIN + GAP, y, colorOf(line));
		}

		Component statusText = Component.translatable("minecod.terminal.status." + status.getSerializedName());
		int statusY = height - MARGIN - BUTTON_HEIGHT + (BUTTON_HEIGHT - font.lineHeight) / 2;
		graphics.text(font, statusText, MARGIN, statusY, switch (status) {
			case IDLE -> GRAY;
			case RUNNING -> GREEN;
			case ERROR -> RED;
		});
	}

	private static int colorOf(String logLine) {
		if (logLine.startsWith(TerminalBlockEntity.ERROR_PREFIX)) {
			return RED;
		}

		return logLine.startsWith(TerminalBlockEntity.RESULT_PREFIX) ? AQUA : WHITE;
	}

	@Override
	public void onClose() {
		send(Action.SAVE);
		super.onClose();
	}

	/** The world keeps running while you type, so you can watch your program work. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void send(Action action) {
		boolean carriesProgram = action == Action.SAVE || action == Action.RUN;
		ClientPlayNetworking.send(new TerminalActionPayload(pos, action, carriesProgram ? program : ""));
	}
}
