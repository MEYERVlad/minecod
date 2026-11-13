package io.github.meyervlad.minecod.gametest;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.Programs;
import io.github.meyervlad.minecod.notebook.Notebook;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;

/**
 * Notebooks, tested through what a player's chat code can do with them. A function that
 * mines is the easiest thing to observe: the block in front of the player disappears.
 */
public class NotebookGameTests {
	private static final BlockPos TARGET = MinecodGameTests.TARGET;

	/** A player facing a dirt block, carrying a notebook with the given text. */
	private static ServerPlayer playerWithNotebook(GameTestHelper helper, String program) {
		helper.setBlock(TARGET, Blocks.DIRT);
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		ItemStack notebook = new ItemStack(Notebook.ITEM);
		notebook.set(Programs.COMPONENT, program);
		player.getInventory().add(notebook);
		return player;
	}

	private static boolean running(ServerPlayer player) {
		return Minecod.sessions().isRunning(player);
	}

	@GameTest(maxTicks = 100)
	public void itsFunctionsCanBeCalledFromChat(GameTestHelper helper) {
		ServerPlayer player = playerWithNotebook(helper, "function dig()\n  return mine()\nend");
		Minecod.sessions().run(player, "dig()");

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, TARGET);
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void editingItChangesWhatChatCodeSees(GameTestHelper helper) {
		ServerPlayer player = playerWithNotebook(helper, "function act() end");
		Minecod.sessions().run(player, "act()");

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!running(player), "the first run never ended"))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.DIRT, TARGET);
					// The player rewrites the notebook; the next run has to pick that up.
					player.getInventory().getItem(0).set(Programs.COMPONENT, "function act() mine() end");
					Minecod.sessions().run(player, "act()");
				})
				.thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, TARGET))
				.thenExecute(() -> MinecodGameTests.removePlayer(helper, player))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void itIsRunOnceNotOnEveryCommand(GameTestHelper helper) {
		ServerPlayer player = playerWithNotebook(helper, "loads = (loads or 0) + 1");
		Minecod.sessions().run(player, "first = 1");

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!running(player), "the first run never ended"))
				.thenExecute(() -> Minecod.sessions().run(player, "second = 2"))
				.thenWaitUntil(() -> helper.assertTrue(!running(player), "the second run never ended"))
				.thenExecute(() -> Minecod.sessions().run(player, "if loads == 1 then mine() end"))
				.thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, TARGET))
				.thenExecute(() -> MinecodGameTests.removePlayer(helper, player))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void aSyntaxErrorInItStopsTheRun(GameTestHelper helper) {
		ServerPlayer player = playerWithNotebook(helper, "function oops(");
		Minecod.sessions().run(player, "mine()");

		helper.runAfterDelay(40, () -> {
			helper.assertTrue(!running(player), "the code ran in spite of the broken notebook");
			helper.assertBlockPresent(Blocks.DIRT, TARGET);
			MinecodGameTests.removePlayer(helper, player);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void anErrorWhileRunningItStopsTheRunEveryTime(GameTestHelper helper) {
		ServerPlayer player = playerWithNotebook(helper, "error('bad notebook')");
		Minecod.sessions().run(player, "mine()");

		helper.startSequence()
				.thenIdle(30)
				.thenExecute(() -> {
					helper.assertTrue(!running(player), "the first run is still going");
					helper.assertBlockPresent(Blocks.DIRT, TARGET);
					// A notebook that failed does not count as loaded, so it fails again.
					Minecod.sessions().run(player, "mine()");
				})
				.thenIdle(30)
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.DIRT, TARGET);
					MinecodGameTests.removePlayer(helper, player);
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void itemAndRecipeAreRegistered(GameTestHelper helper) {
		ResourceKey<Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE, Minecod.id("notebook"));
		helper.assertTrue(helper.getLevel().getServer().getRecipeManager().byKey(recipe).isPresent(), "the notebook has no crafting recipe");
		helper.assertTrue(new ItemStack(Notebook.ITEM).getMaxStackSize() == 1, "notebooks should not stack");
		helper.succeed();
	}
}
