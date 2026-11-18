package dev.flightline.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared progress state for the interactive lesson and its compact in-flight tracker. */
final class FlightTutorial {
    enum Stage {
        TAKEOFF("TAKEOFF", "Build power, rotate, and clean up the aircraft."),
        CRUISE("CRUISE", "Climb, stabilize, and trim for efficient level flight."),
        AUTOPILOT("AUTOPILOT", "Hand the aircraft to the flight director and verify it."),
        LANDING("LANDING", "Configure for approach and bring the aircraft home.");

        private final String title;
        private final String brief;

        Stage(String title, String brief) {
            this.title = title;
            this.brief = brief;
        }

        String title() {
            return title;
        }

        String brief() {
            return brief;
        }
    }

    record Objective(String label, String detail, boolean done) {
    }

    private static final Stage[] STAGES = Stage.values();
    private static final boolean[] COMPLETED = new boolean[STAGES.length];
    private static int stageIndex;
    private static boolean active;

    private FlightTutorial() {
    }

    static void setActive(boolean value) {
        active = value;
    }

    static boolean isActive() {
        return active;
    }

    static Stage stage() {
        return STAGES[stageIndex];
    }

    static int stageIndex() {
        return stageIndex;
    }

    static void setStage(int index) {
        stageIndex = Math.max(0, Math.min(STAGES.length - 1, index));
    }

    static void reset() {
        for (int i = 0; i < COMPLETED.length; i++) COMPLETED[i] = false;
        stageIndex = 0;
        active = false;
    }

    static boolean isCompleted(int index) {
        return index >= 0 && index < COMPLETED.length && COMPLETED[index];
    }

    static boolean allCompleted() {
        for (boolean value : COMPLETED) {
            if (!value) return false;
        }
        return true;
    }

    /** Called once per client tick so the lesson advances while the player is flying. */
    static void tick() {
        if (!active || !FlightLineClient.isFlightEnabled()) return;
        while (stageIndex < STAGES.length - 1 && objectivesFor(STAGES[stageIndex]).stream().allMatch(Objective::done)) {
            COMPLETED[stageIndex] = true;
            stageIndex++;
        }
        if (stageIndex == STAGES.length - 1
                && objectivesFor(Stage.LANDING).stream().allMatch(Objective::done)) {
            COMPLETED[stageIndex] = true;
        }
    }

    static List<Objective> objectives() {
        return objectivesFor(STAGES[stageIndex]);
    }

    private static List<Objective> objectivesFor(Stage stage) {
        int speed = FlightHud.currentSpeedKnots();
        int altitude = FlightHud.currentAltitudeFeet();
        boolean airborne = FlightLineClient.isAirborne();
        return switch (stage) {
            case TAKEOFF -> List.of(
                    new Objective("START ENGINES", "Press E at the aircraft to spool both engines.",
                            FlightHud.anyEngineRunning()),
                    new Objective("ADVANCE THROTTLE", "Hold W until the airspeed needle climbs.",
                            FlightHud.throttle() > 0.35f || speed > 35),
                    new Objective("ROTATE AND LIFT OFF", "Ease the nose up near rotate speed.",
                            airborne || FlightHud.phase() == dev.flightline.flight.FlightPhase.ROTATE),
                    new Objective("RAISE LANDING GEAR", "Press G after a positive climb.",
                            airborne && !FlightHud.gearDown()));
            case CRUISE -> List.of(
                    new Objective("CLIMB TO 1,000 FT", "Use gentle nose-up input; avoid a stall.",
                            altitude >= 1000),
                    new Objective("SETTLE AT 120-300 KT", "Adjust throttle with W / S.",
                            speed >= 120 && speed <= 300),
                    new Objective("LEVEL THE WINGS", "Keep bank under 15 degrees.",
                            Math.abs(FlightHud.currentBank()) < 15.0f),
                    new Objective("HOLD A STABLE PATH", "Keep a small vertical speed, not a dive.",
                            Math.abs(FlightHud.currentVsFpm()) < 1200));
            case AUTOPILOT -> List.of(
                    new Objective("OPEN THE FLIGHT DECK", "Press H or use SYNC AP to configure targets.",
                            FlightHud.isAutopilotEngaged()),
                    new Objective("SET A TARGET ALTITUDE", "Choose a safe altitude above 1,000 ft.",
                            FlightHud.autopilotGuidance().targetAltitudeFeet() >= 1000),
                    new Objective("VERIFY ALTITUDE CAPTURE", "Watch the PFD altitude tape settle.",
                            Math.abs(altitude - FlightHud.autopilotGuidance().targetAltitudeFeet()) < 400),
                    new Objective("VERIFY SPEED CONTROL", "The autothrottle should hold the selected IAS.",
                            Math.abs(speed - FlightHud.autopilotGuidance().targetAirspeedKnots()) < 30));
            case LANDING -> List.of(
                    new Objective("LOWER LANDING GEAR", "Press G before entering the approach.",
                            FlightHud.gearDown()),
                    new Objective("EXTEND FLAPS", "Use C at least twice for approach configuration.",
                            FlightHud.flapDetent() >= 2),
                    new Objective("SET AN ILS RUNWAY", "Use R to pick a threshold and heading, then SET ILS.",
                            FlightHud.isIlsActive()),
                    new Objective("ARM AUTOLAND OR FLY MANUALLY", "H → NAV/ILS → ARM, or hand-fly below 1,500 ft.",
                            FlightHud.autopilotGuidance().autolandArmed()
                                    || (airborne && altitude < 1500)),
                    new Objective("TOUCH DOWN", "Complete a landing roll without crashing.",
                            FlightHud.phase() == dev.flightline.flight.FlightPhase.LANDING_ROLL));
        };
    }

    /** Small always-on lesson card rendered in the world HUD while the lesson is active. */
    static void drawTracker(GuiGraphicsExtractor g, Minecraft mc, int screenWidth, int screenHeight) {
        if (!active || screenWidth < 300 || screenHeight < 240) return;
        Font font = mc.font;
        int width = 190;
        int height = 50;
        int x = 6;
        int y = 148;
        if (y + height > screenHeight - 76) y = Math.max(96, screenHeight - height - 76);
        g.fill(x, y, x + width, y + height, 0xD8091017);
        g.outline(x, y, width, height, 0xFF4EB8C8);
        g.text(font, allCompleted() ? "TUTORIAL COMPLETE" : "LESSON · " + stage().title(),
                x + 5, y + 3, allCompleted() ? 0xFF62D89B : 0xFF72E6F5, true);
        List<Objective> objectives = objectives();
        int done = 0;
        for (Objective objective : objectives) if (objective.done()) done++;
        String next = objectives.stream().filter(objective -> !objective.done())
                .map(Objective::label).findFirst().orElse("ALL OBJECTIVES COMPLETE");
        g.text(font, fit(font, next, width - 10), x + 5, y + 15, 0xFFFFFFFF, true);
        int barX = x + 5;
        int barY = y + height - 9;
        g.fill(barX, barY, barX + width - 10, barY + 4, 0xFF26343D);
        int fill = Math.round((width - 10) * (objectives.isEmpty() ? 1.0f : done / (float) objectives.size()));
        g.fill(barX, barY, barX + fill, barY + 4, 0xFF62D89B);
        g.text(font, "L · FULL LESSON", x + 5, y + 27, 0xFF8198A3, false);
    }

    private static String fit(Font font, String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    static String progressLabel() {
        List<Objective> objectives = objectives();
        long done = objectives.stream().filter(Objective::done).count();
        return String.format(Locale.ROOT, "%d/%d", done, objectives.size());
    }

    static List<String> objectiveLines() {
        List<String> lines = new ArrayList<>();
        for (Objective objective : objectives()) {
            lines.add((objective.done() ? "[x] " : "[ ] ") + objective.label() + " · " + objective.detail());
        }
        return lines;
    }
}
