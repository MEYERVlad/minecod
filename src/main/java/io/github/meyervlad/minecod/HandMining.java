package io.github.meyervlad.minecod;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * The core rule of the mod: players cannot break blocks by hand, only from code.
 *
 * <p>It works by multiplying the player's block break speed by zero. The game syncs that
 * attribute to the client, so punching a block simply does nothing, with no ghost blocks.
 * Creative mode is unaffected because it never consults break speed. Turn the rule off with
 * {@code /gamerule minecod:hand_mining true}.
 */
public final class HandMining {
	public static final GameRule<Boolean> ALLOWED = GameRuleBuilder.forBoolean(false)
			.category(GameRuleCategory.PLAYER)
			.buildAndRegister(Minecod.id("hand_mining"));

	private static final Identifier MODIFIER_ID = Minecod.id("no_hand_mining");
	private static final AttributeModifier MODIFIER = new AttributeModifier(MODIFIER_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

	private HandMining() {
	}

	static void init() {
		// Loading this class registers the game rule.
	}

	/** Keeps every player's break speed in line with the game rule. */
	static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			AttributeInstance speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);

			if (speed == null) {
				continue;
			}

			boolean allowed = player.level().getGameRules().get(ALLOWED);

			if (allowed) {
				speed.removeModifier(MODIFIER_ID);
			} else if (!speed.hasModifier(MODIFIER_ID)) {
				// Transient: not saved with the player, so removing the mod leaves no trace.
				speed.addTransientModifier(MODIFIER);
			}
		}
	}

	/**
	 * How much of {@code state} the player would break in one tick if the rule did not exist.
	 * Scripts mine at this speed, so tools, enchantments and effects all still matter.
	 */
	public static float scriptDestroyProgress(ServerPlayer player, BlockState state, BlockPos pos) {
		AttributeInstance speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		boolean restricted = speed != null && speed.removeModifier(MODIFIER_ID);

		try {
			return state.getDestroyProgress(player, player.level(), pos);
		} finally {
			if (restricted) {
				speed.addTransientModifier(MODIFIER);
			}
		}
	}
}
