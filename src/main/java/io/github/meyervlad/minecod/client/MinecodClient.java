package io.github.meyervlad.minecod.client;

import io.github.meyervlad.minecod.terminal.Terminal;
import io.github.meyervlad.minecod.terminal.TerminalStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

/** Client-only setup. Nothing outside this package may refer to it: a dedicated server has no screens. */
public final class MinecodClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(Terminal.MENU, TerminalInventoryScreen::new);

		ClientPlayNetworking.registerGlobalReceiver(TerminalStatePayload.TYPE, (payload, context) -> {
			if (payload.openScreen()) {
				context.client().gui.setScreen(new TerminalScreen(payload));
			} else if (context.client().gui.screen() instanceof TerminalScreen screen && screen.pos().equals(payload.pos())) {
				screen.update(payload);
			}
		});
	}
}
