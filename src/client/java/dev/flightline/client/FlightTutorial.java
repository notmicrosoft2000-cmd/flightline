package dev.flightline.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import org.jspecify.annotations.Nullable;

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

    /** The shortcut code tells the lesson screen where a pending objective can be handled. */
    record Objective(String label, String detail, boolean done, String shortcut) {
        Objective(String label, String detail, boolean done) {
            this(label, detail, done, "");
        }
    }

    private static final Stage[] STAGES = Stage.values();
    private static final boolean[] COMPLETED = new boolean[STAGES.length];
    private static int stageIndex;
    private static boolean active;
    private static boolean completedHidden;

    private FlightTutorial() {
    }

    static void setActive(boolean value) {
        active = value;
        if (value) completedHidden = false;
    }

    static boolean isActive() {
        return active;
    }

    /** True while the compact tracker should be shown, including the auto-hide at completion. */
    static boolean trackerVisible() {
        return active && !(completedHidden && allCompleted());
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
        completedHidden = false;
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
        if (allCompleted()) completedHidden = true;
    }

    static List<Objective> objectives() {
        return objectivesFor(STAGES[stageIndex]);
    }

    /** First unfinished objective in the stage currently shown to the player. */
    static @Nullable Objective firstPending() {
        for (Objective objective : objectives()) {
            if (!objective.done()) return objective;
        }
        return null;
    }

    private static List<Objective> objectivesFor(Stage stage) {
        int speed = FlightHud.currentSpeedKnots();
        int altitude = FlightHud.currentAltitudeFeet();
        boolean airborne = FlightLineClient.isAirborne();
        return switch (stage) {
            case TAKEOFF -> List.of(
                    new Objective("START ENGINES", "Press E at the aircraft to spool both engines.",
                            FlightHud.anyEngineRunning(), "ENGINES"),
                    new Objective("ADVANCE THROTTLE", "Hold W until the airspeed needle climbs.",
                            FlightHud.throttle() > 0.35f || speed > 35, "FLY"),
                    new Objective("ROTATE AND LIFT OFF", "Ease the nose up near rotate speed.",
                            airborne || FlightHud.phase() == dev.flightline.flight.FlightPhase.ROTATE, "FLY"),
                    new Objective("RAISE LANDING GEAR", "Press G after a positive climb.",
                            airborne && !FlightHud.gearDown(), "GEAR"));
            case CRUISE -> List.of(
                    new Objective("CLIMB TO 1,000 FT", "Use gentle nose-up input; avoid a stall.",
                            altitude >= 1000, "FLY"),
                    new Objective("SETTLE AT 120-300 KT", "Adjust throttle with W / S.",
                            speed >= 120 && speed <= 300, "FLY"),
                    new Objective("LEVEL THE WINGS", "Keep bank under 15 degrees.",
                            Math.abs(FlightHud.currentBank()) < 15.0f, "FLY"),
                    new Objective("HOLD A STABLE PATH", "Keep a small vertical speed, not a dive.",
                            Math.abs(FlightHud.currentVsFpm()) < 1200, "FLY"));
            case AUTOPILOT -> List.of(
                    new Objective("OPEN THE FLIGHT DECK", "Press H, then set a target on the MCP.",
                            FlightHud.isAutopilotEngaged(), "MCP"),
                    new Objective("SET A TARGET ALTITUDE", "Choose a safe altitude above 1,000 ft.",
                            FlightHud.autopilotGuidance().targetAltitudeFeet() >= 1000, "MCP"),
                    new Objective("VERIFY ALTITUDE CAPTURE", "Watch the PFD altitude tape settle.",
                            Math.abs(altitude - FlightHud.autopilotGuidance().targetAltitudeFeet()) < 400, "FLY"),
                    new Objective("VERIFY SPEED CONTROL", "The autothrottle should hold the selected IAS.",
                            Math.abs(speed - FlightHud.autopilotGuidance().targetAirspeedKnots()) < 30, "FLY"));
            case LANDING -> List.of(
                    new Objective("LOWER LANDING GEAR", "Press G before entering the approach.",
                            FlightHud.gearDown(), "GEAR"),
                    new Objective("EXTEND FLAPS", "Use C at least twice for approach configuration.",
                            FlightHud.flapDetent() >= 2, "FLAPS"),
                    new Objective("SET AN ILS RUNWAY", "Use R to pick a threshold and heading, then SET ILS.",
                            FlightHud.isIlsActive(), "ILS_SETUP"),
                    new Objective("ARM AUTOLAND OR FLY MANUALLY", "H → NAV/ILS → ARM, or hand-fly below 1,500 ft.",
                            FlightHud.autopilotGuidance().autolandArmed()
                                    || (airborne && altitude < 1500), "AUTOLAND"),
                    new Objective("TOUCH DOWN", "Complete a landing roll without crashing.",
                            FlightHud.phase() == dev.flightline.flight.FlightPhase.LANDING_ROLL, "FLY"));
        };
    }

    /** Compact one-line lesson nudge; it disappears once every objective is complete. */
    static void drawTracker(GuiGraphicsExtractor g, Minecraft mc, int screenWidth, int screenHeight) {
        if (!trackerVisible() || screenWidth < 300 || screenHeight < 240) return;
        Font font = mc.font;
        int width = 164;
        int height = 26;
        int x = 6;
        int y = 148;
        if (y + height > screenHeight - 64) y = Math.max(84, screenHeight - height - 64);
        int accent = FlightLineTheme.CYAN;
        FlightLineTheme.panel(g, x, y, width, height, FlightLineTheme.CARD, FlightLineTheme.LINE);
        g.fill(x + 4, y + 4, x + 7, y + height - 4, accent);
        List<Objective> objectives = objectives();
        int done = 0;
        for (Objective objective : objectives) if (objective.done()) done++;
        String next = objectives.stream().filter(objective -> !objective.done())
                .map(Objective::label).findFirst().orElse("LESSON COMPLETE");
        g.text(font, fit(font, next, width - 14), x + 10, y + 7, FlightLineTheme.PAPER, true);
        float progress = objectives.isEmpty() ? 1.0f : done / (float) objectives.size();
        FlightLineTheme.bar(g, x + 10, y + height - 8, width - 14, 3, progress,
                FlightLineTheme.GREEN, FlightLineTheme.COAL);
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
