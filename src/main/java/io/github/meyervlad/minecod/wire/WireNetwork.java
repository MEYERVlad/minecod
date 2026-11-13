package io.github.meyervlad.minecod.wire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.meyervlad.minecod.terminal.TerminalBlock;
import io.github.meyervlad.minecod.terminal.TerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Works out what a group of touching wires carries: the strongest signal emitted by any
 * terminal plugged into it.
 *
 * <p>Nothing is stored between calls. A network is found by walking the wires each time
 * something changes (a terminal emits, a wire is placed or removed) or a terminal asks.
 */
public final class WireNetwork {
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final int MAX_REDSTONE_POWER = 15;

	/** One network: its wires and the terminals plugged into it. */
	private record Scan(List<BlockPos> wires, Set<TerminalBlockEntity> terminals) { }

	private WireNetwork() {
	}

	/** What redstone makes of a signal: whole numbers from 0 to 15. */
	public static int redstonePower(double signal) {
		return (int) Math.clamp(Math.floor(signal), 0, MAX_REDSTONE_POWER);
	}

	/**
	 * Brings the networks at the given positions up to date. Positions that hold no wire are
	 * skipped, and a network is only walked once however many of its wires are listed.
	 */
	public static void update(ServerLevel level, Iterable<BlockPos> positions) {
		Set<BlockPos> done = new HashSet<>();

		for (BlockPos pos : positions) {
			if (done.contains(pos) || !isWire(level, pos)) {
				continue;
			}

			Scan scan = scan(level, pos);
			done.addAll(scan.wires());
			apply(level, scan);
		}
	}

	/**
	 * The strongest signal that other terminals put on the wires plugged into {@code reader},
	 * or 0 if there is nobody else on them.
	 */
	public static double strongestOtherSignal(ServerLevel level, TerminalBlockEntity reader) {
		Set<BlockPos> done = new HashSet<>();
		Set<TerminalBlockEntity> others = new HashSet<>();

		for (BlockPos pos : reader.adjacentWirePositions()) {
			if (done.contains(pos) || !isWire(level, pos)) {
				continue;
			}

			Scan scan = scan(level, pos);
			done.addAll(scan.wires());
			others.addAll(scan.terminals());
		}

		others.remove(reader);
		return strongest(others);
	}

	/** The strongest output among the terminals; 0 for none, as a wire nobody drives is silent. */
	private static double strongest(Set<TerminalBlockEntity> terminals) {
		return terminals.stream().mapToDouble(TerminalBlockEntity::output).max().orElse(0);
	}

	private static boolean isWire(ServerLevel level, BlockPos pos) {
		// isLoaded first: a network must never pull unloaded chunks into memory.
		return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof WireBlock;
	}

	private static Scan scan(ServerLevel level, BlockPos start) {
		List<BlockPos> wires = new ArrayList<>();
		Set<TerminalBlockEntity> terminals = new HashSet<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start.immutable());
		queue.add(start.immutable());

		while (!queue.isEmpty()) {
			BlockPos wire = queue.remove();
			wires.add(wire);

			for (Direction direction : DIRECTIONS) {
				BlockPos neighbor = wire.relative(direction);

				if (!level.isLoaded(neighbor)) {
					continue;
				}

				BlockState state = level.getBlockState(neighbor);

				if (state.getBlock() instanceof WireBlock) {
					if (seen.add(neighbor)) {
						queue.add(neighbor);
					}
				} else if (state.getBlock() instanceof TerminalBlock && TerminalBlock.acceptsWire(state, direction.getOpposite())
						&& level.getBlockEntity(neighbor) instanceof TerminalBlockEntity terminal) {
					terminals.add(terminal);
				}
			}
		}

		return new Scan(wires, terminals);
	}

	private static void apply(ServerLevel level, Scan scan) {
		int power = redstonePower(strongest(scan.terminals()));
		List<BlockPos> changed = new ArrayList<>();

		// First write every wire without telling anyone, then tell the neighbours. A neighbour
		// may react by changing this very network (a piston, say); it should find it consistent.
		for (BlockPos wire : scan.wires()) {
			BlockState state = level.getBlockState(wire);

			if (state.getBlock() instanceof WireBlock && state.getValue(WireBlock.POWER) != power) {
				level.setBlock(wire, state.setValue(WireBlock.POWER, power), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				changed.add(wire);
			}
		}

		for (BlockPos wire : changed) {
			BlockState state = level.getBlockState(wire);

			if (state.getBlock() instanceof WireBlock) {
				level.updateNeighborsAt(wire, state.getBlock(), null);
			}
		}
	}
}
