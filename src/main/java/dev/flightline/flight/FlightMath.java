package dev.flightline.flight;

import net.minecraft.world.phys.Vec3;

/** Pure flight-math helpers shared by the authoritative simulation and its tests. */
public final class FlightMath {
    /** Height, in blocks, over which ground effect fades to zero. */
    public static final double GROUND_EFFECT_HEIGHT_BLOCKS = 12.0;
    private static final double GROUND_EFFECT_LIFT_GAIN = 0.32;
    private static final double GROUND_EFFECT_DRAG_REDUCTION = 0.30;

    private FlightMath() {
    }

    /** Unit attitude vector for a nose-pitched, heading-rotated aircraft. */
    public static Vec3 aircraftAttitudeVector(double pitchDegrees, double headingDegrees) {
        double pitchRad = Math.toRadians(pitchDegrees);
        double yawRad = Math.toRadians(headingDegrees);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(
                -Math.sin(yawRad) * cosPitch,
                Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        );
    }

    /** Coordinated turn rate, in radians per tick, for a bank angle and speed. */
    public static double coordinatedTurnRateRadiansPerTick(double bankDegrees, double speed) {
        return FlightTuning.TURN_RATE_FACTOR * FlightTuning.WORLD_SPEED_SCALE
                * Math.tan(Math.toRadians(bankDegrees)) / speed;
    }

    /** Ground-effect strength, from 1.0 at the surface to 0.0 at the effect height limit. */
    public static double groundEffectFactor(double radioAltBlocks) {
        return radioAltBlocks < GROUND_EFFECT_HEIGHT_BLOCKS
                ? Math.max(0.0, (GROUND_EFFECT_HEIGHT_BLOCKS - radioAltBlocks) / GROUND_EFFECT_HEIGHT_BLOCKS)
                : 0.0;
    }

    /** Lift multiplier produced by the given ground-effect strength. */
    public static double groundEffectLiftMultiplier(double factor) {
        return 1.0 + factor * GROUND_EFFECT_LIFT_GAIN;
    }

    /** Drag multiplier produced by the given ground-effect strength. */
    public static double groundEffectDragMultiplier(double factor) {
        return 1.0 - factor * GROUND_EFFECT_DRAG_REDUCTION;
    }

    /** Angle between the aircraft's pitch attitude and its flight path, in degrees. */
    public static double angleOfAttackDegrees(double pitchDegrees, double verticalSpeed, double horizontalSpeed) {
        double flightPathAngle = Math.toDegrees(Math.atan2(verticalSpeed, Math.max(horizontalSpeed, 1.0e-4)));
        return pitchDegrees - flightPathAngle;
    }
}
