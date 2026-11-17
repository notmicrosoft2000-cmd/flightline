package dev.flightline.flight;

/** Client-to-server emergency procedures. Target 0/1 selects an engine where relevant. */
public final class EmergencyAction {
    public static final int EXTINGUISH_ENGINE = 0;
    public static final int RESTART_ENGINE = 1;
    public static final int ALTERNATE_GEAR = 2;
    public static final int RESET_HYDRAULICS = 3;
    public static final int EMERGENCY_DESCENT = 4;
    public static final int TOGGLE_FUEL_DUMP = 5;
    public static final int DECLARE_MAYDAY = 6;
    public static final int EMERGENCY_AUTOLAND = 7;
    public static final int CANCEL_MAYDAY = 8;

    private EmergencyAction() {
    }

    public static boolean isEngineAction(int action) {
        return action == EXTINGUISH_ENGINE || action == RESTART_ENGINE;
    }
}
