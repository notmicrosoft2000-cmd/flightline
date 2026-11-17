package dev.flightline.flight;

/**
 * Shared emergency condition, availability and recommendation bitmasks.
 * All values are protocol-stable: the client and server both interpret these integers.
 */
public final class EmergencyStatus {
    // Active conditions
    public static final int FIRE_1 = 1 << 0;
    public static final int FIRE_2 = 1 << 1;
    public static final int FAILURE_1 = 1 << 2;
    public static final int FAILURE_2 = 1 << 3;
    public static final int FUEL_LOW = 1 << 4;
    public static final int FUEL_EMPTY = 1 << 5;
    public static final int GEAR_FAILED = 1 << 6;
    public static final int HYDRAULIC = 1 << 7;
    public static final int PRESSURISATION = 1 << 8;
    public static final int ICING = 1 << 9;
    public static final int OVERSPEED = 1 << 10;
    public static final int WINDSHEAR = 1 << 11;
    public static final int EMERGENCY_DECLARED = 1 << 12;
    public static final int FUEL_DUMPING = 1 << 13;
    public static final int EMERGENCY_DESCENT = 1 << 14;
    /** A landing emergency with enough fuel remaining to reduce landing weight. */
    public static final int HEAVY_FUEL = 1 << 15;

    // Procedures currently available to the pilot
    public static final int CAN_EXTINGUISH_1 = 1 << 0;
    public static final int CAN_EXTINGUISH_2 = 1 << 1;
    public static final int CAN_RESTART_1 = 1 << 2;
    public static final int CAN_RESTART_2 = 1 << 3;
    public static final int CAN_ALTERNATE_GEAR = 1 << 4;
    public static final int CAN_RESET_HYDRAULICS = 1 << 5;
    public static final int CAN_EMERGENCY_DESCENT = 1 << 6;
    public static final int CAN_DUMP_FUEL = 1 << 7;
    public static final int CAN_STOP_DUMP = 1 << 8;
    public static final int CAN_MAYDAY = 1 << 9;
    public static final int CAN_CANCEL_MAYDAY = 1 << 10;
    public static final int CAN_AUTOLAND = 1 << 11;

    private EmergencyStatus() {
    }

    /** The single best next action for the HUD prompt and the T key. */
    public record Recommendation(int action, int target) {
        public static final Recommendation NONE = new Recommendation(-1, -1);

        public boolean present() {
            return action >= 0;
        }
    }

    /** Chooses the most time-critical procedure that is actually available. */
    public static Recommendation recommend(int conditions, int available) {
        if ((available & CAN_EXTINGUISH_1) != 0) {
            return new Recommendation(EmergencyAction.EXTINGUISH_ENGINE, 0);
        }
        if ((available & CAN_EXTINGUISH_2) != 0) {
            return new Recommendation(EmergencyAction.EXTINGUISH_ENGINE, 1);
        }
        if ((conditions & PRESSURISATION) != 0 && (available & CAN_EMERGENCY_DESCENT) != 0) {
            return new Recommendation(EmergencyAction.EMERGENCY_DESCENT, -1);
        }
        if ((conditions & GEAR_FAILED) != 0 && (available & CAN_ALTERNATE_GEAR) != 0) {
            return new Recommendation(EmergencyAction.ALTERNATE_GEAR, -1);
        }
        if ((conditions & HYDRAULIC) != 0 && (available & CAN_RESET_HYDRAULICS) != 0) {
            return new Recommendation(EmergencyAction.RESET_HYDRAULICS, -1);
        }
        if ((available & CAN_RESTART_1) != 0) {
            return new Recommendation(EmergencyAction.RESTART_ENGINE, 0);
        }
        if ((available & CAN_RESTART_2) != 0) {
            return new Recommendation(EmergencyAction.RESTART_ENGINE, 1);
        }
        boolean urgentLanding = (conditions & (FIRE_1 | FIRE_2 | FAILURE_1 | FAILURE_2
                | FUEL_EMPTY | GEAR_FAILED | HYDRAULIC | PRESSURISATION)) != 0;
        if (urgentLanding && (available & CAN_AUTOLAND) != 0) {
            return new Recommendation(EmergencyAction.EMERGENCY_AUTOLAND, -1);
        }
        if ((available & CAN_MAYDAY) != 0) {
            return new Recommendation(EmergencyAction.DECLARE_MAYDAY, -1);
        }
        if ((conditions & HEAVY_FUEL) != 0 && (available & CAN_DUMP_FUEL) != 0) {
            return new Recommendation(EmergencyAction.TOGGLE_FUEL_DUMP, -1);
        }
        return Recommendation.NONE;
    }

    /** Protocol-ordered condition bits used by the checklist's plain-language explanation. */
    public static final int[] CONDITIONS = {
            FIRE_1, FIRE_2, FAILURE_1, FAILURE_2, FUEL_LOW, FUEL_EMPTY, HEAVY_FUEL,
            GEAR_FAILED, HYDRAULIC, PRESSURISATION, ICING, OVERSPEED, WINDSHEAR,
            EMERGENCY_DECLARED, FUEL_DUMPING, EMERGENCY_DESCENT
    };

    /** Short checklist wording shared by the screen, HUD and notice messages. */
    public static String actionLabel(int action) {
        return switch (action) {
            case EmergencyAction.EXTINGUISH_ENGINE -> "Extinguish engine fire";
            case EmergencyAction.RESTART_ENGINE -> "Restart failed engine";
            case EmergencyAction.ALTERNATE_GEAR -> "Alternate gear extension";
            case EmergencyAction.RESET_HYDRAULICS -> "Hydraulic backup";
            case EmergencyAction.EMERGENCY_DESCENT -> "Emergency descent";
            case EmergencyAction.TOGGLE_FUEL_DUMP -> "Fuel jettison";
            case EmergencyAction.DECLARE_MAYDAY -> "Declare MAYDAY";
            case EmergencyAction.EMERGENCY_AUTOLAND -> "Emergency autoland";
            case EmergencyAction.CANCEL_MAYDAY -> "Cancel MAYDAY";
            default -> "No action";
        };
    }

    public static String engineName(int index, boolean grouped) {
        return (grouped ? "ENGINE GROUP " : "ENGINE ") + (index + 1);
    }

    /** Human-readable label for one condition bit. */
    public static String conditionLabel(int condition, boolean grouped) {
        return switch (condition) {
            case FIRE_1 -> engineName(0, grouped) + " FIRE";
            case FIRE_2 -> engineName(1, grouped) + " FIRE";
            case FAILURE_1 -> engineName(0, grouped) + " FAILED";
            case FAILURE_2 -> engineName(1, grouped) + " FAILED";
            case FUEL_LOW -> "LOW FUEL";
            case FUEL_EMPTY -> "FUEL EMPTY";
            case HEAVY_FUEL -> "HEAVY LANDING WEIGHT";
            case GEAR_FAILED -> "GEAR FAILED";
            case HYDRAULIC -> "HYDRAULIC FAILURE";
            case PRESSURISATION -> "CABIN PRESSURISATION";
            case ICING -> "AIRFRAME ICE";
            case OVERSPEED -> "OVERSPEED";
            case WINDSHEAR -> "WINDSHEAR";
            case EMERGENCY_DECLARED -> "MAYDAY ACTIVE";
            case FUEL_DUMPING -> "FUEL JETTISON ACTIVE";
            case EMERGENCY_DESCENT -> "EMERGENCY DESCENT";
            default -> "UNKNOWN CONDITION";
        };
    }

    /** Plain-language explanation of what a condition means and how to react to it. */
    public static String conditionExplanation(int condition, boolean grouped) {
        return switch (condition) {
            case FIRE_1, FIRE_2 -> "Flames are burning in this nacelle. Discharge a bottle while the fire is still fresh; after the suppression window only a safe landing can secure it. The fire stays in this engine.";
            case FAILURE_1, FAILURE_2 -> "This engine is producing no thrust. Keep airspeed and control, then relight it when it is cool and you have fuel and airflow. The other engine is still available.";
            case FUEL_LOW -> "Fuel is below the low warning. Plan an early landing; do not wait for the tanks to run dry.";
            case FUEL_EMPTY -> "Fuel is gone and the engines will flame out. Fly the aircraft as a glider and aim for a runway.";
            case HEAVY_FUEL -> "You have a landing emergency but a heavy fuel load. Jettison down to the protected 20% reserve before an overweight landing if time allows.";
            case GEAR_FAILED -> "The landing gear will not extend normally. Use alternate extension, then avoid cycling the gear again.";
            case HYDRAULIC -> "Hydraulic pressure is lost, locking flaps and limiting gear control. Hydraulic backup restores enough authority to configure for landing.";
            case PRESSURISATION -> "Cabin altitude is rising because engine bleed air is unavailable. Descend to 10,000 ft and use oxygen procedures.";
            case ICING -> "Ice is building on the airframe. It raises stall speed and reduces lift. Turn anti-ice on, keep speed margin and avoid steep banks.";
            case OVERSPEED -> "Airspeed is above the airframe limit. Reduce throttle and level the nose; prolonged overspeed weakens the wing and damages the aircraft.";
            case WINDSHEAR -> "A sudden wind change is affecting lift and speed. Maintain pitch, add power if available and avoid aggressive control inputs.";
            case EMERGENCY_DECLARED -> "MAYDAY is active. ATC and nearby aircraft have your position and failure summary. Cancel it when the emergency is over.";
            case FUEL_DUMPING -> "Fuel is being jettisoned. It automatically stops at the 20% landing reserve.";
            case EMERGENCY_DESCENT -> "The autopilot is descending to 10,000 ft at a high rate. Monitor airspeed and terrain.";
            default -> "An abnormal condition is active. Use the checklist to find the recommended action.";
        };
    }

    /** Explains why a procedure is recommended, so the checklist teaches instead of guessing. */
    public static String actionExplanation(int action) {
        return switch (action) {
            case EmergencyAction.EXTINGUISH_ENGINE -> "Discharge one extinguisher bottle into the burning engine. It stops the fire but leaves that engine failed.";
            case EmergencyAction.RESTART_ENGINE -> "Attempt a windmilling relight. It needs fuel, airspeed, a cool engine and remaining start attempts.";
            case EmergencyAction.ALTERNATE_GEAR -> "Release the gear by alternate means. This gives you a down-and-locked gear for the landing.";
            case EmergencyAction.RESET_HYDRAULICS -> "Bring the backup hydraulic system online to restore flap and gear authority.";
            case EmergencyAction.EMERGENCY_DESCENT -> "Level at 10,000 ft with a high descent rate so the cabin is safe again.";
            case EmergencyAction.TOGGLE_FUEL_DUMP -> "Reduce landing weight now, while protecting a 20% fuel reserve.";
            case EmergencyAction.DECLARE_MAYDAY -> "Broadcast position and failures to ATC and nearby aircraft, putting them on alert.";
            case EmergencyAction.EMERGENCY_AUTOLAND -> "Hand the approach to the ILS autopilot while you monitor. Needs a valid ILS and a running engine.";
            case EmergencyAction.CANCEL_MAYDAY -> "Close the distress call once the aircraft is safe.";
            default -> "No immediate checklist action.";
        };
    }
}
