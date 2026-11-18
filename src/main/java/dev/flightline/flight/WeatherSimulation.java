package dev.flightline.flight;

/**
 * Lightweight, deterministic weather model. Wind is split into a slowly varying mean flow and a
 * shorter-period gust component, which gives the cockpit a believable "wind 270/25G40" feel instead
 * of a single sine wave. Storms add a much wider gust spread and a vertical component that the
 * flight model turns into turbulence and windshear.
 */
public final class WeatherSimulation {
    private static final long WINDSHEAR_INTERVAL_TICKS = 600L;

    private WeatherSimulation() {
    }

    public static Conditions sample(boolean raining, boolean thundering, long gameTime, long seed) {
        return sample(raining, thundering, 0.8f, gameTime, seed);
    }

    public static Conditions sample(boolean raining, boolean thundering, float biomeTemperature,
                                    long gameTime, long seed) {
        double weatherSeverity = thundering ? 1.0 : raining ? 0.55 : 0.10;
        double seedPhase = (seed & 0xFFFFL) * 0.00013;
        double phase = gameTime * 0.018 + seedPhase;

        // A slow pressure wave plus a faster gust wave. The two never line up for long, so gusts
        // arrive in bursts and fade instead of producing a perfect sine.
        double slowWave = 0.5 + 0.5 * Math.sin(phase * 0.33 + seedPhase * 7.0);
        double gustWave = 0.5 + 0.5 * Math.sin(phase * 1.87 + Math.sin(phase * 0.41) * 1.3);
        double gustEnvelope = 0.55 + 0.45 * Math.sin(phase * 0.71 + 2.1);

        double baseKnots = thundering ? 20.0 + slowWave * 18.0
                : raining ? 8.0 + slowWave * 12.0
                : 3.0 + slowWave * 6.0;
        double gustAmplitude = (thundering ? 16.0 + gustWave * 16.0
                : raining ? 5.0 + gustWave * 9.0
                : 1.0 + gustWave * 3.0) * Math.max(0.35, gustEnvelope);
        double windKnots = baseKnots + gustAmplitude * 0.45;
        int gustKnots = (int) Math.round(windKnots + gustAmplitude * 0.75);

        // Wind slowly veers with the weather system instead of snapping around every tick.
        double directionRadians = (seed & 0xFFFFL) * 0.0021
                + Math.sin(gameTime * 0.00083) * 0.55
                + gameTime * 0.00006;
        double windSpeed = windKnots / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;

        int turbulencePercent = thundering
                ? (int) Math.round(48.0 + 47.0 * (0.5 + 0.5 * Math.sin(phase * 0.91 + 1.7)))
                : raining
                        ? (int) Math.round(18.0 + 36.0 * (0.5 + 0.5 * Math.sin(phase * 0.61 + 1.7)))
                        : (int) Math.round(2.0 + 10.0 * (0.5 + 0.5 * Math.sin(phase * 0.43 + 1.7)));
        int visibilityBlocks = thundering ? 28 : raining ? 64 : 320;
        int condition = thundering ? 2 : raining ? 1 : 0;
        int directionDegrees = Math.floorMod((int) Math.round(Math.toDegrees(directionRadians)), 360);
        return new Conditions(Math.cos(directionRadians) * windSpeed,
                Math.sin(directionRadians) * windSpeed,
                (int) Math.round(windKnots), directionDegrees, turbulencePercent,
                visibilityBlocks, condition, (raining || thundering) && biomeTemperature <= 0.2f,
                Math.max((int) Math.round(windKnots), gustKnots));
    }

    public static boolean isWindshearEvent(boolean thundering, long gameTime, long seed) {
        return thundering && Math.floorMod(gameTime + seed, WINDSHEAR_INTERVAL_TICKS) == 0L;
    }

    /**
     * Deterministic weather cell used by the map screens. The authoritative per-player guidance sets
     * the broad storm intensity; the hash adds stable local structure so a cell keeps its shape while
     * the whole system drifts. No extra network payload is needed because every client samples the
     * same world seed and game time.
     */
    public static WeatherCell sampleCell(int cellX, int cellZ, long gameTime, long seed,
                                         int condition, int turbulencePercent, int gustKnots) {
        long epoch = gameTime / 600L;
        long hash = cellX * 0x9E3779B97F4A7C15L
                ^ cellZ * 0xC2B2AE3D27D4EB4FL
                ^ seed
                ^ epoch * 0xBF58476D1CE4E5B9L;
        int noise = (int) ((mix64(hash) >>> 48) & 0xFFFFL);
        double drift = 0.5 + 0.5 * Math.sin(gameTime * 0.0012
                + cellX * 0.83 + cellZ * 0.39 + (seed & 0xFFFFL) * 0.001);
        double base = condition == 2 ? 0.70 : condition == 1 ? 0.40 : 0.10;
        double severity = Math.min(0.24, turbulencePercent / 400.0)
                + Math.min(0.12, gustKnots / 400.0);
        double intensity = (base * 0.64 + noise / 65535.0 * 0.34 + drift * 0.18 + severity) * 100.0;
        if (condition == 0 && intensity < 36.0) intensity = 0.0;
        int value = (int) Math.round(Math.max(0.0, Math.min(100.0, intensity)));
        int cellCondition = value >= 70 ? 2 : value >= 38 ? 1 : 0;
        return new WeatherCell(value, cellCondition);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    static double advanceIcing(double currentPercent, boolean icingConditions, boolean antiIceEffective) {
        if (icingConditions && !antiIceEffective) return Math.min(100.0, currentPercent + 0.006);
        if (antiIceEffective) return Math.max(0.0, currentPercent - 0.045);
        return Math.max(0.0, currentPercent - 0.001);
    }

    public record Conditions(double windX, double windZ, int windSpeedKnots, int windDirectionDegrees,
                             int turbulencePercent, int visibilityBlocks, int condition,
                             boolean icingConditions, int gustKnots) {
    }

    /** One deterministic radar/map cell. Intensity is 0-100; condition follows the storm scale. */
    public record WeatherCell(int intensity, int condition) {
    }
}
