package dev.flightline.flight;

/** Operator debug scenarios that can be injected into a pilot's authoritative flight session. */
public enum EmergencyScenario {
    FIRE("fire", "Engine fire", true),
    FAILURE("failure", "Engine failure", true),
    GEAR("gear", "Gear failure", false),
    HYDRAULIC("hydraulic", "Hydraulic failure", false),
    CABIN("cabin", "Pressurisation failure", false),
    FUEL("fuel", "Fuel exhaustion", false),
    ICE("ice", "Severe airframe ice", false),
    OVERSPEED("overspeed", "Overspeed stress", false),
    CLEAR("clear", "Clear all debug emergencies", false);

    private final String id;
    private final String label;
    private final boolean targetsEngine;

    EmergencyScenario(String id, String label, boolean targetsEngine) {
        this.id = id;
        this.label = label;
        this.targetsEngine = targetsEngine;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public boolean targetsEngine() {
        return targetsEngine;
    }

    /** Resolves a command argument without throwing, so bad input can produce a useful message. */
    public static EmergencyScenario fromId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        for (EmergencyScenario scenario : values()) {
            if (scenario.id.equals(normalized) || scenario.name().equalsIgnoreCase(normalized)) {
                return scenario;
            }
        }
        return null;
    }
}
