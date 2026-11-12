package dev.flightline.flight;

public enum FlightPhase {
    OFF("OFF"),
    TAXI("TAXI"),
    TAKEOFF_ROLL("TAKEOFF ROLL"),
    ROTATE("ROTATE"),
    AIRBORNE("AIRBORNE"),
    STALL("STALL"),
    LANDING_ROLL("LANDING ROLL"),
    CRASHED("CRASHED");

    private final String label;

    FlightPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static FlightPhase fromId(int id) {
        FlightPhase[] phases = values();
        return id >= 0 && id < phases.length ? java.util.Objects.requireNonNull(phases[id]) : OFF;
    }
}
