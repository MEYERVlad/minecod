package io.github.meyervlad.minecod.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Plays the mod the way a person would: a real client, a real singleplayer world, keyboard
 * and mouse. Screenshots land in {@code build/run/clientGameTest/screenshots}.
 */
public class MinecodClientGameTest implements FabricClientGameTest {
	/** Directly in front of the player's eyes once they stand at 0.5 100 0.5 facing south. */
	private static final BlockPos TARGET = new BlockPos(0, 101, 2);
	private static final int PUNCH_TICKS = 40;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("fill -3 99 -3 3 99 5 minecraft:smooth_stone");
			server.runCommand("tp @a 0.5 100 0.5 0 0");
			placeTarget(server);
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);

			// By hand: nothing happens, on the server or on screen.
			double clientBreakSpeed = context.computeOnClient(client -> client.player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED));
			check(clientBreakSpeed == 0, "client thinks break speed is " + clientBreakSpeed);
			context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, PUNCH_TICKS);
			check(!targetIsAir(server), "the block broke by hand");
			check(!context.computeOnClient(client -> client.level.getBlockState(TARGET).isAir()), "the client saw the block break by hand");
			context.takeScreenshot("1-punching-does-nothing");

			// From chat with the > prefix.
			typeInChat(context, "> mine()");
			context.waitTicks(8);
			check(!targetIsAir(server), "mining was instant");
			context.takeScreenshot("2-script-is-mining");
			server.waitFor(s -> s.overworld().getBlockState(TARGET).isAir());
			world.getConnection().waitForClientboundPackets();
			check(context.computeOnClient(client -> client.level.getBlockState(TARGET).isAir()), "the client still sees the block");

			// From chat as a command, with variables carried over between runs.
			placeTarget(server);
			typeInChat(context, "/run mined = 0");
			typeInChat(context, "/run if mine() then mined = mined + 1 end return mined, inspect()");
			server.waitFor(s -> s.overworld().getBlockState(TARGET).isAir());
			context.waitTicks(5);
			typeInChat(context, "/run help()");
			context.waitTicks(10);
			context.takeScreenshot("3-chat-output");

			// With the rule switched off, hands work again.
			server.runCommand("gamerule minecod:hand_mining true");
			placeTarget(server);
			context.waitTicks(10);
			context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, PUNCH_TICKS);
			check(targetIsAir(server), "the block did not break by hand with minecod:hand_mining on");
		}
	}

	private static void placeTarget(TestServerContext server) {
		server.runCommand("setblock " + TARGET.getX() + " " + TARGET.getY() + " " + TARGET.getZ() + " minecraft:dirt");
	}

	private static boolean targetIsAir(TestServerContext server) {
		return server.computeOnServer(s -> s.overworld().getBlockState(TARGET).isAir());
	}

	private static void typeInChat(ClientGameTestContext context, String text) {
		context.getInput().pressKey(options -> options.keyChat);
		context.waitForScreen(ChatScreen.class);
		context.getInput().typeChars(text);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitForScreen(null);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
