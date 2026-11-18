package dev.flightline.flight;

/**
 * Lightweight, deterministic weather model built on top of the shared regional weather field.
 * The field supplies slowly varying air masses and gradient wind; this class maps them to the
 * compact cockpit guidance and to organized radar cells.
 */
public final class WeatherSimulation {
    private static final long WINDSHEAR_INTERVAL_TICKS = 600L;

    private WeatherSimulation() {
    }

    public static Conditions sample(boolean raining, boolean thundering, long gameTime, long seed) {
        return sample(raining, thundering, 0.8f, gameTime, seed);
    }

    /** Legacy seed-based sampling kept for tests and callers without a world position. */
    public static Conditions sample(boolean raining, boolean thundering, float biomeTemperature,
                                    long gameTime, long seed) {
        int blockX = (int) (((seed & 0xFFFFL) * 37L) % 131071L) - 65535;
        int blockZ = (int) (((seed >>> 16) & 0xFFFFL) * 53L) % 131071 - 65535;
        return sample(raining, thundering, biomeTemperature, blockX, blockZ, 96.0, gameTime);
    }

    public static Conditions sample(boolean raining, boolean thundering, float biomeTemperature,
                                    int blockX, int blockZ, double altitudeBlocks, long gameTime) {
        WeatherField.Sample field = WeatherField.sample(blockX, blockZ, altitudeBlocks,
                raining, thundering, biomeTemperature, gameTime);
        double speedBlocks = field.surfaceWindKnots() / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
        double[] vector = WeatherField.windVector(field.surfaceWindDirectionDegrees(), speedBlocks);
        return new Conditions(vector[0], vector[1],
                (int) Math.round(field.surfaceWindKnots()),
                (int) Math.round(field.surfaceWindDirectionDegrees()),
                (int) Math.round(field.turbulencePercent()), field.visibilityBlocks(),
                field.condition(), (raining || thundering) && biomeTemperature <= 0.2f,
                (int) Math.round(field.gustKnots()));
    }

    public static boolean isWindshearEvent(boolean thundering, long gameTime, long seed) {
        return thundering && Math.floorMod(gameTime + seed, WINDSHEAR_INTERVAL_TICKS) == 0L;
    }

    /**
     * Natural and operator-forced events share the same storm requirement so debug windshear stays
     * a natural phenomenon. Forced events skip only the natural interval check.
     */
    public static boolean shouldStartWindshear(boolean thundering, long gameTime, long seed,
                                               boolean forced) {
        return thundering && (forced || isWindshearEvent(thundering, gameTime, seed));
    }

    /** Existing windshear severity curve, extracted so forced and natural events match exactly. */
    public static double windshearSeverity(int gustKnots) {
        return 0.55 + 0.45 * Math.min(1.0, gustKnots / 70.0);
    }

    /**
     * Deterministic radar cell with a cluster envelope, lifecycle, motion and storm metadata.
     * Broad guidance still comes from the authoritative cockpit payload, so no extra network field
     * is needed for the display-only structure.
     */
    public static WeatherCell sampleCell(int cellX, int cellZ, long gameTime, long seed,
                                         int condition, int turbulencePercent, int gustKnots) {
        double worldX = (cellX + 0.5) * 128.0;
        double worldZ = (cellZ + 0.5) * 128.0;
        WeatherField.CellRegion region = WeatherField.sampleCellRegion(worldX, worldZ, gameTime, seed);

        double broad = condition == 2 ? 1.0 : condition == 1 ? 0.62 : 0.26;
        double envelope = clamp01(region.cluster() * 0.48 + region.front() * 0.32
                + broad * 0.34 + region.humidity() * 0.16);
        double life = 0.55 + 0.45 * Math.sin(gameTime * 0.0011 + region.phase() * Math.PI * 2.0);
        double severity = Math.min(18.0, turbulencePercent * 0.10 + gustKnots * 0.06);
        double intensity = envelope * (0.55 + 0.45 * life) * 100.0
                + (condition == 2 ? 18.0 * life : 0.0) + severity;
        if (condition == 0) intensity *= 0.72;

        int value = (int) Math.round(Math.max(0.0, Math.min(100.0, intensity)));
        if (value < 14) {
            return new WeatherCell(0, 0, 0, 0, 0.0, 0.0, false, false);
        }

        int cellCondition = value >= 72 ? 2 : value >= 36 ? 1 : 0;
        int type = cellCondition == 2 ? (value >= 90 ? 3 : 2) : cellCondition == 1 ? 1 : 0;
        int topFeet = cellCondition == 2 ? 22000 + value * 180
                : cellCondition == 1 ? 8000 + value * 70 : 4000 + value * 40;
        boolean lightning = cellCondition == 2 && value >= 78
                && (Math.floorDiv(gameTime, 20L) + cellX + cellZ) % 4L == 0L;
        boolean hail = type == 3 && value >= 90;

        double[] motion = WeatherField.windVector(region.windDirectionDegrees(), 1.0);
        return new WeatherCell(value, cellCondition, type, topFeet, motion[0], motion[1],
                lightning, hail);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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
    public record WeatherCell(int intensity, int condition, int type, int topFeet,
                              double motionDx, double motionDz, boolean lightning, boolean hail) {
        public WeatherCell(int intensity, int condition) {
            this(intensity, condition, condition == 2 ? 2 : condition == 1 ? 1 : 0,
                    0, 0.0, 0.0, false, false);
        }
    }
}
