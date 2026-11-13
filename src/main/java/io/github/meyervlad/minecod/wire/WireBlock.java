package io.github.meyervlad.minecod.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.meyervlad.minecod.terminal.TerminalBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A cable that carries what terminals emit, any distance, without loss, in any direction.
 *
 * <p>Wires join up with neighbouring wires and with terminals (on every side but the
 * terminal's screen). All wires that touch form one network, and every wire in it holds the
 * same {@link #POWER}, which it also offers to neighbouring redstone devices. Wires never
 * read redstone themselves, so they cannot feed back into their own signal.
 * {@link WireNetwork} does the working out.
 */
public final class WireBlock extends Block {
	/** The network's signal as redstone sees it, 0 to 15. Terminals can read the exact number. */
	public static final IntegerProperty POWER = BlockStateProperties.POWER;

	private static final Map<Direction, BooleanProperty> CONNECTED = PipeBlock.PROPERTY_BY_DIRECTION;
	private static final Direction[] DIRECTIONS = Direction.values();
	/** Indexed by a bit per connected direction, in the order of {@link #DIRECTIONS}. */
	private static final VoxelShape[] SHAPES = makeShapes();

	public WireBlock(Properties properties) {
		super(properties);
		BlockState state = stateDefinition.any().setValue(POWER, 0);

		for (BooleanProperty side : CONNECTED.values()) {
			state = state.setValue(side, false);
		}

		registerDefaultState(state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH,
				BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN, POWER);
	}

	// ---------------------------------------------------------------- joining up

	/** @param towards the direction from the wire to {@code neighbor} */
	private static boolean connectsTo(BlockState neighbor, Direction towards) {
		if (neighbor.getBlock() instanceof WireBlock) {
			return true;
		}

		return neighbor.getBlock() instanceof TerminalBlock && TerminalBlock.acceptsWire(neighbor, towards.getOpposite());
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState state = defaultBlockState();

		for (Direction direction : DIRECTIONS) {
			BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(direction));
			state = state.setValue(CONNECTED.get(direction), connectsTo(neighbor, direction));
		}

		return state;
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
			BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		return state.setValue(CONNECTED.get(direction), connectsTo(neighborState, direction));
	}

	// ---------------------------------------------------------------- the network

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		// Also called when only the state changes; a new wire is what matters.
		if (!oldState.is(this) && level instanceof ServerLevel serverLevel) {
			WireNetwork.update(serverLevel, List.of(pos));
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		// Devices this wire was powering have to notice that it is gone...
		level.updateNeighborsAt(pos, this, null);

		// ...and what is left of the network may have lost its source.
		List<BlockPos> neighbors = new ArrayList<>(DIRECTIONS.length);

		for (Direction direction : DIRECTIONS) {
			neighbors.add(pos.relative(direction));
		}

		WireNetwork.update(level, neighbors);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(POWER);
	}

	// ---------------------------------------------------------------- shape

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		int index = 0;

		for (int i = 0; i < DIRECTIONS.length; i++) {
			if (state.getValue(CONNECTED.get(DIRECTIONS[i]))) {
				index |= 1 << i;
			}
		}

		return SHAPES[index];
	}

	private static VoxelShape makeShapes(int index) {
		// A 4x4x4 pixel core with an arm reaching to each connected face.
		VoxelShape shape = Block.box(6, 6, 6, 10, 10, 10);

		for (int i = 0; i < DIRECTIONS.length; i++) {
			if ((index & 1 << i) != 0) {
				Direction direction = DIRECTIONS[i];
				shape = Shapes.or(shape, Block.box(
						direction.getStepX() < 0 ? 0 : 6, direction.getStepY() < 0 ? 0 : 6, direction.getStepZ() < 0 ? 0 : 6,
						direction.getStepX() > 0 ? 16 : 10, direction.getStepY() > 0 ? 16 : 10, direction.getStepZ() > 0 ? 16 : 10));
			}
		}

		return shape;
	}

	private static VoxelShape[] makeShapes() {
		VoxelShape[] shapes = new VoxelShape[1 << DIRECTIONS.length];

		for (int index = 0; index < shapes.length; index++) {
			shapes[index] = makeShapes(index);
		}

		return shapes;
	}
}
