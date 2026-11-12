package dev.flightline.client;

import dev.flightline.network.ConfigureAutopilotPayload;
import dev.flightline.network.ConfigureIlsPayload;
import dev.flightline.network.FlightInstrumentPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

public class AutopilotScreen extends Screen {
    private static int cachedHeading = 0;
    private static int cachedAltitude = 2000;
    private static int cachedVs = 1000;
    private static int cachedSpeed = 160;
    private static float cachedGlideSlope = 3.0f;

    private @Nullable EditBox headingBox;
    private @Nullable EditBox altitudeBox;
    private @Nullable EditBox vsBox;
    private @Nullable EditBox speedBox;
    private @Nullable Button apMasterBtn;
    private boolean engaged;
    private int activePage;
    private String ilsMessage = "";

    public AutopilotScreen(boolean currentlyEngaged, int currentHeading, int currentAltitudeFeet,
                           int currentVsFpm, int currentSpeedKnots) {
        super(Component.literal("Mode Control Panel"));
        this.engaged = currentlyEngaged;
        cachedGlideSlope = FlightHud.ilsReferenceGlideSlope();
        if (currentHeading >= 0) cachedHeading = Math.floorMod(currentHeading, 360);
        if (currentAltitudeFeet > 0) cachedAltitude = Math.max(100, currentAltitudeFeet);
        if (Math.abs(currentVsFpm) > 50) cachedVs = currentVsFpm;
        if (currentSpeedKnots > 0) cachedSpeed = currentSpeedKnots;
    }

    @Override
    protected void init() {
        super.init();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        double scaleX = panelWidth / 320.0;
        double scaleY = panelHeight / 250.0;
        String minusTen = width < 260 ? "-" : "-10";
        String plusTen = width < 260 ? "+" : "+10";
        String minusFiveHundred = width < 260 ? "-" : "-500";
        String plusFiveHundred = width < 260 ? "+" : "+500";

        addRenderableWidget(tabButton("MCP", 0, 18, left, top, scaleX));
        addRenderableWidget(tabButton("NAV / ILS", 1, 110, left, top, scaleX));
        addRenderableWidget(tabButton("KEYS", 2, 222, left, top, scaleX));

        if (activePage == 0) {
        // HEADING controls
        headingBox = new EditBox(font, place(left, 20, scaleX), place(top, 60, scaleY),
                size(42, scaleX), size(16, scaleY), Component.literal("HDG"));
        headingInput().setValue(required(String.format(Locale.ROOT, "%03d", cachedHeading)));
        headingInput().setMaxLength(3);
        addRenderableWidget(headingInput());

        addRenderableWidget(Button.builder(Component.literal(minusTen), b -> adjustHeading(-10))
                .bounds(place(left, 20, scaleX), place(top, 80, scaleY),
                        size(24, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal(plusTen), b -> adjustHeading(10))
                .bounds(place(left, 46, scaleX), place(top, 80, scaleY),
                        size(24, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal("SYNC"), b -> syncHeading())
                .bounds(place(left, 20, scaleX), place(top, 98, scaleY),
                        size(50, scaleX), size(16, scaleY)).build());

        // ALTITUDE controls
        altitudeBox = new EditBox(font, place(left, 90, scaleX), place(top, 60, scaleY),
                size(54, scaleX), size(16, scaleY), Component.literal("ALT"));
        altitudeInput().setValue(required(String.valueOf(cachedAltitude)));
        altitudeInput().setMaxLength(5);
        addRenderableWidget(altitudeInput());

        addRenderableWidget(Button.builder(Component.literal(minusFiveHundred), b -> adjustAltitude(-500))
                .bounds(place(left, 90, scaleX), place(top, 80, scaleY),
                        size(30, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal(plusFiveHundred), b -> adjustAltitude(500))
                .bounds(place(left, 122, scaleX), place(top, 80, scaleY),
                        size(30, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal("SYNC"), b -> syncAltitude())
                .bounds(place(left, 90, scaleX), place(top, 98, scaleY),
                        size(62, scaleX), size(16, scaleY)).build());

        // VERTICAL SPEED controls
        vsBox = new EditBox(font, place(left, 170, scaleX), place(top, 60, scaleY),
                size(54, scaleX), size(16, scaleY), Component.literal("V/S"));
        verticalSpeedInput().setValue(required(String.valueOf(cachedVs)));
        verticalSpeedInput().setMaxLength(5);
        addRenderableWidget(verticalSpeedInput());

        addRenderableWidget(Button.builder(Component.literal(minusFiveHundred), b -> adjustVs(-500))
                .bounds(place(left, 170, scaleX), place(top, 80, scaleY),
                        size(30, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal(plusFiveHundred), b -> adjustVs(500))
                .bounds(place(left, 202, scaleX), place(top, 80, scaleY),
                        size(30, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal("LEVEL"), b -> setVsZero())
                .bounds(place(left, 170, scaleX), place(top, 98, scaleY),
                        size(62, scaleX), size(16, scaleY)).build());

        // AIRSPEED controls
        speedBox = new EditBox(font, place(left, 250, scaleX), place(top, 60, scaleY),
                size(48, scaleX), size(16, scaleY), Component.literal("IAS"));
        speedInput().setValue(required(String.valueOf(cachedSpeed)));
        speedInput().setMaxLength(3);
        addRenderableWidget(speedInput());

        addRenderableWidget(Button.builder(Component.literal(minusTen), b -> adjustSpeed(-10))
                .bounds(place(left, 250, scaleX), place(top, 80, scaleY),
                        size(23, scaleX), size(16, scaleY)).build());
        addRenderableWidget(Button.builder(Component.literal(plusTen), b -> adjustSpeed(10))
                .bounds(place(left, 275, scaleX), place(top, 80, scaleY),
                        size(23, scaleX), size(16, scaleY)).build());

        // MASTER AP ENGAGE / DISENGAGE BUTTON
        apMasterBtn = Button.builder(getApButtonText(), b -> toggleAp())
                .bounds(place(left, 36, scaleX), place(top, 136, scaleY),
                        size(114, scaleX), size(22, scaleY)).build();
        addRenderableWidget(apMasterButton());
        } else if (activePage == 1) {
            String setIlsText = width < 300 ? "SET ILS" : "SET THRESHOLD HERE";
            String clearIlsText = width < 300 ? "CLEAR" : "CLEAR ILS";
            addRenderableWidget(Button.builder(Component.literal("- 0.1°"), b -> adjustGlideSlope(-0.1f))
                    .bounds(place(left, 35, scaleX), place(top, 90, scaleY),
                            size(76, scaleX), size(20, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal("+ 0.1°"), b -> adjustGlideSlope(0.1f))
                    .bounds(place(left, 209, scaleX), place(top, 90, scaleY),
                            size(76, scaleX), size(20, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal(setIlsText), b -> setIls())
                    .bounds(place(left, 30, scaleX), place(top, 144, scaleY),
                            size(124, scaleX), size(24, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal(clearIlsText), b -> clearIls())
                    .bounds(place(left, 166, scaleX), place(top, 144, scaleY),
                            size(124, scaleX), size(24, scaleY)).build());
        }

        String applyText = width < 280 ? "DONE" : "APPLY & CLOSE";
        addRenderableWidget(Button.builder(Component.literal(applyText), b -> onClose())
                .bounds(place(left, 170, scaleX), place(top, 226, scaleY),
                        size(110, scaleX), size(20, scaleY)).build());
    }

    private Button tabButton(String label, int page, int x, int left, int top, double scaleX) {
        String compactLabel = width < 300 && page == 1 ? "NAV" : label;
        String buttonLabel = activePage == page ? "[" + compactLabel + "]" : compactLabel;
        return Button.builder(Component.literal(buttonLabel), button -> switchPage(page))
                .bounds(place(left, x, scaleX), place(top, 25, panelHeight() / 250.0),
                        size(label.equals("NAV / ILS") ? 96 : 72, scaleX), size(17, panelHeight() / 250.0))
                .build();
    }

    private void switchPage(int page) {
        if (activePage == page) return;
        if (activePage == 0) saveAutopilotValues();
        activePage = page;
        clearWidgets();
        init();
    }

    private void saveAutopilotValues() {
        cachedHeading = parseHeading();
        cachedAltitude = parseAltitude();
        cachedVs = parseVs();
        cachedSpeed = parseSpeed();
    }

    private void adjustGlideSlope(float delta) {
        cachedGlideSlope = Math.max(1.0f, Math.min(6.0f,
                Math.round((cachedGlideSlope + delta) * 10.0f) / 10.0f));
    }

    private void setIls() {
        if (!FlightLineClient.isFlightEnabled()) {
            ilsMessage = "Enable flight mode before setting a runway reference.";
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(true, cachedGlideSlope));
        ilsMessage = "Runway reference sent.";
    }

    private void clearIls() {
        if (!FlightLineClient.isFlightEnabled()) {
            ilsMessage = "Enable flight mode before changing ILS.";
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(false, cachedGlideSlope));
        ilsMessage = "Clear request sent.";
    }

    private Component getApButtonText() {
        if (width < 280) return Component.literal(engaged ? "A/P ON" : "A/P OFF");
        return Component.literal(engaged ? "A/P [ENGAGED]" : "A/P [OFF]");
    }

    private void toggleAp() {
        engaged = !engaged;
        apMasterButton().setMessage(getApButtonText());
    }

    private void adjustHeading(int delta) {
        int current = parseHeading();
        int updated = Math.floorMod(current + delta, 360);
        headingInput().setValue(required(String.format(Locale.ROOT, "%03d", updated)));
    }

    private void syncHeading() {
        int current = Math.floorMod(Math.round(FlightHud.currentHeading()), 360);
        headingInput().setValue(required(String.format(Locale.ROOT, "%03d", current)));
    }

    private void adjustAltitude(int delta) {
        int current = parseAltitude();
        int updated = Math.max(0, Math.min(35000, current + delta));
        altitudeInput().setValue(required(String.valueOf(updated)));
    }

    private void syncAltitude() {
        int current = Math.max(0, FlightHud.currentAltitudeFeet());
        altitudeInput().setValue(required(String.valueOf(current)));
    }

    private void adjustVs(int delta) {
        int current = parseVs();
        int updated = Math.max(-4000, Math.min(4000, current + delta));
        verticalSpeedInput().setValue(required(String.valueOf(updated)));
    }

    private void setVsZero() {
        verticalSpeedInput().setValue(required("0"));
    }

    private void adjustSpeed(int delta) {
        int current = parseSpeed();
        int updated = Math.max(60, Math.min(320, current + delta));
        speedInput().setValue(required(String.valueOf(updated)));
    }

    private int parseHeading() {
        try { return Math.max(0, Math.min(359, Integer.parseInt(headingInput().getValue().trim()))); }
        catch (NumberFormatException e) { return cachedHeading; }
    }

    private int parseAltitude() {
        try { return Math.max(0, Math.min(35000, Integer.parseInt(altitudeInput().getValue().trim()))); }
        catch (NumberFormatException e) { return cachedAltitude; }
    }

    private int parseVs() {
        try { return Math.max(-4000, Math.min(4000, Integer.parseInt(verticalSpeedInput().getValue().trim()))); }
        catch (NumberFormatException e) { return cachedVs; }
    }

    private int parseSpeed() {
        try { return Math.max(60, Math.min(320, Integer.parseInt(speedInput().getValue().trim()))); }
        catch (NumberFormatException e) { return cachedSpeed; }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        double scaleX = panelWidth / 320.0;
        double scaleY = panelHeight / 250.0;

        // Dark avionics cockpit panel
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xEE121820);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFF4EB8C8);
        int headerBottom = place(top, 22, scaleY);
        graphics.fill(left, top, left + panelWidth, headerBottom, 0xFF1C2733);
        graphics.horizontalLine(left, left + panelWidth, headerBottom, 0xFF4EB8C8);

        // Header
        boolean compact = width < 280;
        String title = activePage == 1 ? compact ? "NAV / ILS" : "NAVIGATION / ILS"
                : activePage == 2 ? compact ? "KEYS" : "FLIGHT CONTROLS"
                : compact ? "AUTOPILOT" : "AUTOPILOT / FLIGHT DIRECTOR (MCP)";
        graphics.centeredText(font, title, width / 2, place(top, 7, scaleY), 0xFF72E6F5);

        if (activePage == 0) {
            graphics.text(font, compact ? "HDG" : "HEADING", place(left, 20, scaleX),
                    place(top, 48, scaleY), 0xFF9FC4D0, true);
            graphics.text(font, "ALT", place(left, 90, scaleX), place(top, 48, scaleY), 0xFF9FC4D0, true);
            graphics.text(font, "V/S", place(left, 170, scaleX), place(top, 48, scaleY), 0xFF9FC4D0, true);
            graphics.text(font, compact ? "IAS" : "SPEED", place(left, 250, scaleX),
                    place(top, 48, scaleY), 0xFF9FC4D0, true);
            int statusColor = engaged ? 0xFF62D89B : 0xFF70808A;
            int statusX = place(left, 24, scaleX);
            int statusY = place(top, 167, scaleY);
            graphics.fill(statusX, statusY, statusX + size(8, scaleX), statusY + size(10, scaleY), statusColor);
            graphics.outline(statusX, statusY, size(8, scaleX), size(10, scaleY), 0xFF2A3A46);
            graphics.text(font, engaged ? "AUTOPILOT ENGAGED" : "AUTOPILOT STANDBY",
                    place(left, 38, scaleX), statusY + 1, 0xFFB6C8D1, true);
            FlightInstrumentPayload.AutopilotGuidance guidance = FlightHud.autopilotGuidance();
            String headingAltitude = String.format(Locale.ROOT, "HDG %03.0f°  |  ALT %,d FT",
                    guidance.targetHeadingDegrees(), guidance.targetAltitudeFeet());
            String verticalSpeedAirspeed = String.format(Locale.ROOT, "V/S %+d FPM  |  IAS %d KT",
                    guidance.targetVerticalSpeedFpm(), guidance.targetAirspeedKnots());
            String autothrottle = String.format(Locale.ROOT, "%s  |  THR HOLD ±%.0f KT",
                    engaged ? "A/T AUTO" : "A/T STBY", guidance.autoThrottleDeadbandKnots());
            int configColor = engaged ? 0xFF62D89B : 0xFF9BAAB1;
            graphics.text(font, fitText(font, headingAltitude, panelWidth - 40),
                    place(left, 24, scaleX), place(top, 182, scaleY), configColor, true);
            graphics.text(font, fitText(font, verticalSpeedAirspeed, panelWidth - 40),
                    place(left, 24, scaleX), place(top, 196, scaleY), 0xFFB6C8D1, true);
            graphics.text(font, fitText(font, autothrottle, panelWidth - 40),
                    place(left, 24, scaleX), place(top, 210, scaleY), 0xFFB6C8D1, true);
        } else if (activePage == 1) {
            graphics.centeredText(font, compact ? "ILS APPROACH" : "INSTRUMENT LANDING SYSTEM", width / 2,
                    place(top, 62, scaleY), 0xFFB6C8D1);
            graphics.centeredText(font, "Glide slope: " + String.format(Locale.ROOT, "%.1f°", cachedGlideSlope),
                    width / 2, place(top, 78, scaleY), 0xFFFF6CD9);
            String ilsStatus = !ilsMessage.isEmpty() ? ilsMessage
                    : !FlightLineClient.isFlightEnabled() ? "Enable flight mode to configure an approach."
                    : FlightHud.isIlsActive()
                            ? required(String.format(Locale.ROOT, "REFERENCE ACTIVE  |  LOC %+.1f°  GS %+.1f°",
                                    FlightHud.localizerDeviation(), FlightHud.glideslopeDeviation()))
                            : "Stand at the runway threshold and face down the runway.";
            graphics.centeredText(font, fitText(font, ilsStatus, Math.max(1, panelWidth() - 24)),
                    width / 2, place(top, 112, scaleY), 0xFFB6C8D1);
            graphics.centeredText(font, fitText(font,
                            "SET saves this position and heading as the runway reference.",
                            Math.max(1, panelWidth() - 24)),
                    width / 2, place(top, 128, scaleY), 0xFF83959B);
        } else {
            drawKeyRow(graphics, left, top, scaleX, 54, "Gear", FlightLineClient.keyName(FlightLineClient.toggleGear()));
            drawKeyRow(graphics, left, top, scaleX, 74, "Autopilot panel", FlightLineClient.keyName(FlightLineClient.toggleAutopilot()));
            drawKeyRow(graphics, left, top, scaleX, 94, "Engines", FlightLineClient.keyName(FlightLineClient.startEngines())
                    + " / " + FlightLineClient.keyName(FlightLineClient.shutdownEngines()));
            drawKeyRow(graphics, left, top, scaleX, 114, "Flaps", FlightLineClient.keyName(FlightLineClient.flapsUp())
                    + " / " + FlightLineClient.keyName(FlightLineClient.flapsDown()));
            drawKeyRow(graphics, left, top, scaleX, 134, "Spoilers", FlightLineClient.keyName(FlightLineClient.spoilersExtend())
                    + " / " + FlightLineClient.keyName(FlightLineClient.spoilersRetract()));
            drawKeyRow(graphics, left, top, scaleX, 154, "Pushback / brakes",
                    FlightLineClient.keyName(FlightLineClient.pushback()) + " / " + FlightLineClient.keyName(FlightLineClient.brakes()));
            drawKeyRow(graphics, left, top, scaleX, 174, "Inventory", FlightLineClient.keyName(FlightLineClient.inventory()));
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawKeyRow(GuiGraphicsExtractor graphics, int left, int top, double scaleX,
                            int y, String label, String key) {
        graphics.text(font, label, place(left, 28, scaleX), place(top, y, panelHeight() / 250.0),
                0xFFB6C8D1, true);
        graphics.text(font, key, place(left, 210, scaleX), place(top, y, panelHeight() / 250.0),
                0xFF72E6F5, true);
    }

    private int panelWidth() {
        return Math.max(1, Math.min(320, width - 16));
    }

    private int panelHeight() {
        return Math.max(1, Math.min(250, height - 16));
    }

    private static int place(int origin, int offset, double scale) {
        return origin + Math.round((float) (offset * scale));
    }

    private static int size(int value, double scale) {
        return Math.max(1, Math.round((float) (value * scale)));
    }

    private static String fitText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = required(fitted.substring(0, fitted.length() - 1));
        }
        return fitted;
    }

    private EditBox headingInput() {
        return java.util.Objects.requireNonNull(headingBox, "Autopilot screen is not initialized");
    }

    private EditBox altitudeInput() {
        return java.util.Objects.requireNonNull(altitudeBox, "Autopilot screen is not initialized");
    }

    private EditBox verticalSpeedInput() {
        return java.util.Objects.requireNonNull(vsBox, "Autopilot screen is not initialized");
    }

    private EditBox speedInput() {
        return java.util.Objects.requireNonNull(speedBox, "Autopilot screen is not initialized");
    }

    private Button apMasterButton() {
        return java.util.Objects.requireNonNull(apMasterBtn, "Autopilot screen is not initialized");
    }

    private static String required(@Nullable String value) {
        return java.util.Objects.requireNonNull(value);
    }

    @Override
    public void onClose() {
        if (activePage == 0) saveAutopilotValues();
        ClientPlayNetworking.send(new ConfigureAutopilotPayload(
                engaged, cachedHeading, cachedAltitude, cachedVs, cachedSpeed
        ));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
