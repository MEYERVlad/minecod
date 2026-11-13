package io.github.meyervlad.minecod.terminal;

import io.github.meyervlad.minecod.api.LuaApi;
import io.github.meyervlad.minecod.script.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * {@code mine()} for a terminal: breaks the block in front of it at the speed of a bare hand.
 * Like a bare hand, it is slow on blocks that need a tool and gets no drops from them.
 */
final class TerminalMineTask implements Task {
	/** Vanilla's numbers: break time in ticks is hardness times one of these. */
	private static final float TICKS_PER_HARDNESS = 30;
	private static final float TICKS_PER_HARDNESS_WITHOUT_TOOL = 100;

	private final ServerLevel level;
	private final BlockPos target;
	/** Stands in for an entity id so the client can tell crack overlays apart. */
	private final int overlayId;

	private Block mining;
	private float progress;
	private int shownStage = -1;

	TerminalMineTask(TerminalBlockEntity terminal) {
		this.level = terminal.serverLevel();
		this.target = terminal.frontPos();
		// Far above any real entity id, and unlike the negative ids used for players' scripts.
		this.overlayId = 0x40000000 | (terminal.getBlockPos().hashCode() & 0x3FFFFFFF);
	}

	@Override
	public Varargs tick() {
		BlockState state = level.getBlockState(target);

		if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
			return stop("nothing to mine");
		}

		if (mining == null) {
			mining = state.getBlock();
		} else if (state.getBlock() != mining) {
			return stop("block changed");
		}

		float hardness = state.getDestroySpeed(level, target);

		if (hardness < 0) {
			return stop("unbreakable");
		}

		boolean needsTool = state.requiresCorrectToolForDrops();
		progress += hardness == 0 ? 1 : 1 / (hardness * (needsTool ? TICKS_PER_HARDNESS_WITHOUT_TOOL : TICKS_PER_HARDNESS));

		if (progress < 1) {
			int stage = (int) (progress * 10);

			if (stage != shownStage) {
				level.destroyBlockProgress(overlayId, target, stage);
				shownStage = stage;
			}

			return null;
		}

		clearOverlay();
		level.destroyBlock(target, !needsTool);
		return LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(LuaApi.id(state)));
	}

	@Override
	public void cancel() {
		clearOverlay();
	}

	private Varargs stop(String reason) {
		clearOverlay();
		return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(reason));
	}

	private void clearOverlay() {
		if (shownStage >= 0) {
			level.destroyBlockProgress(overlayId, target, -1);
			shownStage = -1;
		}
	}
}
