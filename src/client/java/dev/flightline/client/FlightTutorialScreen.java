package dev.flightline.client;

import dev.flightline.network.AdjustFlapsPayload;
import dev.flightline.network.ConfigureAutopilotPayload;
import dev.flightline.network.StartEnginesPayload;
import dev.flightline.network.ToggleAutolandPayload;
import dev.flightline.network.ToggleAutopilotPayload;
import dev.flightline.network.ToggleGearPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Interactive four-stage lesson driven by the same live instruments used by the cockpit HUD. */
public final class FlightTutorialScreen extends Screen {
    private static final int BASE_WIDTH = 640;
    private static final int BASE_HEIGHT = 410;
    private static final int TEXT = 0xFFDCE8EE;
    private static final int MUTED = 0xFF8198A3;
    private static final int CYAN = 0xFF4EB8C8;
    private static final int GREEN = 0xFF62D89B;
    private static final int AMBER = 0xFFFFC44D;
    private static final int RED = 0xFFFF5A50;
    private static final int PANEL = 0xF0121A22;
    private static final int CARD = 0xE3101920;
    private static final int BORDER = 0xFF2A3D49;

    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private float scaleX;
    private float scaleY;
    private String status = "The lesson reads your live flight data. Objectives tick off automatically.";
    private long lastFrameNanos = System.nanoTime();
    private float openProgress;

    public FlightTutorialScreen() {
        super(Component.literal("Flight Line Tutorial"));
    }

    @Override
    protected void init() {
        super.init();
        scaler.update(width, height);
        panelWidth = BASE_WIDTH;
        panelHeight = BASE_HEIGHT;
        panelLeft = 8;
        panelTop = 8;
        scaleX = 1.0f;
        scaleY = 1.0f;

        String[] labels = {"TAKEOFF", "CRUISE", "AUTOPILOT", "LANDING"};
        int[] positions = {12, 126, 252, 388};
        int[] widths = {106, 118, 128, 104};
        for (int i = 0; i < labels.length; i++) {
            final int stageIndex = i;
            String text = FlightTutorial.stageIndex() == stageIndex ? "[" + labels[stageIndex] + "]" : labels[stageIndex];
            int tabWidth = widths[stageIndex];
            addRenderableWidget(Button.builder(Component.literal(fit(text, w(tabWidth) - 8)), button -> {
                        FlightTutorial.setStage(stageIndex);
                        status = FlightTutorial.stage().brief();
                        rebuild();
                    })
                    .bounds(x(positions[i]), y(30), w(tabWidth), h(20)).build());
        }
        addRenderableWidget(actionButton(FlightTutorial.isActive() ? "HIDE TRACKER" : "SHOW TRACKER",
                492, 30, 136, () -> {
                    FlightTutorial.setActive(!FlightTutorial.isActive());
                    status = FlightTutorial.isActive() ? "Compact lesson tracker is visible while flying."
                            : "Compact lesson tracker hidden. Press L to reopen the lesson.";
                    rebuild();
                }));

        initActions();
        addRenderableWidget(actionButton("RESET LESSON", 12, 378, 112, () -> {
                    FlightTutorial.reset();
                    status = "Lesson progress reset. Start engines to begin again.";
                    rebuild();
                }));
        addRenderableWidget(actionButton("CLOSE", 516, 378, 112, this::onClose));
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void initActions() {
        switch (FlightTutorial.stage()) {
            case TAKEOFF -> {
                addRenderableWidget(actionButton("START ENGINES", 412, 150, 216, this::startEngines));
                addRenderableWidget(actionButton("HOLD W - THROTTLE", 412, 178, 216, this::flyManually));
                addRenderableWidget(actionButton("PRESS G - GEAR", 412, 206, 216, this::toggleGear));
            }
            case CRUISE -> {
                addRenderableWidget(actionButton("FLY MANUALLY", 412, 150, 216, this::flyManually));
                addRenderableWidget(actionButton("FLAPS +", 412, 178, 104, this::flapsUp));
                addRenderableWidget(actionButton("TOGGLE GEAR", 524, 178, 104, this::toggleGear));
                addRenderableWidget(actionButton("TOGGLE TELEMETRY", 412, 206, 216, () -> {
                    FlightHud.toggleTelemetry();
                    status = FlightHud.isTelemetryVisible() ? "Expanded telemetry detail visible."
                            : "Expanded telemetry detail hidden. Press Y to open it.";
                }));
            }
            case AUTOPILOT -> {
                addRenderableWidget(actionButton("SYNC + ENGAGE AP", 412, 150, 216, this::engageAutopilot));
                addRenderableWidget(actionButton("OPEN FLIGHT DECK", 412, 178, 216, () ->
                        Minecraft.getInstance().gui.setScreen(new AutopilotScreen(
                                FlightHud.isAutopilotEngaged(),
                                Math.round(FlightHud.currentHeading()),
                                FlightHud.currentAltitudeFeet(),
                                FlightHud.currentVsFpm(),
                                FlightHud.currentSpeedKnots()))));
                addRenderableWidget(actionButton(FlightHud.isAutopilotEngaged() ? "AP DISCONNECT" : "MANUAL CONTROL",
                        412, 206, 216, FlightHud.isAutopilotEngaged() ? this::disconnectAutopilot : this::flyManually));
            }
            case LANDING -> {
                addRenderableWidget(actionButton("OPEN RADAR - SET ILS", 412, 144, 216, () ->
                        Minecraft.getInstance().gui.setScreen(new FlightRadarScreen())));
                addRenderableWidget(actionButton("GEAR DOWN CHECK", 412, 172, 104, this::toggleGear));
                addRenderableWidget(actionButton("FLAPS +", 524, 172, 104, this::flapsUp));
                addRenderableWidget(actionButton("ARM AUTOLAND", 412, 200, 216, this::armAutoland));
                addRenderableWidget(actionButton("FLY MANUALLY", 412, 228, 216, this::flyManually));
            }
        }
    }

    private Button actionButton(String label, int x, int y, int width, Runnable action) {
        return Button.builder(Component.literal(fit(label, w(width) - 8)), button -> action.run())
                .bounds(x(x), y(y), w(width), h(22)).build();
    }

    private void startEngines() {
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Enable flight mode first, then start the engines.";
            return;
        }
        ClientPlayNetworking.send(new StartEnginesPayload());
        status = "Engine start requested. The first objective updates when N1 comes alive.";
    }

    private void toggleGear() {
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Enable flight mode first.";
            return;
        }
        ClientPlayNetworking.send(new ToggleGearPayload());
        status = "Gear command sent. Confirm the PFD gear indication.";
    }

    private void flapsUp() {
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Enable flight mode first.";
            return;
        }
        ClientPlayNetworking.send(new AdjustFlapsPayload(1));
        status = "Flap increment sent. Watch the EICAS flap index.";
    }

    private void engageAutopilot() {
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Enable flight mode first.";
            return;
        }
        int heading = Math.floorMod(Math.round(FlightHud.currentHeading()), 360);
        int altitude = Math.max(1000, ((FlightHud.currentAltitudeFeet() + 499) / 500) * 500);
        int speed = Math.max(80, Math.min(320, FlightHud.currentSpeedKnots()));
        ClientPlayNetworking.send(new ConfigureAutopilotPayload(true, heading, altitude, 0, speed));
        status = String.format(Locale.ROOT, "Autopilot engaged · HDG %03d · ALT %d · IAS %d.",
                heading, altitude, speed);
    }

    private void disconnectAutopilot() {
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Enable flight mode first.";
            return;
        }
        ClientPlayNetworking.send(new ToggleAutopilotPayload());
        status = "Autopilot disconnect sent.";
    }

    private void armAutoland() {
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Enable flight mode first.";
            return;
        }
        if (!FlightHud.isIlsActive()) {
            status = "Set an ILS runway with the radar before arming autoland.";
            return;
        }
        ClientPlayNetworking.send(new ToggleAutolandPayload());
        status = "Autoland arm toggled. Verify LOC and GS on the PFD.";
    }

    private void flyManually() {
        FlightTutorial.setActive(true);
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float delta = Math.min(0.05f, Math.max(0.0f, (now - lastFrameNanos) / 1_000_000_000.0f));
        lastFrameNanos = now;
        openProgress += (1.0f - openProgress) * (1.0f - (float) Math.exp(-delta * 10.0f));

        scaler.update(width, height);
        scaler.push(graphics);
        graphics.fill(0, 0, scaler.width(), scaler.height(), 0x30000000);
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(scaler.width() / 2.0f, scaler.height() / 2.0f);
        pose.scale(0.96f + openProgress * 0.04f, 0.96f + openProgress * 0.04f);
        pose.translate(-scaler.width() / 2.0f, -scaler.height() / 2.0f);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, CYAN);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 26, 0xFF1C2733);
        graphics.centeredText(font, fit("FLIGHT TRAINING  ·  " + FlightTutorial.stage().title()
                        + "  ·  " + FlightTutorial.progressLabel(), panelWidth - 16),
                panelLeft + panelWidth / 2, panelTop + 8, 0xFF72E6F5);

        drawObjectives(graphics);
        drawBriefing(graphics);

        int barX = x(12);
        int barY = y(360);
        int barWidth = w(616);
        graphics.fill(barX, barY, barX + barWidth, barY + 6, 0xFF26343D);
        List<FlightTutorial.Objective> objectives = FlightTutorial.objectives();
        int done = 0;
        for (FlightTutorial.Objective objective : objectives) if (objective.done()) done++;
        int filled = objectives.isEmpty() ? barWidth : Math.round(barWidth * done / (float) objectives.size());
        graphics.fill(barX, barY, barX + filled, barY + 6, FlightTutorial.allCompleted() ? GREEN : CYAN);
        graphics.text(font, fit(status, barWidth), barX, barY - 12, MUTED, true);

        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        pose.popMatrix();
        scaler.pop(graphics);
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
        return super.mouseScrolled(scaler.mouseX(mouseX), scaler.mouseY(mouseY), deltaX, deltaY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    private void drawObjectives(GuiGraphicsExtractor graphics) {
        int cardX = x(12);
        int cardY = y(60);
        int cardWidth = w(380);
        int cardHeight = h(288);
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, CARD);
        graphics.outline(cardX, cardY, cardWidth, cardHeight, BORDER);
        graphics.text(font, "LIVE OBJECTIVES", cardX + 8, cardY + 6, CYAN, true);
        List<FlightTutorial.Objective> objectives = FlightTutorial.objectives();
        int rowY = cardY + h(22);
        int step = Math.max(20, Math.round(34 * scaleY));
        for (int i = 0; i < objectives.size(); i++) {
            FlightTutorial.Objective objective = objectives.get(i);
            int color = objective.done() ? GREEN : i == firstPending(objectives) ? AMBER : TEXT;
            graphics.text(font, objective.done() ? "[X] " : "[ ] ", cardX + w(8), rowY, color, true);
            graphics.text(font, fit(objective.label(), cardWidth - w(28)),
                    cardX + w(28), rowY, color, true);
            graphics.text(font, fit(objective.detail(), cardWidth - w(28)),
                    cardX + w(28), rowY + h(10), MUTED, false);
            rowY += step;
        }
    }

    private int firstPending(List<FlightTutorial.Objective> objectives) {
        for (int i = 0; i < objectives.size(); i++) {
            if (!objectives.get(i).done()) return i;
        }
        return -1;
    }

    private void drawBriefing(GuiGraphicsExtractor graphics) {
        int cardX = x(404);
        int cardY = y(60);
        int cardWidth = w(224);
        int cardHeight = h(288);
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, CARD);
        graphics.outline(cardX, cardY, cardWidth, cardHeight, BORDER);
        graphics.text(font, fit(FlightTutorial.stage().title() + " BRIEF", cardWidth - w(16)),
                cardX + w(8), cardY + h(6), AMBER, true);
        graphics.text(font, fit(FlightTutorial.stage().brief(), cardWidth - w(16)),
                cardX + w(8), cardY + h(20), MUTED, false);
        graphics.text(font, fit("AIRCRAFT  " + FlightLineClient.getAircraftType().displayName(),
                cardWidth - w(16)), cardX + w(8), cardY + h(42), TEXT, true);
        graphics.text(font, fit(String.format(Locale.ROOT, "IAS %d KT   ALT %d FT",
                FlightHud.currentSpeedKnots(), FlightHud.currentAltitudeFeet()), cardWidth - w(16)),
                cardX + w(8), cardY + h(56), CYAN, true);
        graphics.text(font, fit(String.format(Locale.ROOT, "HDG %03d°   VS %+d",
                FlightHud.trackDegrees(), FlightHud.currentVsFpm()), cardWidth - w(16)),
                cardX + w(8), cardY + h(70), TEXT, true);
        graphics.text(font, "AUTO ACTIONS", cardX + w(8), cardY + h(94), AMBER, true);
        graphics.text(font, fit("Buttons send the same server-authoritative", cardWidth - w(16)),
                cardX + w(8), cardY + h(108), MUTED, false);
        graphics.text(font, "payloads as the cockpit panels.", cardX + w(8), cardY + h(118), MUTED, false);
        graphics.text(font, fit(FlightLineClient.flightTutorial().getTranslatedKeyMessage().getString()
                        + " reopens this lesson while flying.", cardWidth - w(16)),
                cardX + w(8), cardY + h(132), MUTED, false);
        if (FlightHud.isCrashed()) {
            graphics.text(font, "AIRFRAME DOWN", cardX + w(8), cardY + cardHeight - h(12), RED, true);
        } else if (FlightTutorial.allCompleted()) {
            graphics.text(font, "ALL STAGES COMPLETE", cardX + w(8), cardY + cardHeight - h(12), GREEN, true);
        }
    }

    private int x(int base) {
        return panelLeft + Math.round(base * scaleX);
    }

    private int y(int base) {
        return panelTop + Math.round(base * scaleY);
    }

    private int w(int base) {
        return Math.max(1, Math.round(base * scaleX));
    }

    private int h(int base) {
        return Math.max(1, Math.round(base * scaleY));
    }

    private String fit(String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
