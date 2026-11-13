package io.github.meyervlad.minecod.gametest;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.meyervlad.minecod.client.TerminalInventoryScreen;
import io.github.meyervlad.minecod.client.TerminalScreen;
import io.github.meyervlad.minecod.terminal.TerminalBlock;
import io.github.meyervlad.minecod.terminal.TerminalBlockEntity;
import io.github.meyervlad.minecod.terminal.TerminalStatus;
import io.github.meyervlad.minecod.wire.WireBlock;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Plays the mod the way a person would: a real client, a real singleplayer world, keyboard
 * and mouse. Screenshots land in {@code build/run/clientGameTest/screenshots}.
 */
public class MinecodClientGameTest implements FabricClientGameTest {
	/** Directly in front of the player's eyes once they stand at 0.5 100 0.5 facing south. */
	private static final BlockPos TARGET = new BlockPos(0, 101, 2);
	private static final BlockPos TERMINAL = new BlockPos(0, 100, 2);
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

			testTerminal(context, world);
			testWires(context, world);

			// With the rule switched off, hands work again.
			server.runCommand("tp @a 0.5 100 0.5 0 0");
			server.runCommand("gamerule minecod:hand_mining true");
			placeTarget(server);
			context.waitTicks(10);
			context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, PUNCH_TICKS);
			check(targetIsAir(server), "the block did not break by hand with minecod:hand_mining on");
		}
	}

	/** Opens a terminal with a right click, types a program, runs it and watches it mine. */
	private static void testTerminal(ClientGameTestContext context, TestSingleplayerContext world) {
		TestServerContext server = world.getServer();
		// Typed below with Tab in front of the indented lines.
		List<String> lines = List.of("while true do", "  if mine() then print('mined') end", "  sleep(0.2)", "end");
		String program = String.join("\n", lines);
		context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));

		// The terminal sits below eye level with its screen towards the player.
		server.runCommand("setblock " + coordinates(TERMINAL) + " minecod:terminal[facing=north]");
		server.runCommand("tp @a 0.5 100 0.5 0 37");
		context.waitTicks(10);
		context.takeScreenshot("4-terminal-in-the-world");

		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitForScreen(TerminalScreen.class);

		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				context.getInput().pressKey(InputConstants.KEY_RETURN);
			}

			String line = lines.get(i);

			if (line.startsWith("  ")) {
				context.getInput().pressKey(InputConstants.KEY_TAB);
			}

			context.getInput().typeChars(line.strip());
		}

		context.clickScreenButton("minecod.terminal.run");
		server.waitFor(s -> terminal(s).isRunning());
		check(terminal(server).program().equals(program), "the server got the program as: " + terminal(server).program());

		// Give it something to mine and wait for the screen to show the output.
		server.runCommand("setblock " + coordinates(TERMINAL.north()) + " minecraft:dirt");
		server.waitFor(s -> s.overworld().getBlockState(TERMINAL.north()).isAir());
		server.waitFor(s -> terminal(s).logLines().contains("mined"));
		context.waitTicks(15);
		context.takeScreenshot("5-terminal-editor");

		// Closing keeps the program running.
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);
		context.waitTicks(5);
		check(terminal(server).isRunning(), "closing the screen stopped the program");
		check(context.computeOnClient(client -> client.level.getBlockState(TERMINAL).getValue(TerminalBlock.STATUS)) == TerminalStatus.RUNNING,
				"the client does not see the terminal as running");
		context.takeScreenshot("6-terminal-running");

		// The editor's Inventory button leads to the slots, and what the terminal mined is there.
		server.runOnServer(s -> terminal(s).setItem(TerminalBlockEntity.TOOL_SLOT, new ItemStack(Items.IRON_PICKAXE)));
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitForScreen(TerminalScreen.class);
		context.clickScreenButton("minecod.terminal.inventory");
		context.waitForScreen(TerminalInventoryScreen.class);
		context.waitTicks(5);
		check(context.computeOnClient(client -> ((TerminalInventoryScreen) client.gui.screen()).getMenu().getSlot(1).getItem().is(Items.DIRT)),
				"the inventory screen does not show the mined dirt");
		context.takeScreenshot("7-terminal-inventory");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);

		// Reopening shows the saved program; Stop stops it.
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitForScreen(TerminalScreen.class);
		context.clickScreenButton("minecod.terminal.stop");
		server.waitFor(s -> !terminal(s).isRunning());
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);
		check(terminal(server).program().equals(program), "the program changed after reopening: " + terminal(server).program());
		server.runCommand("setblock " + coordinates(TERMINAL) + " minecraft:air");
	}

	/** Places a wire by hand, then wires a terminal to a lamp and watches the signal arrive. */
	private static void testWires(ClientGameTestContext context, TestSingleplayerContext world) {
		TestServerContext server = world.getServer();

		// By hand: the new wire should reach for the one already there.
		BlockPos placed = TERMINAL;
		BlockPos existing = TERMINAL.south();
		server.runCommand("setblock " + coordinates(existing) + " minecod:wire");
		server.runCommand("item replace entity @a weapon.mainhand with minecod:wire 16");
		server.runCommand("tp @a 0.5 100 0.5 0 39");
		context.waitTicks(10);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		server.waitFor(s -> s.overworld().getBlockState(placed).getBlock() instanceof WireBlock);
		check(server.computeOnServer(s -> s.overworld().getBlockState(placed).getValue(BlockStateProperties.SOUTH)), "the placed wire does not reach for its neighbour");
		check(server.computeOnServer(s -> s.overworld().getBlockState(existing).getValue(BlockStateProperties.NORTH)), "the neighbour does not reach for the placed wire");
		server.runCommand("setblock " + coordinates(existing) + " minecraft:air");

		// A terminal, a run of wire up and over, and a lamp at the end of it.
		BlockPos lamp = TERMINAL.above(2);
		List<BlockPos> wires = List.of(TERMINAL.east(), TERMINAL.east(2), TERMINAL.east(2).above(), TERMINAL.east(2).above(2), TERMINAL.east().above(2));
		server.runCommand("setblock " + coordinates(TERMINAL) + " minecod:terminal[facing=north]");
		wires.forEach(wire -> server.runCommand("setblock " + coordinates(wire) + " minecod:wire"));
		server.runCommand("setblock " + coordinates(lamp) + " minecraft:redstone_lamp");
		server.runCommand("tp @a 1.5 100 -2.5 0 0");
		context.waitTicks(10);
		context.takeScreenshot("8-wires-off");

		server.runOnServer(s -> {
			terminal(s).setProgram("emit(15)");
			terminal(s).run();
		});
		server.waitFor(s -> s.overworld().getBlockState(lamp).getValue(RedstoneLampBlock.LIT));
		world.getConnection().waitForClientboundPackets();
		context.waitTicks(5);
		check(context.computeOnClient(client -> client.level.getBlockState(wires.get(2)).getValue(WireBlock.POWER)) == 15, "the client does not see the wire as live");
		check(context.computeOnClient(client -> client.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT)), "the client does not see the lamp lit");
		context.takeScreenshot("9-wires-on");

		server.runOnServer(s -> terminal(s).stop());
		wires.forEach(wire -> server.runCommand("setblock " + coordinates(wire) + " minecraft:air"));
		server.runCommand("setblock " + coordinates(lamp) + " minecraft:air");
		server.runCommand("setblock " + coordinates(TERMINAL) + " minecraft:air");
	}

	private static TerminalBlockEntity terminal(MinecraftServer server) {
		return (TerminalBlockEntity) server.overworld().getBlockEntity(TERMINAL);
	}

	private static TerminalBlockEntity terminal(TestServerContext server) {
		return server.computeOnServer(MinecodClientGameTest::terminal);
	}

	private static String coordinates(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static void placeTarget(TestServerContext server) {
		server.runCommand("setblock " + coordinates(TARGET) + " minecraft:dirt");
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
