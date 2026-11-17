package dev.flightline.client;

import dev.flightline.flight.AircraftType;
import dev.flightline.flight.EmergencyAction;
import dev.flightline.flight.EmergencyStatus;
import dev.flightline.flight.FlightTuning;
import dev.flightline.network.EmergencyActionPayload;
import dev.flightline.network.EmergencyStatusPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Interactive in-flight checklist with one clear next action and grouped procedures. */
public final class EmergencyScreen extends Screen {
    private static final int PANEL_WIDTH = 860;
    private static final int PANEL_HEIGHT = 600;
    private static final int CARD_HEIGHT = 44;
    private static final int LINE_HEIGHT = 12;
    private static final int NORMAL_TEXT = 0xFFD8E4EA;
    private static final int MUTED_TEXT = 0xFF8FA2AA;
    private static final int WARNING_TEXT = 0xFFFF8A7A;
    private static final int AMBER_TEXT = 0xFFFFC44D;
    private static final int ACCENT_TEXT = 0xFF62D89B;
    private static final int CYAN_TEXT = 0xFF4EB8C8;
    private static final int PANEL_FILL = 0xF0121A22;
    private static final int SUBPANEL_FILL = 0xE30D1419;
    private static final int BORDER = 0xFF2A3D49;

    private static final int[][] PROCEDURE_SLOTS = {
            {EmergencyAction.EXTINGUISH_ENGINE, 0},
            {EmergencyAction.EXTINGUISH_ENGINE, 1},
            {EmergencyAction.RESTART_ENGINE, 0},
            {EmergencyAction.RESTART_ENGINE, 1},
            {EmergencyAction.ALTERNATE_GEAR, -1},
            {EmergencyAction.RESET_HYDRAULICS, -1},
            {EmergencyAction.EMERGENCY_DESCENT, -1},
            {EmergencyAction.TOGGLE_FUEL_DUMP, -1},
            {EmergencyAction.DECLARE_MAYDAY, -1},
            {EmergencyAction.EMERGENCY_AUTOLAND, -1}
    };

    private final List<ActionButton> actionButtons = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int infoLeft;
    private int infoRight;
    private int infoTop;
    private int infoBottom;
    private int procLeft;
    private int procRight;
    private int procTop;
    private int visibleCards = 1;
    private int cardScroll;
    private int activeTab;
    private List<ConditionCard> cards = List.of();
    private @Nullable Button runFixButton;

    public EmergencyScreen() {
        super(Component.literal("Emergency Checklist"));
    }

    @Override
    protected void init() {
        super.init();
        actionButtons.clear();
        panelWidth = Math.max(1, Math.min(PANEL_WIDTH, width - 24));
        panelHeight = Math.max(1, Math.min(PANEL_HEIGHT, height - 24));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int bodyTop = panelTop + 62;
        int bodyBottom = panelTop + panelHeight - 30;
        int gap = 10;
        int infoWidth = Math.max(120, (int) ((panelWidth - 28 - gap) * 0.46));

        infoLeft = panelLeft + 12;
        infoRight = infoLeft + infoWidth;
        infoTop = bodyTop + 22;
        infoBottom = bodyBottom - 4;
        visibleCards = Math.max(1, (infoBottom - infoTop) / CARD_HEIGHT);

        procLeft = infoRight + gap;
        procRight = panelLeft + panelWidth - 12;
        procTop = bodyTop;

        int tabY = procTop + 4;
        addRenderableWidget(Button.builder(Component.literal("NEXT ACTION"), b -> switchTab(0))
                .bounds(procLeft + 4, tabY, 130, 18).build());
        addRenderableWidget(Button.builder(Component.literal("PROCEDURES"), b -> switchTab(1))
                .bounds(procLeft + 140, tabY, 130, 18).build());
        addRenderableWidget(Button.builder(Component.literal("CLOSE"), b -> onClose())
                .bounds(panelLeft + panelWidth - 88, panelTop + 7, 76, 18).build());

        if (activeTab == 0) {
            runFixButton = Button.builder(Component.literal("RUN RECOMMENDED"), b -> executeRecommended())
                    .bounds(procLeft + 12, procTop + 132, procRight - procLeft - 24, 24)
                    .build();
            addRenderableWidget(runFixButton);
        } else {
            runFixButton = null;
            for (int i = 0; i < PROCEDURE_SLOTS.length; i++) {
                int y = slotY(i);
                addAction(PROCEDURE_SLOTS[i][0], PROCEDURE_SLOTS[i][1],
                        procLeft + 12, y, procRight - procLeft - 24, 18);
            }
        }
    }

    private int slotY(int index) {
        int extra = (index >= 4 ? 12 : 0) + (index >= 7 ? 12 : 0);
        return procTop + 40 + index * 32 + extra;
    }

    private void switchTab(int tab) {
        if (activeTab == tab) return;
        activeTab = tab;
        clearWidgets();
        init();
    }

    private void addAction(int action, int target, int x, int y, int width, int height) {
        Button button = Button.builder(Component.literal(""), clicked -> sendAction(clicked))
                .bounds(x, y, width, height)
                .build();
        actionButtons.add(new ActionButton(button, action, target, width));
        addRenderableWidget(button);
    }

    private void sendAction(Button button) {
        for (ActionButton entry : actionButtons) {
            if (entry.button == button) {
                ClientPlayNetworking.send(new EmergencyActionPayload(entry.action, entry.target));
                return;
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        EmergencyStatusPayload status = FlightHud.emergencyStatus();
        boolean grouped = FlightLineClient.getAircraftType() == AircraftType.GINA_787;

        for (ActionButton entry : actionButtons) {
            entry.action = entry.baseAction;
            if (entry.baseAction == EmergencyAction.DECLARE_MAYDAY
                    && status.hasCondition(EmergencyStatus.EMERGENCY_DECLARED)) {
                entry.action = EmergencyAction.CANCEL_MAYDAY;
            }
            entry.button.active = actionAvailable(status, entry.action, entry.target);
            entry.button.setMessage(Component.literal(fitText(
                    actionLabel(entry.action, entry.target, status), entry.width - 10)));
        }
        if (runFixButton != null) {
            boolean hasFix = status.recommendation().present();
            runFixButton.active = hasFix;
            String key = FlightLineClient.keyName(FlightLineClient.executeEmergency());
            runFixButton.setMessage(Component.literal(fitText(
                    hasFix ? key + " · RUN " + actionLabel(status.recommendedAction(),
                            status.recommendedTarget(), status) : "NO AUTOMATED FIX",
                    runFixButton.getWidth() - 10)));
        }

        graphics.fill(0, 0, width, height, 0x30000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_FILL);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, CYAN_TEXT);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 28, 0xFF1C2733);
        graphics.centeredText(font, "EMERGENCY CHECKLIST", panelLeft + panelWidth / 2,
                panelTop + 9, WARNING_TEXT);
        drawSeverityBanner(graphics, status, grouped);

        graphics.fill(infoLeft - 2, infoTop - 24, infoRight + 2, infoBottom, SUBPANEL_FILL);
        graphics.outline(infoLeft - 2, infoTop - 24, infoRight - infoLeft + 4,
                infoBottom - infoTop + 24, BORDER);
        graphics.centeredText(font, "ACTIVE CONDITIONS", infoLeft + (infoRight - infoLeft) / 2,
                infoTop - 19, CYAN_TEXT);

        graphics.fill(procLeft, procTop, procRight, panelTop + panelHeight - 30, SUBPANEL_FILL);
        graphics.outline(procLeft, procTop, procRight - procLeft,
                panelTop + panelHeight - 30 - procTop, BORDER);

        cards = buildCards(status, grouped);
        drawConditionCards(graphics);
        if (activeTab == 0) {
            drawNextAction(graphics, status, grouped);
        } else {
            drawProcedures(graphics, status, grouped);
        }

        String checklistKey = FlightLineClient.keyName(FlightLineClient.emergencyChecklist());
        String fixKey = FlightLineClient.keyName(FlightLineClient.executeEmergency());
        graphics.centeredText(font, fitText(
                checklistKey + " closes · " + fixKey + " runs the fix · scroll conditions · every action is server-checked",
                panelWidth - 20), panelLeft + panelWidth / 2, panelTop + panelHeight - 20, MUTED_TEXT);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSeverityBanner(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                    boolean grouped) {
        int top = panelTop + 32;
        int bottom = panelTop + 58;
        boolean critical = status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2
                | EmergencyStatus.FAILURE_1 | EmergencyStatus.FAILURE_2 | EmergencyStatus.FUEL_EMPTY);
        boolean caution = status.active();
        int color = critical ? WARNING_TEXT : caution ? AMBER_TEXT : ACCENT_TEXT;
        int fill = critical ? 0xFF3A1414 : caution ? 0xFF332A13 : 0xFF12301F;

        graphics.fill(panelLeft + 12, top, panelLeft + panelWidth - 12, bottom, fill);
        graphics.outline(panelLeft + 12, top, panelWidth - 24, bottom - top, color);
        String severity = critical ? "CRITICAL" : caution ? "CAUTION" : "ALL SYSTEMS NORMAL";
        graphics.text(font, severity, panelLeft + 20, top + 6, color, true);
        String summary = critical ? "Landing emergency · follow the next action"
                : caution ? "Abnormal condition active · follow the next action"
                : grouped ? "GINA 787 · 6 engines in 2 groups"
                : "KASA A380 · 2 independent engines";
        graphics.text(font, fitText(summary, panelWidth - 180),
                panelLeft + 110, top + 6, NORMAL_TEXT, true);

        String resources = "BOTTLES " + status.extinguisherBottles()
                + "  ·  RELIGHT E1 " + status.restartAttempts1() + "/3"
                + "  ·  E2 " + status.restartAttempts2() + "/3";
        graphics.text(font, fitText(resources, 250),
                panelLeft + panelWidth - font.width(resources) - 20, top + 6, MUTED_TEXT, true);
    }

    private void drawConditionCards(GuiGraphicsExtractor graphics) {
        if (cards.isEmpty()) {
            int y = infoTop + 12;
            graphics.text(font, "No active failures.", infoLeft + 8, y, ACCENT_TEXT, true);
            graphics.text(font, "Fuel " + FlightHud.fuelPercent() + "% · autopilot "
                    + (FlightHud.isAutopilotEngaged() ? "ENGAGED" : "STANDBY"),
                    infoLeft + 8, y + 18, NORMAL_TEXT, true);
            graphics.text(font, "Use PROCEDURES to rehearse a checklist, or an", infoLeft + 8,
                    y + 40, MUTED_TEXT, true);
            graphics.text(font, "operator can inject a debug emergency with", infoLeft + 8,
                    y + 54, MUTED_TEXT, true);
            graphics.text(font, "/flightline emergency <scenario>.", infoLeft + 8,
                    y + 68, CYAN_TEXT, true);
            return;
        }

        int maxScroll = Math.max(0, cards.size() - visibleCards);
        cardScroll = Math.max(0, Math.min(maxScroll, cardScroll));
        int end = Math.min(cards.size(), cardScroll + visibleCards);
        for (int i = cardScroll; i < end; i++) {
            ConditionCard card = cards.get(i);
            int y = infoTop + (i - cardScroll) * CARD_HEIGHT;
            int bottom = y + CARD_HEIGHT - 3;
            graphics.fill(infoLeft, y, infoRight, bottom, 0xE3162028);
            graphics.outline(infoLeft, y, infoRight - infoLeft, bottom - y, card.color());
            graphics.fill(infoLeft, y, infoLeft + 3, bottom, card.color());
            graphics.text(font, fitText(card.title(), infoRight - infoLeft - 14),
                    infoLeft + 8, y + 3, card.color(), true);

            List<String> detail = wrap(card.detail(), infoRight - infoLeft - 16);
            for (int line = 0; line < Math.min(2, detail.size()); line++) {
                graphics.text(font, detail.get(line), infoLeft + 8, y + 16 + line * LINE_HEIGHT,
                        NORMAL_TEXT, true);
            }
            if (card.barFraction() >= 0.0f) {
                int barLeft = infoLeft + 8;
                int barRight = infoRight - 8;
                int barTop = y + CARD_HEIGHT - 10;
                graphics.fill(barLeft, barTop, barRight, barTop + 5, 0xFF101A20);
                int fillWidth = Math.round((barRight - barLeft) * card.barFraction());
                graphics.fill(barLeft, barTop, barLeft + fillWidth, barTop + 5, card.color());
                int labelWidth = font.width(card.barLabel());
                graphics.text(font, card.barLabel(), barRight - labelWidth, barTop - 1,
                        card.color(), true);
            }
        }
        if (maxScroll > 0) {
            int trackHeight = infoBottom - infoTop;
            int thumbHeight = Math.max(14, trackHeight * visibleCards / cards.size());
            int thumbTop = infoTop + (int) (cardScroll / (float) maxScroll * (trackHeight - thumbHeight));
            graphics.fill(infoRight - 6, infoTop, infoRight - 3, infoBottom, 0xFF1F2A33);
            graphics.fill(infoRight - 6, thumbTop, infoRight - 3, thumbTop + thumbHeight, CYAN_TEXT);
        }
    }

    private void drawNextAction(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                boolean grouped) {
        int top = procTop + 28;
        int bottom = procTop + 124;
        var recommendation = status.recommendation();
        int color = recommendation.present() ? ACCENT_TEXT : MUTED_TEXT;
        graphics.fill(procLeft + 10, top, procRight - 10, bottom, 0xFF17222B);
        graphics.outline(procLeft + 10, top, procRight - procLeft - 20, bottom - top, color);

        if (!recommendation.present()) {
            graphics.text(font, "NO AUTOMATED FIX", procLeft + 18, top + 8, MUTED_TEXT, true);
            graphics.text(font, "Hold the aircraft, configure manually and land as",
                    procLeft + 18, top + 26, NORMAL_TEXT, true);
            graphics.text(font, "soon as practical. Procedures below remain available.",
                    procLeft + 18, top + 42, NORMAL_TEXT, true);
        } else {
            graphics.text(font, "RECOMMENDED NEXT ACTION", procLeft + 18, top + 8, CYAN_TEXT, true);
            String label = actionLabel(recommendation.action(), recommendation.target(), status);
            graphics.text(font, fitText(label, procRight - procLeft - 36),
                    procLeft + 18, top + 22, color, true);
            List<String> why = wrap(EmergencyStatus.actionExplanation(recommendation.action()),
                    procRight - procLeft - 36);
            for (int i = 0; i < Math.min(2, why.size()); i++) {
                graphics.text(font, why.get(i), procLeft + 18, top + 40 + i * LINE_HEIGHT,
                        NORMAL_TEXT, true);
            }
            if (EmergencyAction.isEngineAction(recommendation.action())) {
                graphics.text(font, "Affects " + EmergencyStatus.engineName(recommendation.target(), grouped)
                                + " only · the other engine keeps running.",
                        procLeft + 18, top + 66, MUTED_TEXT, true);
            }
        }

        int progressTop = procTop + 170;
        graphics.text(font, "CHECKPOINT STATUS", procLeft + 14, progressTop, CYAN_TEXT, true);
        String[][] checkpoints = {
                {"Fires extinguished", status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2) ? " " : "X"},
                {"Gear usable", status.hasCondition(EmergencyStatus.GEAR_FAILED) ? " " : "X"},
                {"Hydraulics restored", status.hasCondition(EmergencyStatus.HYDRAULIC) ? " " : "X"},
                {"Cabin descent complete", status.hasCondition(EmergencyStatus.PRESSURISATION) ? " " : "X"},
                {"MAYDAY declared", status.hasCondition(EmergencyStatus.EMERGENCY_DECLARED) ? "X" : " "},
                {"Autoland armed", FlightHud.autopilotGuidance().autolandArmed() ? "X" : " "}
        };
        for (int i = 0; i < checkpoints.length; i++) {
            int y = progressTop + 18 + i * 16;
            boolean done = "X".equals(checkpoints[i][1]);
            graphics.fill(procLeft + 16, y, procLeft + 26, y + 10, done ? 0xFF173423 : 0xFF1B2227);
            graphics.outline(procLeft + 16, y, 10, 10, done ? ACCENT_TEXT : BORDER);
            graphics.text(font, "[" + checkpoints[i][1] + "]", procLeft + 16, y, done ? ACCENT_TEXT : MUTED_TEXT, true);
            graphics.text(font, checkpoints[i][0], procLeft + 36, y, done ? ACCENT_TEXT : NORMAL_TEXT, true);
        }
    }

    private void drawProcedures(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                boolean grouped) {
        graphics.text(font, "Available procedures are bright. Locked procedures show why.",
                procLeft + 14, procTop + 26, MUTED_TEXT, true);
        String[] headers = {"ENGINE", "AIRFRAME", "FUEL / ATC"};
        int[] headerSlots = {0, 4, 7};
        for (int i = 0; i < headers.length; i++) {
            graphics.text(font, headers[i], procLeft + 14, slotY(headerSlots[i]) - 12, CYAN_TEXT, true);
        }
        for (int i = 0; i < PROCEDURE_SLOTS.length; i++) {
            int action = PROCEDURE_SLOTS[i][0];
            if (action == EmergencyAction.DECLARE_MAYDAY
                    && status.hasCondition(EmergencyStatus.EMERGENCY_DECLARED)) {
                action = EmergencyAction.CANCEL_MAYDAY;
            }
            int target = PROCEDURE_SLOTS[i][1];
            boolean available = actionAvailable(status, action, target);
            graphics.text(font, fitText(requirementHint(action, target, status, grouped),
                            procRight - procLeft - 28),
                    procLeft + 16, slotY(i) + 19, available ? MUTED_TEXT : AMBER_TEXT, true);
        }
    }

    private static List<ConditionCard> buildCards(EmergencyStatusPayload status, boolean grouped) {
        List<ConditionCard> cards = new ArrayList<>();
        float suppressionWindow = FlightTuning.ENGINE_FIRE_SUPPRESSION_WINDOW_TICKS;
        for (int condition : EmergencyStatus.CONDITIONS) {
            if (!status.hasCondition(condition)) continue;
            int color = switch (condition) {
                case EmergencyStatus.FIRE_1, EmergencyStatus.FIRE_2, EmergencyStatus.FAILURE_1,
                        EmergencyStatus.FAILURE_2, EmergencyStatus.FUEL_EMPTY -> WARNING_TEXT;
                case EmergencyStatus.EMERGENCY_DECLARED, EmergencyStatus.FUEL_DUMPING,
                        EmergencyStatus.EMERGENCY_DESCENT -> CYAN_TEXT;
                default -> AMBER_TEXT;
            };
            float bar = -1.0f;
            String barLabel = "";
            if (condition == EmergencyStatus.FIRE_1 || condition == EmergencyStatus.FIRE_2) {
                int ticks = condition == EmergencyStatus.FIRE_1 ? status.fireTicks1() : status.fireTicks2();
                int seconds = Math.max(0, (int) ((suppressionWindow - ticks) / 20.0f));
                bar = Math.max(0.0f, Math.min(1.0f, (suppressionWindow - ticks) / suppressionWindow));
                barLabel = seconds > 0 ? "BOTTLE WINDOW " + seconds + "S" : "WINDOW CLOSED · LAND";
            }
            cards.add(new ConditionCard(EmergencyStatus.conditionLabel(condition, grouped),
                    shorten(EmergencyStatus.conditionExplanation(condition, grouped), 112),
                    color, bar, barLabel));
        }
        return cards;
    }

    private static String shorten(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        String cut = text.substring(0, maxLength);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > maxLength / 2) cut = cut.substring(0, lastSpace);
        return cut + "…";
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawWord : text.split("\\s+")) {
            if (rawWord.isEmpty()) continue;
            String word = rawWord;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            while (font.width(word) > maxWidth && word.length() > 1) {
                int cut = 1;
                while (cut < word.length() && font.width(word.substring(0, cut + 1)) <= maxWidth) cut++;
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private String fitText(String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    private static boolean actionAvailable(EmergencyStatusPayload status, int action, int target) {
        return switch (action) {
            case EmergencyAction.EXTINGUISH_ENGINE -> status.can(target == 0
                    ? EmergencyStatus.CAN_EXTINGUISH_1 : EmergencyStatus.CAN_EXTINGUISH_2);
            case EmergencyAction.RESTART_ENGINE -> status.can(target == 0
                    ? EmergencyStatus.CAN_RESTART_1 : EmergencyStatus.CAN_RESTART_2);
            case EmergencyAction.ALTERNATE_GEAR -> status.can(EmergencyStatus.CAN_ALTERNATE_GEAR);
            case EmergencyAction.RESET_HYDRAULICS -> status.can(EmergencyStatus.CAN_RESET_HYDRAULICS);
            case EmergencyAction.EMERGENCY_DESCENT -> status.can(EmergencyStatus.CAN_EMERGENCY_DESCENT);
            case EmergencyAction.TOGGLE_FUEL_DUMP -> status.can(EmergencyStatus.CAN_DUMP_FUEL
                    | EmergencyStatus.CAN_STOP_DUMP);
            case EmergencyAction.DECLARE_MAYDAY -> status.can(EmergencyStatus.CAN_MAYDAY);
            case EmergencyAction.CANCEL_MAYDAY -> status.can(EmergencyStatus.CAN_CANCEL_MAYDAY);
            case EmergencyAction.EMERGENCY_AUTOLAND -> status.can(EmergencyStatus.CAN_AUTOLAND);
            default -> false;
        };
    }

    private static String actionLabel(int action, int target, EmergencyStatusPayload status) {
        boolean grouped = FlightLineClient.getAircraftType() == AircraftType.GINA_787;
        return switch (action) {
            case EmergencyAction.EXTINGUISH_ENGINE -> EmergencyStatus.engineName(target, grouped) + " EXTINGUISH";
            case EmergencyAction.RESTART_ENGINE -> EmergencyStatus.engineName(target, grouped) + " RELIGHT";
            case EmergencyAction.ALTERNATE_GEAR -> "ALT GEAR";
            case EmergencyAction.RESET_HYDRAULICS -> "HYD BACKUP";
            case EmergencyAction.EMERGENCY_DESCENT -> "EMER DESCENT";
            case EmergencyAction.TOGGLE_FUEL_DUMP -> status.hasCondition(EmergencyStatus.FUEL_DUMPING)
                    ? "STOP JETTISON" : "FUEL JETTISON";
            case EmergencyAction.DECLARE_MAYDAY -> "DECLARE MAYDAY";
            case EmergencyAction.CANCEL_MAYDAY -> "CANCEL MAYDAY";
            case EmergencyAction.EMERGENCY_AUTOLAND -> "AUTOLAND";
            default -> "UNAVAILABLE";
        };
    }

    private static String requirementHint(int action, int target, EmergencyStatusPayload status,
                                          boolean grouped) {
        boolean available = actionAvailable(status, action, target);
        if (available) {
            return switch (action) {
                case EmergencyAction.EXTINGUISH_ENGINE -> "Bottle ready · fire is still in the suppression window";
                case EmergencyAction.RESTART_ENGINE -> "Cool engine, fuel and airspeed are available";
                case EmergencyAction.ALTERNATE_GEAR -> "Gear can be forced down and locked";
                case EmergencyAction.RESET_HYDRAULICS -> "Backup hydraulic power is available";
                case EmergencyAction.EMERGENCY_DESCENT -> "Descent to 10,000 ft can be commanded";
                case EmergencyAction.TOGGLE_FUEL_DUMP -> status.hasCondition(EmergencyStatus.FUEL_DUMPING)
                        ? "Dump active · stops at 20% reserve" : "Heavy landing weight · 20% reserve protected";
                case EmergencyAction.DECLARE_MAYDAY -> "Broadcasts position and failures to ATC";
                case EmergencyAction.CANCEL_MAYDAY -> "Closes the distress call";
                case EmergencyAction.EMERGENCY_AUTOLAND -> "ILS, gear, engine and speed margin are valid";
                default -> "Available";
            };
        }
        return switch (action) {
            case EmergencyAction.EXTINGUISH_ENGINE -> "Needs an active fire, a bottle and a fresh suppression window";
            case EmergencyAction.RESTART_ENGINE -> "Needs fuel, airspeed, a cool engine and attempts left";
            case EmergencyAction.ALTERNATE_GEAR -> "Only with a gear failure";
            case EmergencyAction.RESET_HYDRAULICS -> "Only with a hydraulic failure";
            case EmergencyAction.EMERGENCY_DESCENT -> "Needs a cabin emergency while airborne";
            case EmergencyAction.TOGGLE_FUEL_DUMP -> "Needs a landing emergency and at least 60% fuel";
            case EmergencyAction.DECLARE_MAYDAY -> "Nothing to declare yet";
            case EmergencyAction.CANCEL_MAYDAY -> "No MAYDAY is active";
            case EmergencyAction.EMERGENCY_AUTOLAND -> "Needs valid ILS, gear, engine and airspeed margin";
            default -> "Unavailable";
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= infoLeft - 2 && mouseX <= infoRight + 2 && mouseY >= infoTop && mouseY <= infoBottom) {
            int maxScroll = Math.max(0, cards.size() - visibleCards);
            cardScroll = Math.max(0, Math.min(maxScroll, cardScroll + (deltaY < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (FlightLineClient.executeEmergency().matches(event)) {
            executeRecommended();
            return true;
        }
        if (FlightLineClient.emergencyChecklist().matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void executeRecommended() {
        EmergencyStatusPayload status = FlightHud.emergencyStatus();
        if (!status.recommendation().present()) return;
        ClientPlayNetworking.send(new EmergencyActionPayload(
                status.recommendedAction(), status.recommendedTarget()));
    }

    private record ConditionCard(String title, String detail, int color, float barFraction, String barLabel) {
    }

    private static final class ActionButton {
        private final Button button;
        private final int baseAction;
        private final int target;
        private final int width;
        private int action;

        private ActionButton(Button button, int action, int target, int width) {
            this.button = button;
            this.baseAction = action;
            this.action = action;
            this.target = target;
            this.width = width;
        }
    }
}
