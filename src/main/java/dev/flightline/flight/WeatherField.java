package dev.flightline.flight;

/**
 * Deterministic, position-aware weather field shared by the flight model and the map displays.
 * Regions drift slowly with time, so the same block always samples the same air mass for a given
 * moment without needing a network payload for every cell on the radar.
 */
public final class WeatherField {
    /** Shared by the server simulation and every client display. */
    public static final long WEATHER_SEED = 0x464C4947485452L;

    private WeatherField() {
    }

    public record Sample(double lowPressure, double humidity, double instability, double front,
                         double surfaceWindKnots, double surfaceWindDirectionDegrees,
                         double gustKnots, double flightWindKnots, double flightWindDirectionDegrees,
                         double turbulencePercent, int visibilityBlocks, int condition,
                         double precipitation, int freezingLevelFeet, double qnh) {
    }

    public record CellRegion(double cluster, double front, double humidity, double phase,
                             double windDirectionDegrees, double windSpeedKnots) {
    }

    public static Sample sample(int blockX, int blockZ, double altitudeBlocks,
                                boolean raining, boolean thundering, float biomeTemperature,
                                long gameTime) {
        return sample(blockX, blockZ, altitudeBlocks, raining, thundering, biomeTemperature,
                gameTime, WEATHER_SEED);
    }

    public static Sample sample(int blockX, int blockZ, double altitudeBlocks,
                                boolean raining, boolean thundering, float biomeTemperature,
                                long gameTime, long seed) {
        double broad = thundering ? 1.0 : raining ? 0.58 : 0.12;

        double regional = driftNoise(blockX / 2048.0, blockZ / 2048.0, gameTime, seed ^ 0x51EDL);
        double meso = driftNoise(blockX / 512.0, blockZ / 512.0, gameTime, seed ^ 0x22A3L);
        double frontRaw = driftNoise(blockX / 1280.0 + blockZ / 5120.0,
                blockZ / 1280.0, gameTime, seed ^ 0x33B7L);
        double front = clamp01((frontRaw - 0.42) * 2.6) * (0.30 + broad * 0.70);

        double lowPressure = clamp01(broad * 0.56 + regional * 0.30 + front * 0.22);
        double humidity = clamp01(broad * 0.50 + regional * 0.20 + meso * 0.24 + front * 0.16);
        double instability = clamp01(broad * 0.50 + meso * 0.28 + front * 0.24 - 0.10);

        int condition = thundering && instability > 0.44 ? 2
                : raining || humidity > 0.58 ? 1 : 0;
        double precipitation = clamp01(humidity * (condition == 0 ? 0.42 : 0.82) + front * 0.24);

        double[] wind = windFromGradient(blockX, blockZ, gameTime, seed, regional);
        double direction = wrapDegrees(wind[0] + regional * 28.0 - 8.0);

        double gustWave = 0.5 + 0.5 * Math.sin(gameTime * 0.017 + meso * 7.1);
        double gustEnvelope = 0.5 + 0.5 * Math.sin(gameTime * 0.006 + regional * 4.3);
        double surfaceKnots = clamp(4.0 + broad * 13.0 + humidity * 7.0
                + instability * 9.0 + front * 12.0 + gustWave * 3.0, 2.0, 86.0);
        double gustKnots = clamp(surfaceKnots + 2.0
                + broad * 16.0 * gustWave * (0.45 + 0.55 * gustEnvelope)
                + (condition == 2 ? 10.0 : 0.0) * gustWave, surfaceKnots, 98.0);

        double altitudeFactor = clamp01((altitudeBlocks - 40.0) / 500.0);
        double jet = altitudeFactor * (18.0 + regional * 34.0 + broad * 18.0);
        double flightKnots = clamp(surfaceKnots + jet, surfaceKnots, 140.0);
        double flightDirection = wrapDegrees(direction + 18.0
                + altitudeFactor * 32.0 * Math.sin(regional * 5.3));

        double turbulence = clamp(2.0 + broad * 44.0 + instability * 26.0 + front * 14.0
                + (condition == 2 ? 10.0 : 0.0) + gustWave * 6.0, 0.0, 98.0);
        int visibility = (int) Math.round(clamp(320.0 - precipitation * 250.0 - front * 36.0
                - (condition == 2 ? 34.0 : 0.0), 16.0, 320.0));

        int freezingLevel = (int) Math.round(clamp(1800.0 + biomeTemperature * 2200.0
                - lowPressure * 500.0, 200.0, 5200.0));
        double qnh = 1013.0 - lowPressure * 18.0 + regional * 4.0;

        return new Sample(lowPressure, humidity, instability, front, surfaceKnots, direction,
                gustKnots, flightKnots, flightDirection, turbulence, visibility, condition,
                precipitation, freezingLevel, qnh);
    }

    /** Lightweight region data used to organize radar cells into clusters and fronts. */
    public static CellRegion sampleCellRegion(double worldX, double worldZ, long gameTime, long seed) {
        double cluster = driftNoise(worldX / 640.0, worldZ / 640.0, gameTime, seed ^ 0xA17BL);
        double frontRaw = driftNoise(worldX / 1600.0 + worldZ / 6400.0,
                worldZ / 1600.0, gameTime, seed ^ 0xB23DL);
        double front = clamp01((frontRaw - 0.40) * 2.4);
        double humidity = driftNoise(worldX / 1000.0, worldZ / 1000.0, gameTime, seed ^ 0xC31FL);
        int cellX = (int) Math.floor(worldX / 128.0);
        int cellZ = (int) Math.floor(worldZ / 128.0);
        double phase = random(cellX, cellZ, seed ^ 0xD42AL);
        double direction = wrapDegrees(240.0
                + driftNoise(worldX / 2048.0, worldZ / 2048.0, gameTime, seed ^ 0xE58FL) * 120.0);
        double speed = 8.0 + humidity * 14.0 + front * 10.0;
        return new CellRegion(cluster, front, humidity, phase, direction, speed);
    }

    /**
     * Converts a meteorological "wind from" direction into a world-space vector pointing where the
     * air is going. North is -Z, east is +X.
     */
    public static double[] windVector(double fromDegrees, double speed) {
        double radians = Math.toRadians(fromDegrees);
        return new double[]{-Math.sin(radians) * speed, Math.cos(radians) * speed};
    }

    private static double[] windFromGradient(int blockX, int blockZ, long gameTime, long seed,
                                             double regional) {
        double scale = 1.0 / 2048.0;
        long salt = seed ^ 0x71C3L;
        double east = driftNoise((blockX + 128) * scale, blockZ * scale, gameTime, salt);
        double west = driftNoise((blockX - 128) * scale, blockZ * scale, gameTime, salt);
        double south = driftNoise(blockX * scale, (blockZ + 128) * scale, gameTime, salt);
        double north = driftNoise(blockX * scale, (blockZ - 128) * scale, gameTime, salt);
        double flowX = -(south - north);
        double flowZ = east - west;
        double length = Math.hypot(flowX, flowZ);
        if (length < 1.0e-5) {
            flowX = Math.sin(regional * Math.PI * 2.0);
            flowZ = Math.cos(regional * Math.PI * 2.0);
            length = 1.0;
        }
        double toX = flowX / length;
        double toZ = flowZ / length;
        return new double[]{wrapDegrees(Math.toDegrees(Math.atan2(-toX, toZ))), length};
    }

    private static double driftNoise(double x, double z, long gameTime, long salt) {
        double time = gameTime / 1800.0;
        long epoch = (long) Math.floor(time);
        double blend = smooth(time - epoch);
        double base = epoch * 0.85;
        double a = valueNoise(x + base * 0.7, z - base * 0.35, salt);
        double b = valueNoise(x + (base + 0.85) * 0.7, z - (base + 0.85) * 0.35, salt);
        return lerp(a, b, blend);
    }

    private static double valueNoise(double x, double z, long salt) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double zf = z - zi;
        double u = smooth(xf);
        double v = smooth(zf);
        double a = random(xi, zi, salt);
        double b = random(xi + 1, zi, salt);
        double c = random(xi, zi + 1, salt);
        double d = random(xi + 1, zi + 1, salt);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    private static double random(int x, int z, long salt) {
        long hash = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt;
        return (mix64(hash) >>> 11) / 9007199254740992.0;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }
}
