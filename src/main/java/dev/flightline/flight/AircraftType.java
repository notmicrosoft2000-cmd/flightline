package dev.flightline.flight;

import java.util.Locale;

public enum AircraftType {
    KASA_A380("kasa", "Kasa A380", "Twin-engine widebody airliner. Agile handling, standard cruise.",
            2, 172, 140, 124, 98, 1.0f, 1.0),
    GINA_787("gina", "The Gina 787", "Mega airliner with 6 giant high-bypass turbofans. High-speed cruiser (200-210 kt) with advanced turbulence damping.",
            6, 215, 205, 135, 105, 0.45f, 1.45);

    private final String id;
    private final String displayName;
    private final String description;
    private final int engineCount;
    private final int maxAirspeedKnots;
    private final int cruiseAirspeedKnots;
    private final int rotationSpeedKnots;
    private final int stallSpeedKnots;
    private final float turbulenceSensitivity;
    private final double thrustMultiplier;

    AircraftType(String id, String displayName, String description, int engineCount,
                 int maxAirspeedKnots, int cruiseAirspeedKnots, int rotationSpeedKnots,
                 int stallSpeedKnots, float turbulenceSensitivity, double thrustMultiplier) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.engineCount = engineCount;
        this.maxAirspeedKnots = maxAirspeedKnots;
        this.cruiseAirspeedKnots = cruiseAirspeedKnots;
        this.rotationSpeedKnots = rotationSpeedKnots;
        this.stallSpeedKnots = stallSpeedKnots;
        this.turbulenceSensitivity = turbulenceSensitivity;
        this.thrustMultiplier = thrustMultiplier;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public int engineCount() {
        return engineCount;
    }

    public int maxAirspeedKnots() {
        return maxAirspeedKnots;
    }

    public int cruiseAirspeedKnots() {
        return cruiseAirspeedKnots;
    }

    public int rotationSpeedKnots() {
        return rotationSpeedKnots;
    }

    public int stallSpeedKnots() {
        return stallSpeedKnots;
    }

    public float turbulenceSensitivity() {
        return turbulenceSensitivity;
    }

    public double thrustMultiplier() {
        return thrustMultiplier;
    }

    public double maxAirspeedBlocksPerTick() {
        return maxAirspeedKnots / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
    }

    public double rotationSpeedBlocksPerTick() {
        return rotationSpeedKnots / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
    }

    public double stallSpeedBlocksPerTick() {
        return stallSpeedKnots / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
    }

    public static AircraftType fromId(String input) {
        if (input == null) return KASA_A380;
        String clean = input.trim().toLowerCase(Locale.ROOT);
        if (clean.contains("gina") || clean.contains("787")) {
            return GINA_787;
        }
        return KASA_A380;
    }
}
