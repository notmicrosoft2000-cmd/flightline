package dev.flightline;

import net.minecraft.resources.Identifier;

/** Side-effect-free identifiers shared by payloads and saved data. */
public final class FlightLineIds {
    public static final String MOD_ID = "flightline";

    private FlightLineIds() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
