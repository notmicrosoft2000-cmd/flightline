package dev.flightline.client;

import dev.flightline.flight.AircraftType;
import dev.flightline.flight.EmergencyAction;
import dev.flightline.flight.EmergencyStatus;
import dev.flightline.flight.FlightTuning;
import dev.flightline.network.EmergencyActionPayload;
import dev.flightline.network.EmergencyStatusPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
    private static final int NORMAL_TEXT = FlightLineTheme.TEXT_DIM;
    private static final int MUTED_TEXT = FlightLineTheme.MUTED;
    private static final int WARNING_TEXT = FlightLineTheme.RED;
    private static final int AMBER_TEXT = FlightLineTheme.AMBER;
    private static final int ACCENT_TEXT = FlightLineTheme.GREEN;
    private static final int CYAN_TEXT = FlightLineTheme.CYAN;
    private static final int BORDER = FlightLineTheme.LINE;

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
    private final ScreenScaler scaler = new ScreenScaler(PANEL_WIDTH, PANEL_HEIGHT);
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
    private @Nullable AbstractWidget runFixButton;

    public EmergencyScreen() {
        super(Component.literal("Emergency Checklist"));
    }

    @Override
    protected void init() {
        super.init();
        actionButtons.clear();
        scaler.update(width, height);
        panelWidth = PANEL_WIDTH;
        panelHeight = PANEL_HEIGHT;
        panelLeft = 8;
        panelTop = 8;

        infoLeft = x(12);
        infoRight = x(396);
        infoTop = y(84);
        infoBottom = y(570);
        visibleCards = Math.max(1, (infoBottom - infoTop) / Math.max(1, h(CARD_HEIGHT)));

        procLeft = x(406);
        procRight = x(848);
        procTop = y(62);

        int tabY = y(66);
        int tabWidth = w(130);
        addRenderableWidget(FlightLineTheme.button(fitText("NEXT ACTION", tabWidth - 8),
                x(410), tabY, tabWidth, h(18), () -> switchTab(0)));
        addRenderableWidget(FlightLineTheme.button(fitText("PROCEDURES", tabWidth - 8),
                x(546), tabY, tabWidth, h(18), () -> switchTab(1)));
        addRenderableWidget(FlightLineTheme.button("CLOSE",
                x(772), y(7), w(76), h(18), this::onClose));

        if (activeTab == 0) {
            runFixButton = FlightLineTheme.button("EXECUTE NEXT ACTION",
                    x(418), y(194), w(418), h(24), this::executeRecommended);
            addRenderableWidget(runFixButton);
        } else {
            runFixButton = null;
            for (int i = 0; i < PROCEDURE_SLOTS.length; i++) {
                addAction(PROCEDURE_SLOTS[i][0], PROCEDURE_SLOTS[i][1],
                        x(418), slotY(i), w(418), h(18));
            }
        }
    }

    private int slotY(int index) {
        int extra = (index >= 4 ? 12 : 0) + (index >= 7 ? 12 : 0);
        return y(116 + index * 32 + extra);
    }

    private void switchTab(int tab) {
        if (activeTab == tab) return;
        activeTab = tab;
        clearWidgets();
        init();
    }

    private void addAction(int action, int target, int x, int y, int width, int height) {
        var holder = new AbstractWidget[1];
        holder[0] = FlightLineTheme.button("", x, y, width, height,
                () -> sendAction(holder[0]));
        actionButtons.add(new ActionButton(holder[0], action, target, width));
        addRenderableWidget(holder[0]);
    }

    private void sendAction(AbstractWidget button) {
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
                    hasFix ? key + " · " + actionLabel(status.recommendedAction(),
                            status.recommendedTarget(), status) : "MANUAL CHECKLIST",
                    runFixButton.getWidth() - 10)));
        }

        scaler.update(width, height);
        scaler.push(graphics);
        scaler.scrim(graphics);
        FlightLineTheme.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight,
                FlightLineTheme.PANEL, FlightLineTheme.STONE);
        FlightLineTheme.header(graphics, font, panelLeft + 4, panelTop + 4,
                panelWidth - 8, 24, "05", "EMERGENCY CHECKLIST", WARNING_TEXT);
        drawSeverityBanner(graphics, status, grouped);

        FlightLineTheme.inset(graphics, x(10), y(60), w(388), h(510), BORDER);
        graphics.fill(x(12), y(60), x(396), y(80), FlightLineTheme.HEADER);
        graphics.fill(x(12), y(80), x(396), y(81), CYAN_TEXT);
        graphics.centeredText(font, "ACTIVE CONDITIONS", infoLeft + (infoRight - infoLeft) / 2,
                y(65), CYAN_TEXT);

        FlightLineTheme.inset(graphics, x(406), y(62), w(442), h(508), BORDER);
        graphics.fill(x(406), y(62), x(848), y(82), FlightLineTheme.HEADER);
        graphics.fill(x(406), y(82), x(848), y(83), CYAN_TEXT);

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
                checklistKey + " closes · " + fixKey + " executes the next action · scroll conditions · procedures follow the approved checklist",
                panelWidth - w(20)), panelLeft + panelWidth / 2, y(578), MUTED_TEXT);
        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    private void drawSeverityBanner(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                    boolean grouped) {
        int top = y(32);
        int bottom = y(58);
        boolean critical = status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2
                | EmergencyStatus.FAILURE_1 | EmergencyStatus.FAILURE_2 | EmergencyStatus.FUEL_EMPTY);
        boolean caution = status.active();
        int color = critical ? WARNING_TEXT : caution ? AMBER_TEXT : ACCENT_TEXT;
        int fill = critical ? FlightLineTheme.COAL : caution ? FlightLineTheme.COAL
                : FlightLineTheme.INSET;

        graphics.fill(x(12), top, x(848), bottom, fill);
        graphics.outline(x(12), top, w(836), bottom - top, color);
        graphics.fill(x(12), top, x(17), bottom, color);
        String severity = critical ? "CRITICAL" : caution ? "CAUTION" : "ALL SYSTEMS NORMAL";
        graphics.text(font, severity, x(22), top + h(6), color, true);

        String resources = "BOTTLES " + status.extinguisherBottles()
                + " · E1 " + status.restartAttempts1() + "/3 · E2 " + status.restartAttempts2() + "/3";
        String fittedResources = fitText(resources, w(236));
        int resourcesX = x(848) - font.width(fittedResources) - w(8);
        graphics.text(font, fittedResources, resourcesX, top + h(6), MUTED_TEXT, true);

        String summary = critical ? "Landing emergency · follow the next action"
                : caution ? "Abnormal condition active · follow the next action"
                : grouped ? "GINA 787 · 6 engines in 2 groups"
                : "KASA A380 · 2 independent engines";
        int summaryX = x(22) + font.width(severity) + w(12);
        graphics.text(font, fitText(summary, Math.max(1, resourcesX - summaryX - w(10))),
                summaryX, top + h(6), NORMAL_TEXT, true);
    }

    private void drawConditionCards(GuiGraphicsExtractor graphics) {
        if (cards.isEmpty()) {
            int lineY = infoTop + h(12);
            int textWidth = infoRight - infoLeft - w(16);
            graphics.text(font, "No active failures.", infoLeft + w(8), lineY, ACCENT_TEXT, true);
            graphics.text(font, fitText("Fuel " + FlightHud.fuelPercent() + "% · autopilot "
                    + (FlightHud.isAutopilotEngaged() ? "ENGAGED" : "STANDBY"), textWidth),
                    infoLeft + w(8), lineY + h(18), NORMAL_TEXT, true);
            graphics.text(font, fitText("Use PROCEDURES to review the approved", textWidth),
                    infoLeft + w(8), lineY + h(40), MUTED_TEXT, true);
            graphics.text(font, fitText("checklist for each aircraft system.", textWidth),
                    infoLeft + w(8), lineY + h(54), MUTED_TEXT, true);
            graphics.text(font, fitText("Maintain configuration and continue the flight.", textWidth),
                    infoLeft + w(8), lineY + h(68), CYAN_TEXT, true);
            return;
        }

        int maxScroll = Math.max(0, cards.size() - visibleCards);
        cardScroll = Math.max(0, Math.min(maxScroll, cardScroll));
        int end = Math.min(cards.size(), cardScroll + visibleCards);
        for (int i = cardScroll; i < end; i++) {
            ConditionCard card = cards.get(i);
            int cardY = infoTop + (i - cardScroll) * h(CARD_HEIGHT);
            int bottom = cardY + h(CARD_HEIGHT) - 3;
            FlightLineTheme.card(graphics, infoLeft, cardY, infoRight - infoLeft,
                    bottom - cardY, card.color());
            graphics.text(font, fitText(card.title(), infoRight - infoLeft - w(14)),
                    infoLeft + w(10), cardY + h(5), card.color(), true);

            List<String> detail = wrap(card.detail(), infoRight - infoLeft - w(18));
            int detailLines = card.barFraction() >= 0.0f ? 1 : 2;
            for (int line = 0; line < Math.min(detailLines, detail.size()); line++) {
                graphics.text(font, fitText(detail.get(line), infoRight - infoLeft - w(18)),
                        infoLeft + w(10), cardY + h(17) + line * h(LINE_HEIGHT),
                        NORMAL_TEXT, true);
            }
            if (card.barFraction() >= 0.0f) {
                int barLeft = infoLeft + w(10);
                int barRight = infoRight - w(8);
                int barTop = cardY + h(CARD_HEIGHT) - h(10);
                graphics.fill(barLeft, barTop, barRight, barTop + h(5), FlightLineTheme.INSET);
                int fillWidth = Math.round((barRight - barLeft) * card.barFraction());
                graphics.fill(barLeft, barTop, barLeft + fillWidth, barTop + h(5), card.color());
                String barLabel = fitText(card.barLabel(), barRight - barLeft - w(4));
                graphics.text(font, barLabel, barRight - font.width(barLabel), barTop - h(1),
                        card.color(), true);
            }
        }
        if (maxScroll > 0) {
            int trackHeight = infoBottom - infoTop;
            int thumbHeight = Math.max(14, trackHeight * visibleCards / cards.size());
            int thumbTop = infoTop + (int) (cardScroll / (float) maxScroll * (trackHeight - thumbHeight));
            graphics.fill(infoRight - w(6), infoTop, infoRight - w(3), infoBottom,
                    FlightLineTheme.COAL);
            graphics.fill(infoRight - w(6), thumbTop, infoRight - w(3), thumbTop + thumbHeight,
                    CYAN_TEXT);
        }
    }

    private void drawNextAction(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                boolean grouped) {
        int top = y(90);
        int bottom = y(186);
        var recommendation = status.recommendation();
        int color = recommendation.present() ? ACCENT_TEXT : MUTED_TEXT;
        int textX = x(426);
        int textWidth = w(418) - w(24);
        FlightLineTheme.panel(graphics, x(416), top, w(422), bottom - top,
                FlightLineTheme.CARD, BORDER);
        graphics.fill(x(416), top, x(422), bottom, color);

        if (!recommendation.present()) {
            graphics.text(font, "MANUAL ACTION REQUIRED", textX, top + h(8), MUTED_TEXT, true);
            graphics.text(font, fitText("Hold the aircraft, configure manually and land as", textWidth),
                    textX, top + h(26), NORMAL_TEXT, true);
            graphics.text(font, fitText("soon as practical. Procedures below remain available.", textWidth),
                    textX, top + h(42), NORMAL_TEXT, true);
        } else {
            graphics.text(font, "RECOMMENDED NEXT ACTION", textX, top + h(8), CYAN_TEXT, true);
            String label = actionLabel(recommendation.action(), recommendation.target(), status);
            graphics.text(font, fitText(label, textWidth), textX, top + h(22), color, true);
            List<String> why = wrap(EmergencyStatus.actionExplanation(recommendation.action()),
                    textWidth);
            for (int i = 0; i < Math.min(2, why.size()); i++) {
                graphics.text(font, fitText(why.get(i), textWidth), textX,
                        top + h(40) + i * h(LINE_HEIGHT), NORMAL_TEXT, true);
            }
            if (EmergencyAction.isEngineAction(recommendation.action())) {
                graphics.text(font, fitText("Affects "
                                + EmergencyStatus.engineName(recommendation.target(), grouped)
                                + " only · the other engine keeps running.", textWidth),
                        textX, top + h(66), MUTED_TEXT, true);
            }
        }

        int progressTop = y(226);
        graphics.text(font, "CHECKPOINT STATUS", x(418), progressTop, CYAN_TEXT, true);
        String[][] checkpoints = {
                {"Fires extinguished", status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2) ? " " : "X"},
                {"Gear usable", status.hasCondition(EmergencyStatus.GEAR_FAILED) ? " " : "X"},
                {"Hydraulics restored", status.hasCondition(EmergencyStatus.HYDRAULIC) ? " " : "X"},
                {"Cabin descent complete", status.hasCondition(EmergencyStatus.PRESSURISATION) ? " " : "X"},
                {"MAYDAY declared", status.hasCondition(EmergencyStatus.EMERGENCY_DECLARED) ? "X" : " "},
                {"Autoland armed", FlightHud.autopilotGuidance().autolandArmed() ? "X" : " "}
        };
        for (int i = 0; i < checkpoints.length; i++) {
            int rowY = progressTop + h(18) + i * h(16);
            boolean done = "X".equals(checkpoints[i][1]);
            graphics.fill(x(422), rowY, x(432), rowY + h(10),
                    done ? FlightLineTheme.GREEN_DARK : FlightLineTheme.COAL);
            graphics.outline(x(422), rowY, w(10), h(10), done ? ACCENT_TEXT : FlightLineTheme.STONE);
            graphics.text(font, "[" + checkpoints[i][1] + "]", x(422), rowY,
                    done ? ACCENT_TEXT : MUTED_TEXT, true);
            graphics.text(font, fitText(checkpoints[i][0], w(380)), x(442), rowY,
                    done ? ACCENT_TEXT : NORMAL_TEXT, true);
        }
    }

    private void drawProcedures(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                boolean grouped) {
        graphics.text(font, fitText("Available procedures are bright. Locked procedures show why.",
                w(418)), x(418), y(88), MUTED_TEXT, true);
        String[] headers = {"ENGINE", "AIRFRAME", "FUEL / ATC"};
        int[] headerSlots = {0, 4, 7};
        for (int i = 0; i < headers.length; i++) {
            graphics.text(font, headers[i], x(418), slotY(headerSlots[i]) - h(12), CYAN_TEXT, true);
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
                            w(402)),
                    x(418), slotY(i) + h(19), available ? MUTED_TEXT : AMBER_TEXT, true);
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
        if (font.width(text) <= maxWidth) return text;
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted + "…") > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted + "…";
    }

    private int x(int base) { return panelLeft + base; }
    private int y(int base) { return panelTop + base; }
    private int w(int base) { return Math.max(1, base); }
    private int h(int base) { return Math.max(1, base); }

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
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(scaler.map(event), doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(scaler.map(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        return super.mouseDragged(scaler.map(event), deltaX / scaler.scale(), deltaY / scaler.scale());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int logicalX = scaler.mouseX(mouseX);
        int logicalY = scaler.mouseY(mouseY);
        if (logicalX >= infoLeft - 2 && logicalX <= infoRight + 2
                && logicalY >= infoTop && logicalY <= infoBottom) {
            int maxScroll = Math.max(0, cards.size() - visibleCards);
            cardScroll = Math.max(0, Math.min(maxScroll, cardScroll + (deltaY < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(logicalX, logicalY, deltaX, deltaY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
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
        private final AbstractWidget button;
        private final int baseAction;
        private final int target;
        private final int width;
        private int action;

        private ActionButton(AbstractWidget button, int action, int target, int width) {
            this.button = button;
            this.baseAction = action;
            this.action = action;
            this.target = target;
            this.width = width;
        }
    }
}
