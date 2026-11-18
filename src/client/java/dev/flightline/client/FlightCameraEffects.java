package dev.flightline.client;

import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightWarning;

/**
 * Client-only camera motion model. Inputs arrive from the existing instrument payload; nothing here
 * is applied to the player or to gameplay state. Oscillators are sampled from absolute time, so
 * long flights cannot accumulate drift.
 */
public final class FlightCameraEffects {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final float MAX_FRAME_SECONDS = 0.10f;
    private static final float TILT_TAU_SECONDS = 0.35f;
    private static final float JITTER_TAU_SECONDS = 0.25f;
    private static final long TAKEOFF_ENVELOPE_NANOS = 1_200_000_000L;
    private static final long ROLLOUT_ENVELOPE_NANOS = 1_500_000_000L;

    /** Hard tilt cap: 0.045 rad is about 2.6 degrees. */
    private static final float MAX_TILT_RADIANS = 0.045f;
    /** Normal positional cap, raised only while the WINDSHEAR bit is active. */
    private static final float MAX_POSITION_OFFSET = 0.025f;
    private static final float MAX_WINDSHEAR_POSITION_OFFSET = 0.05f;

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
    private static long takeoffEnvelopeUntilNanos;
    private static long rolloutEnvelopeUntilNanos;
    private static float tiltIntensity;
    private static float jitterIntensity;
    private static long lastFrameNanos;
    private static float frameDeltaSeconds;
    private static Shake cachedSample = Shake.NONE;
    private static boolean sampleValid;

    private FlightCameraEffects() {
    }

    /** Shake is suppressed when flight is off or when ATC camera-follow owns the view. */
    public static boolean isActive() {
        return FlightLineClient.isFlightEnabled()
                && phase != FlightPhase.OFF
                && phase != FlightPhase.TAXI
                && phase != FlightPhase.CRASHED;
    }

    /** Clears all smoothed motion state. The camera mixin separately removes any applied offset. */
    public static void reset() {
        tiltIntensity = 0.0f;
        jitterIntensity = 0.0f;
        lastFrameNanos = 0L;
        frameDeltaSeconds = 0.0f;
        cachedSample = Shake.NONE;
        sampleValid = false;
    }

    public static void updateFlightState(FlightPhase nextPhase) {
        if (phase != nextPhase) {
            long now = System.nanoTime();
            if (nextPhase == FlightPhase.ROTATE) {
                takeoffEnvelopeUntilNanos = now + TAKEOFF_ENVELOPE_NANOS;
            }
            if (nextPhase == FlightPhase.LANDING_ROLL) {
                rolloutEnvelopeUntilNanos = now + ROLLOUT_ENVELOPE_NANOS;
            }
        }
        phase = nextPhase;
        if (!isActive()) {
            airborne = false;
            takeoffEnvelopeUntilNanos = 0L;
            rolloutEnvelopeUntilNanos = 0L;
            reset();
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
        airborne = isActive() && FlightLineClient.isAirborne();
    }

    /**
     * Computes exactly one motion sample per rendered frame. Called from Camera.update before the
     * camera aligns itself with the entity, so repeated Camera.setRotation calls only read this
     * cached result and never smooth twice.
     */
    public static void beginFrame(long nowNanos) {
        if (!isActive()) {
            reset();
            return;
        }

        long deltaNanos = lastFrameNanos == 0L ? 0L : Math.max(0L, nowNanos - lastFrameNanos);
        lastFrameNanos = nowNanos;
        frameDeltaSeconds = Math.min(MAX_FRAME_SECONDS, deltaNanos / (float) NANOS_PER_SECOND);

        float sensitivity = clamp01(FlightLineClient.getAircraftType().turbulenceSensitivity());
        boolean windshear = (warnings & FlightWarning.WINDSHEAR) != 0;
        float severity = targetSeverity(nowNanos, sensitivity, windshear);

        tiltIntensity += (severity - tiltIntensity) * smoothing(frameDeltaSeconds, TILT_TAU_SECONDS);
        float jitterTarget = clamp01(severity * (windshear ? 1.0f : 0.85f));
        jitterIntensity += (jitterTarget - jitterIntensity) * smoothing(frameDeltaSeconds, JITTER_TAU_SECONDS);

        double time = nowNanos / (double) NANOS_PER_SECOND;
        cachedSample = buildSample(time, sensitivity, windshear);
        sampleValid = true;
    }

    public static Shake currentSample() {
        return sampleValid ? cachedSample : Shake.NONE;
    }

    /** Roll interpolation in the camera mixin uses the same frame-rate independent delta. */
    public static float frameDeltaSeconds() {
        return frameDeltaSeconds;
    }

    private static float smoothing(float deltaSeconds, float tauSeconds) {
        return 1.0f - (float) Math.exp(-deltaSeconds / tauSeconds);
    }

    private static float targetSeverity(long nowNanos, float sensitivity, boolean windshear) {
        float target = 0.0f;

        if (FlightLineClient.areEnginesActive()) {
            target = Math.max(target, 0.025f + clamp01(FlightHud.throttle()) * 0.035f);
        }

        if (airborne) {
            float turbulence = clamp01(turbulencePercent / 100.0f);
            float wind = clamp01(windSpeedKnots / 70.0f);
            float gust = clamp01(gustKnots / 70.0f);
            float atmosphere = Math.max(turbulence * 0.70f, Math.max(wind * 0.38f, gust * 0.60f))
                    * sensitivity;
            if (weatherCondition >= 1) {
                atmosphere = Math.max(atmosphere, 0.20f * sensitivity);
            }
            if (weatherCondition >= 2) {
                atmosphere = Math.max(atmosphere, 0.58f * sensitivity);
            }
            target = Math.max(target, atmosphere);
        }

        if (windshear) {
            // Windshear severity is server-only. The warning bit plus the server-boosted
            // turbulence/gust values are the display-only proxy carried by the instrument payload.
            float proxy = Math.max(clamp01(turbulencePercent / 100.0f) * 0.80f,
                    clamp01(gustKnots / 70.0f) * 0.90f);
            target = Math.max(target, Math.min(1.0f, 0.60f * sensitivity + 0.45f * proxy));
        }

        if ((warnings & FlightWarning.STALL) != 0) {
            target = Math.max(target, 0.48f);
        }
        if ((warnings & FlightWarning.AIRFRAME_STRESS) != 0
                || (warnings & FlightWarning.OVERSPEED) != 0) {
            target = Math.max(target, 0.32f);
        }

        boolean groundRoll = phase == FlightPhase.TAKEOFF_ROLL || phase == FlightPhase.ROTATE
                || nowNanos < takeoffEnvelopeUntilNanos
                || phase == FlightPhase.LANDING_ROLL || nowNanos < rolloutEnvelopeUntilNanos;
        if (groundRoll) {
            target = Math.max(target, 0.26f);
        }

        if (Math.abs(bankDegrees) > 20.0f) {
            target = Math.max(target, Math.min(0.28f, (Math.abs(bankDegrees) - 20.0f) * 0.004f));
        }
        if (airborne && radioAltitudeFeet < 100 && verticalSpeedFpm < -120) {
            target = Math.max(target, 0.34f * (1.0f - clamp01(radioAltitudeFeet / 100.0f)));
        }

        return clamp01(target);
    }

    private static Shake buildSample(double time, float sensitivity, boolean windshear) {
        if (tiltIntensity < 0.0005f && jitterIntensity < 0.0005f) {
            return Shake.NONE;
        }

        // Layer 1: slow, incommensurate roll/pitch/yaw sways feel like a drifting air mass.
        float rollWave = (float) (Math.sin(time * 1.13) * 0.62 + Math.sin(time * 0.47 + 1.2) * 0.38);
        float pitchWave = (float) (Math.cos(time * 0.91 + 0.5) * 0.60 + Math.sin(time * 0.37 + 2.1) * 0.40);
        float yawWave = (float) (Math.sin(time * 0.73 + 2.0) * 0.65 + Math.cos(time * 0.29 + 0.4) * 0.35);

        // Layer 2: short buffet bursts for stall and ground roll only.
        float buffet = 0.0f;
        if ((warnings & FlightWarning.STALL) != 0) {
            buffet = (float) (Math.sin(time * 18.0) * 0.20 + Math.sin(time * 23.7 + 1.1) * 0.12);
        } else if (phase == FlightPhase.LANDING_ROLL || phase == FlightPhase.TAKEOFF_ROLL) {
            buffet = (float) (Math.sin(time * 21.0) * 0.10);
        }

        float tiltCap = MAX_TILT_RADIANS * (0.55f + 0.45f * sensitivity);
        float roll = clamp(rollWave * tiltIntensity + buffet * tiltIntensity, -1.0f, 1.0f) * tiltCap;
        float pitch = clamp(pitchWave * tiltIntensity + buffet * 0.6f * tiltIntensity, -1.0f, 1.0f) * tiltCap;
        float yaw = clamp(yawWave * tiltIntensity, -1.0f, 1.0f) * tiltCap;

        // Layer 3: finer positional jitter on frequencies well above the tilt sway.
        float positionCap = (windshear ? MAX_WINDSHEAR_POSITION_OFFSET : MAX_POSITION_OFFSET)
                * (0.55f + 0.45f * sensitivity);
        float amplitude = jitterIntensity * positionCap;
        float offsetX = clamp((float) (Math.sin(time * 11.7) * 0.55
                + Math.sin(time * 19.3 + 1.7) * 0.30
                + Math.sin(time * 25.1 + 0.6) * 0.15), -1.0f, 1.0f) * amplitude;
        float offsetY = clamp((float) (Math.cos(time * 13.1 + 0.4) * 0.50
                + Math.sin(time * 21.7 + 2.2) * 0.30
                + Math.cos(time * 25.9) * 0.20), -1.0f, 1.0f) * amplitude;
        float offsetZ = clamp((float) (Math.sin(time * 10.3 + 2.8) * 0.50
                + Math.cos(time * 17.9 + 1.1) * 0.32
                + Math.sin(time * 23.3 + 0.9) * 0.18), -1.0f, 1.0f) * amplitude;

        return new Shake(pitch, yaw, roll, offsetX, offsetY, offsetZ);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Pose and render-only camera offset for the current frame. All components are hard capped. */
    public record Shake(float pitchRadians, float yawRadians, float rollRadians,
                        float offsetX, float offsetY, float offsetZ) {
        public static final Shake NONE = new Shake(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }
}
