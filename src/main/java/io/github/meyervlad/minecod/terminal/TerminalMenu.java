package io.github.meyervlad.minecod.terminal;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A terminal's inventory as a player sees it: the tool slot on the left, a 3x3 block of
 * storage in the middle, and the player's own inventory below.
 */
public final class TerminalMenu extends AbstractContainerMenu {
	private static final int SLOT_SIZE = 18;
	private static final int TOOL_X = 26;
	private static final int TOOL_Y = 35;
	private static final int STORAGE_X = 80;
	private static final int STORAGE_Y = 17;
	private static final int STORAGE_COLUMNS = 3;
	private static final int PLAYER_X = 8;
	private static final int PLAYER_Y = 84;

	private final Container terminal;

	/** Client side: a stand-in container that the server fills in. */
	public TerminalMenu(int id, Inventory playerInventory) {
		this(id, playerInventory, new SimpleContainer(TerminalBlockEntity.SLOTS));
	}

	public TerminalMenu(int id, Inventory playerInventory, Container terminal) {
		super(Terminal.MENU, id);
		checkContainerSize(terminal, TerminalBlockEntity.SLOTS);
		this.terminal = terminal;
		terminal.startOpen(playerInventory.player);

		addSlot(new Slot(terminal, TerminalBlockEntity.TOOL_SLOT, TOOL_X, TOOL_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return TerminalBlockEntity.isTool(stack);
			}
		});

		for (int i = 0; i < TerminalBlockEntity.STORAGE_SLOTS; i++) {
			int x = STORAGE_X + i % STORAGE_COLUMNS * SLOT_SIZE;
			int y = STORAGE_Y + i / STORAGE_COLUMNS * SLOT_SIZE;
			addSlot(new Slot(terminal, 1 + i, x, y));
		}

		addStandardInventorySlots(playerInventory, PLAYER_X, PLAYER_Y);
	}

	@Override
	public boolean stillValid(Player player) {
		return terminal.stillValid(player);
	}

	/** Shift-click: out of the terminal into the player's inventory, or the other way round. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);

		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		int terminalSlots = TerminalBlockEntity.SLOTS;

		if (index < terminalSlots) {
			if (!moveItemStackTo(stack, terminalSlots, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (!moveItemStackTo(stack, 0, terminalSlots, false)) {
			// moveItemStackTo asks each slot, so a tool lands in the tool slot and the rest in storage.
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}

		return original;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		terminal.stopOpen(player);
	}
}
