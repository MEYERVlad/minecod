package io.github.meyervlad.minecod.wire;

import io.github.meyervlad.minecod.Minecod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** The wire's registered pieces: block and item. */
public final class Wire {
	private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, Minecod.id("wire"));
	private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, Minecod.id("wire"));

	public static final WireBlock BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY, new WireBlock(
			BlockBehaviour.Properties.of()
					.setId(BLOCK_KEY)
					.mapColor(MapColor.FIRE)
					.instabreak()
					.noOcclusion()
					// Pistons leave wires alone, so a network never changes shape behind its own back.
					.pushReaction(PushReaction.IMMOVEABLE)
					.sound(SoundType.METAL)));

	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix()));

	private Wire() {
	}

	/** Loading this class registers the wire. */
	public static void init() {
	}
}
