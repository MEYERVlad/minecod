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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * A terminal's editor: the program on top, what it printed below, and buttons to run it,
 * stop it and get at its inventory. Closing the screen saves the text.
 */
public final class TerminalScreen extends CodeScreen {
	private static final int LOG_LINES = 6;
	private static final int LABEL_HEIGHT = 14;
	private static final int REFRESH_INTERVAL_TICKS = 10;

	private static final int GRAY = 0xFFA0A0A0;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int AQUA = 0xFF55FFFF;
	private static final int BLACK = 0xFF000000;

	private final BlockPos pos;
	private List<String> log;
	private TerminalStatus status;

	private int logTop;
	private int age;

	public TerminalScreen(TerminalStatePayload state) {
		super(Component.translatable("block.minecod.terminal"), state.program());
		this.pos = state.pos();
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
		int buttonsTop = buttonsTop();
		logTop = buttonsTop - GAP - logHeight();
		// Between the editor and the log sits one line of text: the "Output" label on the left
		// and, drawn by the editor itself, the character counter on the right.
		addEditor(logTop - LABEL_HEIGHT, Component.translatable("minecod.terminal.placeholder"));

		int x = width - MARGIN - BUTTON_WIDTH;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		x -= BUTTON_WIDTH + GAP;
		addRenderableWidget(Button.builder(Component.translatable("minecod.terminal.stop"), button -> send(Action.STOP))
				.bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		x -= BUTTON_WIDTH + GAP;
		addRenderableWidget(Button.builder(Component.translatable("minecod.terminal.run"), button -> send(Action.RUN))
				.bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("minecod.terminal.inventory"), button -> {
			// The inventory replaces this screen without closing it, so save by hand.
			send(Action.SAVE);
			send(Action.INVENTORY);
		}).bounds(MARGIN, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private int logHeight() {
		return LOG_LINES * font.lineHeight + GAP;
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

		int logWidth = width - 2 * MARGIN;
		graphics.text(font, Component.translatable("minecod.terminal.output"), MARGIN, logTop - LABEL_HEIGHT + GAP, GRAY);
		graphics.fill(MARGIN, logTop, MARGIN + logWidth, logTop + logHeight(), BLACK);
		graphics.outline(MARGIN, logTop, logWidth, logHeight(), GRAY);

		// The newest lines, with the last one at the bottom.
		int first = Math.max(0, log.size() - LOG_LINES);

		for (int i = first; i < log.size(); i++) {
			String line = log.get(i);
			int y = logTop + GAP / 2 + (i - first) * font.lineHeight;
			graphics.text(font, font.plainSubstrByWidth(line, logWidth - 2 * GAP), MARGIN + GAP, y, colorOf(line));
		}

		Component statusText = Component.translatable("minecod.terminal.status." + status.getSerializedName());
		int titleY = (TITLE_HEIGHT - font.lineHeight) / 2;
		graphics.text(font, statusText, width - MARGIN - font.width(statusText), titleY, switch (status) {
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

	private void send(Action action) {
		boolean carriesProgram = action == Action.SAVE || action == Action.RUN;
		ClientPlayNetworking.send(new TerminalActionPayload(pos, action, carriesProgram ? program : ""));
	}
}
