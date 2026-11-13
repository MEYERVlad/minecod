package io.github.meyervlad.minecod.terminal;

import net.minecraft.util.StringRepresentable;

/** What a terminal's screen shows to the world: a property of the block, so it is visible from afar. */
public enum TerminalStatus implements StringRepresentable {
	/** Not running anything. */
	IDLE("idle"),
	RUNNING("running"),
	/** The last program stopped with an error. */
	ERROR("error");

	private static final TerminalStatus[] VALUES = values();

	private final String name;

	TerminalStatus(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	/** For reading from the network: any unknown number is {@link #IDLE}. */
	public static TerminalStatus byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
	}
}
