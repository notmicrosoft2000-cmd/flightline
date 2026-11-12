package dev.flightline.flight;

public final class FlightWarning {
    public static final int STALL = 1;
    public static final int SINK_RATE = 1 << 1;
    public static final int PULL_UP = 1 << 2;
    public static final int BANK_ANGLE = 1 << 3;
    public static final int GEAR_UP = 1 << 4;
    public static final int GEAR_HOT = 1 << 5;
    public static final int GEAR_FAILED = 1 << 6;
    public static final int WINDSHEAR = 1 << 7;
    public static final int ENGINE_FAILURE = 1 << 8;
    public static final int ENGINE_FIRE = 1 << 9;
    public static final int FUEL_LOW = 1 << 10;
    public static final int FUEL_EMPTY = 1 << 11;
    public static final int DONT_SINK = 1 << 12;
    public static final int TCAS_TRAFFIC = 1 << 13;
    public static final int TCAS_RESOLUTION = 1 << 14;
    public static final int GLIDESLOPE_HIGH = 1 << 15;
    public static final int GLIDESLOPE_LOW = 1 << 16;
    public static final int TCAS_CLIMB = 1 << 17;
    public static final int TCAS_DESCEND = 1 << 18;
    public static final int TOO_LOW_FLAPS = 1 << 19;
    public static final int TAKEOFF_CONFIG = 1 << 20;
    public static final int OVERSPEED = 1 << 21;
    public static final int TCAS_CROSSING = 1 << 22;
    public static final int TCAS_CLEAR = 1 << 23;
    public static final int TERRAIN = 1 << 24;
    public static final int TOO_LOW_TERRAIN = 1 << 25;
    public static final int GEAR_SELECTED_ON_GROUND = 1 << 26;

    private FlightWarning() {
    }
}
