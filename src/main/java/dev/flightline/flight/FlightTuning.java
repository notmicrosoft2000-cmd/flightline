package dev.flightline.flight;

/** Initial gameplay tuning, expressed in blocks per tick and approximate knots. */
public final class FlightTuning {
    /** Reduces Minecraft world-space velocity while retaining aircraft-indicated speeds. */
    public static final double WORLD_SPEED_SCALE = 0.60;
    public static final double ROTATION_SPEED = 3.5 * WORLD_SPEED_SCALE;
    public static final double ROTATION_PITCH_DEGREES = 5.0;
    public static final double STALL_SPEED = 2.6 * WORLD_SPEED_SCALE;
    public static final double CRITICAL_ANGLE_OF_ATTACK_DEGREES = 18.0;
    public static final double MAX_NOSE_UP_PITCH_DEGREES = 30.0;
    public static final double MAX_NOSE_DOWN_PITCH_DEGREES = -20.0;
    public static final double MAX_AIRSPEED = 4.3 * WORLD_SPEED_SCALE;
    public static final double OVERSPEED_WARNING_THRESHOLD = MAX_AIRSPEED * 0.99;
    static final int OVERSPEED_STRESS_START_TICKS = 80;
    static final int OVERSPEED_DAMAGE_START_TICKS = 160;
    static final int OVERSPEED_DAMAGE_INTERVAL_TICKS = 40;
    public static final double SAFE_TOUCHDOWN_SINK_SPEED = 0.30 * WORLD_SPEED_SCALE;
    public static final double MAX_HARD_LANDING_SINK_SPEED = 0.58 * WORLD_SPEED_SCALE;
    public static final double SAFE_TOUCHDOWN_SPEED = MAX_AIRSPEED * 0.98;
    public static final double MAX_HARD_LANDING_SPEED = MAX_AIRSPEED * 1.08;
    public static final double AUTOTHROTTLE_DEADBAND_KNOTS = 2.0;
    public static final double KNOTS_PER_BLOCK_PER_TICK = 38.88 / WORLD_SPEED_SCALE;
    public static final double THROTTLE_LEVER_RATE = 0.02;
    public static final double ENGINE_SPOOL_UP_RATE = 0.012;
    public static final double ENGINE_SPOOL_DOWN_RATE = 0.02;
    public static final double GROUND_THRUST = 0.025 * WORLD_SPEED_SCALE;
    public static final double AIR_THRUST = 0.02 * WORLD_SPEED_SCALE;
    public static final double WHEEL_BRAKE_DECELERATION = 0.035 * WORLD_SPEED_SCALE;
    public static final double PUSHBACK_TARGET_SPEED = 0.20 * WORLD_SPEED_SCALE;
    public static final double PUSHBACK_ACCELERATION = 0.02 * WORLD_SPEED_SCALE;
    public static final double PUSHBACK_STOPPING_DECELERATION = 0.08 * WORLD_SPEED_SCALE;
    public static final double MAX_BANK_DEGREES = 80.0;
    public static final double BANK_RESPONSE_DEGREES_PER_TICK = 0.95;
    public static final double BANK_RELEASE_RESPONSE_DEGREES_PER_TICK = 0.38;
    public static final double TURN_RATE_FACTOR = 1.05;
    public static final double CRITICAL_BANK_DEGREES = 65.0;

    private FlightTuning() {
    }

    static double adjustThrottle(double throttle, boolean increase) {
        double delta = increase ? THROTTLE_LEVER_RATE : -THROTTLE_LEVER_RATE;
        return Math.max(0.0, Math.min(1.0, throttle + delta));
    }

    static double updateEngineSpool(double current, double target) {
        double rate = target > current ? ENGINE_SPOOL_UP_RATE : ENGINE_SPOOL_DOWN_RATE;
        if (current < target) return Math.min(target, current + rate);
        return Math.max(target, current - rate);
    }

    static boolean isOverspeed(double airspeed) {
        return airspeed > OVERSPEED_WARNING_THRESHOLD;
    }

    static int advanceOverspeedStressTicks(int ticks, boolean overspeed) {
        return overspeed ? Math.min(1200, ticks + 1) : Math.max(0, ticks - 2);
    }

    static double overspeedLiftFactor(int stressTicks) {
        if (stressTicks <= OVERSPEED_STRESS_START_TICKS) return 1.0;
        return Math.max(0.65, 1.0 - (stressTicks - OVERSPEED_STRESS_START_TICKS) * 0.001);
    }

    static boolean shouldApplyOverspeedDamage(int stressTicks, int cooldownTicks) {
        return stressTicks >= OVERSPEED_DAMAGE_START_TICKS && cooldownTicks <= 0;
    }

    static double icedStallSpeed(double icingPercent, double flapPosition) {
        double icingFactor = 1.0 + Math.max(0.0, Math.min(100.0, icingPercent)) / 100.0 * 0.45;
        double flapFactor = 1.0 - Math.max(0.0, Math.min(1.0, flapPosition)) * 0.12;
        return STALL_SPEED * icingFactor * flapFactor;
    }

    static double icingLiftFactor(double icingPercent) {
        return 1.0 - Math.max(0.0, Math.min(100.0, icingPercent)) / 100.0 * 0.38;
    }

    static double spoilerLiftFactor(boolean spoilers) {
        return spoilers ? 0.30 : 1.0;
    }

    static PushbackMotion advancePushback(double speed, boolean reversing) {
        if (!reversing) {
            double nextSpeed = Math.max(0.0, speed - PUSHBACK_STOPPING_DECELERATION);
            return nextSpeed == 0.0
                    ? new PushbackMotion(0.0, true)
                    : new PushbackMotion(nextSpeed, false);
        }
        return new PushbackMotion(Math.min(PUSHBACK_TARGET_SPEED, speed + PUSHBACK_ACCELERATION), true);
    }

    record PushbackMotion(double speed, boolean reversing) {
    }
}
