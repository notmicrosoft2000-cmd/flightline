package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
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

/** Interactive in-flight checklist that exposes every server-authoritative emergency procedure. */
public final class EmergencyScreen extends Screen {
    private static final int PANEL_WIDTH = 760;
    private static final int PANEL_HEIGHT = 560;
    private static final int LINE_HEIGHT = 12;
    private static final int NORMAL_TEXT = 0xFFD8E4EA;
    private static final int MUTED_TEXT = 0xFF8FA2AA;
    private static final int WARNING_TEXT = 0xFFFF8A7A;
    private static final int ACCENT_TEXT = 0xFF62D89B;

    private final List<ActionButton> actionButtons = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int infoLeft;
    private int infoRight;
    private int infoTop;
    private int infoBottom;
    private int visibleInfoLines = 1;
    private int scrollOffset;
    private List<InfoLine> infoLines = List.of();
    private @Nullable Button executeButton;

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

        int bodyTop = panelTop + 34;
        int bodyBottom = panelTop + panelHeight - 28;
        int gap = 10;
        int infoWidth = Math.max(120, (int) ((panelWidth - 24 - gap) * 0.52));
        int actionLeft = panelLeft + 12 + infoWidth + gap;
        int actionWidth = Math.max(120, panelWidth - 24 - infoWidth - gap);

        infoLeft = panelLeft + 12;
        infoRight = infoLeft + infoWidth;
        infoTop = bodyTop + 26;
        infoBottom = bodyBottom - 8;
        visibleInfoLines = Math.max(1, (infoBottom - infoTop) / LINE_HEIGHT);

        int columnWidth = (actionWidth - 8) / 2;
        int buttonWidth = Math.max(32, columnWidth - 4);
        int buttonTop = bodyTop + 96;
        int availableHeight = Math.max(120, bodyBottom - buttonTop - 6);
        int rowSpacing = Math.max(20, Math.min(32, availableHeight / 6));
        int buttonHeight = Math.max(18, Math.min(22, rowSpacing - 4));
        int leftColumn = actionLeft + 2;
        int rightColumn = actionLeft + 6 + columnWidth;

        addAction(EmergencyAction.EXTINGUISH_ENGINE, 0, leftColumn, buttonTop, buttonWidth, buttonHeight);
        addAction(EmergencyAction.EXTINGUISH_ENGINE, 1, rightColumn, buttonTop, buttonWidth, buttonHeight);
        addAction(EmergencyAction.RESTART_ENGINE, 0, leftColumn, buttonTop + rowSpacing, buttonWidth, buttonHeight);
        addAction(EmergencyAction.RESTART_ENGINE, 1, rightColumn, buttonTop + rowSpacing, buttonWidth, buttonHeight);
        addAction(EmergencyAction.ALTERNATE_GEAR, -1, leftColumn, buttonTop + rowSpacing * 2, buttonWidth, buttonHeight);
        addAction(EmergencyAction.RESET_HYDRAULICS, -1, rightColumn, buttonTop + rowSpacing * 2, buttonWidth, buttonHeight);
        addAction(EmergencyAction.EMERGENCY_DESCENT, -1, leftColumn, buttonTop + rowSpacing * 3, buttonWidth, buttonHeight);
        addAction(EmergencyAction.TOGGLE_FUEL_DUMP, -1, rightColumn, buttonTop + rowSpacing * 3, buttonWidth, buttonHeight);
        addAction(EmergencyAction.DECLARE_MAYDAY, -1, leftColumn, buttonTop + rowSpacing * 4, buttonWidth, buttonHeight);
        addAction(EmergencyAction.EMERGENCY_AUTOLAND, -1, rightColumn, buttonTop + rowSpacing * 4, buttonWidth, buttonHeight);

        executeButton = Button.builder(Component.literal("RUN RECOMMENDED"), button -> executeRecommended())
                .bounds(leftColumn, buttonTop + rowSpacing * 5, buttonWidth, buttonHeight)
                .build();
        addRenderableWidget(executeButton);
        addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                .bounds(rightColumn, buttonTop + rowSpacing * 5, buttonWidth, buttonHeight)
                .build());
    }

    private void addAction(int action, int target, int x, int y, int width, int height) {
        Button button = Button.builder(Component.literal(""), clicked -> sendAction(clicked))
                .bounds(x, y, width, height)
                .build();
        ActionButton entry = new ActionButton(button, action, target, width);
        actionButtons.add(entry);
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
            entry.button.setMessage(Component.literal(fitText(font, actionLabel(entry.action, entry.target, status),
                    entry.width - 8)));
        }
        if (executeButton != null) {
            executeButton.active = status.recommendation().present();
            String key = FlightLineClient.keyName(FlightLineClient.executeEmergency());
            executeButton.setMessage(Component.literal(fitText(font,
                    status.recommendation().present() ? key + " RUN FIX" : "NO FIX READY",
                    executeButton.getWidth() - 8)));
        }

        graphics.fill(0, 0, width, height, 0x30000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121A22);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF4EB8C8);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 28, 0xFF1C2733);
        graphics.centeredText(font, "EMERGENCY CHECKLIST", width / 2, panelTop + 8, 0xFFFF6F6F);

        int bodyTop = panelTop + 34;
        int bodyBottom = panelTop + panelHeight - 28;
        int gap = 10;
        int infoWidth = infoRight - infoLeft;
        int actionLeft = infoRight + gap;
        int actionWidth = panelLeft + panelWidth - 12 - actionLeft;

        graphics.fill(infoLeft - 2, bodyTop, infoRight + 2, bodyBottom, 0xE30D1419);
        graphics.outline(infoLeft - 2, bodyTop, infoWidth + 4, bodyBottom - bodyTop, 0xFF2A3D49);
        graphics.centeredText(font, "CURRENT SITUATION", infoLeft + infoWidth / 2, bodyTop + 8, 0xFF4EB8C8);

        graphics.fill(actionLeft, bodyTop, actionLeft + actionWidth, bodyBottom, 0xE3101720);
        graphics.outline(actionLeft, bodyTop, actionWidth, bodyBottom - bodyTop, 0xFF2A3D49);
        graphics.centeredText(font, "PROCEDURES", actionLeft + actionWidth / 2, bodyTop + 8, 0xFF4EB8C8);

        drawRecommendation(graphics, status, actionLeft, bodyTop, actionWidth);
        drawInfo(graphics, status, grouped);
        graphics.centeredText(font, fitText(font,
                        "Scroll the situation panel · O closes · T runs the recommended fix · server revalidates every action",
                        panelWidth - 20),
                width / 2, panelTop + panelHeight - 16, 0xFF83959B);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRecommendation(GuiGraphicsExtractor graphics, EmergencyStatusPayload status,
                                    int actionLeft, int bodyTop, int actionWidth) {
        int top = bodyTop + 24;
        int bottom = top + 60;
        graphics.fill(actionLeft + 6, top, actionLeft + actionWidth - 6, bottom, 0xFF17222B);
        var recommendation = status.recommendation();
        if (!recommendation.present()) {
            graphics.text(font, "NEXT: NO AUTOMATED FIX", actionLeft + 12, top + 8, MUTED_TEXT, true);
            graphics.text(font, "Configure manually, then land.", actionLeft + 12, top + 24, NORMAL_TEXT, true);
            graphics.text(font, "Open the guide for procedures.", actionLeft + 12, top + 38, MUTED_TEXT, true);
            return;
        }
        String key = FlightLineClient.keyName(FlightLineClient.executeEmergency());
        String heading = "NEXT " + key + ": " + EmergencyStatus.actionLabel(recommendation.action());
        graphics.text(font, fitText(font, heading, actionWidth - 20),
                actionLeft + 12, top + 6, ACCENT_TEXT, true);
        List<String> why = wrap(EmergencyStatus.actionExplanation(recommendation.action()), actionWidth - 20);
        for (int i = 0; i < Math.min(2, why.size()); i++) {
            graphics.text(font, why.get(i), actionLeft + 12, top + 22 + i * LINE_HEIGHT, NORMAL_TEXT, true);
        }
        if (EmergencyAction.isEngineAction(recommendation.action())) {
            graphics.text(font, "Targets " + EmergencyStatus.engineName(recommendation.target(),
                            FlightLineClient.getAircraftType() == AircraftType.GINA_787) + " only.",
                    actionLeft + 12, top + 46, MUTED_TEXT, true);
        }
    }

    private void drawInfo(GuiGraphicsExtractor graphics, EmergencyStatusPayload status, boolean grouped) {
        infoLines = wrapInfo(buildInfoLines(status, grouped), infoRight - infoLeft - 12);
        int maxScroll = Math.max(0, infoLines.size() - visibleInfoLines);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        int end = Math.min(infoLines.size(), scrollOffset + visibleInfoLines);
        for (int i = scrollOffset; i < end; i++) {
            InfoLine line = infoLines.get(i);
            graphics.text(font, line.text(), infoLeft + 6, infoTop + (i - scrollOffset) * LINE_HEIGHT,
                    line.color(), true);
        }
        if (maxScroll > 0 && infoLines.size() > 0) {
            int trackTop = infoTop;
            int trackBottom = infoBottom;
            int trackHeight = Math.max(1, trackBottom - trackTop);
            int thumbHeight = Math.max(14, trackHeight * visibleInfoLines / infoLines.size());
            int maxThumbTop = Math.max(0, trackHeight - thumbHeight);
            int thumbTop = trackTop + (int) (scrollOffset / (float) maxScroll * maxThumbTop);
            graphics.fill(infoRight - 6, trackTop, infoRight - 3, trackBottom, 0xFF1F2A33);
            graphics.fill(infoRight - 6, thumbTop, infoRight - 3, thumbTop + thumbHeight, 0xFF4EB8C8);
        }
    }

    private static List<InfoLine> buildInfoLines(EmergencyStatusPayload status, boolean grouped) {
        List<InfoLine> lines = new ArrayList<>();
        if (!status.active()) {
            lines.add(new InfoLine("All monitored systems are normal.", ACCENT_TEXT));
            lines.add(new InfoLine("", MUTED_TEXT));
            lines.add(new InfoLine("Use the procedures panel to practise a checklist, or an operator can inject a debug emergency with /flightline emergency.", NORMAL_TEXT));
            return lines;
        }

        lines.add(new InfoLine("WHAT IS WRONG", WARNING_TEXT));
        boolean found = false;
        for (int condition : EmergencyStatus.CONDITIONS) {
            if (!status.hasCondition(condition)) continue;
            found = true;
            lines.add(new InfoLine("- " + EmergencyStatus.conditionLabel(condition, grouped), WARNING_TEXT));
            String detail = EmergencyStatus.conditionExplanation(condition, grouped);
            if (condition == EmergencyStatus.FIRE_1 || condition == EmergencyStatus.FIRE_2) {
                int fireTicks = condition == EmergencyStatus.FIRE_1 ? status.fireTicks1() : status.fireTicks2();
                int seconds = Math.max(0, (FlightTuning.ENGINE_FIRE_SUPPRESSION_WINDOW_TICKS - fireTicks) / 20);
                detail += seconds > 0
                        ? " Bottle window: " + seconds + "s remaining."
                        : " Bottle window expired: land immediately.";
            }
            lines.add(new InfoLine(detail, NORMAL_TEXT));
        }
        if (!found) {
            lines.add(new InfoLine("An abnormal condition is active; see the recommended fix.", NORMAL_TEXT));
        }

        lines.add(new InfoLine("", MUTED_TEXT));
        lines.add(new InfoLine("WHAT TO DO NEXT", 0xFF4EB8C8));
        var recommendation = status.recommendation();
        if (recommendation.present()) {
            lines.add(new InfoLine(EmergencyStatus.actionLabel(recommendation.action()) + " - "
                    + EmergencyStatus.actionExplanation(recommendation.action()), ACCENT_TEXT));
            if (EmergencyAction.isEngineAction(recommendation.action())) {
                lines.add(new InfoLine("Only " + EmergencyStatus.engineName(recommendation.target(), grouped)
                        + " is affected; the other engine keeps running.", NORMAL_TEXT));
            }
        } else {
            lines.add(new InfoLine("No automatic fix is available right now. Keep the aircraft controlled, configure manually and land as soon as practical.", NORMAL_TEXT));
        }
        lines.add(new InfoLine("", MUTED_TEXT));
        lines.add(new InfoLine("Bottles: " + status.extinguisherBottles()
                + "   Relight attempts: E1 " + status.restartAttempts1() + " / E2 " + status.restartAttempts2(),
                MUTED_TEXT));
        return lines;
    }

    private List<InfoLine> wrapInfo(List<InfoLine> source, int maxWidth) {
        List<InfoLine> result = new ArrayList<>();
        for (InfoLine line : source) {
            if (line.text().isEmpty()) {
                result.add(line);
            } else {
                for (String wrapped : wrap(line.text(), maxWidth)) {
                    result.add(new InfoLine(wrapped, line.color()));
                }
            }
        }
        return result;
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawWord : text.split("\\s+")) {
            if (rawWord.isEmpty()) continue;
            String word = rawWord;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (fontWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            while (fontWidth(word) > maxWidth && word.length() > 1) {
                int cut = 1;
                while (cut < word.length() && fontWidth(word.substring(0, cut + 1)) <= maxWidth) cut++;
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private int fontWidth(String text) {
        return font.width(text);
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

    private String fitText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= infoLeft - 2 && mouseX <= infoRight + 2 && mouseY >= infoTop && mouseY <= infoBottom) {
            int maxScroll = Math.max(0, infoLines.size() - visibleInfoLines);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + (deltaY < 0 ? 2 : -2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_T) {
            executeRecommended();
            return true;
        }
        if (event.key() == InputConstants.KEY_O) {
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

    private record InfoLine(String text, int color) {
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
