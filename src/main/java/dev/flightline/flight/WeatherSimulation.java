package dev.flightline.flight;

public final class WeatherSimulation {
    private static final long WINDSHEAR_INTERVAL_TICKS = 600L;

    private WeatherSimulation() {
    }

    public static Conditions sample(boolean raining, boolean thundering, long gameTime, long seed) {
        return sample(raining, thundering, 0.8f, gameTime, seed);
    }

    public static Conditions sample(boolean raining, boolean thundering, float biomeTemperature,
                                    long gameTime, long seed) {
        double weatherSeverity = thundering ? 1.0 : raining ? 0.62 : 0.12;
        double phase = gameTime * 0.035 + (seed & 0xFFFFL) * 0.001;
        double gust = 0.5 + 0.5 * Math.sin(phase * (thundering ? 1.1 : 0.37));
        double windKnots = thundering ? 24.0 + gust * 66.0
                : raining ? 10.0 + gust * 42.0 : 4.0 + gust * 12.0;
        double directionRadians = gameTime * (thundering ? 0.0018 : 0.0007)
                + (seed & 0xFFFFL) * 0.0002;
        double windSpeed = windKnots / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
        int turbulencePercent = thundering
                ? (int) Math.round(50.0 + 50.0 * (0.5 + 0.5 * Math.sin(phase * 0.91 + 1.7)))
                : raining
                        ? (int) Math.round(18.0 + 54.0 * (0.5 + 0.5 * Math.sin(phase * 0.61 + 1.7)))
                        : (int) Math.round(4.0 + 16.0 * (0.5 + 0.5 * Math.sin(phase * 0.43 + 1.7)));
        int visibilityBlocks = thundering ? 40 : raining ? 72 : 320;
        int condition = thundering ? 2 : raining ? 1 : 0;
        int directionDegrees = Math.floorMod((int) Math.round(Math.toDegrees(directionRadians)), 360);
        return new Conditions(Math.cos(directionRadians) * windSpeed,
                Math.sin(directionRadians) * windSpeed,
                (int) Math.round(windKnots), directionDegrees, turbulencePercent,
                visibilityBlocks, condition, (raining || thundering) && biomeTemperature <= 0.2f);
    }

    public static boolean isWindshearEvent(boolean thundering, long gameTime, long seed) {
        return thundering && Math.floorMod(gameTime + seed, WINDSHEAR_INTERVAL_TICKS) == 0L;
    }

    static double advanceIcing(double currentPercent, boolean icingConditions, boolean antiIceEffective) {
        if (icingConditions && !antiIceEffective) return Math.min(100.0, currentPercent + 0.006);
        if (antiIceEffective) return Math.max(0.0, currentPercent - 0.045);
        return Math.max(0.0, currentPercent - 0.001);
    }

    public record Conditions(double windX, double windZ, int windSpeedKnots, int windDirectionDegrees,
                             int turbulencePercent, int visibilityBlocks, int condition,
                             boolean icingConditions) {
    }
}
