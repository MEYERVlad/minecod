package io.github.meyervlad.minecod.notebook;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * A notebook holds Lua that the player wrote once and wants to keep: usually functions.
 * While it is in their inventory, code they type in chat can call those functions.
 * Using the notebook opens it for editing.
 */
public final class NotebookItem extends Item {
	public NotebookItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer) {
			// The client already has the text, on its copy of the item; it only needs telling to show it.
			ServerPlayNetworking.send(serverPlayer, new OpenNotebookPayload(hand));
		}

		return InteractionResult.SUCCESS;
	}
}
