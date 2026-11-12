package dev.flightline.client;

import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightWarning;

public final class FlightCameraEffects {
    private static FlightPhase phase = FlightPhase.OFF;
    private static boolean airborne;
    private static float bankDegrees;
    private static int warnings;
    private static int radioAltitudeFeet;
    private static int verticalSpeedFpm;
    private static int windSpeedKnots;
    private static int turbulencePercent;
    private static int takeoffTicks;
    private static int rolloutTicks;
    private static float shake;

    private FlightCameraEffects() {
    }

    public static void updateFlightState(FlightPhase nextPhase) {
        if (phase != nextPhase) {
            if (nextPhase == FlightPhase.ROTATE) takeoffTicks = 50;
            if (nextPhase == FlightPhase.LANDING_ROLL) rolloutTicks = 35;
        }
        phase = nextPhase;
        if (nextPhase == FlightPhase.OFF || nextPhase == FlightPhase.TAXI
                || nextPhase == FlightPhase.CRASHED) {
            airborne = false;
        }
    }

    public static void updateInstruments(float bank, int activeWarnings, int radioAltitude,
                                         int verticalSpeed, int windSpeed, int turbulence) {
        bankDegrees = bank;
        warnings = activeWarnings;
        radioAltitudeFeet = radioAltitude;
        verticalSpeedFpm = verticalSpeed;
        windSpeedKnots = windSpeed;
        turbulencePercent = turbulence;
        airborne = FlightLineClient.isAirborne();
    }

    public static Shake sample(long timeNanos) {
        float target = 0.0f;
        if (airborne) target = Math.min(0.006f, windSpeedKnots * 0.00035f);
        if (airborne) target = Math.max(target, turbulencePercent * 0.00014f);
        if ((warnings & FlightWarning.WINDSHEAR) != 0) target = 0.035f;
        if (phase == FlightPhase.TAKEOFF_ROLL || phase == FlightPhase.ROTATE) {
            target = Math.max(target, 0.012f);
        }
        if (takeoffTicks > 0) {
            target = Math.max(target, 0.014f);
            takeoffTicks--;
        }
        if (Math.abs(bankDegrees) > 12.0f) {
            target = Math.max(target, Math.min(0.018f, (Math.abs(bankDegrees) - 12.0f) * 0.00028f));
        }
        if (airborne && radioAltitudeFeet < 120 && verticalSpeedFpm < -100) {
            target = Math.max(target, 0.016f * (1.0f - Math.max(0, radioAltitudeFeet) / 120.0f));
        }
        if (phase == FlightPhase.LANDING_ROLL || rolloutTicks > 0) {
            target = Math.max(target, 0.016f);
            if (rolloutTicks > 0) rolloutTicks--;
        }

        shake += (target - shake) * 0.16f;
        if (shake < 0.0001f) return Shake.NONE;
        double time = timeNanos / 1_000_000_000.0;
        return new Shake((float) Math.sin(time * 17.0) * shake,
                (float) Math.sin(time * 23.0 + 1.4) * shake * 0.72f,
                (float) Math.sin(time * 13.0 + 0.7) * shake * 0.45f);
    }

    public record Shake(float pitchRadians, float yawRadians, float rollRadians) {
        private static final Shake NONE = new Shake(0.0f, 0.0f, 0.0f);
    }
}
