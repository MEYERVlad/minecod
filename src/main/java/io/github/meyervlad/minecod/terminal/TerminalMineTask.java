package io.github.meyervlad.minecod.terminal;

import java.util.List;

import io.github.meyervlad.minecod.api.LuaApi;
import io.github.meyervlad.minecod.script.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * {@code mine()} for a terminal: breaks the block in front of it with the tool in its tool
 * slot, at the speed a player would manage with that tool. Without the right tool it is as
 * slow as a bare hand and, like a bare hand, gets nothing from blocks that need one.
 *
 * <p>What the block drops goes into the terminal's storage; only what does not fit falls
 * on the ground.
 */
final class TerminalMineTask implements Task {
	/** Vanilla's numbers: break time in ticks is hardness times one of these. */
	private static final float TICKS_PER_HARDNESS = 30;
	private static final float TICKS_PER_HARDNESS_WITHOUT_TOOL = 100;

	private final TerminalBlockEntity terminal;
	private final ServerLevel level;
	private final BlockPos target;
	/** Stands in for an entity id so the client can tell crack overlays apart. */
	private final int overlayId;

	private Block mining;
	private float progress;
	private int shownStage = -1;

	TerminalMineTask(TerminalBlockEntity terminal) {
		this.terminal = terminal;
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

		ItemStack tool = terminal.tool();
		boolean harvests = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		progress += hardness == 0 ? 1 : speed(tool, state) / (hardness * (harvests ? TICKS_PER_HARDNESS : TICKS_PER_HARDNESS_WITHOUT_TOOL));

		if (progress < 1) {
			int stage = (int) (progress * 10);

			if (stage != shownStage) {
				level.destroyBlockProgress(overlayId, target, stage);
				shownStage = stage;
			}

			return null;
		}

		clearOverlay();
		// Work out the drops while the block (and any block entity) is still there.
		List<ItemStack> drops = harvests ? Block.getDrops(state, level, target, level.getBlockEntity(target), null, tool) : List.of();
		level.destroyBlock(target, false);

		if (harvests) {
			// Experience from ores and the like.
			state.spawnAfterBreak(level, target, tool, true);
		}

		for (ItemStack drop : drops) {
			ItemStack rest = terminal.store(drop);

			if (!rest.isEmpty()) {
				Block.popResource(level, target, rest);
			}
		}

		if (hardness != 0 && tool.isDamageableItem()) {
			// A tool that wears out completely leaves the slot empty.
			tool.hurtAndBreak(1, level, null, broken -> { });
		}

		terminal.setChanged();
		return LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(LuaApi.id(state)));
	}

	/** A tool's speed on a block, as vanilla works it out for a player: efficiency only helps the right tool. */
	private float speed(ItemStack tool, BlockState state) {
		float speed = tool.getDestroySpeed(state);

		if (speed > 1) {
			int efficiency = EnchantmentHelper.getItemEnchantmentLevel(
					level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), tool);

			if (efficiency > 0) {
				speed += efficiency * efficiency + 1;
			}
		}

		return speed;
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
