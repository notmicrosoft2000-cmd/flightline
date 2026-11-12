package dev.flightline.flight;

public final class WeatherSimulation {
    private WeatherSimulation() {
    }

    public static Conditions sample(boolean raining, boolean thundering, long gameTime, long seed) {
        double weatherSeverity = thundering ? 1.0 : raining ? 0.62 : 0.16;
        double phase = gameTime * 0.035 + (seed & 0xFFFFL) * 0.001;
        double gust = 0.5 + 0.5 * Math.sin(phase * 0.37);
        double windKnots = 4.0 + weatherSeverity * (12.0 + gust * 32.0);
        double directionRadians = gameTime * 0.0007 + (seed & 0xFFFFL) * 0.0002;
        double windSpeed = windKnots / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
        int turbulencePercent = (int) Math.round(5.0 + weatherSeverity
                * (25.0 + 70.0 * (0.5 + 0.5 * Math.sin(phase * 0.61 + 1.7))));
        int visibilityBlocks = thundering ? 64 : raining ? 96 : 320;
        int condition = thundering ? 2 : raining ? 1 : 0;
        int directionDegrees = Math.floorMod((int) Math.round(Math.toDegrees(directionRadians)), 360);
        return new Conditions(Math.cos(directionRadians) * windSpeed,
                Math.sin(directionRadians) * windSpeed,
                (int) Math.round(windKnots), directionDegrees, turbulencePercent,
                visibilityBlocks, condition);
    }

    public static boolean isWindshearEvent(boolean thundering, long gameTime, long seed) {
        return thundering && Math.floorMod(gameTime + seed, 320L) == 0L;
    }

    public record Conditions(double windX, double windZ, int windSpeedKnots, int windDirectionDegrees,
                             int turbulencePercent, int visibilityBlocks, int condition) {
    }
}
