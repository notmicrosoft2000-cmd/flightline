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
    private static int gustKnots;
    private static int weatherCondition;
    private static int takeoffTicks;
    private static int rolloutTicks;
    private static float shake;

    private FlightCameraEffects() {
    }

    public static void updateFlightState(FlightPhase nextPhase) {
        if (phase != nextPhase) {
            if (nextPhase == FlightPhase.ROTATE) takeoffTicks = 30;
            if (nextPhase == FlightPhase.LANDING_ROLL) rolloutTicks = 35;
        }
        phase = nextPhase;
        if (nextPhase == FlightPhase.OFF || nextPhase == FlightPhase.TAXI
                || nextPhase == FlightPhase.CRASHED) {
            airborne = false;
        }
    }

    public static void updateInstruments(float bank, int activeWarnings, int radioAltitude,
                                         int verticalSpeed, int windSpeed, int turbulence,
                                         int gust, int condition) {
        bankDegrees = bank;
        warnings = activeWarnings;
        radioAltitudeFeet = radioAltitude;
        verticalSpeedFpm = verticalSpeed;
        windSpeedKnots = windSpeed;
        turbulencePercent = turbulence;
        gustKnots = gust;
        weatherCondition = condition;
        airborne = FlightLineClient.isAirborne();
    }

    public static Shake sample(long timeNanos) {
        float target = 0.0f;
        float turbSens = FlightLineClient.getAircraftType().turbulenceSensitivity();

        // Subtle continuous engine hum
        if (FlightLineClient.areEnginesActive()) {
            target = Math.max(target, 0.0006f + FlightHud.throttle() * 0.0010f);
        }
        // Realistic airliner turbulence: gentle atmospheric air-pocket displacement
        if (airborne) {
            float turbFrac = Math.max(0.0f, Math.min(1.0f, turbulencePercent / 100.0f));
            float windFrac = Math.max(0.0f, Math.min(1.0f, windSpeedKnots / 70.0f));
            float gustFrac = Math.max(0.0f, Math.min(1.0f, gustKnots / 70.0f));
            target = Math.max(target, turbFrac * 0.0090f * turbSens);
            target = Math.max(target, windFrac * 0.0055f * turbSens);
            target = Math.max(target, gustFrac * 0.0080f * turbSens);
            // Storms are felt in the airframe, not just read on the weather page.
            if (weatherCondition >= 1) {
                target = Math.max(target, 0.006f * turbSens);
            }
            if (weatherCondition >= 2) {
                target = Math.max(target, 0.020f * turbSens);
            }
        }
        if ((warnings & FlightWarning.WINDSHEAR) != 0) target = Math.max(target, 0.022f * turbSens);
        if ((warnings & FlightWarning.STALL) != 0) target = Math.max(target, 0.018f);
        if ((warnings & FlightWarning.AIRFRAME_STRESS) != 0 || (warnings & FlightWarning.OVERSPEED) != 0) {
            target = Math.max(target, 0.012f);
        }
        if (phase == FlightPhase.TAKEOFF_ROLL || phase == FlightPhase.ROTATE) {
            target = Math.max(target, 0.008f);
        }
        if (takeoffTicks > 0) {
            target = Math.max(target, 0.009f);
            takeoffTicks--;
        }
        if (Math.abs(bankDegrees) > 20.0f) {
            target = Math.max(target, Math.min(0.006f, (Math.abs(bankDegrees) - 20.0f) * 0.00015f));
        }
        if (airborne && radioAltitudeFeet < 100 && verticalSpeedFpm < -120) {
            target = Math.max(target, 0.007f * (1.0f - Math.max(0, radioAltitudeFeet) / 100.0f));
        }
        if (phase == FlightPhase.LANDING_ROLL || rolloutTicks > 0) {
            target = Math.max(target, 0.010f);
            if (rolloutTicks > 0) rolloutTicks--;
        }

        // Smoothly interpolate shake intensity to avoid harsh jerking
        shake += (target - shake) * 0.10f;
        if (shake < 0.00005f) return Shake.NONE;

        double time = timeNanos / 1_000_000_000.0;

        // Atmospheric turbulence motion: low-frequency rolling sways and pitch bumps
        float rollAtmosphere = (float) (Math.sin(time * 3.4) * 0.65 + Math.sin(time * 1.5 + 1.2) * 0.35);
        float pitchAtmosphere = (float) (Math.cos(time * 2.9 + 0.5) * 0.60 + Math.sin(time * 1.2) * 0.40);
        float yawAtmosphere = (float) (Math.sin(time * 2.2 + 2.0) * 0.70 + Math.cos(time * 0.95 + 0.4) * 0.30);

        // Subtle high-frequency chatter only during stall buffeting or high-speed ground rollout
        float buffet = 0.0f;
        if ((warnings & FlightWarning.STALL) != 0) {
            buffet = (float) Math.sin(time * 22.0) * 0.0035f;
        } else if (phase == FlightPhase.LANDING_ROLL || phase == FlightPhase.TAKEOFF_ROLL) {
            buffet = (float) Math.sin(time * 24.0) * 0.0018f;
        }

        return new Shake(
                pitchAtmosphere * shake * 0.55f + buffet * 0.5f,
                yawAtmosphere * shake * 0.45f,
                rollAtmosphere * shake * 0.85f + buffet * 0.8f
        );
    }

    public record Shake(float pitchRadians, float yawRadians, float rollRadians) {
        private static final Shake NONE = new Shake(0.0f, 0.0f, 0.0f);
    }
}
