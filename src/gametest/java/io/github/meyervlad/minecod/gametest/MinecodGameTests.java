package io.github.meyervlad.minecod.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.github.meyervlad.minecod.HandMining;
import io.github.meyervlad.minecod.Minecod;
import io.github.meyervlad.minecod.api.PlayerApi;
import io.github.meyervlad.minecod.script.LuaSandbox;
import io.github.meyervlad.minecod.script.Script;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.luaj.vm2.Globals;

/**
 * Tests that need a real world. The player stands at (1, 1, 1) of the test area looking
 * south, so the block in front of their eyes is {@link #TARGET}.
 */
public class MinecodGameTests {
	private static final BlockPos TARGET = new BlockPos(1, 2, 3);

	/** A survival-mode player with a fake network connection, standing in the test area. */
	private static ServerPlayer spawnPlayer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-player"), false);
		ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);

		Vec3 feet = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
		player.snapTo(feet.x, feet.y, feet.z, 0, 0);
		player.setYHeadRot(0);
		return player;
	}

	/** Nothing ticks this player's movement, so the head (which aiming uses) is turned by hand. */
	private static void turn(ServerPlayer player, float yaw) {
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
	}

	private static void removePlayer(GameTestHelper helper, ServerPlayer player) {
		Minecod.sessions().close(player.getUUID());
		helper.getLevel().getServer().getPlayerList().remove(player);
	}

	/** Runs code for the player the way the mod does, but lets the test see what it returned. */
	private static Script startScript(GameTestHelper helper, ServerPlayer player, String code) {
		Globals globals = LuaSandbox.create();
		PlayerApi.install(globals, () -> player);
		Script script = Script.start(globals, LuaSandbox.compile(globals, code, "=test"), "gametest");
		helper.onEachTick(script::tick);
		return script;
	}

	private static void expectResults(GameTestHelper helper, Script script, List<String> expected) {
		helper.assertTrue(script.isDone(), "script is still running");
		helper.assertTrue(script.state() == Script.State.FINISHED, "script " + script.state() + ": " + script.error());
		helper.assertTrue(script.results().equals(expected), "script returned " + script.results() + ", expected " + expected);
	}

	@GameTest(maxTicks = 100)
	public void runCommandMinesTheBlockInFrontOfThePlayer(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.DIRT);
		ServerPlayer player = spawnPlayer(helper);

		helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "/run mine()");

		helper.runAfterDelay(5, () -> {
			// Dirt takes 15 ticks by hand; mining must not be instant.
			helper.assertBlockPresent(Blocks.DIRT, TARGET);
			helper.assertTrue(Minecod.sessions().isRunning(player), "script should still be mining");
		});

		helper.runAfterDelay(6, () -> helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, TARGET);
			helper.assertTrue(!Minecod.sessions().isRunning(player), "script should have finished");
			removePlayer(helper, player);
		}));
	}

	@GameTest(maxTicks = 100)
	public void mineReportsWhatItBroke(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.DIRT);
		ServerPlayer player = spawnPlayer(helper);
		Script script = startScript(helper, player, "return mine()");

		helper.succeedWhen(() -> {
			expectResults(helper, script, List.of("true", "minecraft:dirt"));
			helper.assertBlockPresent(Blocks.AIR, TARGET);
			// Mined for real: the block dropped itself, as it would for a player's hands.
			helper.assertItemEntityPresent(Items.DIRT, TARGET, 2);
			removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void mineFailsWithNothingInReach(GameTestHelper helper) {
		ServerPlayer player = spawnPlayer(helper);
		Script script = startScript(helper, player, "return mine()");

		helper.succeedWhen(() -> {
			expectResults(helper, script, List.of("false", "no block in reach"));
			removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void mineStopsWhenThePlayerLooksAway(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		ServerPlayer player = spawnPlayer(helper);
		Script script = startScript(helper, player, "return mine()");

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(!script.isDone(), "stone should take a while");
			turn(player, 180);
		});

		helper.runAfterDelay(6, () -> helper.succeedWhen(() -> {
			expectResults(helper, script, List.of("false", "looked away"));
			helper.assertBlockPresent(Blocks.STONE, TARGET);
			removePlayer(helper, player);
		}));
	}

	@GameTest(maxTicks = 100)
	public void haltStopsMining(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.STONE);
		ServerPlayer player = spawnPlayer(helper);
		Minecod.sessions().run(player, "while true do mine() end");

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(Minecod.sessions().isRunning(player), "script should be running");
			helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "/halt");
			helper.assertTrue(!Minecod.sessions().isRunning(player), "script should be stopped");
		});

		helper.runAfterDelay(40, () -> {
			helper.assertBlockPresent(Blocks.STONE, TARGET);
			removePlayer(helper, player);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void inspectAndPosDescribeTheWorld(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.OAK_LOG);
		ServerPlayer player = spawnPlayer(helper);
		BlockPos target = helper.absolutePos(TARGET);
		BlockPos feet = helper.absolutePos(new BlockPos(1, 1, 1));
		Script script = startScript(helper, player, "local id, x, y, z = inspect() local px, py, pz = pos() return id, x, y, z, px, py, pz");

		helper.succeedWhen(() -> {
			expectResults(helper, script, List.of("minecraft:oak_log",
					String.valueOf(target.getX()), String.valueOf(target.getY()), String.valueOf(target.getZ()),
					String.valueOf(feet.getX()), String.valueOf(feet.getY()), String.valueOf(feet.getZ())));
			removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void sleepTakesGameTime(GameTestHelper helper) {
		ServerPlayer player = spawnPlayer(helper);
		long started = helper.getTick();
		Script script = startScript(helper, player, "sleep(0.5) return 'awake'");

		helper.succeedWhen(() -> {
			expectResults(helper, script, List.of("awake"));
			long elapsed = helper.getTick() - started;
			helper.assertTrue(elapsed >= 10 && elapsed <= 13, "sleep(0.5) took " + elapsed + " ticks");
			removePlayer(helper, player);
		});
	}

	@GameTest(maxTicks = 100)
	public void handsCannotBreakBlocksButScriptsCan(GameTestHelper helper) {
		helper.setBlock(TARGET, Blocks.DIRT);
		ServerPlayer player = spawnPlayer(helper);
		BlockPos target = helper.absolutePos(TARGET);

		helper.runAfterDelay(2, () -> {
			BlockState dirt = helper.getLevel().getBlockState(target);
			helper.assertTrue(!helper.getLevel().getGameRules().get(HandMining.ALLOWED), "hand mining should be off by default");
			// The client must learn about the zero, or it would show blocks breaking and popping back.
			helper.assertTrue(Attributes.BLOCK_BREAK_SPEED.value().isClientSyncable(), "break speed should be synced to clients");
			helper.assertTrue(player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED) == 0, "break speed should be zero");
			helper.assertTrue(!(dirt.getDestroyProgress(player, helper.getLevel(), target) > 0), "hands should make no progress");
			helper.assertTrue(HandMining.scriptDestroyProgress(player, dirt, target) > 0, "scripts should make progress");
			helper.assertTrue(player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED) == 0, "break speed should be zero again");
			removePlayer(helper, player);
			helper.succeed();
		});
	}
}
