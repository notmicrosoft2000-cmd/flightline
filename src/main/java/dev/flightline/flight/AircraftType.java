package dev.flightline.flight;

import java.util.Locale;

public enum AircraftType {
    KASA_A380("kasa", "Kasa A380", "Twin-engine widebody airliner. Agile handling, standard cruise.",
            2, 172, 140, 124, 98, 1.0f, 1.0, 1.0f, 100.0, 1.0),
    GINA_787("gina", "The Gina 787", "Mega airliner with 6 giant high-bypass turbofans. Fast cruiser "
            + "(245 kt cruise, 280 kt maximum) with damped turbulence and airbrakes.",
            6, 280, 245, 140, 112, 0.55f, 1.7, 1.55f, 85.0, 1.2);

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
    private final float weight;
    private final double fuelCapacityPercent;
    private final double fuelConsumptionRate;

    AircraftType(String id, String displayName, String description, int engineCount,
                 int maxAirspeedKnots, int cruiseAirspeedKnots, int rotationSpeedKnots,
                 int stallSpeedKnots, float turbulenceSensitivity, double thrustMultiplier,
                 float weight, double fuelCapacityPercent, double fuelConsumptionRate) {
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
        this.weight = weight;
        this.fuelCapacityPercent = fuelCapacityPercent;
        this.fuelConsumptionRate = fuelConsumptionRate;
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

    public float weight() {
        return weight;
    }

    public double fuelCapacityPercent() {
        return fuelCapacityPercent;
    }

    public double fuelConsumptionRate() {
        return fuelConsumptionRate;
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
