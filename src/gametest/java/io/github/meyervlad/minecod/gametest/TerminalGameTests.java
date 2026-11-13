package io.github.meyervlad.minecod.gametest;

import java.util.List;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.Programs;
import io.github.meyervlad.minecod.script.Script;
import io.github.meyervlad.minecod.terminal.Terminal;
import io.github.meyervlad.minecod.terminal.TerminalBlock;
import io.github.meyervlad.minecod.terminal.TerminalBlockEntity;
import io.github.meyervlad.minecod.terminal.TerminalStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * The terminal block in a real world. Terminals here face south, so the block they work on
 * is one step south of them and their redstone output is one step north.
 */
public class TerminalGameTests {
	private static final BlockPos TERMINAL = new BlockPos(2, 2, 2);
	private static final BlockPos FRONT = TERMINAL.south();
	private static final BlockPos BACK = TERMINAL.north();
	private static final BlockPos SIDE = TERMINAL.east();

	private static TerminalBlockEntity place(GameTestHelper helper, BlockPos pos, String program) {
		return place(helper, pos, Direction.SOUTH, program);
	}

	static TerminalBlockEntity place(GameTestHelper helper, BlockPos pos, Direction facing, String program) {
		helper.setBlock(pos, Terminal.BLOCK.defaultBlockState().setValue(TerminalBlock.FACING, facing));
		TerminalBlockEntity terminal = helper.getBlockEntity(pos, TerminalBlockEntity.class);
		terminal.setProgram(program);
		return terminal;
	}

	static void expectLog(GameTestHelper helper, TerminalBlockEntity terminal, List<String> expected) {
		helper.assertTrue(terminal.logLines().equals(expected), "terminal printed " + terminal.logLines() + ", expected " + expected);
	}

	private static void expectStored(GameTestHelper helper, TerminalBlockEntity terminal, Item item, int expected) {
		int stored = terminal.countItem(item);
		helper.assertTrue(stored == expected, "terminal holds " + stored + " of " + item + ", expected " + expected);
	}

	private static void expectStatus(GameTestHelper helper, TerminalBlockEntity terminal, TerminalStatus expected) {
		helper.assertTrue(terminal.status() == expected, "terminal is " + terminal.status() + ", expected " + expected);
	}

	static boolean lit(GameTestHelper helper, BlockPos lamp) {
		return helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
	}

	@GameTest(maxTicks = 100)
	public void minesTheBlockInFront(GameTestHelper helper) {
		helper.setBlock(FRONT, Blocks.DIRT);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "return mine()");
		terminal.run();

		helper.runAfterDelay(5, () -> {
			// Dirt takes 15 ticks by hand.
			helper.assertBlockPresent(Blocks.DIRT, FRONT);
			expectStatus(helper, terminal, TerminalStatus.RUNNING);
		});

		helper.runAfterDelay(6, () -> helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, FRONT);
			// What it mines goes into its storage, not onto the ground.
			expectStored(helper, terminal, Items.DIRT, 1);
			helper.assertItemEntityNotPresent(Items.DIRT, FRONT, 2);
			expectStatus(helper, terminal, TerminalStatus.IDLE);
			expectLog(helper, terminal, List.of("= true, minecraft:dirt"));
		}));
	}

	@GameTest(maxTicks = 100)
	public void aPickaxeMakesItFastAndGetsTheDrops(GameTestHelper helper) {
		helper.setBlock(FRONT, Blocks.STONE);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "return mine()");
		terminal.setItem(TerminalBlockEntity.TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE));
		long started = helper.getTick();
		terminal.run();

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, FRONT);
			long elapsed = helper.getTick() - started;
			// Bare hands need 150 ticks for stone; an iron pickaxe needs 8.
			helper.assertTrue(elapsed <= 12, "stone took " + elapsed + " ticks with an iron pickaxe");
			expectStored(helper, terminal, Items.COBBLESTONE, 1);
			helper.assertTrue(terminal.tool().getDamageValue() == 1, "the pickaxe took " + terminal.tool().getDamageValue() + " damage");
		});
	}

	@GameTest(maxTicks = 100)
	public void aWornOutToolLeavesTheSlotEmpty(GameTestHelper helper) {
		helper.setBlock(FRONT, Blocks.STONE);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "mine() return tool()");
		ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
		pickaxe.setDamageValue(pickaxe.getMaxDamage() - 1);
		terminal.setItem(TerminalBlockEntity.TOOL_SLOT, pickaxe);
		terminal.run();

		helper.succeedWhen(() -> {
			expectLog(helper, terminal, List.of("= nil"));
			helper.assertTrue(terminal.tool().isEmpty(), "the broken pickaxe is still there: " + terminal.tool());
		});
	}

	@GameTest(maxTicks = 100)
	public void whatDoesNotFitFallsOnTheGround(GameTestHelper helper) {
		helper.setBlock(FRONT, Blocks.DIRT);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "return mine()");

		for (int slot = 1; slot < TerminalBlockEntity.SLOTS; slot++) {
			terminal.setItem(slot, new ItemStack(Items.STONE, 64));
		}

		terminal.run();

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, FRONT);
			helper.assertItemEntityPresent(Items.DIRT, FRONT, 2);
			expectStored(helper, terminal, Items.DIRT, 0);
		});
	}

	@GameTest(maxTicks = 100)
	public void toolAndCountDescribeTheInventory(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL,
				"local id, uses = tool() return id, uses, count(), count('minecraft:dirt'), count('minecraft:stick')");
		terminal.setItem(TerminalBlockEntity.TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE));
		terminal.setItem(1, new ItemStack(Items.DIRT, 5));
		terminal.setItem(4, new ItemStack(Items.COBBLESTONE, 3));
		terminal.run();

		helper.succeedWhen(() -> expectLog(helper, terminal, List.of("= minecraft:iron_pickaxe, 250, 8, 5, 0")));
	}

	@GameTest(maxTicks = 100)
	public void countRejectsAnUnknownItem(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL, "return count('minecraft:no_such_thing')");
		terminal.run();

		helper.succeedWhen(() -> {
			expectStatus(helper, terminal, TerminalStatus.ERROR);
			helper.assertTrue(terminal.logLines().getFirst().contains("no such item"), "terminal printed " + terminal.logLines());
		});
	}

	@GameTest(maxTicks = 100)
	public void pushUnloadsStorageIntoAChest(GameTestHelper helper) {
		helper.setBlock(SIDE, Blocks.CHEST);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "return push(), count()");
		terminal.setItem(TerminalBlockEntity.TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE));
		terminal.setItem(1, new ItemStack(Items.DIRT, 10));
		terminal.run();

		helper.succeedWhen(() -> {
			expectLog(helper, terminal, List.of("= 10, 0"));
			int inChest = helper.getBlockEntity(SIDE, ChestBlockEntity.class).countItem(Items.DIRT);
			helper.assertTrue(inChest == 10, "the chest holds " + inChest + " dirt");
			helper.assertTrue(terminal.tool().is(Items.IRON_PICKAXE), "the tool went with the rest");
		});
	}

	@GameTest(maxTicks = 100)
	public void aHopperTakesStorageButNotTheTool(GameTestHelper helper) {
		BlockPos hopper = TERMINAL.below();
		helper.setBlock(hopper, Blocks.HOPPER);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "");
		terminal.setItem(TerminalBlockEntity.TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE));
		terminal.setItem(1, new ItemStack(Items.DIRT, 2));

		helper.succeedWhen(() -> {
			int inHopper = helper.getBlockEntity(hopper, HopperBlockEntity.class).countItem(Items.DIRT);
			helper.assertTrue(inHopper == 2, "the hopper holds " + inHopper + " dirt");
			helper.assertTrue(terminal.tool().is(Items.IRON_PICKAXE), "the hopper took the tool");
		});
	}

	@GameTest(maxTicks = 100)
	public void breakingItDropsWhatItHeld(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL, "");
		terminal.setItem(TerminalBlockEntity.TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE));
		terminal.setItem(1, new ItemStack(Items.DIRT, 2));
		helper.setBlock(TERMINAL, Blocks.AIR);

		helper.succeedWhen(() -> {
			helper.assertItemEntityPresent(Items.IRON_PICKAXE, TERMINAL, 2);
			helper.assertItemEntityPresent(Items.DIRT, TERMINAL, 2);
		});
	}

	@GameTest(maxTicks = 300)
	public void isSlowAndGetsNothingFromBlocksThatNeedATool(GameTestHelper helper) {
		helper.setBlock(FRONT, Blocks.STONE);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "return mine()");
		long started = helper.getTick();
		terminal.run();

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, FRONT);
			long elapsed = helper.getTick() - started;
			// Stone has hardness 1.5, and a bare hand needs 100 ticks per point.
			helper.assertTrue(elapsed >= 150, "stone broke after only " + elapsed + " ticks");
			helper.assertItemEntityNotPresent(Items.COBBLESTONE, FRONT, 2);
			helper.assertItemEntityNotPresent(Items.STONE, FRONT, 2);
		});
	}

	@GameTest(maxTicks = 100)
	public void inspectNamesTheBlockInFront(GameTestHelper helper) {
		helper.setBlock(FRONT, Blocks.OAK_LOG);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "print(inspect()) mine() return inspect()");
		terminal.run();

		helper.succeedWhen(() -> {
			expectStatus(helper, terminal, TerminalStatus.IDLE);
			expectLog(helper, terminal, List.of("minecraft:oak_log", "= nil"));
		});
	}

	@GameTest(maxTicks = 100)
	public void emitsRedstoneFromItsBackOnly(GameTestHelper helper) {
		helper.setBlock(BACK, Blocks.REDSTONE_LAMP);
		helper.setBlock(SIDE, Blocks.REDSTONE_LAMP);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "emit(15)");
		terminal.run();

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(lit(helper, BACK), "the lamp behind the terminal is off"))
				.thenExecute(() -> {
					helper.assertTrue(!lit(helper, SIDE), "the lamp beside the terminal is on");
					// The program has ended, but what it emitted stays until someone stops it.
					expectStatus(helper, terminal, TerminalStatus.IDLE);
					terminal.stop();
				})
				.thenWaitUntil(() -> helper.assertTrue(!lit(helper, BACK), "the lamp stayed on after stop"))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void breakingItSwitchesItsOutputOff(GameTestHelper helper) {
		helper.setBlock(BACK, Blocks.REDSTONE_LAMP);
		place(helper, TERMINAL, "emit(true)").run();

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(lit(helper, BACK), "the lamp behind the terminal is off"))
				.thenExecute(() -> helper.setBlock(TERMINAL, Blocks.AIR))
				.thenWaitUntil(() -> helper.assertTrue(!lit(helper, BACK), "the lamp stayed on after the terminal was removed"))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void readsRedstoneFromEverySideButItsBack(GameTestHelper helper) {
		helper.setBlock(SIDE, Blocks.REDSTONE_BLOCK);
		TerminalBlockEntity poweredFromSide = place(helper, TERMINAL, "return redstone()");

		BlockPos second = new BlockPos(5, 2, 5);
		helper.setBlock(second.north(), Blocks.REDSTONE_BLOCK);
		TerminalBlockEntity poweredFromBack = place(helper, second, "return redstone()");

		poweredFromSide.run();
		poweredFromBack.run();

		helper.succeedWhen(() -> {
			expectLog(helper, poweredFromSide, List.of("= 15"));
			expectLog(helper, poweredFromBack, List.of("= 0"));
		});
	}

	@GameTest(maxTicks = 100)
	public void reactsToALever(GameTestHelper helper) {
		helper.setBlock(BACK, Blocks.REDSTONE_LAMP);
		helper.setBlock(SIDE, Blocks.LEVER);
		TerminalBlockEntity terminal = place(helper, TERMINAL, "while true do emit(redstone()) sleep(0.1) end");
		terminal.run();

		helper.startSequence()
				.thenIdle(10)
				.thenExecute(() -> {
					helper.assertTrue(!lit(helper, BACK), "the lamp is on before the lever was pulled");
					helper.pullLever(SIDE);
				})
				.thenWaitUntil(() -> helper.assertTrue(lit(helper, BACK), "the lamp did not follow the lever"))
				.thenExecute(terminal::stop)
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void syntaxErrorIsReported(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL, "print('never')\nfor");
		terminal.run();

		helper.runAfterDelay(2, () -> {
			expectStatus(helper, terminal, TerminalStatus.ERROR);
			helper.assertTrue(terminal.logLines().size() == 1 && terminal.logLines().getFirst().startsWith("! terminal:2:"),
					"terminal printed " + terminal.logLines());
			helper.assertTrue(!terminal.isRunning() && !terminal.isEnabled(), "a broken program should not keep running");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void runtimeErrorIsReportedWithItsLine(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL, "local nothing = nil\nprint('before')\nnothing.field = 1");
		terminal.run();

		helper.succeedWhen(() -> {
			expectStatus(helper, terminal, TerminalStatus.ERROR);
			List<String> log = terminal.logLines();
			helper.assertTrue(log.size() == 2 && log.get(0).equals("before") && log.get(1).startsWith("! terminal:3:"), "terminal printed " + log);
		});
	}

	@GameTest(maxTicks = 100)
	public void runningAgainStartsFromScratch(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL, "runs = (runs or 0) + 1 print(runs) while true do sleep(1) end");
		terminal.run();

		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					expectLog(helper, terminal, List.of("1"));
					terminal.run();
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// No leftover variables and no leftover output.
					expectLog(helper, terminal, List.of("1"));
					terminal.stop();
					expectStatus(helper, terminal, TerminalStatus.IDLE);
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void removingItStopsItsProgram(GameTestHelper helper) {
		TerminalBlockEntity terminal = place(helper, TERMINAL, "while true do sleep(1) end");
		terminal.run();

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(terminal.isRunning(), "the program should be running");
			helper.setBlock(TERMINAL, Blocks.AIR);
			helper.assertTrue(!terminal.isRunning(), "the program outlived its terminal");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void programAndRunStateAreSaved(GameTestHelper helper) {
		String program = "emit(7)\nwhile true do sleep(1) end";
		TerminalBlockEntity terminal = place(helper, TERMINAL, program);
		terminal.run();

		helper.runAfterDelay(5, () -> {
			CompoundTag saved = terminal.saveWithFullMetadata(helper.getLevel().registryAccess());
			BlockEntity loaded = BlockEntity.loadStatic(terminal.getBlockPos(), terminal.getBlockState(), saved, helper.getLevel().registryAccess());

			helper.assertTrue(loaded instanceof TerminalBlockEntity, "loaded " + loaded);
			TerminalBlockEntity copy = (TerminalBlockEntity) loaded;
			helper.assertTrue(copy.program().equals(program), "program came back as " + copy.program());
			helper.assertTrue(copy.isEnabled(), "a running program should start again after loading");
			helper.assertTrue(copy.output() == 7, "output came back as " + copy.output());

			terminal.stop();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void aMinedTerminalKeepsItsProgramOnTheItem(GameTestHelper helper) {
		String program = "print('still here')";
		TerminalBlockEntity terminal = place(helper, TERMINAL, program);

		List<ItemStack> drops = Block.getDrops(terminal.getBlockState(), helper.getLevel(), terminal.getBlockPos(), terminal);
		helper.assertTrue(drops.size() == 1 && drops.getFirst().is(Terminal.ITEM), "a terminal drops " + drops);
		ItemStack item = drops.getFirst();
		helper.assertTrue(program.equals(item.get(Programs.COMPONENT)), "the item carries " + item.get(Programs.COMPONENT));

		// Placed again, by a player, it has the program back.
		BlockPos elsewhere = new BlockPos(5, 2, 5);
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, item);
		helper.placeAt(player, item, elsewhere.below(), Direction.UP);
		TerminalBlockEntity placed = helper.getBlockEntity(elsewhere, TerminalBlockEntity.class);
		helper.assertTrue(placed.program().equals(program), "the placed terminal holds: " + placed.program());
		MinecodGameTests.removePlayer(helper, player);
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void blockItemAndRecipeAreRegistered(GameTestHelper helper) {
		helper.assertTrue(Terminal.BLOCK.asItem() == Terminal.ITEM, "the terminal block has no item");

		ResourceKey<Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE, Minecod.id("terminal"));
		helper.assertTrue(helper.getLevel().getServer().getRecipeManager().byKey(recipe).isPresent(), "the terminal has no crafting recipe");
		helper.succeed();
	}

	/** The player can get a terminal back, the only way this mod allows: a script and a pickaxe. */
	@GameTest(maxTicks = 100)
	public void aPlayerScriptWithAPickaxeMinesItAndGetsItBack(GameTestHelper helper) {
		place(helper, MinecodGameTests.TARGET, "");
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
		Script script = MinecodGameTests.startScript(helper, player, "return mine()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("true", "minecod:terminal"));
			helper.assertBlockPresent(Blocks.AIR, MinecodGameTests.TARGET);
			helper.assertItemEntityPresent(Terminal.ITEM, MinecodGameTests.TARGET, 2);
			MinecodGameTests.removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 400)
	public void withoutAPickaxeItDropsNothing(GameTestHelper helper) {
		place(helper, MinecodGameTests.TARGET, "");
		ServerPlayer player = MinecodGameTests.spawnPlayer(helper);
		Script script = MinecodGameTests.startScript(helper, player, "return mine()");

		helper.succeedWhen(() -> {
			MinecodGameTests.expectResults(helper, script, List.of("true", "minecod:terminal"));
			helper.assertItemEntityNotPresent(Terminal.ITEM, MinecodGameTests.TARGET, 2);
			MinecodGameTests.removePlayer(helper, player);
		});
	}
}
