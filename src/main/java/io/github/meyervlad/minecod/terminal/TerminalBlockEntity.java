package io.github.meyervlad.minecod.terminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import io.github.meyervlad.minecod.Programs;
import io.github.meyervlad.minecod.script.LuaSandbox;
import io.github.meyervlad.minecod.script.Script;
import io.github.meyervlad.minecod.wire.WireBlock;
import io.github.meyervlad.minecod.wire.WireNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The state of one terminal: its program, whether that program should be running, the
 * signal it emits, the lines it has printed, and its inventory. Server thread only.
 *
 * <p>The inventory is one tool slot, which decides how fast and how well the terminal
 * mines, and a few storage slots, where what it mines ends up. Hoppers and other terminals
 * can reach the storage but not the tool.
 *
 * <p>A running program cannot be saved, so after the world is reloaded a terminal that was
 * running starts its program again from the first line.
 */
public final class TerminalBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	public static final int MAX_PROGRAM_LENGTH = Programs.MAX_LENGTH;
	public static final int MAX_LOG_LINES = 50;
	public static final int MAX_LINE_LENGTH = 200;
	/** Marks a log line as an error. */
	public static final String ERROR_PREFIX = "! ";
	/** Marks a log line as the values a program returned. */
	public static final String RESULT_PREFIX = "= ";

	public static final int TOOL_SLOT = 0;
	public static final int STORAGE_SLOTS = 9;
	public static final int SLOTS = 1 + STORAGE_SLOTS;
	private static final int[] STORAGE = IntStream.rangeClosed(1, STORAGE_SLOTS).toArray();

	// Saved with the world.
	private String program = "";
	private boolean enabled;
	private double output;
	private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

	/** {@link #wireSignal()} walks the wires, so its answer is kept for the rest of the tick. */
	private double cachedWireSignal;
	private int cachedWireSignalTick = -1;

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
		String clipped = Programs.clip(program);

		if (!clipped.equals(this.program)) {
			this.program = clipped;
			setChanged();
		}
	}

	/** Starts the stored program from the beginning, replacing a running one. */
	public void run() {
		halt();
		log.clear();
		setOutput(0);
		enabled = true;
		setChanged();
		start();
	}

	/** Stops the program and switches its signal off. */
	public void stop() {
		halt();
		setOutput(0);
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

	/** The signal the program emits: onto its wires as it is, and out of the back as redstone. */
	public double output() {
		return output;
	}

	/** The redstone power coming out of the back, 0 to 15. */
	public int power() {
		return WireNetwork.redstonePower(output);
	}

	void setOutput(double value) {
		double signal = Double.isNaN(value) ? 0 : value;

		if (signal == output) {
			return;
		}

		int oldPower = power();
		output = signal;
		setChanged();

		if (level == null || isRemoved()) {
			return;
		}

		if (power() != oldPower) {
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock(), null);
		}

		WireNetwork.update(serverLevel(), adjacentWirePositions());
	}

	/** Where wires plugged into this terminal would be: next to every side but the screen. */
	public List<BlockPos> adjacentWirePositions() {
		List<BlockPos> positions = new ArrayList<>();
		BlockState state = getBlockState();

		for (Direction side : Direction.values()) {
			if (TerminalBlock.acceptsWire(state, side)) {
				positions.add(worldPosition.relative(side));
			}
		}

		return positions;
	}

	/** The strongest signal other terminals put on this terminal's wires, or 0 without any. */
	double wireSignal() {
		int tick = serverLevel().getServer().getTickCount();

		if (tick != cachedWireSignalTick) {
			cachedWireSignal = WireNetwork.strongestOtherSignal(serverLevel(), this);
			cachedWireSignalTick = tick;
		}

		return cachedWireSignal;
	}

	/**
	 * The strongest redstone signal reaching the terminal from any side but its back. Wires
	 * do not count: they are read with {@link #wireSignal()}, exactly and without an echo.
	 */
	int redstoneInput() {
		Direction back = facing().getOpposite();
		int strongest = 0;

		for (Direction side : Direction.values()) {
			BlockPos neighbor = worldPosition.relative(side);

			if (side != back && !(level.getBlockState(neighbor).getBlock() instanceof WireBlock)) {
				strongest = Math.max(strongest, level.getSignal(neighbor, side));
			}
		}

		return strongest;
	}

	// ---------------------------------------------------------------- inventory

	/** The tool the terminal mines with; empty means bare hands. */
	public ItemStack tool() {
		return items.get(TOOL_SLOT);
	}

	/** Only things that wear out count as tools. */
	public static boolean isTool(ItemStack stack) {
		return stack.isDamageableItem();
	}

	/**
	 * Puts a stack into storage, merging with what is there.
	 *
	 * @return what did not fit
	 */
	ItemStack store(ItemStack stack) {
		// Going through a face applies the same rules as a hopper: storage only.
		return HopperBlockEntity.addItem(null, this, stack, Direction.UP);
	}

	/** How many items are in storage, of one kind or, with {@code null}, of any. */
	int stored(Item kind) {
		int count = 0;

		for (int slot : STORAGE) {
			ItemStack stack = items.get(slot);

			if (kind == null || stack.is(kind)) {
				count += stack.getCount();
			}
		}

		return count;
	}

	/**
	 * Moves as much of the storage as will fit into the containers touching the terminal,
	 * on any side but the screen.
	 *
	 * @return how many items moved
	 */
	int pushToNeighbors() {
		int moved = 0;

		for (Direction side : Direction.values()) {
			if (side == facing()) {
				continue;
			}

			Container target = HopperBlockEntity.getContainerAt(level, worldPosition.relative(side));

			if (target == null) {
				continue;
			}

			for (int slot : STORAGE) {
				ItemStack stack = items.get(slot);

				if (!stack.isEmpty()) {
					ItemStack rest = HopperBlockEntity.addItem(this, target, stack.copy(), side.getOpposite());
					moved += stack.getCount() - rest.getCount();
					items.set(slot, rest);
				}
			}
		}

		if (moved > 0) {
			setChanged();
		}

		return moved;
	}

	@Override
	public int getContainerSize() {
		return SLOTS;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot != TOOL_SLOT || isTool(stack);
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		return STORAGE;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return slot != TOOL_SLOT;
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return slot != TOOL_SLOT;
	}

	@Override
	protected Component getDefaultName() {
		return Component.translatable("block.minecod.terminal");
	}

	@Override
	protected AbstractContainerMenu createMenu(int id, Inventory playerInventory) {
		return new TerminalMenu(id, playerInventory, this);
	}

	// ---------------------------------------------------------------- saving

	/** A terminal that is mined keeps its program on the item, and gets it back when placed. */
	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);

		if (!program.isEmpty()) {
			components.set(Programs.COMPONENT, program);
		}
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		program = components.getOrDefault(Programs.COMPONENT, "");
	}

	@Override
	public void removeComponentsFromTag(ValueOutput output) {
		super.removeComponentsFromTag(output);
		output.discard("program");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("program", program);
		output.putBoolean("enabled", enabled);
		output.putDouble("output", this.output);
		ContainerHelper.saveAllItems(output, items);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		program = input.getStringOr("program", "");
		enabled = input.getBooleanOr("enabled", false);
		// "power" is what worlds saved before wires existed call it.
		output = input.getDoubleOr("output", input.getIntOr("power", 0));
		items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
	}
}
