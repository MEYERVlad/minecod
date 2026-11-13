package io.github.meyervlad.minecod.api;

import java.util.function.Supplier;

import io.github.meyervlad.minecod.HandMining;
import io.github.meyervlad.minecod.script.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * {@code mine()}: breaks the block the player is looking at, taking as long as it would by
 * hand with the held tool. The player has to keep looking at the block until it breaks.
 */
final class MineTask implements Task {
	private final Supplier<ServerPlayer> playerLookup;

	private ServerLevel level;
	private BlockPos target;
	/** Stands in for an entity id so the client can tell crack overlays apart. Never a real id. */
	private int overlayId;
	private float progress;
	private int shownStage = -1;

	MineTask(Supplier<ServerPlayer> playerLookup) {
		this.playerLookup = playerLookup;
	}

	@Override
	public Varargs tick() {
		ServerPlayer player = playerLookup.get();

		if (player == null || !player.isAlive()) {
			return stop("dead");
		}

		BlockHitResult hit = Targeting.block(player);

		if (target == null) {
			if (hit == null) {
				return stop("no block in reach");
			}

			level = player.level();
			target = hit.getBlockPos().immutable();
			overlayId = ~player.getId();
		} else if (player.level() != level || hit == null || !hit.getBlockPos().equals(target)) {
			return stop("looked away");
		}

		BlockState state = level.getBlockState(target);

		if (state.isAir()) {
			return stop("block is gone");
		}

		if (!level.mayInteract(player, target)) {
			return stop("protected");
		}

		float perTick = HandMining.scriptDestroyProgress(player, state, target);

		if (!(perTick > 0)) {
			return stop("unbreakable");
		}

		progress += perTick;

		if (progress < 1) {
			int stage = (int) (progress * 10);

			if (stage != shownStage) {
				level.destroyBlockProgress(overlayId, target, stage);
				shownStage = stage;
			}

			return null;
		}

		String id = LuaApi.id(state);
		clearOverlay();

		if (!player.gameMode.destroyBlock(target)) {
			return stop("cannot break");
		}

		return LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(id));
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
