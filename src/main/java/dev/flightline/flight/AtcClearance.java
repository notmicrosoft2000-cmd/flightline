package dev.flightline.flight;

import dev.flightline.network.AtcOrderPayload;

import java.util.Locale;

/**
 * Validated, server-owned ATC clearance. The same clearance drives player autopilot targets and
 * radio phraseology so the operator, pilot and flight deck cannot disagree about an instruction.
 */
public record AtcClearance(int order, int value, int referenceEntityId,
                           double targetHeadingDegrees, int targetAltitudeFeet,
                           int targetVerticalSpeedFpm, int targetAirspeedKnots,
                           boolean dynamicTarget) {
    public static final int MIN_ALTITUDE_FEET = 100;
    public static final int MAX_ALTITUDE_FEET = 40_000;
    public static final int MIN_SPEED_KNOTS = 40;
    public static final int MAX_SPEED_KNOTS = 300;
    private static final int ATC_VERTICAL_SPEED_FPM = 1_500;

    public static boolean isValidOrder(int order) {
        return order >= AtcOrderPayload.HOLD && order <= AtcOrderPayload.FOLLOW;
    }

    public static boolean isValidValue(int order, int value) {
        return switch (order) {
            case AtcOrderPayload.HOLD, AtcOrderPayload.ORBIT, AtcOrderPayload.RETURN -> value == 0;
            case AtcOrderPayload.TURN -> value >= 0 && value < 360;
            case AtcOrderPayload.CLIMB, AtcOrderPayload.DESCEND ->
                    value >= MIN_ALTITUDE_FEET && value <= MAX_ALTITUDE_FEET;
            case AtcOrderPayload.SPEED -> value >= MIN_SPEED_KNOTS && value <= MAX_SPEED_KNOTS;
            case AtcOrderPayload.FOLLOW -> value == 0;
            default -> false;
        };
    }

    public static AtcClearance create(int order, int value, int referenceEntityId,
                                      double currentHeadingDegrees, int currentAltitudeFeet,
                                      int currentSpeedKnots) {
        if (!isValidOrder(order) || !isValidValue(order, value)) {
            throw new IllegalArgumentException("Invalid ATC clearance");
        }
        double heading = wrapDegrees(currentHeadingDegrees);
        int altitude = Math.max(MIN_ALTITUDE_FEET, currentAltitudeFeet);
        int speed = Math.max(MIN_SPEED_KNOTS, Math.min(MAX_SPEED_KNOTS, currentSpeedKnots));
        boolean dynamic = order == AtcOrderPayload.ORBIT
                || order == AtcOrderPayload.RETURN
                || order == AtcOrderPayload.FOLLOW;

        return switch (order) {
            case AtcOrderPayload.HOLD -> new AtcClearance(order, value, referenceEntityId,
                    heading, altitude, 0, speed, false);
            case AtcOrderPayload.TURN -> new AtcClearance(order, value, referenceEntityId,
                    value, altitude, 0, speed, false);
            case AtcOrderPayload.CLIMB -> new AtcClearance(order, value, referenceEntityId,
                    heading, value, ATC_VERTICAL_SPEED_FPM, speed, false);
            case AtcOrderPayload.DESCEND -> new AtcClearance(order, value, referenceEntityId,
                    heading, value, -ATC_VERTICAL_SPEED_FPM, speed, false);
            case AtcOrderPayload.SPEED -> new AtcClearance(order, value, referenceEntityId,
                    heading, altitude, 0, value, false);
            default -> new AtcClearance(order, value, referenceEntityId,
                    heading, altitude, 0, speed, dynamic);
        };
    }

    public String radioText() {
        return switch (order) {
            case AtcOrderPayload.HOLD -> "hold present heading and altitude";
            case AtcOrderPayload.TURN -> "turn heading " + String.format(Locale.ROOT, "%03d", value);
            case AtcOrderPayload.CLIMB -> "climb and maintain " + value + " feet";
            case AtcOrderPayload.DESCEND -> "descend and maintain " + value + " feet";
            case AtcOrderPayload.SPEED -> "maintain " + value + " knots";
            case AtcOrderPayload.ORBIT -> "orbit my position";
            case AtcOrderPayload.RETURN -> "return to my position";
            case AtcOrderPayload.FOLLOW -> "follow aircraft " + referenceEntityId;
            default -> "release control";
        };
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = Math.floorMod((int) Math.round(degrees), 360);
        return wrapped;
    }
}
