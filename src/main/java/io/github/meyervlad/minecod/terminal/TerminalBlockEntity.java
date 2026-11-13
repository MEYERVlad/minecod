package io.github.meyervlad.minecod.terminal;

import java.util.ArrayDeque;
import java.util.List;

import io.github.meyervlad.minecod.script.LuaSandbox;
import io.github.meyervlad.minecod.script.Script;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The state of one terminal: its program, whether that program should be running, the
 * redstone power it emits and the lines it has printed. Server thread only.
 *
 * <p>A running program cannot be saved, so after the world is reloaded a terminal that was
 * running starts its program again from the first line.
 */
public final class TerminalBlockEntity extends BlockEntity {
	/** In characters. Small enough to fit one network packet even as three-byte UTF-8. */
	public static final int MAX_PROGRAM_LENGTH = 8_000;
	public static final int MAX_LOG_LINES = 50;
	public static final int MAX_LINE_LENGTH = 200;
	/** Marks a log line as an error. */
	public static final String ERROR_PREFIX = "! ";
	/** Marks a log line as the values a program returned. */
	public static final String RESULT_PREFIX = "= ";

	private static final int MAX_POWER = 15;

	// Saved with the world.
	private String program = "";
	private boolean enabled;
	private int power;

	private final ArrayDeque<String> log = new ArrayDeque<>();
	private Script script;

	public TerminalBlockEntity(BlockPos pos, BlockState state) {
		super(Terminal.ENTITY, pos, state);
	}

	// ---------------------------------------------------------------- program control

	public String program() {
		return program;
	}

	/** Stores a program. A program that is already running is not affected until {@link #run()}. */
	public void setProgram(String program) {
		String clipped = program.length() <= MAX_PROGRAM_LENGTH ? program : program.substring(0, MAX_PROGRAM_LENGTH);

		if (!clipped.equals(this.program)) {
			this.program = clipped;
			setChanged();
		}
	}

	/** Starts the stored program from the beginning, replacing a running one. */
	public void run() {
		halt();
		log.clear();
		setPower(0);
		enabled = true;
		setChanged();
		start();
	}

	/** Stops the program and switches the redstone output off. */
	public void stop() {
		halt();
		setPower(0);
		setStatus(TerminalStatus.IDLE);

		if (enabled) {
			enabled = false;
			setChanged();
		}
	}

	public boolean isRunning() {
		return script != null;
	}

	/** Whether the program is meant to be running, i.e. will start again after the world reloads. */
	public boolean isEnabled() {
		return enabled;
	}

	public TerminalStatus status() {
		return getBlockState().getValue(TerminalBlock.STATUS);
	}

	/** Everything a client needs to show this terminal's screen. */
	public TerminalStatePayload state(boolean openScreen) {
		return new TerminalStatePayload(worldPosition, program, List.copyOf(log), status(), openScreen);
	}

	void serverTick() {
		if (script == null) {
			if (enabled) {
				// Was running when the world was saved.
				start();
			}

			return;
		}

		script.tick();

		if (!script.isDone()) {
			return;
		}

		Script finished = script;
		script = null;
		enabled = false;
		setChanged();

		switch (finished.state()) {
			case FINISHED -> {
				if (!finished.results().isEmpty()) {
					log(RESULT_PREFIX + String.join(", ", finished.results()));
				}

				setStatus(TerminalStatus.IDLE);
			}
			case FAILED, HUNG -> {
				log(ERROR_PREFIX + finished.error());
				setStatus(TerminalStatus.ERROR);
			}
			case KILLED, RUNNING -> setStatus(TerminalStatus.IDLE);
		}
	}

	private void start() {
		// A fresh Lua state for every run: programs start with no leftover variables.
		Globals globals = LuaSandbox.create();
		TerminalApi.install(globals, this);
		LuaValue chunk;

		try {
			chunk = LuaSandbox.compileProgram(globals, program, "=terminal");
		} catch (LuaError e) {
			log(ERROR_PREFIX + LuaSandbox.describe(e));
			enabled = false;
			setChanged();
			setStatus(TerminalStatus.ERROR);
			return;
		}

		script = Script.start(globals, chunk, "terminal " + worldPosition.toShortString());
		setStatus(TerminalStatus.RUNNING);
	}

	private void halt() {
		if (script != null) {
			script.kill();
			script = null;
		}
	}

	/** Also called when the chunk unloads; {@link #enabled} survives, so the program resumes later. */
	@Override
	public void setRemoved() {
		halt();
		super.setRemoved();
	}

	private void setStatus(TerminalStatus status) {
		if (level == null || isRemoved() || status() == status) {
			return;
		}

		level.setBlock(worldPosition, getBlockState().setValue(TerminalBlock.STATUS, status), Block.UPDATE_CLIENTS);
	}

	// ---------------------------------------------------------------- what programs can do

	/** Adds text to the terminal's screen, dropping the oldest lines when it is full. */
	void log(String text) {
		for (String line : text.split("\n", -1)) {
			log.addLast(line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH) + "…");
		}

		while (log.size() > MAX_LOG_LINES) {
			log.removeFirst();
		}
	}

	public List<String> logLines() {
		return List.copyOf(log);
	}

	ServerLevel serverLevel() {
		return (ServerLevel) level;
	}

	Direction facing() {
		return getBlockState().getValue(TerminalBlock.FACING);
	}

	/** The position the terminal looks at and mines. */
	BlockPos frontPos() {
		return worldPosition.relative(facing());
	}

	/** The redstone power coming out of the back, 0 to 15. */
	public int power() {
		return power;
	}

	void setPower(int power) {
		int clamped = Math.clamp(power, 0, MAX_POWER);

		if (clamped == this.power) {
			return;
		}

		this.power = clamped;
		setChanged();

		if (level != null && !isRemoved()) {
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock(), null);
		}
	}

	/** The strongest redstone signal reaching the terminal from any side but its back. */
	int input() {
		Direction back = facing().getOpposite();
		int strongest = 0;

		for (Direction side : Direction.values()) {
			if (side != back) {
				strongest = Math.max(strongest, level.getSignal(worldPosition.relative(side), side));
			}
		}

		return strongest;
	}

	// ---------------------------------------------------------------- saving

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("program", program);
		output.putBoolean("enabled", enabled);
		output.putInt("power", power);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		program = input.getStringOr("program", "");
		enabled = input.getBooleanOr("enabled", false);
		power = Math.clamp(input.getIntOr("power", 0), 0, MAX_POWER);
	}
}
