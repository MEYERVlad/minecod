package io.github.meyervlad.minecod.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class Targeting {
	private Targeting() {
	}

	/** The block the player is looking at and can reach, or {@code null}. */
	static BlockHitResult block(ServerPlayer player) {
		HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
		return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit : null;
	}
}
