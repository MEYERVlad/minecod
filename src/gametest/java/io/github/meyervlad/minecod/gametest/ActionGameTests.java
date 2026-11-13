package io.github.meyervlad.minecod.gametest;

import java.util.List;

import io.github.meyervlad.minecod.script.Script;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * What a player's chat code can do besides mining: place, use, and look into the inventory.
 * As in {@link MinecodGameTests}, the player looks south at {@link MinecodGameTests#TARGET}.
 */
public class ActionGameTests {
	private static final BlockPos TARGET = MinecodGameTests.TARGET;
	/** The space between the player and the target: where a placed block ends up. */
	private static final BlockPos BEFORE_TARGET = TARGET.north();

	private static void expectFailure(GameTestHelper helper, Script script, String messagePart) {
		helper.assertTrue(script.state() == Script.State.FAILED, "script is " + script.state());
		helper.assertTrue(script.error().contains(messagePart), "script failed with: " + script.error());
	}

	@GameTest(maxTicks = 100)
	public void placePutsTheHeldBlockAgainstTheTarget(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 2));
		Script script = MinecodGameTests.startScript(helper, player, "return place()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("true", "minecraft:dirt"));
			helper.assertBlockPresent(Blocks.DIRT, BEFORE_TARGET);
			helper.assertTrue(player.getMainHandItem().getCount() == 1, "the player still holds " + player.getMainHandItem());
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void placeNeedsABlockInHand(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
		Script script = MinecodGameTests.startScript(helper, player, "return place()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("false", "no block in hand"));
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void placeNeedsSomethingToPlaceAgainst(GameTestHelper helper) {
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
		Script script = MinecodGameTests.startScript(helper, player, "return place()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("false", "no block in reach"));
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void useFlipsALever(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		helper.setBlock(BEFORE_TARGET, Blocks.LEVER.defaultBlockState()
				.setValue(LeverBlock.FACE, AttachFace.WALL)
				.setValue(LeverBlock.FACING, Direction.NORTH));
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		Script script = MinecodGameTests.startScript(helper, player, "return use()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("true"));
			helper.assertTrue(helper.getBlockState(BEFORE_TARGET).getValue(LeverBlock.POWERED), "the lever was not flipped");
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void usePlacesTheHeldBlockLikeARightClick(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
		Script script = MinecodGameTests.startScript(helper, player, "return use()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("true"));
			helper.assertBlockPresent(Blocks.DIRT, BEFORE_TARGET);
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void useSaysWhenNothingHappened(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		Script script = MinecodGameTests.startScript(helper, player, "return use()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("false", "nothing happened"));
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void heldSelectAndCountSeeTheInventory(GameTestHelper helper) {
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.getInventory().setItem(0, new ItemStack(Items.DIRT, 5));
		player.getInventory().setItem(3, new ItemStack(Items.OAK_SAPLING, 2));
		player.getInventory().setSelectedSlot(0);
		Script script = MinecodGameTests.startScript(helper, player, """
				local first, firstCount = held()
				select(4)
				local second, secondCount = held()
				return first, firstCount, second, secondCount,
					count('minecraft:dirt'), count(), select('minecraft:dirt'), select('minecraft:stone')
				""");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script,
					List.of("minecraft:dirt", "5", "minecraft:oak_sapling", "2", "5", "7", "true", "false"));
			helper.assertTrue(player.getInventory().getSelectedSlot() == 0, "slot " + player.getInventory().getSelectedSlot() + " is selected");
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void heldIsNilWithAnEmptyHand(GameTestHelper helper) {
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		Script script = MinecodGameTests.startScript(helper, player, "return held()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("nil"));
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void selectRejectsASlotOffTheHotbar(GameTestHelper helper) {
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		Script script = MinecodGameTests.startScript(helper, player, "select(10)");

		helper.succeedWhen(() -> {
			helper.assertTrue(script.isDone(), "script is still running");
			expectFailure(helper, script, "slot must be between 1 and 9");
			MinecodGameTests.removePlayer(helper, player);
		});
	}
}
