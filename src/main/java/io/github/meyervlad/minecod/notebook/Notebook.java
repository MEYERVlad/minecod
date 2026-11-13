package io.github.meyervlad.minecod.notebook;

import java.util.ArrayList;
import java.util.List;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.Programs;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The notebook's registered item, and how the rest of the mod reads notebooks. */
public final class Notebook {
	private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, Minecod.id("notebook"));
	private static final ResourceKey<CreativeModeTab> TOOLS_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));

	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new NotebookItem(new Item.Properties().setId(ITEM_KEY).stacksTo(1)));

	private Notebook() {
	}

	/** Loading this class registers the item; this adds everything around it. */
	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(TOOLS_TAB).register(output -> output.accept(ITEM));

		PayloadTypeRegistry.clientboundPlay().register(OpenNotebookPayload.TYPE, OpenNotebookPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SaveNotebookPayload.TYPE, SaveNotebookPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SaveNotebookPayload.TYPE, SaveNotebookPayload::handle);
	}

	/**
	 * What is written in the notebooks a player carries, in inventory order. Empty notebooks
	 * are left out.
	 */
	public static List<String> programs(Player player) {
		List<String> programs = new ArrayList<>();
		Inventory inventory = player.getInventory();

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);

			if (stack.getItem() instanceof NotebookItem) {
				String program = stack.getOrDefault(Programs.COMPONENT, "");

				if (!program.isBlank()) {
					programs.add(program);
				}
			}
		}

		return programs;
	}
}
