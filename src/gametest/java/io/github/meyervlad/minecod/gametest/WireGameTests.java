package io.github.meyervlad.minecod.gametest;

import java.util.ArrayList;
import java.util.List;

import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.terminal.TerminalBlockEntity;
import io.github.meyervlad.minecod.wire.Wire;
import io.github.meyervlad.minecod.wire.WireBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Wires in a real world. The usual layout is a terminal at {@link #SENDER} with its screen
 * turned away, a line of wire running south from it and something to drive at the far end.
 */
public class WireGameTests {
	private static final BlockPos SENDER = new BlockPos(1, 2, 1);
	private static final BlockPos RECEIVER = new BlockPos(1, 2, 6);
	/** A program that keeps a signal on the wire until it is stopped. */
	private static final String HOLD = " while true do sleep(1) end";

	/** Places a wire the way a player would, so that it joins up with its neighbours. */
	private static void placeWire(GameTestHelper helper, BlockPos pos) {
		BlockState joined = Block.updateFromNeighbourShapes(Wire.BLOCK.defaultBlockState(), helper.getLevel(), helper.absolutePos(pos));
		helper.setBlock(pos, joined);
	}

	/** The straight run of wire between {@link #SENDER} and {@link #RECEIVER}. */
	private static List<BlockPos> straightRun(GameTestHelper helper) {
		List<BlockPos> wires = new ArrayList<>();

		for (int z = SENDER.getZ() + 1; z < RECEIVER.getZ(); z++) {
			wires.add(new BlockPos(SENDER.getX(), SENDER.getY(), z));
		}

		wires.forEach(pos -> placeWire(helper, pos));
		return wires;
	}

	private static int power(GameTestHelper helper, BlockPos wire) {
		return helper.getBlockState(wire).getValue(WireBlock.POWER);
	}

	private static void expectPower(GameTestHelper helper, BlockPos wire, int expected) {
		helper.assertTrue(power(helper, wire) == expected, "wire at " + wire.toShortString() + " carries " + power(helper, wire) + ", expected " + expected);
	}

	@GameTest(maxTicks = 100)
	public void carriesASignalBetweenTerminals(GameTestHelper helper) {
		straightRun(helper);
		TerminalBlockEntity sender = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(42)" + HOLD);
		TerminalBlockEntity receiver = TerminalGameTests.place(helper, RECEIVER, Direction.SOUTH, "sleep(0.5) return signal()");
		sender.run();
		receiver.run();

		helper.succeedWhen(() -> {
			TerminalGameTests.expectLog(helper, receiver, List.of("= 42"));
			sender.stop();
		});
	}

	@GameTest(maxTicks = 100)
	public void carriesNumbersRedstoneCannot(GameTestHelper helper) {
		List<BlockPos> wires = straightRun(helper);
		TerminalBlockEntity sender = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(1000.5)" + HOLD);
		TerminalBlockEntity receiver = TerminalGameTests.place(helper, RECEIVER, Direction.SOUTH, "sleep(0.5) return signal()");
		sender.run();
		receiver.run();

		helper.succeedWhen(() -> {
			TerminalGameTests.expectLog(helper, receiver, List.of("= 1000.5"));
			// Redstone devices next to the wire get as much of it as redstone can express.
			expectPower(helper, wires.getLast(), 15);
			sender.stop();
		});
	}

	@GameTest(maxTicks = 100)
	public void aTerminalDoesNotHearItself(GameTestHelper helper) {
		straightRun(helper);
		TerminalBlockEntity terminal = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(7) sleep(0.5) return signal()");
		terminal.run();

		helper.succeedWhen(() -> TerminalGameTests.expectLog(helper, terminal, List.of("= 0")));
	}

	@GameTest(maxTicks = 100)
	public void theStrongestEmitterWins(GameTestHelper helper) {
		List<BlockPos> wires = straightRun(helper);
		BlockPos listenerPos = new BlockPos(2, 2, 3);
		TerminalBlockEntity weak = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(3)" + HOLD);
		TerminalBlockEntity strong = TerminalGameTests.place(helper, RECEIVER, Direction.SOUTH, "emit(9)" + HOLD);
		TerminalBlockEntity listener = TerminalGameTests.place(helper, listenerPos, Direction.EAST, "sleep(0.5) return signal()");
		weak.run();
		strong.run();
		listener.run();

		helper.succeedWhen(() -> {
			TerminalGameTests.expectLog(helper, listener, List.of("= 9"));
			expectPower(helper, wires.getFirst(), 9);
			weak.stop();
			strong.stop();
		});
	}

	/** Redstone dust would have faded out long before the end of this run. */
	@GameTest(maxTicks = 100)
	public void powersRedstoneDevicesAtAnyDistance(GameTestHelper helper) {
		List<BlockPos> wires = new ArrayList<>();

		// Three lanes joined at alternate ends: 19 wires, each touching only the next.
		for (int z = 2; z <= 6; z++) {
			wires.add(new BlockPos(1, 2, z));
		}

		wires.add(new BlockPos(2, 2, 6));

		for (int z = 6; z >= 1; z--) {
			wires.add(new BlockPos(3, 2, z));
		}

		wires.add(new BlockPos(4, 2, 1));

		for (int z = 1; z <= 6; z++) {
			wires.add(new BlockPos(5, 2, z));
		}

		BlockPos lamp = new BlockPos(5, 2, 7);
		wires.forEach(pos -> placeWire(helper, pos));
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		helper.assertTrue(wires.size() > 15, "the run is too short to prove anything");
		TerminalBlockEntity sender = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(15)" + HOLD);
		sender.run();

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(TerminalGameTests.lit(helper, lamp), "the lamp at the end of the wire is off"))
				.thenExecute(() -> {
					wires.forEach(wire -> expectPower(helper, wire, 15));
					sender.stop();
				})
				.thenWaitUntil(() -> helper.assertTrue(!TerminalGameTests.lit(helper, lamp), "the lamp stayed on after stop"))
				.thenExecute(() -> wires.forEach(wire -> expectPower(helper, wire, 0)))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void cuttingAWireCutsTheSignal(GameTestHelper helper) {
		List<BlockPos> wires = straightRun(helper);
		BlockPos lamp = RECEIVER;
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		TerminalBlockEntity sender = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(15)" + HOLD);
		sender.run();

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(TerminalGameTests.lit(helper, lamp), "the lamp is off"))
				.thenExecute(() -> helper.setBlock(wires.get(1), Blocks.AIR))
				.thenWaitUntil(() -> helper.assertTrue(!TerminalGameTests.lit(helper, lamp), "the lamp stayed on after the wire was cut"))
				.thenExecute(() -> {
					// The half still plugged into the terminal keeps its signal.
					expectPower(helper, wires.getFirst(), 15);
					expectPower(helper, wires.getLast(), 0);
				})
				// Mending it brings the signal back without the terminal doing anything.
				.thenExecute(() -> placeWire(helper, wires.get(1)))
				.thenWaitUntil(() -> helper.assertTrue(TerminalGameTests.lit(helper, lamp), "the lamp stayed off after the wire was mended"))
				.thenExecute(sender::stop)
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void removingTheTerminalSilencesItsWires(GameTestHelper helper) {
		List<BlockPos> wires = straightRun(helper);
		TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(15)" + HOLD).run();

		helper.startSequence()
				.thenWaitUntil(() -> expectPower(helper, wires.getLast(), 15))
				.thenExecute(() -> helper.setBlock(SENDER, Blocks.AIR))
				.thenExecute(() -> wires.forEach(wire -> expectPower(helper, wire, 0)))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void doesNotPlugIntoATerminalsScreen(GameTestHelper helper) {
		BlockPos inFront = SENDER.south();
		BlockPos beside = SENDER.east();
		TerminalBlockEntity terminal = TerminalGameTests.place(helper, SENDER, Direction.SOUTH, "emit(15)" + HOLD);
		placeWire(helper, inFront);
		placeWire(helper, beside);
		terminal.run();

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(!helper.getBlockState(inFront).getValue(BlockStateProperties.NORTH), "the wire reaches for the screen");
			helper.assertTrue(helper.getBlockState(beside).getValue(BlockStateProperties.WEST), "the wire does not reach for the terminal's side");
			expectPower(helper, inFront, 0);
			expectPower(helper, beside, 15);
			terminal.stop();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void neighbouringWiresJoinUp(GameTestHelper helper) {
		BlockPos first = new BlockPos(2, 2, 2);
		placeWire(helper, first);
		placeWire(helper, first.above());
		placeWire(helper, first.east());

		BlockState state = helper.getBlockState(first);
		helper.assertTrue(state.getValue(BlockStateProperties.UP) && state.getValue(BlockStateProperties.EAST), "the first wire did not reach for the later ones: " + state);
		helper.assertTrue(!state.getValue(BlockStateProperties.DOWN) && !state.getValue(BlockStateProperties.WEST), "the wire reaches for nothing: " + state);
		helper.assertTrue(helper.getBlockState(first.above()).getValue(BlockStateProperties.DOWN), "the upper wire does not reach back");
		helper.succeed();
	}

	/** A terminal next to a live wire reads it with signal(), and only with signal(). */
	@GameTest(maxTicks = 100)
	public void redstoneReadingIgnoresWires(GameTestHelper helper) {
		straightRun(helper);
		TerminalBlockEntity sender = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "emit(15)" + HOLD);
		TerminalBlockEntity receiver = TerminalGameTests.place(helper, RECEIVER, Direction.SOUTH, "sleep(0.5) return redstone(), signal()");
		sender.run();
		receiver.run();

		helper.succeedWhen(() -> {
			TerminalGameTests.expectLog(helper, receiver, List.of("= 0, 15"));
			sender.stop();
		});
	}

	@GameTest(maxTicks = 100)
	public void readingRedstoneIsSlowAndReadingAWireIsNot(GameTestHelper helper) {
		TerminalBlockEntity viaWire = TerminalGameTests.place(helper, SENDER, Direction.NORTH, "for i = 1, 10 do signal() end");
		TerminalBlockEntity viaRedstone = TerminalGameTests.place(helper, RECEIVER, Direction.SOUTH, "for i = 1, 10 do redstone() end");
		viaWire.run();
		viaRedstone.run();

		helper.runAfterDelay(4, () -> {
			helper.assertTrue(!viaWire.isRunning(), "ten wire reads took more than a few ticks");
			helper.assertTrue(viaRedstone.isRunning(), "ten redstone reads were over in a few ticks");
		});

		helper.runAfterDelay(5, () -> helper.succeedWhen(() -> helper.assertTrue(!viaRedstone.isRunning(), "the redstone reads never finished")));
	}

	@GameTest(maxTicks = 100)
	public void blockItemAndRecipeAreRegistered(GameTestHelper helper) {
		helper.assertTrue(Wire.BLOCK.asItem() == Wire.ITEM, "the wire block has no item");

		ResourceKey<Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE, Minecod.id("wire"));
		helper.assertTrue(helper.getLevel().getServer().getRecipeManager().byKey(recipe).isPresent(), "the wire has no crafting recipe");
		helper.succeed();
	}
}
