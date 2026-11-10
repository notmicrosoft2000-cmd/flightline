package dev.flightline.flight;

/** Initial gameplay tuning, expressed in blocks per tick and approximate knots. */
public final class FlightTuning {
    /** Reduces Minecraft world-space velocity while retaining aircraft-indicated speeds. */
    public static final double WORLD_SPEED_SCALE = 0.60;
    public static final double ROTATION_SPEED = 3.5 * WORLD_SPEED_SCALE;
    public static final double ROTATION_PITCH_DEGREES = 5.0;
    public static final double STALL_SPEED = 2.6 * WORLD_SPEED_SCALE;
    public static final double CRITICAL_ANGLE_OF_ATTACK_DEGREES = 18.0;
    public static final double MAX_AIRSPEED = 4.3 * WORLD_SPEED_SCALE;
    public static final double KNOTS_PER_BLOCK_PER_TICK = 38.88 / WORLD_SPEED_SCALE;
    public static final double THROTTLE_LEVER_RATE = 0.004;
    public static final double ENGINE_SPOOL_UP_RATE = 0.0025;
    public static final double ENGINE_SPOOL_DOWN_RATE = 0.0018;
    public static final double GROUND_THRUST = 0.025 * WORLD_SPEED_SCALE;
    public static final double AIR_THRUST = 0.02 * WORLD_SPEED_SCALE;
    public static final double WHEEL_BRAKE_DECELERATION = 0.035 * WORLD_SPEED_SCALE;
    public static final double MAX_BANK_DEGREES = 30.0;
    public static final double BANK_RESPONSE_DEGREES_PER_TICK = 0.12;

    private FlightTuning() {
    }
}
