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

/**
 * Interactive in-flight emergency checklist and QRH procedures panel.
 * Clean, standard dimensions with active conditions on the left and grouped recovery actions on the right.
 */
public final class EmergencyScreen extends Screen {
    private static final int PANEL_WIDTH = 580;
    private static final int PANEL_HEIGHT = 370;
    private static final int CARD_HEIGHT = 44;

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
    private int panelLeft = 8;
    private int panelTop = 8;
    private int cardScroll;
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
        panelLeft = 8;
        panelTop = 8;

        // Top-right close button
        addRenderableWidget(FlightLineTheme.button("CLOSE",
                panelLeft + PANEL_WIDTH - 76, panelTop + 6, 68, 18, this::onClose));

        // Primary recommended action button
        runFixButton = FlightLineTheme.button("MANUAL CHECKLIST",
                panelLeft + 284, panelTop + 78, 284, 22, this::executeRecommended);
        addRenderableWidget(runFixButton);

        // Grouped procedure buttons on the right side
        // Engine row 1: Extinguish E1, Extinguish E2
        addAction(EmergencyAction.EXTINGUISH_ENGINE, 0, panelLeft + 284, panelTop + 140, 138, 18);
        addAction(EmergencyAction.EXTINGUISH_ENGINE, 1, panelLeft + 430, panelTop + 140, 138, 18);

        // Engine row 2: Restart E1, Restart E2
        addAction(EmergencyAction.RESTART_ENGINE, 0, panelLeft + 284, panelTop + 162, 138, 18);
        addAction(EmergencyAction.RESTART_ENGINE, 1, panelLeft + 430, panelTop + 162, 138, 18);

        // Airframe row: Alternate Gear, Reset Hydraulics
        addAction(EmergencyAction.ALTERNATE_GEAR, -1, panelLeft + 284, panelTop + 208, 138, 18);
        addAction(EmergencyAction.RESET_HYDRAULICS, -1, panelLeft + 430, panelTop + 208, 138, 18);

        // Emergency Ops: Descent, Fuel Jettison, Mayday, Autoland
        addAction(EmergencyAction.EMERGENCY_DESCENT, -1, panelLeft + 284, panelTop + 254, 138, 18);
        addAction(EmergencyAction.TOGGLE_FUEL_DUMP, -1, panelLeft + 430, panelTop + 254, 138, 18);
        addAction(EmergencyAction.DECLARE_MAYDAY, -1, panelLeft + 284, panelTop + 276, 138, 18);
        addAction(EmergencyAction.EMERGENCY_AUTOLAND, -1, panelLeft + 430, panelTop + 276, 138, 18);
    }

    private void addAction(int action, int target, int x, int y, int width, int height) {
        var holder = new AbstractWidget[1];
        holder[0] = FlightLineTheme.button("", x, y, width, height, () -> sendAction(holder[0]));
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

        // Update button states and labels
        for (ActionButton entry : actionButtons) {
            entry.action = entry.baseAction;
            if (entry.baseAction == EmergencyAction.DECLARE_MAYDAY
                    && status.hasCondition(EmergencyStatus.EMERGENCY_DECLARED)) {
                entry.action = EmergencyAction.CANCEL_MAYDAY;
            }
            entry.button.active = actionAvailable(status, entry.action, entry.target);
            entry.button.setMessage(Component.literal(fitText(
                    actionLabel(entry.action, entry.target, status), entry.width - 8)));
        }

        if (runFixButton != null) {
            boolean hasFix = status.recommendation().present();
            runFixButton.active = hasFix;
            String key = FlightLineClient.keyName(FlightLineClient.executeEmergency());
            if (hasFix) {
                String label = key + " · " + actionLabel(status.recommendedAction(), status.recommendedTarget(), status);
                runFixButton.setMessage(Component.literal(fitText(label, runFixButton.getWidth() - 10)));
            } else {
                runFixButton.setMessage(Component.literal("ALL CHECKPOINTS CLEAR"));
            }
        }

        scaler.update(width, height);
        scaler.push(graphics);
        scaler.scrim(graphics);

        // Main background panel
        FlightLineTheme.panel(graphics, panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT,
                FlightLineTheme.PANEL, FlightLineTheme.STONE);

        // Header bar
        FlightLineTheme.header(graphics, font, panelLeft + 4, panelTop + 4,
                PANEL_WIDTH - 8, 22, "05", "EMERGENCY PROCEDURES & CHECKLIST", WARNING_TEXT);

        // Severity banner
        drawSeverityBanner(graphics, status, grouped);

        // Left box: Active Conditions
        int leftBoxX = panelLeft + 12;
        int leftBoxY = panelTop + 60;
        int leftBoxW = 260;
        int leftBoxH = 264;
        FlightLineTheme.inset(graphics, leftBoxX, leftBoxY, leftBoxW, leftBoxH, BORDER);
        graphics.fill(leftBoxX, leftBoxY, leftBoxX + leftBoxW, leftBoxY + 16, FlightLineTheme.HEADER);
        graphics.fill(leftBoxX, leftBoxY + 16, leftBoxX + leftBoxW, leftBoxY + 17, CYAN_TEXT);
        graphics.centeredText(font, "ACTIVE ALERTS & CONDITIONS", leftBoxX + leftBoxW / 2, leftBoxY + 4, CYAN_TEXT);

        cards = buildCards(status, grouped);
        drawConditionCards(graphics, leftBoxX, leftBoxY + 18, leftBoxW, leftBoxH - 18);

        // Right box: Actions & Procedures
        int rightBoxX = panelLeft + 280;
        int rightBoxY = panelTop + 60;
        int rightBoxW = 290;
        int rightBoxH = 264;
        FlightLineTheme.inset(graphics, rightBoxX, rightBoxY, rightBoxW, rightBoxH, BORDER);
        graphics.fill(rightBoxX, rightBoxY, rightBoxX + rightBoxW, rightBoxY + 16, FlightLineTheme.HEADER);
        graphics.fill(rightBoxX, rightBoxY + 16, rightBoxX + rightBoxW, rightBoxY + 17, CYAN_TEXT);
        graphics.centeredText(font, "RECOVERY & PROCEDURES", rightBoxX + rightBoxW / 2, rightBoxY + 4, CYAN_TEXT);

        // Procedure section headers
        graphics.text(font, "RECOMMENDED IMMEDIATE ACTION", rightBoxX + 4, panelTop + 66, CYAN_TEXT, true);
        if (status.recommendation().present()) {
            String explanation = EmergencyStatus.actionExplanation(status.recommendedAction());
            graphics.text(font, fitText(explanation, rightBoxW - 12), rightBoxX + 4, panelTop + 104, NORMAL_TEXT, true);
        } else {
            graphics.text(font, "No emergency actions needed. Maintain flight path.", rightBoxX + 4, panelTop + 104, MUTED_TEXT, true);
        }

        graphics.fill(rightBoxX + 4, panelTop + 118, rightBoxX + rightBoxW - 4, panelTop + 119, BORDER);

        graphics.text(font, "ENGINES", rightBoxX + 4, panelTop + 126, CYAN_TEXT, true);
        graphics.text(font, "AIRFRAME & HYDRAULICS", rightBoxX + 4, panelTop + 194, CYAN_TEXT, true);
        graphics.text(font, "EMERGENCY OPERATIONS", rightBoxX + 4, panelTop + 240, CYAN_TEXT, true);

        // Footer hint
        String checklistKey = FlightLineClient.keyName(FlightLineClient.emergencyChecklist());
        String fixKey = FlightLineClient.keyName(FlightLineClient.executeEmergency());
        String footer = checklistKey + " closes · " + fixKey + " executes recommended action · Click buttons to execute checklist";
        graphics.centeredText(font, fitText(footer, PANEL_WIDTH - 20),
                panelLeft + PANEL_WIDTH / 2, panelTop + PANEL_HEIGHT - 14, MUTED_TEXT);

        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    private void drawSeverityBanner(GuiGraphicsExtractor graphics, EmergencyStatusPayload status, boolean grouped) {
        int top = panelTop + 30;
        int bottom = panelTop + 54;
        boolean critical = status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2
                | EmergencyStatus.FAILURE_1 | EmergencyStatus.FAILURE_2 | EmergencyStatus.FUEL_EMPTY);
        boolean caution = status.active();
        int color = critical ? WARNING_TEXT : caution ? AMBER_TEXT : ACCENT_TEXT;
        int fill = critical ? FlightLineTheme.COAL : caution ? FlightLineTheme.COAL : FlightLineTheme.INSET;

        int left = panelLeft + 12;
        int right = panelLeft + PANEL_WIDTH - 12;

        graphics.fill(left, top, right, bottom, fill);
        graphics.outline(left, top, right - left, bottom - top, color);
        graphics.fill(left, top, left + 4, bottom, color);

        String severity = critical ? "CRITICAL EMERGENCY" : caution ? "CAUTION - ABNORMAL" : "ALL SYSTEMS NORMAL";
        graphics.text(font, severity, left + 10, top + 7, color, true);

        String resources = "BOTTLES " + status.extinguisherBottles()
                + " · E1 " + status.restartAttempts1() + "/3 · E2 " + status.restartAttempts2() + "/3 · FUEL "
                + FlightHud.fuelPercent() + "%";
        String fittedResources = fitText(resources, 240);
        int resourcesX = right - font.width(fittedResources) - 8;
        graphics.text(font, fittedResources, resourcesX, top + 7, MUTED_TEXT, true);
    }

    private void drawConditionCards(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (cards.isEmpty()) {
            int lineY = y + 16;
            int textWidth = width - 16;
            graphics.text(font, "No active failures detected.", x + 8, lineY, ACCENT_TEXT, true);
            graphics.text(font, fitText("Fuel: " + FlightHud.fuelPercent() + "% · AP: "
                            + (FlightHud.isAutopilotEngaged() ? "ENGAGED" : "STANDBY"), textWidth),
                    x + 8, lineY + 16, NORMAL_TEXT, true);
            graphics.text(font, fitText("All engine, hydraulic, and pressurization", textWidth),
                    x + 8, lineY + 36, MUTED_TEXT, true);
            graphics.text(font, fitText("systems are operating within normal limits.", textWidth),
                    x + 8, lineY + 48, MUTED_TEXT, true);
            graphics.text(font, fitText("Procedures on the right remain available", textWidth),
                    x + 8, lineY + 68, CYAN_TEXT, true);
            graphics.text(font, fitText("for manual drills and checklist reviews.", textWidth),
                    x + 8, lineY + 80, CYAN_TEXT, true);
            return;
        }

        int visibleCards = Math.max(1, height / CARD_HEIGHT);
        int maxScroll = Math.max(0, cards.size() - visibleCards);
        cardScroll = Math.max(0, Math.min(maxScroll, cardScroll));

        for (int i = 0; i < visibleCards && (i + cardScroll) < cards.size(); i++) {
            ConditionCard card = cards.get(i + cardScroll);
            int cardY = y + 4 + i * (CARD_HEIGHT + 2);
            int cardW = width - 8;

            FlightLineTheme.panel(graphics, x + 4, cardY, cardW, CARD_HEIGHT,
                    FlightLineTheme.CARD, BORDER);
            graphics.fill(x + 4, cardY, x + 7, cardY + CARD_HEIGHT, card.color);

            graphics.text(font, card.title, x + 12, cardY + 5, card.color, true);
            graphics.text(font, fitText(card.detail, cardW - 16), x + 12, cardY + 17, NORMAL_TEXT, true);

            if (card.bar >= 0.0f) {
                int barW = cardW - 16;
                graphics.fill(x + 12, cardY + 30, x + 12 + barW, cardY + 34, FlightLineTheme.INSET);
                graphics.fill(x + 12, cardY + 30, x + 12 + Math.round(barW * card.bar), cardY + 34, card.color);
                graphics.text(font, card.barLabel, x + 12, cardY + 36, MUTED_TEXT, true);
            }
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
                barLabel = seconds > 0 ? "BOTTLE WINDOW: " + seconds + "S" : "WINDOW CLOSED · LAND ASAP";
            }
            cards.add(new ConditionCard(EmergencyStatus.conditionLabel(condition, grouped),
                    shorten(EmergencyStatus.conditionExplanation(condition, grouped), 60),
                    color, bar, barLabel));
        }
        return cards;
    }

    private static String shorten(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private String fitText(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted + "…") > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted + "…";
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
            case EmergencyAction.TOGGLE_FUEL_DUMP -> status.can(EmergencyStatus.CAN_DUMP_FUEL | EmergencyStatus.CAN_STOP_DUMP);
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
            case EmergencyAction.ALTERNATE_GEAR -> "ALT GEAR EXTEND";
            case EmergencyAction.RESET_HYDRAULICS -> "HYD BACKUP PWR";
            case EmergencyAction.EMERGENCY_DESCENT -> "EMERGENCY DESCENT";
            case EmergencyAction.TOGGLE_FUEL_DUMP -> status.hasCondition(EmergencyStatus.FUEL_DUMPING)
                    ? "STOP JETTISON" : "FUEL JETTISON";
            case EmergencyAction.DECLARE_MAYDAY -> "DECLARE MAYDAY";
            case EmergencyAction.CANCEL_MAYDAY -> "CANCEL MAYDAY";
            case EmergencyAction.EMERGENCY_AUTOLAND -> "EMERGENCY AUTOLAND";
            default -> "UNAVAILABLE";
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
        int leftBoxX = panelLeft + 12;
        int leftBoxY = panelTop + 60;
        if (logicalX >= leftBoxX && logicalX <= leftBoxX + 260
                && logicalY >= leftBoxY && logicalY <= leftBoxY + 264) {
            cardScroll = Math.max(0, cardScroll + (deltaY < 0 ? 1 : -1));
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

    private static final class ActionButton {
        private final AbstractWidget button;
        private final int baseAction;
        private final int target;
        private final int width;
        private int action;

        private ActionButton(AbstractWidget button, int baseAction, int target, int width) {
            this.button = button;
            this.baseAction = baseAction;
            this.target = target;
            this.width = width;
            this.action = baseAction;
        }
    }

    private record ConditionCard(String title, String detail, int color, float bar, String barLabel) {
    }
}
