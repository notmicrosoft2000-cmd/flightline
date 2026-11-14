package dev.flightline.client;

import dev.flightline.network.ConfigureAutopilotPayload;
import dev.flightline.network.ConfigureIlsPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.ToggleAutolandPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
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
    private static int cachedThresholdX;
    private static int cachedThresholdZ;
    private static int cachedRunwayHeading;
    private static boolean thresholdDefaultsInitialized;

    private @Nullable EditBox headingBox;
    private @Nullable EditBox altitudeBox;
    private @Nullable EditBox vsBox;
    private @Nullable EditBox speedBox;
    private @Nullable EditBox thresholdXBox;
    private @Nullable EditBox thresholdZBox;
    private @Nullable EditBox runwayHeadingBox;
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
        if (!thresholdDefaultsInitialized) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                cachedThresholdX = player.blockPosition().getX();
                cachedThresholdZ = player.blockPosition().getZ();
                cachedRunwayHeading = Math.floorMod(Math.round(player.getYRot()), 360);
            }
            thresholdDefaultsInitialized = true;
        }
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

        addRenderableWidget(tabButton("MCP", 0, 8, left, top, scaleX));
        addRenderableWidget(tabButton("NAV / ILS", 1, 80, left, top, scaleX));
        addRenderableWidget(tabButton("WX", 2, 174, left, top, scaleX));
        addRenderableWidget(tabButton("KEYS", 3, 240, left, top, scaleX));

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
            thresholdXBox = coordinateInput(left, top, scaleX, scaleY, 38, "X", cachedThresholdX);
            thresholdZBox = coordinateInput(left, top, scaleX, scaleY, 126, "Z", cachedThresholdZ);
            runwayHeadingBox = coordinateInput(left, top, scaleX, scaleY, 214, "HDG", cachedRunwayHeading);
            addRenderableWidget(Button.builder(Component.literal("- 0.1°"), b -> adjustGlideSlope(-0.1f))
                    .bounds(place(left, 54, scaleX), place(top, 95, scaleY),
                            size(76, scaleX), size(18, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal("+ 0.1°"), b -> adjustGlideSlope(0.1f))
                    .bounds(place(left, 190, scaleX), place(top, 95, scaleY),
                            size(76, scaleX), size(18, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal("SET RUNWAY / ILS"), b -> setIls())
                    .bounds(place(left, 20, scaleX), place(top, 122, scaleY),
                            size(138, scaleX), size(20, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal("USE MY POSITION"), b -> useCurrentPosition())
                    .bounds(place(left, 162, scaleX), place(top, 122, scaleY),
                            size(138, scaleX), size(20, scaleY)).build());
            addRenderableWidget(Button.builder(Component.literal("CLEAR ILS"), b -> clearIls())
                    .bounds(place(left, 20, scaleX), place(top, 148, scaleY),
                            size(280, scaleX), size(18, scaleY)).build());
            String autolandText = FlightHud.autopilotGuidance().autolandArmed()
                    ? "DISARM AUTOLAND"
                    : "ARM ILS AUTOLAND";
            Button autolandButton = Button.builder(Component.literal(autolandText), b -> {
                        ClientPlayNetworking.send(new ToggleAutolandPayload());
                        ilsMessage = "";
                    })
                    .bounds(place(left, 20, scaleX), place(top, 196, scaleY),
                            size(280, scaleX), size(22, scaleY)).build();
            autolandButton.active = FlightLineClient.isFlightEnabled()
                    && (FlightHud.isIlsActive() || FlightHud.autopilotGuidance().autolandArmed());
            addRenderableWidget(autolandButton);
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
                        size(switch (page) {
                            case 0 -> 64;
                            case 1 -> 88;
                            case 2 -> 52;
                            default -> 62;
                        }, scaleX), size(17, panelHeight() / 250.0))
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

    private void saveIlsValues() {
        cachedThresholdX = parseCoordinate(thresholdXInput(), "threshold X");
        cachedThresholdZ = parseCoordinate(thresholdZInput(), "threshold Z");
        cachedRunwayHeading = Math.floorMod(parseCoordinate(runwayHeadingInput(), "runway heading"), 360);
    }

    private int parseCoordinate(EditBox input, String label) {
        try {
            return Integer.parseInt(input.getValue().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a whole-number " + label + ".");
        }
    }

    private void useCurrentPosition() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            ilsMessage = "Player position is unavailable.";
            return;
        }
        thresholdXInput().setValue(String.valueOf(player.blockPosition().getX()));
        thresholdZInput().setValue(String.valueOf(player.blockPosition().getZ()));
        runwayHeadingInput().setValue(String.valueOf(Math.floorMod(Math.round(player.getYRot()), 360)));
        ilsMessage = "Threshold coordinates filled from your position.";
    }

    private EditBox coordinateInput(int left, int top, double scaleX, double scaleY, int x,
                                    String label, int initialValue) {
        int fieldX = place(left, x, scaleX);
        EditBox input = new EditBox(font, fieldX, place(top, 60, scaleY),
                size(54, scaleX), size(18, scaleY), Component.literal(label));
        input.setValue(String.valueOf(initialValue));
        input.setMaxLength(10);
        addRenderableWidget(input);
        return input;
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
        try {
            saveIlsValues();
        } catch (IllegalArgumentException exception) {
            ilsMessage = exception.getMessage();
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(true, cachedGlideSlope,
                cachedThresholdX, cachedThresholdZ, cachedRunwayHeading));
        ilsMessage = "Runway reference submitted.";
    }

    private void clearIls() {
        if (!FlightLineClient.isFlightEnabled()) {
            ilsMessage = "Enable flight mode before changing ILS.";
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(false, cachedGlideSlope,
                cachedThresholdX, cachedThresholdZ, cachedRunwayHeading));
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
                : activePage == 2 ? compact ? "WX" : "WEATHER / TURBULENCE"
                : activePage == 3 ? compact ? "KEYS" : "FLIGHT CONTROLS"
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
            graphics.text(font, "THRESHOLD X", place(left, 38, scaleX), place(top, 48, scaleY), 0xFF9FC4D0, true);
            graphics.text(font, "THRESHOLD Z", place(left, 126, scaleX), place(top, 48, scaleY), 0xFF9FC4D0, true);
            graphics.text(font, "RUNWAY HDG", place(left, 214, scaleX), place(top, 48, scaleY), 0xFF9FC4D0, true);
            graphics.centeredText(font, String.format(Locale.ROOT,
                            "GS %.1f°  ·  HDG 0=S 90=W 180=N 270=E", cachedGlideSlope),
                    width / 2, place(top, 82, scaleY), 0xFFFF6CD9);
            String ilsStatus = !ilsMessage.isEmpty() ? ilsMessage
                    : FlightHud.isIlsActive()
                            ? required(String.format(Locale.ROOT, "LOC %+.1f°  GS %+.1f°",
                                    FlightHud.localizerDeviation(), FlightHud.glideslopeDeviation()))
                            : "Enter runway X/Z; threshold height is read from terrain.";
            graphics.centeredText(font, fitText(font, ilsStatus, Math.max(1, panelWidth() - 24)),
                    width / 2, place(top, 174, scaleY), 0xFFB6C8D1);
            String armedStatus = FlightHud.autopilotGuidance().autolandArmed()
                    ? "ARMED · 130 KT · GEAR / FLAPS MANAGED"
                    : FlightLineClient.isFlightEnabled()
                            ? "Arm airborne inside the ILS approach area."
                            : "Enable flight mode to configure or arm.";
            graphics.centeredText(font, fitText(font, armedStatus, Math.max(1, panelWidth() - 24)),
                    width / 2, place(top, 187, scaleY),
                    FlightHud.autopilotGuidance().autolandArmed() ? 0xFF62D89B : 0xFF83959B);
        } else if (activePage == 2) {
            FlightInstrumentPayload.WeatherGuidance weather = FlightHud.weatherGuidance();
            String condition = switch (weather.condition()) {
                case 2 -> "THUNDERSTORM";
                case 1 -> "RAIN";
                default -> "CLEAR";
            };
            graphics.centeredText(font, condition + "  |  WIND " + weather.windSpeedKnots() + " KT",
                    width / 2, place(top, 57, scaleY),
                    weather.condition() == 2 ? 0xFFFF8B52 : weather.condition() == 1 ? 0xFF72C8F0 : 0xFF62D89B);
            graphics.centeredText(font, String.format(Locale.ROOT, "DIRECTION %03d°",
                            weather.windDirectionDegrees()),
                    width / 2, place(top, 76, scaleY), 0xFFB6C8D1);
            graphics.centeredText(font, "TURBULENCE  " + weather.turbulencePercent() + "%",
                    width / 2, place(top, 104, scaleY),
                    weather.turbulencePercent() >= 70 ? 0xFFFF5148
                            : weather.turbulencePercent() >= 35 ? 0xFFFFCE52 : 0xFF62D89B);
            int gaugeX = place(left, 36, scaleX);
            int gaugeY = place(top, 119, scaleY);
            int gaugeWidth = size(248, scaleX);
            graphics.fill(gaugeX, gaugeY, gaugeX + gaugeWidth, gaugeY + size(7, scaleY), 0xFF263540);
            graphics.fill(gaugeX, gaugeY,
                    gaugeX + Math.round(gaugeWidth * weather.turbulencePercent() / 100.0f),
                    gaugeY + size(7, scaleY),
                    weather.turbulencePercent() >= 70 ? 0xFFFF5148
                            : weather.turbulencePercent() >= 35 ? 0xFFFFCE52 : 0xFF62D89B);
            graphics.centeredText(font, "ESTIMATED VISIBILITY  " + weather.visibilityBlocks() + " BLOCKS",
                    width / 2, place(top, 145, scaleY), 0xFFB6C8D1);
            String antiIceStatus = weather.antiIceEffective() ? "CLEARING"
                    : weather.antiIceActive() ? "ON · WAITING FOR ENGINE POWER" : "OFF";
            graphics.centeredText(font, "ICING  " + weather.icingPercent() + "%  ·  ANTI-ICE "
                            + antiIceStatus,
                    width / 2, place(top, 161, scaleY),
                    weather.icingPercent() >= 20 ? 0xFFFFCE52 : 0xFFB6C8D1);
            graphics.centeredText(font, weather.condition() == 2
                            ? "Windshear possible: maintain attitude and airspeed."
                            : weather.condition() == 1
                                    ? "Rain increases gusts and reduces reported visibility."
                                    : "Wind and light turbulence remain active in clear weather.",
                    width / 2, place(top, 180, scaleY), 0xFF83959B);
            graphics.centeredText(font, "Anti-ice removes buildup only while an engine is running.",
                    width / 2, place(top, 196, scaleY), 0xFF83959B);
        } else {
            drawKeyRow(graphics, left, top, scaleX, 54, "Gear", FlightLineClient.keyName(FlightLineClient.toggleGear()));
            drawKeyRow(graphics, left, top, scaleX, 74, "Autopilot panel", FlightLineClient.keyName(FlightLineClient.toggleAutopilot()));
            drawKeyRow(graphics, left, top, scaleX, 94, "Engines / inventory",
                    FlightLineClient.keyName(FlightLineClient.inventory()) + " / "
                            + FlightLineClient.keyName(FlightLineClient.shutdownEngines()));
            drawKeyRow(graphics, left, top, scaleX, 114, "Throttle",
                    FlightLineClient.keyName(FlightLineClient.throttleUp()) + " / "
                            + FlightLineClient.keyName(FlightLineClient.throttleDown()));
            drawKeyRow(graphics, left, top, scaleX, 134, "Rudder",
                    FlightLineClient.keyName(FlightLineClient.rudderLeft()) + " / "
                            + FlightLineClient.keyName(FlightLineClient.rudderRight()));
            drawKeyRow(graphics, left, top, scaleX, 154, "Flaps",
                    FlightLineClient.keyName(FlightLineClient.flapsIncrease()) + " / "
                            + FlightLineClient.keyName(FlightLineClient.flapsDecrease()));
            drawKeyRow(graphics, left, top, scaleX, 174, "Spoilers",
                    FlightLineClient.keyName(FlightLineClient.spoilersToggle()));
            drawKeyRow(graphics, left, top, scaleX, 194, "Pushback / brakes",
                    FlightLineClient.keyName(FlightLineClient.pushback()) + " / "
                            + FlightLineClient.keyName(FlightLineClient.brakes()));
            drawKeyRow(graphics, left, top, scaleX, 214, "Radar / displays / prompts",
                    FlightLineClient.keyName(FlightLineClient.flightRadar()) + "/"
                            + FlightLineClient.keyName(FlightLineClient.togglePfd()) + "/"
                            + FlightLineClient.keyName(FlightLineClient.toggleEicas()) + "/"
                            + FlightLineClient.keyName(FlightLineClient.toggleNdRadar()) + "/"
                            + FlightLineClient.keyName(FlightLineClient.toggleRadioAltimeter()) + "/"
                            + FlightLineClient.keyName(FlightLineClient.toggleKeybindHints()));
            drawKeyRow(graphics, left, top, scaleX, 224, "Anti-ice", FlightLineClient.keyName(FlightLineClient.antiIce()));
            graphics.centeredText(font, "FLAPS add lift and drag · SPOILERS dump lift and slow descent",
                    width / 2, place(top, 239, panelHeight() / 250.0), 0xFF83959B);
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

    private EditBox thresholdXInput() {
        return java.util.Objects.requireNonNull(thresholdXBox, "ILS page is not initialized");
    }

    private EditBox thresholdZInput() {
        return java.util.Objects.requireNonNull(thresholdZBox, "ILS page is not initialized");
    }

    private EditBox runwayHeadingInput() {
        return java.util.Objects.requireNonNull(runwayHeadingBox, "ILS page is not initialized");
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
