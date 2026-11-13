package io.github.meyervlad.minecod.terminal;

import java.util.Set;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.wire.Wire;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** The terminal's registered pieces: block, item and block entity type. */
public final class Terminal {
	private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, Minecod.id("terminal"));
	private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, Minecod.id("terminal"));
	private static final ResourceKey<CreativeModeTab> REDSTONE_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("redstone_blocks"));

	public static final TerminalBlock BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY, new TerminalBlock(
			BlockBehaviour.Properties.of()
					.setId(BLOCK_KEY)
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(SoundType.METAL)));

	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix()));

	public static final BlockEntityType<TerminalBlockEntity> ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Minecod.id("terminal"),
			new BlockEntityType<>(TerminalBlockEntity::new, Set.of(BLOCK)));

	private Terminal() {
	}

	/** Loading this class registers the block; this adds everything around it. */
	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(REDSTONE_TAB).register(output -> {
			output.accept(ITEM);
			output.accept(Wire.ITEM);
		});

		PayloadTypeRegistry.clientboundPlay().register(TerminalStatePayload.TYPE, TerminalStatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TerminalActionPayload.TYPE, TerminalActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TerminalActionPayload.TYPE, TerminalActionPayload::handle);
	}
}
