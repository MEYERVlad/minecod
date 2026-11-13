package io.github.meyervlad.minecod.terminal;

import java.util.ArrayList;
import java.util.List;

import io.github.meyervlad.minecod.wire.WireNetwork;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A block that stores a Lua program and runs it on its own.
 *
 * <p>Its screen side is the front: that is where it mines. Wires plug into every other side.
 * Redstone goes in through every side except the back and comes out of the back only, so a
 * terminal never reads its own output. The logic lives in {@link TerminalBlockEntity}.
 */
public final class TerminalBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final EnumProperty<TerminalStatus> STATUS = EnumProperty.create("status", TerminalStatus.class);

	public TerminalBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STATUS, TerminalStatus.IDLE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, STATUS);
	}

	/** Placed with the screen towards the player, like a furnace. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TerminalBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}

		return createTickerHelper(type, Terminal.ENTITY, (tickLevel, pos, tickState, terminal) -> terminal.serverTick());
	}

	/** Right click opens the editor. */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal) {
			ServerPlayNetworking.send(serverPlayer, terminal.state(true));
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	/** @param direction points from the block that is asking towards this one */
	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		boolean askedFromBehind = direction == state.getValue(FACING);
		return askedFromBehind && level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal ? terminal.power() : 0;
	}

	/**
	 * Whether a wire may plug into the given side of a terminal: any side but the screen,
	 * which has to stay clear for mining.
	 */
	public static boolean acceptsWire(BlockState terminal, Direction side) {
		return side != terminal.getValue(FACING);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		// Whatever the terminal was powering has to notice that it is gone: redstone devices...
		level.updateNeighborsAt(pos, this, null);

		// ...and the wires that carried its signal.
		List<BlockPos> neighbors = new ArrayList<>();

		for (Direction direction : Direction.values()) {
			neighbors.add(pos.relative(direction));
		}

		WireNetwork.update(level, neighbors);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}
}
