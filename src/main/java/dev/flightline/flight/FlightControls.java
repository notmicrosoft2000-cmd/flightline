package dev.flightline.flight;

/** Compact input bits shared by the logical client and server. */
public final class FlightControls {
    public static final int FORWARD = 1;
    public static final int REVERSE = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int BRAKE = 1 << 4;

    private FlightControls() {
    }
}
