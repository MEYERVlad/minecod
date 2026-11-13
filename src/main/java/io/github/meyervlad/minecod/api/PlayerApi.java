package io.github.meyervlad.minecod.api;

import java.util.function.Supplier;

import io.github.meyervlad.minecod.Messages;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The Lua functions available to code a player types in chat. They act through the player:
 * {@code mine()} breaks what the player is looking at, {@code place()} puts down what they
 * hold.
 *
 * <p>To add one: write a method, register it in {@link #install} and describe it under
 * {@code minecod.help.<name>} in the language files.
 */
public final class PlayerApi extends LuaApi {
	private final Supplier<ServerPlayer> playerLookup;

	private PlayerApi(Supplier<ServerPlayer> playerLookup) {
		this.playerLookup = playerLookup;
	}

	/**
	 * @param playerLookup finds the player the scripts act for; returns {@code null} while they
	 *                     are offline. Called on the server thread.
	 */
	public static void install(Globals globals, Supplier<ServerPlayer> playerLookup) {
		PlayerApi api = new PlayerApi(playerLookup);
		api.add(globals, "print(...)", api::print);
		api.add(globals, "sleep(seconds)", LuaApi::sleep);
		api.add(globals, "pos() -> x, y, z", api::pos);
		api.add(globals, "inspect() -> id, x, y, z", api::inspect);
		api.add(globals, "mine() -> true, id | false, reason", api::mine);
		api.add(globals, "place() -> true, id | false, reason", api::place);
		api.add(globals, "use() -> true | false, reason", api::use);
		api.add(globals, "held() -> id, count", api::held);
		api.add(globals, "select(slot | id) -> true | false", api::select);
		api.add(globals, "count([id]) -> number", api::count);
		api.add(globals, "help()", api::help);
	}

	private Varargs print(Varargs args) {
		Component message = Messages.output(line(args));

		// A whole tick's budget per line, so a runaway loop cannot flood the chat.
		return Script.await(() -> {
			player().sendSystemMessage(message);
			return LuaValue.NONE;
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	private Varargs pos(Varargs args) {
		return Script.await(() -> coordinates(player().blockPosition()));
	}

	private Varargs inspect(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			BlockHitResult hit = Targeting.block(player);

			if (hit == null) {
				return LuaValue.NIL;
			}

			BlockPos target = hit.getBlockPos();
			return LuaValue.varargsOf(LuaValue.valueOf(id(player.level().getBlockState(target))), coordinates(target));
		});
	}

	private Varargs mine(Varargs args) {
		return Script.await(new MineTask(playerLookup));
	}

	/** Places the block in the player's hand against the block they are looking at. */
	private Varargs place(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			ItemStack stack = player.getMainHandItem();

			if (!(stack.getItem() instanceof BlockItem)) {
				return failure("no block in hand");
			}

			BlockHitResult hit = Targeting.block(player);

			if (hit == null) {
				return failure("no block in reach");
			}

			String id = id(stack);
			int count = stack.getCount();

			// Straight to the item, so that a chest gets a block put against it instead of being opened.
			if (!stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction()) {
				return failure("cannot place");
			}

			if (player.hasInfiniteMaterials()) {
				stack.setCount(count);
			}

			swingArm(player);
			return LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(id));
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	/** A right click: on the block the player is looking at or, failing that, with the held item in the air. */
	private Varargs use(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			ItemStack stack = player.getMainHandItem();
			BlockHitResult hit = Targeting.block(player);

			boolean acted = hit != null
					&& player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND, hit).consumesAction();

			if (!acted && !stack.isEmpty()) {
				acted = player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND).consumesAction();
			}

			if (!acted) {
				return failure("nothing happened");
			}

			swingArm(player);
			return LuaValue.TRUE;
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	/** What is in the player's hand, or nil. */
	private Varargs held(Varargs args) {
		return Script.await(() -> {
			ItemStack stack = player().getMainHandItem();
			return stack.isEmpty() ? LuaValue.NIL : LuaValue.varargsOf(LuaValue.valueOf(id(stack)), LuaValue.valueOf(stack.getCount()));
		});
	}

	/** Switches the hotbar to a slot, given by number or by what should be in it. */
	private Varargs select(Varargs args) {
		boolean byNumber = args.arg1().type() == LuaValue.TNUMBER;
		int number = byNumber ? args.checkint(1) : 0;
		String id = byNumber ? null : args.checkjstring(1);

		if (byNumber && (number < 1 || number > Inventory.getSelectionSize())) {
			throw new LuaError("slot must be between 1 and " + Inventory.getSelectionSize());
		}

		return Script.await(() -> {
			ServerPlayer player = player();
			Inventory inventory = player.getInventory();
			int slot = byNumber ? number - 1 : hotbarSlotWith(inventory, item(id));

			if (slot < 0) {
				return LuaValue.FALSE;
			}

			inventory.setSelectedSlot(slot);
			// Normally the client decides which slot is selected; this time it has to be told.
			player.connection.send(new ClientboundSetHeldSlotPacket(slot));
			return LuaValue.TRUE;
		});
	}

	/** Shows everyone, the player included, that the hand did something. */
	private static void swingArm(ServerPlayer player) {
		player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), true);
	}

	private static int hotbarSlotWith(Inventory inventory, Item item) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (inventory.getItem(slot).is(item)) {
				return slot;
			}
		}

		return -1;
	}

	/** How many items the player carries: of the given kind, or of every kind. */
	private Varargs count(Varargs args) {
		String id = args.optjstring(1, null);
		return Script.await(() -> {
			Inventory inventory = player().getInventory();
			Item kind = id == null ? null : item(id);
			int count = 0;

			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack stack = inventory.getItem(slot);

				if (kind == null || stack.is(kind)) {
					count += stack.getCount();
				}
			}

			return LuaValue.valueOf(count);
		});
	}

	private Varargs help(Varargs args) {
		return Script.await(() -> {
			ServerPlayer player = player();
			player.sendSystemMessage(Component.translatable("minecod.help.title"));

			for (String signature : signatures()) {
				player.sendSystemMessage(Component.literal("  " + signature + " ").withStyle(ChatFormatting.YELLOW)
						.append(Component.translatable("minecod.help." + nameOf(signature)).withStyle(ChatFormatting.GRAY)));
			}

			return LuaValue.NONE;
		}, Script.INSTRUCTIONS_PER_TICK);
	}

	private ServerPlayer player() {
		ServerPlayer player = playerLookup.get();

		if (player == null) {
			throw new LuaError("player is offline");
		}

		return player;
	}
}
