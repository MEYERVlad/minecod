package io.github.meyervlad.minecod;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

/** Lua source text as something an item can carry: a notebook's functions, a terminal's program. */
public final class Programs {
	/** In characters. Small enough to fit one network packet even as three-byte UTF-8. */
	public static final int MAX_LENGTH = 8_000;

	/** The program stored on an item stack. Sent to clients, so they can show it in an editor. */
	public static final DataComponentType<String> COMPONENT = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Minecod.id("program"),
			DataComponentType.<String>builder()
					.persistent(Codec.string(0, MAX_LENGTH))
					.networkSynchronized(ByteBufCodecs.stringUtf8(MAX_LENGTH))
					.build());

	private Programs() {
	}

	/** Loading this class registers the component. */
	static void init() {
	}

	/** Cuts text that came from a client down to what may be stored. */
	public static String clip(String program) {
		return program.length() <= MAX_LENGTH ? program : program.substring(0, MAX_LENGTH);
	}
}
