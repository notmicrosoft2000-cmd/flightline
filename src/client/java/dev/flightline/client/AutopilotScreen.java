package dev.flightline.client;

import dev.flightline.flight.WeatherSimulation;
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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/** Graphical mode control panel with live flight-director, ILS and weather pages. */
public class AutopilotScreen extends Screen {
    private static final int BASE_WIDTH = 600;
    private static final int BASE_HEIGHT = 400;

    private static final int TEXT = 0xFFDCE8EE;
    private static final int MUTED = 0xFF8198A3;
    private static final int CYAN = 0xFF4EB8C8;
    private static final int GREEN = 0xFF62D89B;
    private static final int AMBER = 0xFFFFC44D;
    private static final int RED = 0xFFFF5A50;
    private static final int PURPLE = 0xFFC678FF;
    private static final int PANEL_FILL = 0xF0121A22;
    private static final int CARD_FILL = 0xE3101920;
    private static final int BORDER = 0xFF2A3D49;

    private static int cachedHeading;
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

    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private float scaleX;
    private float scaleY;

    public AutopilotScreen(boolean currentlyEngaged, int currentHeading, int currentAltitudeFeet,
                           int currentVsFpm, int currentSpeedKnots) {
        super(Component.literal("Mode Control Panel"));
        this.engaged = currentlyEngaged;
        cachedGlideSlope = FlightHud.ilsReferenceGlideSlope();
        if (currentHeading >= 0) cachedHeading = Math.floorMod(currentHeading, 360);
        if (currentAltitudeFeet > 0) cachedAltitude = clamp(currentAltitudeFeet, 100, 35000);
        if (Math.abs(currentVsFpm) > 50) cachedVs = clamp(currentVsFpm, -4000, 4000);
        if (currentSpeedKnots > 0) cachedSpeed = clamp(currentSpeedKnots, 60, 320);
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
        scaler.update(width, height);
        panelWidth = BASE_WIDTH;
        panelHeight = BASE_HEIGHT;
        panelLeft = 8;
        panelTop = 8;
        scaleX = 1.0f;
        scaleY = 1.0f;

        addRenderableWidget(tabButton("MCP", 0, 12, 108));
        addRenderableWidget(tabButton("NAV / ILS", 1, 126, 112));
        addRenderableWidget(tabButton("WX", 2, 244, 76));
        addRenderableWidget(tabButton("SYS", 3, 326, 84));

        if (activePage == 0) {
            initMcpPage();
        } else if (activePage == 1) {
            initNavPage();
        } else if (activePage == 2) {
            initWeatherPage();
        } else {
            initSystemsPage();
        }

        addRenderableWidget(Button.builder(Component.literal("APPLY"), b -> onClose())
                .bounds(x(494), y(370), w(94), h(20)).build());
    }

    private void initMcpPage() {
        headingBox = numberInput(24, 282, 58, "HDG", cachedHeading, 3);
        altitudeBox = numberInput(176, 282, 60, "ALT", cachedAltitude, 5);
        vsBox = numberInput(320, 282, 60, "V/S", cachedVs, 5);
        speedBox = numberInput(464, 282, 58, "IAS", cachedSpeed, 3);

        addRenderableWidget(stepButton("-10", 24, 304, 27, () -> adjustHeading(-10)));
        addRenderableWidget(stepButton("+10", 55, 304, 27, () -> adjustHeading(10)));
        addRenderableWidget(compactActionButton("SYNC", 24, 326, 58, this::syncHeading));

        addRenderableWidget(stepButton("-500", 176, 304, 28, () -> adjustAltitude(-500)));
        addRenderableWidget(stepButton("+500", 208, 304, 28, () -> adjustAltitude(500)));
        addRenderableWidget(compactActionButton("SYNC", 176, 326, 60, this::syncAltitude));

        addRenderableWidget(stepButton("-500", 320, 304, 28, () -> adjustVs(-500)));
        addRenderableWidget(stepButton("+500", 352, 304, 28, () -> adjustVs(500)));
        addRenderableWidget(compactActionButton("LEVEL", 320, 326, 60, this::setVsZero));

        addRenderableWidget(stepButton("-10", 464, 304, 27, () -> adjustSpeed(-10)));
        addRenderableWidget(stepButton("+10", 495, 304, 27, () -> adjustSpeed(10)));
        addRenderableWidget(compactActionButton("SYNC", 464, 326, 58, this::syncSpeed));

        apMasterBtn = Button.builder(apButtonText(), b -> toggleAp())
                .bounds(x(12), y(350), w(180), h(20)).build();
        addRenderableWidget(apMasterBtn);
    }

    private void initNavPage() {
        thresholdXBox = numberInput(382, 130, 76, "X", cachedThresholdX, 10);
        thresholdZBox = numberInput(466, 130, 76, "Z", cachedThresholdZ, 10);
        runwayHeadingBox = numberInput(382, 186, 76, "HDG", cachedRunwayHeading, 3);

        addRenderableWidget(compactActionButton("- 0.1°", 382, 152, 76, () -> adjustGlideSlope(-0.1f)));
        addRenderableWidget(compactActionButton("+ 0.1°", 466, 152, 76, () -> adjustGlideSlope(0.1f)));
        addRenderableWidget(compactActionButton("SET RUNWAY", 382, 222, 160, this::setIls));
        addRenderableWidget(compactActionButton("USE MY POSITION", 382, 246, 160, this::useCurrentPosition));
        addRenderableWidget(compactActionButton("CLEAR ILS", 382, 270, 160, this::clearIls));

        String armLabel = FlightHud.autopilotGuidance().autolandArmed()
                ? "DISARM AUTOLAND" : "ARM ILS AUTOLAND";
        Button autoland = actionButton(armLabel, 382, 324, 160, () -> {
            ClientPlayNetworking.send(new ToggleAutolandPayload());
            ilsMessage = "";
        });
        autoland.active = FlightLineClient.isFlightEnabled()
                && (FlightHud.isIlsActive() || FlightHud.autopilotGuidance().autolandArmed());
        addRenderableWidget(autoland);
    }

    private void initWeatherPage() {
        // The weather page is a live radar readout with no editable controls.
    }

    private void initSystemsPage() {
        // The systems page is a live aircraft schematic with no editable controls.
    }

    private Button tabButton(String label, int page, int x, int width) {
        String shortLabel = label.equals("NAV / ILS") && panelWidth < 420 ? "NAV" : label;
        String text = activePage == page ? "[" + shortLabel + "]" : shortLabel;
        return Button.builder(Component.literal(fit(text, w(width) - 8)), button -> switchPage(page))
                .bounds(x(x), y(30), w(width), h(20)).build();
    }

    private Button stepButton(String label, int x, int y, int width, Runnable action) {
        return Button.builder(Component.literal(fit(label, w(width) - 6)), button -> action.run())
                .bounds(x(x), y(y), w(width), h(20)).build();
    }

    private Button actionButton(String label, int x, int y, int width, Runnable action) {
        return Button.builder(Component.literal(fit(label, w(width) - 8)), button -> action.run())
                .bounds(x(x), y(y), w(width), h(22)).build();
    }

    private Button compactActionButton(String label, int x, int y, int width, Runnable action) {
        return Button.builder(Component.literal(fit(label, w(width) - 8)), button -> action.run())
                .bounds(x(x), y(y), w(width), h(18)).build();
    }

    private EditBox numberInput(int x, int y, int width, String label, int value, int maxLength) {
        EditBox input = new EditBox(font, x(x), y(y), w(width), h(20), Component.literal(label));
        input.setValue(String.valueOf(value));
        input.setMaxLength(maxLength);
        addRenderableWidget(input);
        return input;
    }

    private void switchPage(int page) {
        if (activePage == page) return;
        if (activePage == 0) saveAutopilotValues();
        activePage = page;
        ilsMessage = "";
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
        cachedThresholdX = parseCoordinate(thresholdXInput(), "THRESHOLD X");
        cachedThresholdZ = parseCoordinate(thresholdZInput(), "THRESHOLD Z");
        cachedRunwayHeading = Math.floorMod(parseCoordinate(runwayHeadingInput(), "RWY HDG"), 360);
    }

    private int parseCoordinate(EditBox input, String label) {
        try {
            return Integer.parseInt(input.getValue().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("INVALID " + label);
        }
    }

    private void useCurrentPosition() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            ilsMessage = "NO POSITION FIX";
            return;
        }
        thresholdXInput().setValue(String.valueOf(player.blockPosition().getX()));
        thresholdZInput().setValue(String.valueOf(player.blockPosition().getZ()));
        runwayHeadingInput().setValue(String.valueOf(Math.floorMod(Math.round(player.getYRot()), 360)));
        ilsMessage = "ILS REFERENCE UPDATED";
    }

    private void adjustGlideSlope(float delta) {
        cachedGlideSlope = clamp(Math.round((cachedGlideSlope + delta) * 10.0f) / 10.0f, 1.0f, 6.0f);
    }

    private void setIls() {
        if (!FlightLineClient.isFlightEnabled()) {
            ilsMessage = "AIRCRAFT NOT CONFIGURED";
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
        ilsMessage = "ILS TUNED";
    }

    private void clearIls() {
        if (!FlightLineClient.isFlightEnabled()) {
            ilsMessage = "AIRCRAFT NOT CONFIGURED";
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(false, cachedGlideSlope,
                cachedThresholdX, cachedThresholdZ, cachedRunwayHeading));
        ilsMessage = "ILS CLEARED";
    }

    private Component apButtonText() {
        return Component.literal(engaged ? "A/P ENGAGED" : "A/P STANDBY");
    }

    private void toggleAp() {
        engaged = !engaged;
        if (apMasterBtn != null) apMasterBtn.setMessage(apButtonText());
    }

    private void adjustHeading(int delta) {
        headingInput().setValue(String.format(Locale.ROOT, "%03d",
                Math.floorMod(parseHeading() + delta, 360)));
    }

    private void syncHeading() {
        headingInput().setValue(String.format(Locale.ROOT, "%03d",
                Math.floorMod(Math.round(FlightHud.currentHeading()), 360)));
    }

    private void adjustAltitude(int delta) {
        altitudeInput().setValue(String.valueOf(clamp(parseAltitude() + delta, 0, 35000)));
    }

    private void syncAltitude() {
        altitudeInput().setValue(String.valueOf(clamp(FlightHud.currentAltitudeFeet(), 0, 35000)));
    }

    private void adjustVs(int delta) {
        verticalSpeedInput().setValue(String.valueOf(clamp(parseVs() + delta, -4000, 4000)));
    }

    private void setVsZero() {
        verticalSpeedInput().setValue("0");
    }

    private void adjustSpeed(int delta) {
        speedInput().setValue(String.valueOf(clamp(parseSpeed() + delta, 60, 320)));
    }

    private void syncSpeed() {
        speedInput().setValue(String.valueOf(clamp(FlightHud.currentSpeedKnots(), 60, 320)));
    }

    private int parseHeading() {
        try { return clamp(Integer.parseInt(headingInput().getValue().trim()), 0, 359); }
        catch (NumberFormatException e) { return cachedHeading; }
    }

    private int parseAltitude() {
        try { return clamp(Integer.parseInt(altitudeInput().getValue().trim()), 0, 35000); }
        catch (NumberFormatException e) { return cachedAltitude; }
    }

    private int parseVs() {
        try { return clamp(Integer.parseInt(verticalSpeedInput().getValue().trim()), -4000, 4000); }
        catch (NumberFormatException e) { return cachedVs; }
    }

    private int parseSpeed() {
        try { return clamp(Integer.parseInt(speedInput().getValue().trim()), 60, 320); }
        catch (NumberFormatException e) { return cachedSpeed; }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateGeometry();
        scaler.update(width, height);
        scaler.push(graphics);
        graphics.fill(0, 0, scaler.width(), scaler.height(), 0x30000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_FILL);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, CYAN);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + h(24), 0xFF1C2733);
        fittedCenteredText(graphics, "AUTOPILOT / FLIGHT DIRECTOR", panelLeft + panelWidth / 2,
                panelTop + y(7), 0xFF72E6F5, panelWidth - 8);

        drawFma(graphics);
        card(graphics, 12, 92, 576, 280, BORDER);

        switch (activePage) {
            case 0 -> drawMcpPage(graphics);
            case 1 -> drawNavPage(graphics);
            case 2 -> drawWeatherPage(graphics);
            default -> drawSystemsPage(graphics);
        }

        fittedCenteredText(graphics, activePageName(), panelLeft + x(294), panelTop + y(376), MUTED, w(320));
        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    private void updateGeometry() {
        panelWidth = BASE_WIDTH;
        panelHeight = BASE_HEIGHT;
        panelLeft = 8;
        panelTop = 8;
        scaleX = 1.0f;
        scaleY = 1.0f;
    }

    private void drawFma(GuiGraphicsExtractor graphics) {
        var guidance = FlightHud.autopilotGuidance();
        boolean ap = FlightHud.isAutopilotEngaged() || engaged;
        boolean at = ap;
        boolean hdg = ap && !guidance.autolandArmed() && !guidance.atcControlled();
        boolean alt = hdg && guidance.targetVerticalSpeedFpm() == 0;
        boolean vs = hdg && guidance.targetVerticalSpeedFpm() != 0;
        boolean ias = ap;
        boolean ils = FlightHud.isIlsActive();
        boolean autoland = guidance.autolandArmed();
        boolean atc = guidance.atcControlled();

        graphics.fill(panelLeft + x(12), panelTop + y(54), panelLeft + x(588),
                panelTop + y(84), 0xFF0C1318);
        graphics.outline(panelLeft + x(12), panelTop + y(54), w(576), h(30), BORDER);
        fmaChip(graphics, 18, 57, 58, "A/P", ap ? GREEN : MUTED, ap);
        fmaChip(graphics, 80, 57, 58, "A/T", at ? GREEN : MUTED, at);
        fmaChip(graphics, 142, 57, 58, "HDG", hdg ? GREEN : MUTED, hdg);
        fmaChip(graphics, 204, 57, 58, alt ? "ALT" : "V/S", alt || vs ? GREEN : MUTED, alt || vs);
        fmaChip(graphics, 266, 57, 58, "IAS", ias ? GREEN : MUTED, ias);
        fmaChip(graphics, 328, 57, 58, "ILS", ils ? autoland ? GREEN : CYAN : MUTED, ils);
        fmaChip(graphics, 390, 57, 58, "A/L", autoland ? GREEN : MUTED, autoland);
        fmaChip(graphics, 452, 57, 126, atc ? "ATC CONTROL" : "ATC STANDBY",
                atc ? AMBER : MUTED, atc);
    }

    private void fmaChip(GuiGraphicsExtractor graphics, int x, int y, int width,
                         String label, int color, boolean active) {
        int fill = active ? 0xFF173326 : 0xFF131C22;
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + 24), fill);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(24), color);
        fittedCenteredText(graphics, label, panelLeft + x(x + width / 2), panelTop + y(y + 7),
                color, w(width) - 6);
    }

    private void drawMcpPage(GuiGraphicsExtractor graphics) {
        int currentHeading = Math.floorMod(Math.round(FlightHud.currentHeading()), 360);
        int currentAltitude = FlightHud.currentAltitudeFeet();
        int currentSpeed = FlightHud.currentSpeedKnots();
        int currentVs = FlightHud.currentVsFpm();

        panel(graphics, 18, 100, 132, 154, "HEADING / HDG");
        drawHeadingDial(graphics, 84, 183, 48, currentHeading, parseHeading());
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %03d°  SEL %03d°",
                currentHeading, parseHeading()), panelLeft + x(84), panelTop + y(240), TEXT, w(124));

        panel(graphics, 158, 100, 128, 154, "ALTITUDE / ALT");
        drawTape(graphics, 172, 126, 100, 104, currentAltitude, parseAltitude(), 500, "FT", 1000);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %,d  SEL %,d",
                currentAltitude, parseAltitude()), panelLeft + x(222), panelTop + y(240), TEXT, w(120));

        panel(graphics, 294, 100, 128, 154, "AIRSPEED / IAS");
        drawTape(graphics, 308, 126, 100, 104, currentSpeed, parseSpeed(), 10, "KT", 50);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %d  SEL %d",
                currentSpeed, parseSpeed()), panelLeft + x(358), panelTop + y(240), TEXT, w(120));

        panel(graphics, 430, 100, 152, 154, "VERTICAL SPEED / V/S");
        drawVerticalSpeed(graphics, 506, 183, 104, currentVs, parseVs());
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %+d  SEL %+d FPM",
                currentVs, parseVs()), panelLeft + x(506), panelTop + y(240), TEXT, w(144));

        panel(graphics, 18, 264, 132, 86, "HEADING SELECT");
        panel(graphics, 158, 264, 128, 86, "ALTITUDE SELECT");
        panel(graphics, 294, 264, 128, 86, "VERTICAL SELECT");
        panel(graphics, 430, 264, 152, 86, "SPEED SELECT");

        var guidance = FlightHud.autopilotGuidance();
        String config = String.format(Locale.ROOT, "A/T %s  ·  THRUST ±%.0f KT  ·  N1 %d%%",
                FlightHud.isAutopilotEngaged() ? "AUTO" : "STBY",
                guidance.autoThrottleDeadbandKnots(), FlightHud.engineN1());
        fittedCenteredText(graphics, config, panelLeft + x(394), panelTop + y(358), MUTED, freeWidth(200, 588));
    }

    private void drawHeadingDial(GuiGraphicsExtractor graphics, int cx, int cy, int radius,
                                 int current, int selected) {
        graphics.fill(panelLeft + x(cx - 2), panelTop + y(cy - 2),
                panelLeft + x(cx + 2), panelTop + y(cy + 2), 0xFF1D2D36);
        graphics.outline(panelLeft + x(cx - radius), panelTop + y(cy - radius),
                w(radius * 2), h(radius * 2), BORDER);

        graphics.outline(panelLeft + x(cx - radius + 9), panelTop + y(cy - radius + 9),
                w((radius - 9) * 2), h((radius - 9) * 2), 0xFF1F3039);
        for (int degree = 0; degree < 360; degree += 15) {
            int r = degree % 90 == 0 ? radius - 4 : degree % 30 == 0 ? radius - 7 : radius - 10;
            int[] point = headingPoint(cx, cy, r, degree);
            int size = degree % 90 == 0 ? 3 : 2;
            graphics.fill(panelLeft + x(point[0]), panelTop + y(point[1]),
                    panelLeft + x(point[0] + size), panelTop + y(point[1] + size),
                    degree % 90 == 0 ? CYAN : degree % 30 == 0 ? TEXT : BORDER);
        }
        for (int degree = 30; degree < 360; degree += 30) {
            if (degree % 90 == 0) continue;
            int[] point = headingPoint(cx, cy, radius - 19, degree);
            graphics.centeredText(font, String.format(Locale.ROOT, "%02d", degree / 10),
                    panelLeft + x(point[0]), panelTop + y(point[1] - 3), MUTED);
        }
        for (int[] cardinal : new int[][]{{0, 0, 'N'}, {1, 90, 'E'}, {2, 180, 'S'}, {3, 270, 'W'}}) {
            int[] point = headingPoint(cx, cy, radius + 8, cardinal[1]);
            graphics.centeredText(font, String.valueOf((char) cardinal[2]),
                    panelLeft + x(point[0]), panelTop + y(point[1] - 3), CYAN);
        }

        int[] currentPoint = headingPoint(cx, cy, radius - 14, current);
        line(graphics, cx, cy, currentPoint[0], currentPoint[1], GREEN);
        triangle(graphics, headingPoint(cx, cy, radius - 26, selected), selected, AMBER, 4);
    }

    private void drawTape(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                          int current, int selected, int step, String unit, int labelStep) {
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), 0xFF0C141A);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), BORDER);
        int center = y + height / 2;
        graphics.fill(panelLeft + x(x), panelTop + y(center - 1),
                panelLeft + x(x + width), panelTop + y(center + 1), CYAN);

        graphics.enableScissor(panelLeft + x(x + 1), panelTop + y(y + 1), w(width - 2), h(height - 2));
        int selectedTick = Math.round((selected - current) / (float) step);
        int majorEvery = Math.max(1, labelStep / step);
        for (int i = -6; i <= 6; i++) {
            int tickY = center - i * 10;
            if (tickY < y + 2 || tickY > y + height - 2) continue;
            boolean major = i % majorEvery == 0;
            graphics.fill(panelLeft + x(x + 3), panelTop + y(tickY),
                    panelLeft + x(x + (major ? 20 : 12)), panelTop + y(tickY + 1),
                    major ? TEXT : BORDER);
            if (major) {
                fittedText(graphics, String.valueOf(current + i * step), panelLeft + x(x + 24),
                        panelTop + y(tickY - 4), MUTED, w(width - 28), true);
            }
        }
        int selectedY = clamp(center - selectedTick * 10, y + 7, y + height - 7);
        line(graphics, x + width - 12, selectedY - 5, x + width - 2, selectedY, AMBER);
        line(graphics, x + width - 2, selectedY, x + width - 12, selectedY + 5, AMBER);
        graphics.disableScissor();

        graphics.fill(panelLeft + x(x + 3), panelTop + y(y + height - 15),
                panelLeft + x(x + 55), panelTop + y(y + height - 2), 0xFF111D24);
        graphics.outline(panelLeft + x(x + 3), panelTop + y(y + height - 15), w(52), h(13), AMBER);
        fittedCenteredText(graphics, selected + " " + unit, panelLeft + x(x + 29),
                panelTop + y(y + height - 13), AMBER, w(48));
    }

    private void drawVerticalSpeed(GuiGraphicsExtractor graphics, int cx, int cy, int height,
                                   int current, int selected) {
        int top = cy - height / 2;
        graphics.fill(panelLeft + x(cx - 34), panelTop + y(top),
                panelLeft + x(cx + 34), panelTop + y(top + height), 0xFF0C141A);
        graphics.outline(panelLeft + x(cx - 34), panelTop + y(top), w(68), h(height), BORDER);
        graphics.fill(panelLeft + x(cx - 34), panelTop + y(cy),
                panelLeft + x(cx + 34), panelTop + y(cy + 1), MUTED);
        for (int i = -2; i <= 2; i++) {
            int tickY = cy + i * 20;
            boolean major = i == 0;
            graphics.fill(panelLeft + x(cx - (major ? 32 : 26)), panelTop + y(tickY),
                    panelLeft + x(cx - 18), panelTop + y(tickY + 1), major ? TEXT : MUTED);
            if (i != 0) {
                fittedText(graphics, String.format(Locale.ROOT, "%+d", i),
                        panelLeft + x(cx + 12), panelTop + y(tickY - 4), MUTED, w(18), true);
            }
        }
        int needle = cy - clamp(selected / 100, -40, 40);
        line(graphics, cx - 30, needle, cx + 30, needle, AMBER);
        line(graphics, cx - 30, needle - 3, cx - 30, needle + 3, AMBER);
        line(graphics, cx + 30, needle - 3, cx + 30, needle + 3, AMBER);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %+d", current),
                panelLeft + x(cx), panelTop + y(top + 6), CYAN, w(64));
        fittedCenteredText(graphics, String.format(Locale.ROOT, "SEL %+d", selected),
                panelLeft + x(cx), panelTop + y(top + height - 12), AMBER, w(64));
    }

    private void drawNavPage(GuiGraphicsExtractor graphics) {
        panel(graphics, 18, 100, 348, 196, "ILS APPROACH / GUIDANCE");
        panel(graphics, 374, 100, 208, 200, "RUNWAY REFERENCE");
        panel(graphics, 18, 304, 564, 58, "STATUS / ARMING");

        boolean ils = FlightHud.isIlsActive();
        float localizer = FlightHud.localizerDeviation();
        float glideslope = FlightHud.glideslopeDeviation();
        int centerX = 192;
        int centerY = 208;

        graphics.fill(panelLeft + x(42), panelTop + y(centerY - 52),
                panelLeft + x(342), panelTop + y(centerY + 52), 0xFF0B1217);
        graphics.outline(panelLeft + x(42), panelTop + y(centerY - 52), w(300), h(104), ils ? GREEN : BORDER);

        line(graphics, 72, centerY, 312, centerY, ils ? MUTED : BORDER);
        for (int i = -4; i <= 4; i++) {
            int tickX = centerX + i * 28;
            graphics.fill(panelLeft + x(tickX), panelTop + y(centerY - 4),
                    panelLeft + x(tickX + 1), panelTop + y(centerY + 5), MUTED);
        }
        int locX = clamp(centerX + Math.round(localizer * 14), 78, 306);
        diamond(graphics, locX, centerY, CYAN, true);

        line(graphics, 62, 164, 62, 252, ils ? MUTED : BORDER);
        for (int i = -2; i <= 2; i++) {
            int tickY = centerY + i * 18;
            graphics.fill(panelLeft + x(58), panelTop + y(tickY),
                    panelLeft + x(67), panelTop + y(tickY + 1), MUTED);
        }
        int gsY = clamp(centerY - Math.round(glideslope * 9), 168, 248);
        diamond(graphics, 62, gsY, PURPLE, false);

        drawRunway(graphics, centerX, 258);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "LOC %+.1f°  GS %+.1f°  ANGLE %.1f°",
                localizer, glideslope, cachedGlideSlope), panelLeft + x(centerX),
                panelTop + y(128), ils ? TEXT : MUTED, w(340));

        fittedText(graphics, "THRESHOLD", panelLeft + x(382), panelTop + y(118), MUTED,
                freeWidth(382, 466), true);
        fittedText(graphics, "POSITION", panelLeft + x(466), panelTop + y(118), MUTED,
                freeWidth(466, 550), true);
        graphics.text(font, "RUNWAY HDG", panelLeft + x(382), panelTop + y(172), MUTED, true);
        drawMiniCompass(graphics, 520, 208, 20, cachedRunwayHeading);

        String[] status = navStatus(ils);
        int color = statusColor(status[0]);
        graphics.fill(panelLeft + x(26), panelTop + y(304), panelLeft + x(574), panelTop + y(362),
                color == GREEN ? 0xFF14301F : color == AMBER ? 0xFF302514 : 0xFF211419);
        graphics.outline(panelLeft + x(26), panelTop + y(304), w(548), h(58), color);
        fittedText(graphics, status[0], panelLeft + x(34), panelTop + y(312), color,
                freeWidth(34, 366), true);
        if (status[1] != null) {
            fittedText(graphics, status[1], panelLeft + x(34), panelTop + y(326), MUTED,
                    freeWidth(34, 366), true);
        }
        if (!ilsMessage.isEmpty()) {
            fittedText(graphics, ilsMessage, panelLeft + x(34), panelTop + y(344), CYAN,
                    freeWidth(34, 366), true);
        }
    }

    private String[] navStatus(boolean ils) {
        var guidance = FlightHud.autopilotGuidance();
        if (!FlightLineClient.isFlightEnabled()) {
            return new String[]{"AIRCRAFT NOT CONFIGURED", "Approach guidance unavailable."};
        }
        if (!ils) {
            return new String[]{"NO ILS SIGNAL", "Verify runway threshold and approach coverage."};
        }
        if (guidance.autolandArmed()) {
            return new String[]{"AUTOLAND ACTIVE", "Monitor approach · verify landing configuration."};
        }
        if (!FlightHud.anyEngineRunning()) {
            return new String[]{"ENGINE REQUIRED", "At least one engine must be running."};
        }
        if (!guidance.atcControlled()) {
            return new String[]{"APPROACH READY", "Gear " + (FlightHud.gearDown() ? "DOWN" : "UP")
                    + " · ILS valid · autoland available."};
        }
        return new String[]{"ATC CONTROL ACTIVE", "Clearance must be released before manual arming."};
    }

    private int statusColor(String status) {
        if (status.startsWith("AUTOLAND") || status.startsWith("APPROACH READY")) return GREEN;
        if (status.startsWith("ATC")) return AMBER;
        return RED;
    }

    private void drawMiniCompass(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int heading) {
        drawCircleOutline(graphics, cx, cy, radius, BORDER);
        graphics.centeredText(font, "N", panelLeft + x(cx), panelTop + y(cy - radius - 7), CYAN);
        int[] point = headingPoint(cx, cy, radius - 3, heading);
        triangle(graphics, point, heading, AMBER, 4);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "%03d°", heading),
                panelLeft + x(cx), panelTop + y(cy + radius + 4), TEXT, w(46));
    }

    private void drawCircleOutline(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        int x = radius;
        int y = 0;
        int error = 1 - radius;
        while (x >= y) {
            int[][] points = {{x, y}, {y, x}, {-y, x}, {-x, y}, {-x, -y}, {-y, -x}, {y, -x}, {x, -y}};
            for (int[] point : points) {
                graphics.fill(panelLeft + x(cx + point[0]), panelTop + y(cy + point[1]),
                        panelLeft + x(cx + point[0] + 1), panelTop + y(cy + point[1] + 1), color);
            }
            y++;
            if (error < 0) error += 2 * y + 1;
            else {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    private void drawRunway(GuiGraphicsExtractor graphics, int cx, int y) {
        int color = FlightHud.gearDown() ? GREEN : AMBER;
        line(graphics, cx - 46, y + 32, cx - 16, y, color);
        line(graphics, cx + 46, y + 32, cx + 16, y, color);
        graphics.fill(panelLeft + x(cx - 46), panelTop + y(y + 32),
                panelLeft + x(cx + 46), panelTop + y(y + 34), color);
        for (int i = 0; i < 4; i++) {
            int dashY = y + 6 + i * 7;
            graphics.fill(panelLeft + x(cx - 1), panelTop + y(dashY),
                    panelLeft + x(cx + 1), panelTop + y(dashY + 3), TEXT);
        }
        graphics.centeredText(font, String.format(Locale.ROOT, "DME %.1f NM",
                        FlightHud.thresholdDistanceBlocks() / 10.0f),
                panelLeft + x(cx), panelTop + y(y + 22), MUTED);
    }

    private void drawWeatherPage(GuiGraphicsExtractor graphics) {
        var weather = FlightHud.weatherGuidance();
        panel(graphics, 18, 100, 396, 262, "WX RADAR / LIVE CONDITIONS");
        panel(graphics, 422, 100, 160, 262, "AIRCRAFT SYSTEMS");

        drawWeatherRadar(graphics, 36, 122, 360, 180, weather);
        String metar = String.format(Locale.ROOT, "%s  WIND %03d/%02dG%02d  VIS %.1fSM",
                weather.condition() == 2 ? "TSRA" : weather.condition() == 1 ? "RA" : "CAVOK",
                weather.windDirectionDegrees(), weather.windSpeedKnots(), weather.gustKnots(),
                weather.visibilityBlocks() / 64.0f);
        int conditionColor = conditionColor(weather);
        fittedCenteredText(graphics, metar, panelLeft + x(216), panelTop + y(318), conditionColor, w(376));
        fittedCenteredText(graphics, String.format(Locale.ROOT, "TURB %d%%  ·  ICE %d%%  ·  %s",
                        weather.turbulencePercent(), weather.icingPercent(),
                        weather.antiIceEffective() ? "A-ICE CLEAR" : weather.antiIceActive() ? "A-ICE ON" : "A-ICE OFF"),
                panelLeft + x(216), panelTop + y(340), MUTED, w(376));

        gauge(graphics, 438, 146, 128, "TURBULENCE", weather.turbulencePercent(),
                weather.turbulencePercent() >= 70 ? RED : weather.turbulencePercent() >= 35 ? AMBER : GREEN);
        gauge(graphics, 438, 202, 128, "ICING", weather.icingPercent(),
                weather.icingPercent() >= 20 ? AMBER : GREEN);
        gauge(graphics, 438, 258, 128, "VISIBILITY",
                clamp(100 - weather.visibilityBlocks() / 4, 0, 100),
                weather.visibilityBlocks() < 80 ? AMBER : GREEN);

        String antiIce = weather.antiIceEffective() ? "CLEARING"
                : weather.antiIceActive() ? "ON / NO POWER" : "OFF";
        graphics.text(font, "ANTI-ICE", panelLeft + x(438), panelTop + y(300), MUTED, true);
        graphics.text(font, antiIce, panelLeft + x(438), panelTop + y(314),
                weather.antiIceEffective() ? GREEN : weather.antiIceActive() ? AMBER : MUTED, true);
        graphics.text(font, "FUEL", panelLeft + x(438), panelTop + y(344), MUTED, true);
        graphics.text(font, FlightHud.fuelPercent() + "%", panelLeft + x(480), panelTop + y(344), TEXT, true);
        if (!ilsMessage.isEmpty()) {
            fittedCenteredText(graphics, ilsMessage, panelLeft + x(216),
                    panelTop + y(350), CYAN, w(376));
        }
    }

    private void drawWeatherRadar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                  FlightInstrumentPayload.WeatherGuidance weather) {
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), 0xFF081015);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), BORDER);
        graphics.enableScissor(panelLeft + x(x), panelTop + y(y), w(width), h(height));
        for (int lineX = x + 30; lineX < x + width; lineX += 30) {
            graphics.fill(panelLeft + x(lineX), panelTop + y(y), panelLeft + x(lineX + 1),
                    panelTop + y(y + height), 0xFF15242C);
        }
        for (int lineY = y + 30; lineY < y + height; lineY += 30) {
            graphics.fill(panelLeft + x(x), panelTop + y(lineY), panelLeft + x(x + width),
                    panelTop + y(lineY + 1), 0xFF15242C);
        }
        graphics.fill(panelLeft + x(x + width / 2 - 1), panelTop + y(y),
                panelLeft + x(x + width / 2 + 1), panelTop + y(y + height), CYAN);
        graphics.fill(panelLeft + x(x), panelTop + y(y + height / 2 - 1),
                panelLeft + x(x + width), panelTop + y(y + height / 2 + 1), CYAN);

        long gameTime = Minecraft.getInstance().level == null
                ? 0L : Minecraft.getInstance().level.getGameTime();
        int storm = weather.condition();
        for (int cellZ = -2; cellZ <= 2; cellZ++) {
            for (int cellX = -2; cellX <= 2; cellX++) {
                var cell = WeatherSimulation.sampleCell(cellX, cellZ, gameTime,
                        WeatherDisplay.CELL_SEED, storm, weather.turbulencePercent(), weather.gustKnots());
                if (cell.intensity() < 24) continue;
                int px = x + width / 2 + cellX * 68
                        + Math.round((float) (Math.sin(gameTime * 0.0012 + cellX * 0.83 + cellZ * 0.39) * 10));
                int py = y + height / 2 + cellZ * 34;
                int half = 7 + cell.intensity() / 10;
                WeatherDisplay.drawCellGlyph(graphics, panelLeft + x(px), panelTop + y(py),
                        w(half), cell, false);
            }
        }
        drawWeatherLegend(graphics, x + width - 58, y + height - 44);

        long phase = System.nanoTime() / 25_000_000L;
        int sweep = x + (int) (phase % Math.max(1, width - 2));
        graphics.fill(panelLeft + x(sweep), panelTop + y(y), panelLeft + x(sweep + 2),
                panelTop + y(y + height), 0x8062D89B);
        drawWindArrow(graphics, x + 34, y + 28, weather.windDirectionDegrees(),
                weather.windSpeedKnots(), conditionColor(weather));
        graphics.disableScissor();
    }

    private void drawWeatherLegend(GuiGraphicsExtractor graphics, int x, int y) {
        int[][] swatches = {{GREEN, CYAN, AMBER, RED}};
        int[] colors = swatches[0];
        for (int i = 0; i < colors.length; i++) {
            graphics.fill(panelLeft + x(x + i * 12), panelTop + y(y),
                    panelLeft + x(x + i * 12 + 9), panelTop + y(y + 7), colors[i]);
        }
        fittedText(graphics, "LIGHT / HEAVY", panelLeft + x(x), panelTop + y(y + 9), MUTED, w(58), true);
    }

    private int conditionColor(FlightInstrumentPayload.WeatherGuidance weather) {
        return weather.condition() == 2 ? RED : weather.condition() == 1 ? CYAN : GREEN;
    }

    private void drawWindArrow(GuiGraphicsExtractor graphics, int x, int y, int direction, int speed,
                               int color) {
        int[] end = headingPoint(x, y, 20 + Math.min(20, speed / 4), direction);
        line(graphics, x, y, end[0], end[1], color);
        int[] left = headingPoint(end[0], end[1], 7, direction + 150);
        int[] right = headingPoint(end[0], end[1], 7, direction - 150);
        line(graphics, end[0], end[1], left[0], left[1], color);
        line(graphics, end[0], end[1], right[0], right[1], color);
    }

    private void gauge(GuiGraphicsExtractor graphics, int x, int y, int width, String label,
                       int percent, int color) {
        fittedText(graphics, label, panelLeft + x(x), panelTop + y(y), MUTED, w(width) - 30, true);
        fittedText(graphics, percent + "%", panelLeft + x(x + width - 26),
                panelTop + y(y), color, w(26), true);
        graphics.fill(panelLeft + x(x), panelTop + y(y + 14), panelLeft + x(x + width),
                panelTop + y(y + 22), 0xFF1A262E);
        graphics.fill(panelLeft + x(x), panelTop + y(y + 14),
                panelLeft + x(x + Math.round(width * clamp(percent, 0, 100) / 100.0f)),
                panelTop + y(y + 22), color);
        graphics.outline(panelLeft + x(x), panelTop + y(y + 14), w(width), h(8), BORDER);
    }

    private void drawSystemsPage(GuiGraphicsExtractor graphics) {
        panel(graphics, 18, 100, 396, 262, "AIRCRAFT CONFIGURATION");
        panel(graphics, 422, 100, 160, 262, "ENGINE / ICE / FUEL");

        drawAirframeSchematic(graphics, 216, 214);

        String gear = FlightHud.gearDown() ? "DOWN & LOCKED" : "UP";
        fittedText(graphics, "GEAR", panelLeft + x(36), panelTop + y(310), MUTED, w(54), true);
        fittedText(graphics, gear, panelLeft + x(36), panelTop + y(324),
                FlightHud.gearDown() ? GREEN : AMBER, w(120), true);
        fittedText(graphics, "FLAPS", panelLeft + x(164), panelTop + y(310), MUTED, w(54), true);
        fittedText(graphics, "F" + FlightHud.flapDetent(), panelLeft + x(164), panelTop + y(324), TEXT, w(70), true);
        fittedText(graphics, "SPOILERS", panelLeft + x(252), panelTop + y(310), MUTED, w(74), true);
        fittedText(graphics, FlightHud.spoilersDeployed() ? "DEPLOYED" : "STOWED",
                panelLeft + x(252), panelTop + y(324), FlightHud.spoilersDeployed() ? AMBER : TEXT, w(100), true);

        gauge(graphics, 438, 134, 128, "N1", FlightHud.engineN1(),
                FlightHud.anyEngineRunning() ? GREEN : RED);
        gauge(graphics, 438, 186, 128, "FUEL", FlightHud.fuelPercent(),
                FlightHud.fuelPercent() < 20 ? RED : FlightHud.fuelPercent() < 40 ? AMBER : GREEN);

        var weather = FlightHud.weatherGuidance();
        gauge(graphics, 438, 238, 128, "ICE", weather.icingPercent(),
                weather.icingPercent() >= 20 ? AMBER : GREEN);
        graphics.text(font, "ANTI-ICE", panelLeft + x(438), panelTop + y(292), MUTED, true);
        graphics.text(font, weather.antiIceEffective() ? "ON · CLEARING"
                        : weather.antiIceActive() ? "ON · NO POWER" : "OFF",
                panelLeft + x(438), panelTop + y(306),
                weather.antiIceEffective() ? GREEN : weather.antiIceActive() ? AMBER : MUTED, true);
        graphics.text(font, "AIRBRAKES", panelLeft + x(438), panelTop + y(330), MUTED, true);
        graphics.text(font, FlightHud.airbrakesDeployed() ? "DEPLOYED" : "STOWED",
                panelLeft + x(510), panelTop + y(330), FlightHud.airbrakesDeployed() ? AMBER : TEXT, true);
    }

    private void drawAirframeSchematic(GuiGraphicsExtractor graphics, int cx, int cy) {
        int color = FlightHud.anyEngineRunning() ? CYAN : MUTED;
        line(graphics, cx, cy - 66, cx, cy + 54, color);
        line(graphics, cx - 22, cy - 38, cx + 22, cy - 38, color);
        line(graphics, cx - 78, cy + 6, cx + 78, cy + 6, color);
        line(graphics, cx - 78, cy + 6, cx - 78, cy + 32, color);
        line(graphics, cx + 78, cy + 6, cx + 78, cy + 32, color);
        line(graphics, cx - 78, cy + 32, cx + 78, cy + 32, color);
        line(graphics, cx - 22, cy - 38, cx - 58, cy + 6, color);
        line(graphics, cx + 22, cy - 38, cx + 58, cy + 6, color);
        line(graphics, cx - 34, cy + 32, cx - 54, cy + 56, color);
        line(graphics, cx + 34, cy + 32, cx + 54, cy + 56, color);
        line(graphics, cx - 54, cy + 56, cx - 34, cy + 56, color);
        line(graphics, cx + 34, cy + 56, cx + 54, cy + 56, color);

        int gearColor = FlightHud.gearDown() ? GREEN : AMBER;
        graphics.fill(panelLeft + x(cx - 4), panelTop + y(cy + 52), panelLeft + x(cx + 4), panelTop + y(cy + 60), gearColor);
        for (int wing : new int[]{-68, 68}) {
            graphics.fill(panelLeft + x(cx + wing - 3), panelTop + y(cy + 30),
                    panelLeft + x(cx + wing + 3), panelTop + y(cy + 36), gearColor);
        }
        int flapColor = FlightHud.flapDetent() > 0 ? CYAN : BORDER;
        line(graphics, cx - 62, cy + 7, cx - 22, cy + (FlightHud.flapDetent() > 0 ? 22 : 12), flapColor);
        line(graphics, cx + 62, cy + 7, cx + 22, cy + (FlightHud.flapDetent() > 0 ? 22 : 12), flapColor);
        fittedCenteredText(graphics, "PRIMARY FLIGHT CONTROLS", panelLeft + x(cx),
                panelTop + y(cy + 74), MUTED, w(300));
    }

    private void card(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int borderColor) {
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), CARD_FILL);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), borderColor);
    }

    private void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String title) {
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), CARD_FILL);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), BORDER);
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + 18), 0xFF172631);
        fittedText(graphics, title, panelLeft + x(x + 7), panelTop + y(y + 4), CYAN,
                w(width) - 14, true);
    }

    private void triangle(GuiGraphicsExtractor graphics, int[] tip, int heading, int color, int size) {
        int[] left = headingPoint(tip[0], tip[1], size, heading + 145);
        int[] right = headingPoint(tip[0], tip[1], size, heading - 145);
        line(graphics, tip[0], tip[1], left[0], left[1], color);
        line(graphics, left[0], left[1], right[0], right[1], color);
        line(graphics, right[0], right[1], tip[0], tip[1], color);
    }

    private void diamond(GuiGraphicsExtractor graphics, int cx, int cy, int color, boolean horizontal) {
        if (horizontal) {
            line(graphics, cx - 5, cy - 5, cx + 5, cy, color);
            line(graphics, cx + 5, cy, cx - 5, cy + 5, color);
            line(graphics, cx - 5, cy + 5, cx - 5, cy - 5, color);
        } else {
            line(graphics, cx - 5, cy - 5, cx + 5, cy, color);
            line(graphics, cx + 5, cy, cx, cy + 6, color);
            line(graphics, cx, cy + 6, cx - 5, cy - 5, color);
        }
    }

    private void line(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int error = dx - dy;
        while (true) {
            graphics.fill(panelLeft + x(x1), panelTop + y(y1), panelLeft + x(x1 + 1),
                    panelTop + y(y1 + 1), color);
            if (x1 == x2 && y1 == y2) break;
            int doubled = error * 2;
            if (doubled > -dy) {
                error -= dy;
                x1 += sx;
            }
            if (doubled < dx) {
                error += dx;
                y1 += sy;
            }
        }
    }

    private static int[] headingPoint(int cx, int cy, int radius, int degrees) {
        double radians = Math.toRadians(degrees);
        return new int[]{Math.round(cx + (float) (Math.sin(radians) * radius)),
                Math.round(cy - (float) (Math.cos(radians) * radius))};
    }

    private String activePageName() {
        return switch (activePage) {
            case 0 -> "MCP";
            case 1 -> "NAV / ILS";
            case 2 -> "WX";
            default -> "SYS";
        };
    }

    private int x(int value) {
        return Math.round(value * scaleX);
    }

    private int y(int value) {
        return Math.round(value * scaleY);
    }

    private int w(int value) {
        return Math.max(1, Math.round(value * scaleX));
    }

    private int h(int value) {
        return Math.max(1, Math.round(value * scaleY));
    }

    private String fit(String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    private int freeWidth(int baseStart, int baseEnd) {
        return Math.max(1, x(baseEnd) - x(baseStart) - 6);
    }

    private void fittedText(GuiGraphicsExtractor graphics, String text, int x, int y,
                            int color, int maxWidth, boolean shadow) {
        graphics.text(font, fit(text, maxWidth), x, y, color, shadow);
    }

    private void fittedCenteredText(GuiGraphicsExtractor graphics, String text, int centerX, int y,
                                    int color, int maxWidth) {
        graphics.centeredText(font, fit(text, maxWidth), centerX, y, color);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private EditBox headingInput() {
        return Objects.requireNonNull(headingBox, "MCP page is not initialized");
    }

    private EditBox altitudeInput() {
        return Objects.requireNonNull(altitudeBox, "MCP page is not initialized");
    }

    private EditBox verticalSpeedInput() {
        return Objects.requireNonNull(vsBox, "MCP page is not initialized");
    }

    private EditBox speedInput() {
        return Objects.requireNonNull(speedBox, "MCP page is not initialized");
    }

    private EditBox thresholdXInput() {
        return Objects.requireNonNull(thresholdXBox, "NAV page is not initialized");
    }

    private EditBox thresholdZInput() {
        return Objects.requireNonNull(thresholdZBox, "NAV page is not initialized");
    }

    private EditBox runwayHeadingInput() {
        return Objects.requireNonNull(runwayHeadingBox, "NAV page is not initialized");
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

    @Override
    public void onClose() {
        if (headingBox != null) saveAutopilotValues();
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
