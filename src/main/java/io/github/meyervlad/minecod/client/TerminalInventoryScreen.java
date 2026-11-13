package io.github.meyervlad.minecod.client;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.terminal.TerminalMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/** A terminal's inventory: tool slot, storage and the player's items. The slots come from {@link TerminalMenu}. */
public final class TerminalInventoryScreen extends AbstractContainerScreen<TerminalMenu> {
	private static final Identifier BACKGROUND = Minecod.id("textures/gui/terminal.png");
	private static final int TEXTURE_SIZE = 256;

	public TerminalInventoryScreen(TerminalMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight, TEXTURE_SIZE, TEXTURE_SIZE);
	}
}
