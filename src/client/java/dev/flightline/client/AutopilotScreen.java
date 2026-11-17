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
        panelWidth = Math.max(1, Math.min(BASE_WIDTH, width - 16));
        panelHeight = Math.max(1, Math.min(BASE_HEIGHT, height - 16));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        scaleX = panelWidth / (float) BASE_WIDTH;
        scaleY = panelHeight / (float) BASE_HEIGHT;

        addRenderableWidget(tabButton("MCP", 0, 12, 108));
        addRenderableWidget(tabButton("NAV / ILS", 1, 126, 112));
        addRenderableWidget(tabButton("WX", 2, 244, 76));
        addRenderableWidget(tabButton("KEYS", 3, 326, 84));

        if (activePage == 0) {
            initMcpPage();
        } else if (activePage == 1) {
            initNavPage();
        } else if (activePage == 2) {
            initWeatherPage();
        } else {
            initKeysPage();
        }

        addRenderableWidget(Button.builder(Component.literal("APPLY & CLOSE"), b -> onClose())
                .bounds(x(494), y(370), w(94), h(20)).build());
    }

    private void initMcpPage() {
        headingBox = numberInput(24, 272, 58, "HDG", cachedHeading, 3);
        altitudeBox = numberInput(176, 272, 60, "ALT", cachedAltitude, 5);
        vsBox = numberInput(320, 272, 60, "V/S", cachedVs, 5);
        speedBox = numberInput(464, 272, 58, "IAS", cachedSpeed, 3);

        addRenderableWidget(stepButton("-10", 24, 298, 27, () -> adjustHeading(-10)));
        addRenderableWidget(stepButton("+10", 55, 298, 27, () -> adjustHeading(10)));
        addRenderableWidget(actionButton("SYNC", 24, 322, 58, this::syncHeading));

        addRenderableWidget(stepButton("-500", 176, 298, 28, () -> adjustAltitude(-500)));
        addRenderableWidget(stepButton("+500", 208, 298, 28, () -> adjustAltitude(500)));
        addRenderableWidget(actionButton("SYNC", 176, 322, 60, this::syncAltitude));

        addRenderableWidget(stepButton("-500", 320, 298, 28, () -> adjustVs(-500)));
        addRenderableWidget(stepButton("+500", 352, 298, 28, () -> adjustVs(500)));
        addRenderableWidget(actionButton("LEVEL", 320, 322, 60, this::setVsZero));

        addRenderableWidget(stepButton("-10", 464, 298, 27, () -> adjustSpeed(-10)));
        addRenderableWidget(stepButton("+10", 495, 298, 27, () -> adjustSpeed(10)));
        addRenderableWidget(actionButton("SYNC", 464, 322, 58, this::syncSpeed));

        apMasterBtn = Button.builder(apButtonText(), b -> toggleAp())
                .bounds(x(12), y(344), w(180), h(24)).build();
        addRenderableWidget(apMasterBtn);
    }

    private void initNavPage() {
        thresholdXBox = numberInput(382, 144, 76, "X", cachedThresholdX, 10);
        thresholdZBox = numberInput(466, 144, 76, "Z", cachedThresholdZ, 10);
        runwayHeadingBox = numberInput(382, 198, 76, "HDG", cachedRunwayHeading, 3);

        addRenderableWidget(actionButton("- 0.1°", 382, 178, 76, () -> adjustGlideSlope(-0.1f)));
        addRenderableWidget(actionButton("+ 0.1°", 466, 178, 76, () -> adjustGlideSlope(0.1f)));
        addRenderableWidget(actionButton("SET RUNWAY", 382, 232, 160, this::setIls));
        addRenderableWidget(actionButton("USE MY POSITION", 382, 260, 160, this::useCurrentPosition));
        addRenderableWidget(actionButton("CLEAR ILS", 382, 288, 160, this::clearIls));

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
        // The weather page is a live readout; controls stay on the keycap page.
    }

    private void initKeysPage() {
        addRenderableWidget(actionButton("OPEN CONTROLS", 430, 324, 148, () -> {
            Minecraft.getInstance().gui.setScreen(new FlightControlsScreen());
        }));
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

    private void adjustGlideSlope(float delta) {
        cachedGlideSlope = clamp(Math.round((cachedGlideSlope + delta) * 10.0f) / 10.0f, 1.0f, 6.0f);
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

    private Component apButtonText() {
        return Component.literal(engaged ? "A/P ENGAGED · CLICK TO OFF" : "A/P STANDBY · CLICK TO ON");
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
        graphics.fill(0, 0, width, height, 0x30000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_FILL);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, CYAN);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + h(24), 0xFF1C2733);
        graphics.centeredText(font, "AUTOPILOT / FLIGHT DIRECTOR", panelLeft + panelWidth / 2,
                panelTop + y(7), 0xFF72E6F5);

        drawFma(graphics);
        card(graphics, 12, 92, 576, 270, BORDER);

        switch (activePage) {
            case 0 -> drawMcpPage(graphics);
            case 1 -> drawNavPage(graphics);
            case 2 -> drawWeatherPage(graphics);
            default -> drawKeysPage(graphics);
        }

        graphics.text(font, fit("MODE: " + activePageName() + "  ·  ALL CHANGES ARE SERVER-AUTHORITATIVE",
                w(470)), panelLeft + x(12), panelTop + y(377), MUTED, true);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void updateGeometry() {
        panelWidth = Math.max(1, Math.min(BASE_WIDTH, width - 16));
        panelHeight = Math.max(1, Math.min(BASE_HEIGHT, height - 16));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        scaleX = panelWidth / (float) BASE_WIDTH;
        scaleY = panelHeight / (float) BASE_HEIGHT;
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
        graphics.centeredText(font, label, panelLeft + x(x + width / 2), panelTop + y(y + 7), color);
    }

    private void drawMcpPage(GuiGraphicsExtractor graphics) {
        int currentHeading = Math.floorMod(Math.round(FlightHud.currentHeading()), 360);
        int currentAltitude = FlightHud.currentAltitudeFeet();
        int currentSpeed = FlightHud.currentSpeedKnots();
        int currentVs = FlightHud.currentVsFpm();

        panel(graphics, 18, 100, 132, 154, "HEADING / HDG");
        drawHeadingDial(graphics, 84, 183, 48, currentHeading, parseHeading());
        graphics.centeredText(font, String.format(Locale.ROOT, "CUR %03d°   SEL %03d°",
                currentHeading, parseHeading()), panelLeft + x(84), panelTop + y(240), TEXT);

        panel(graphics, 158, 100, 128, 154, "ALTITUDE / ALT");
        drawTape(graphics, 172, 126, 100, 104, currentAltitude, parseAltitude(), 500, "FT", 1000);
        graphics.centeredText(font, String.format(Locale.ROOT, "CUR %,d   SEL %,d",
                currentAltitude, parseAltitude()), panelLeft + x(222), panelTop + y(240), TEXT);

        panel(graphics, 294, 100, 128, 154, "AIRSPEED / IAS");
        drawTape(graphics, 308, 126, 100, 104, currentSpeed, parseSpeed(), 10, "KT", 50);
        graphics.centeredText(font, String.format(Locale.ROOT, "CUR %d   SEL %d",
                currentSpeed, parseSpeed()), panelLeft + x(358), panelTop + y(240), TEXT);

        panel(graphics, 430, 100, 152, 154, "VERTICAL SPEED / V/S");
        drawVerticalSpeed(graphics, 506, 183, 104, currentVs, parseVs());
        graphics.centeredText(font, String.format(Locale.ROOT, "CUR %+d   SEL %+d FPM",
                currentVs, parseVs()), panelLeft + x(506), panelTop + y(240), TEXT);

        panel(graphics, 18, 264, 132, 82, "HEADING SELECT");
        panel(graphics, 158, 264, 128, 82, "ALTITUDE SELECT");
        panel(graphics, 294, 264, 128, 82, "VERTICAL SELECT");
        panel(graphics, 430, 264, 152, 82, "SPEED SELECT");

        var guidance = FlightHud.autopilotGuidance();
        String config = String.format(Locale.ROOT,
                "THRUST ±%.0f KT DEADBAND  ·  A/T %s  ·  FUEL %d%%",
                guidance.autoThrottleDeadbandKnots(),
                FlightHud.isAutopilotEngaged() ? "AUTO" : "STBY", FlightHud.fuelPercent());
        graphics.centeredText(font, fit(config, w(340)), panelLeft + x(356), panelTop + y(356), MUTED);
        if (guidance.atcControlled()) {
            graphics.centeredText(font, "ATC CONTROL ACTIVE · F7 RELEASES", panelLeft + x(356),
                    panelTop + y(356), AMBER);
        }
    }

    private void drawHeadingDial(GuiGraphicsExtractor graphics, int cx, int cy, int radius,
                                 int current, int selected) {
        graphics.fill(panelLeft + x(cx - 2), panelTop + y(cy - 2),
                panelLeft + x(cx + 2), panelTop + y(cy + 2), 0xFF1D2D36);
        graphics.outline(panelLeft + x(cx - radius), panelTop + y(cy - radius),
                w(radius * 2), h(radius * 2), BORDER);

        for (int degree = 0; degree < 360; degree += 15) {
            int r = degree % 90 == 0 ? radius - 4 : radius - 9;
            int[] point = headingPoint(cx, cy, r, degree);
            int size = degree % 90 == 0 ? 3 : 2;
            graphics.fill(panelLeft + x(point[0]), panelTop + y(point[1]),
                    panelLeft + x(point[0] + size), panelTop + y(point[1] + size),
                    degree % 90 == 0 ? CYAN : BORDER);
        }
        for (int[] cardinal : new int[][]{{0, 0, 'N'}, {1, 90, 'E'}, {2, 180, 'S'}, {3, 270, 'W'}}) {
            int[] point = headingPoint(cx, cy, radius + 8, cardinal[1]);
            graphics.centeredText(font, String.valueOf((char) cardinal[2]),
                    panelLeft + x(point[0]), panelTop + y(point[1] - 3), CYAN);
        }

        int[] currentPoint = headingPoint(cx, cy, radius - 14, current);
        line(graphics, cx, cy, currentPoint[0], currentPoint[1], GREEN);
        int[] bug = headingPoint(cx, cy, radius - 3, selected);
        graphics.fill(panelLeft + x(bug[0] - 3), panelTop + y(bug[1] - 3),
                panelLeft + x(bug[0] + 4), panelTop + y(bug[1] + 4), AMBER);
    }

    private void drawTape(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                          int current, int selected, int step, String unit, int labelStep) {
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), 0xFF0C141A);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), BORDER);
        int center = y + height / 2;
        graphics.fill(panelLeft + x(x), panelTop + y(center - 1),
                panelLeft + x(x + width), panelTop + y(center + 1), CYAN);

        int offset = clamp((selected - current) / step, -4, 4);
        int selectedY = clamp(center - offset * 10, y + 6, y + height - 6);
        for (int i = -4; i <= 4; i++) {
            int tickY = center - i * 10;
            if (tickY < y + 3 || tickY > y + height - 3) continue;
            graphics.fill(panelLeft + x(x + 4), panelTop + y(tickY),
                    panelLeft + x(x + 18), panelTop + y(tickY + 1), MUTED);
            int value = current + i * step;
            if (i % Math.max(1, labelStep / step) == 0) {
                graphics.text(font, String.valueOf(value), panelLeft + x(x + 22),
                        panelTop + y(tickY - 4), MUTED, true);
            }
        }
        graphics.fill(panelLeft + x(x + 2), panelTop + y(selectedY - 6),
                panelLeft + x(x + width - 2), panelTop + y(selectedY - 5), AMBER);
        graphics.fill(panelLeft + x(x + width - 8), panelTop + y(selectedY - 8),
                panelLeft + x(x + width - 2), panelTop + y(selectedY - 2), AMBER);
        graphics.text(font, selected + " " + unit, panelLeft + x(x + 4),
                panelTop + y(y + height - 13), AMBER, true);
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
            graphics.fill(panelLeft + x(cx - 30), panelTop + y(tickY),
                    panelLeft + x(cx - 20), panelTop + y(tickY + 1), MUTED);
        }
        int needle = cy - clamp(selected / 100, -40, 40);
        line(graphics, cx - 28, needle, cx + 28, cy, AMBER);
        graphics.fill(panelLeft + x(cx - 34), panelTop + y(needle - 1),
                panelLeft + x(cx - 24), panelTop + y(needle + 2), AMBER);
        graphics.centeredText(font, String.format(Locale.ROOT, "%+d", current),
                panelLeft + x(cx), panelTop + y(top + 8), CYAN);
        graphics.centeredText(font, String.format(Locale.ROOT, "%+d", selected),
                panelLeft + x(cx), panelTop + y(top + height - 14), AMBER);
    }

    private void drawNavPage(GuiGraphicsExtractor graphics) {
        panel(graphics, 18, 100, 348, 196, "ILS APPROACH / GUIDANCE");
        panel(graphics, 374, 100, 208, 196, "RUNWAY REFERENCE");
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
        graphics.centeredText(font, String.format(Locale.ROOT, "LOC %+.1f°   GS %+.1f°   GS ANGLE %.1f°",
                localizer, glideslope, cachedGlideSlope), panelLeft + x(centerX),
                panelTop + y(128), ils ? TEXT : MUTED);

        graphics.text(font, "THRESHOLD X", panelLeft + x(382), panelTop + y(118), MUTED, true);
        graphics.text(font, "THRESHOLD Z", panelLeft + x(466), panelTop + y(118), MUTED, true);
        graphics.text(font, "RUNWAY HDG", panelLeft + x(382), panelTop + y(174), MUTED, true);
        graphics.text(font, "0=S  90=W  180=N  270=E", panelLeft + x(382), panelTop + y(226), MUTED, true);

        String[] status = navStatus(ils);
        int color = statusColor(status[0]);
        graphics.fill(panelLeft + x(26), panelTop + y(318), panelLeft + x(574), panelTop + y(350),
                color == GREEN ? 0xFF14301F : color == AMBER ? 0xFF302514 : 0xFF211419);
        graphics.outline(panelLeft + x(26), panelTop + y(318), w(548), h(32), color);
        graphics.centeredText(font, fit(status[0], w(534)), panelLeft + x(300), panelTop + y(324), color);
        if (status[1] != null) {
            graphics.centeredText(font, fit(status[1], w(534)), panelLeft + x(300), panelTop + y(338), MUTED);
        }
        if (!ilsMessage.isEmpty()) {
            graphics.centeredText(font, fit(ilsMessage, w(534)), panelLeft + x(300),
                    panelTop + y(356), CYAN);
        }
    }

    private String[] navStatus(boolean ils) {
        var guidance = FlightHud.autopilotGuidance();
        if (!FlightLineClient.isFlightEnabled()) {
            return new String[]{"ENABLE FLIGHT MODE", "Flight mode must be active before arming an approach."};
        }
        if (!ils) {
            return new String[]{"NO ILS COVERAGE", "Set a runway threshold inside the approach area."};
        }
        if (guidance.autolandArmed()) {
            return new String[]{"AUTOLAND ACTIVE", "Monitor the approach · gear and flaps are managed."};
        }
        if (!FlightHud.anyEngineRunning()) {
            return new String[]{"NEEDS ENGINE", "At least one engine group must be running."};
        }
        if (!guidance.atcControlled()) {
            return new String[]{"READY FOR ARM", "Gear " + (FlightHud.gearDown() ? "DOWN" : "UP")
                    + "  ·  ILS valid  ·  autopilot manages the landing."};
        }
        return new String[]{"ATC CONTROL ACTIVE", "F7 releases control before manual arming."};
    }

    private int statusColor(String status) {
        if (status.startsWith("AUTOLAND") || status.startsWith("READY")) return GREEN;
        if (status.startsWith("ATC")) return AMBER;
        return RED;
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
        graphics.centeredText(font, "THRESHOLD " + Math.round(FlightHud.thresholdDistanceBlocks()) + " BLK",
                panelLeft + x(cx), panelTop + y(y + 22), MUTED);
    }

    private void drawWeatherPage(GuiGraphicsExtractor graphics) {
        var weather = FlightHud.weatherGuidance();
        panel(graphics, 18, 100, 396, 262, "WEATHER RADAR / LIVE CONDITIONS");
        panel(graphics, 422, 100, 160, 262, "SYSTEMS");

        drawWeatherRadar(graphics, 36, 122, 360, 180, weather);
        String condition = weather.condition() == 2 ? "THUNDERSTORM"
                : weather.condition() == 1 ? "RAIN" : "CLEAR";
        int conditionColor = weather.condition() == 2 ? RED
                : weather.condition() == 1 ? CYAN : GREEN;
        graphics.centeredText(font, condition + "  ·  " + weather.windSpeedKnots() + " KT  ·  "
                + String.format(Locale.ROOT, "%03d°", weather.windDirectionDegrees()),
                panelLeft + x(216), panelTop + y(318), conditionColor);
        graphics.centeredText(font, fit("GUST " + weather.gustKnots() + " KT  ·  VISIBILITY "
                + weather.visibilityBlocks() + " BLOCKS", w(376)), panelLeft + x(216),
                panelTop + y(340), MUTED);

        gauge(graphics, 438, 146, 128, "TURBULENCE", weather.turbulencePercent(),
                weather.turbulencePercent() >= 70 ? RED : weather.turbulencePercent() >= 35 ? AMBER : GREEN);
        gauge(graphics, 438, 202, 128, "ICING", weather.icingPercent(),
                weather.icingPercent() >= 20 ? AMBER : GREEN);
        gauge(graphics, 438, 258, 128, "VISIBILITY",
                clamp(100 - weather.visibilityBlocks() / 4, 0, 100),
                weather.visibilityBlocks() < 80 ? AMBER : GREEN);

        String antiIce = weather.antiIceEffective() ? "CLEARING"
                : weather.antiIceActive() ? "ON · NEEDS POWER" : "OFF";
        graphics.text(font, "ANTI-ICE", panelLeft + x(438), panelTop + y(300), MUTED, true);
        graphics.text(font, antiIce, panelLeft + x(438), panelTop + y(314),
                weather.antiIceEffective() ? GREEN : weather.antiIceActive() ? AMBER : MUTED, true);
        graphics.text(font, "KEY " + FlightLineClient.keyName(FlightLineClient.antiIce()),
                panelLeft + x(438), panelTop + y(328), CYAN, true);
        graphics.text(font, "FUEL", panelLeft + x(438), panelTop + y(344), MUTED, true);
        graphics.text(font, FlightHud.fuelPercent() + "%", panelLeft + x(480), panelTop + y(344), TEXT, true);
        if (!ilsMessage.isEmpty()) {
            graphics.centeredText(font, fit(ilsMessage, w(376)), panelLeft + x(216),
                    panelTop + y(356), CYAN);
        }
    }

    private void drawWeatherRadar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                  FlightInstrumentPayload.WeatherGuidance weather) {
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), 0xFF081015);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), BORDER);
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

        int storm = weather.condition();
        int cells = storm == 2 ? 11 : storm == 1 ? 7 : 3;
        for (int i = 0; i < cells; i++) {
            int cellX = x + 22 + ((i * 47 + weather.windDirectionDegrees()) % (width - 44));
            int cellY = y + 22 + ((i * 31 + weather.turbulencePercent()) % (height - 44));
            int size = 8 + (i % 3) * 4;
            int color = storm == 2 && i % 3 == 0 ? RED : storm == 0 ? GREEN : AMBER;
            graphics.fill(panelLeft + x(cellX), panelTop + y(cellY),
                    panelLeft + x(cellX + size), panelTop + y(cellY + size), color);
        }

        long phase = System.nanoTime() / 25_000_000L;
        int sweep = x + (int) (phase % width);
        graphics.fill(panelLeft + x(sweep), panelTop + y(y), panelLeft + x(sweep + 2),
                panelTop + y(y + height), 0x8062D89B);
        drawWindArrow(graphics, x + 34, y + 28, weather.windDirectionDegrees(),
                weather.windSpeedKnots(), conditionColor(weather));
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
        graphics.text(font, label, panelLeft + x(x), panelTop + y(y), MUTED, true);
        graphics.text(font, percent + "%", panelLeft + x(x + width - 24), panelTop + y(y), color, true);
        graphics.fill(panelLeft + x(x), panelTop + y(y + 14), panelLeft + x(x + width),
                panelTop + y(y + 22), 0xFF1A262E);
        graphics.fill(panelLeft + x(x), panelTop + y(y + 14),
                panelLeft + x(x + Math.round(width * clamp(percent, 0, 100) / 100.0f)),
                panelTop + y(y + 22), color);
        graphics.outline(panelLeft + x(x), panelTop + y(y + 14), w(width), h(8), BORDER);
    }

    private void drawKeysPage(GuiGraphicsExtractor graphics) {
        panel(graphics, 18, 100, 396, 262, "KEYCAP REFERENCE");
        panel(graphics, 422, 100, 160, 262, "QUICK LINKS");

        keyRow(graphics, 34, 118, "GEAR", FlightLineClient.keyName(FlightLineClient.toggleGear()));
        keyRow(graphics, 34, 146, "AUTOPILOT PANEL", FlightLineClient.keyName(FlightLineClient.toggleAutopilot()));
        keyRow(graphics, 34, 174, "ENGINES / INVENTORY",
                FlightLineClient.keyName(FlightLineClient.inventory()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.shutdownEngines()));
        keyRow(graphics, 34, 202, "THROTTLE",
                FlightLineClient.keyName(FlightLineClient.throttleUp()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.throttleDown()));
        keyRow(graphics, 34, 230, "RUDDER",
                FlightLineClient.keyName(FlightLineClient.rudderLeft()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.rudderRight()));
        keyRow(graphics, 34, 258, "FLAPS",
                FlightLineClient.keyName(FlightLineClient.flapsIncrease()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.flapsDecrease()));
        keyRow(graphics, 34, 286, "SPOILERS / AIRBRAKES",
                FlightLineClient.keyName(FlightLineClient.spoilersToggle()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.airbrakeToggle()));
        keyRow(graphics, 34, 314, "PUSHBACK / BRAKES",
                FlightLineClient.keyName(FlightLineClient.pushback()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.brakes()));
        keyRow(graphics, 34, 342, "EMERGENCY / FIX",
                FlightLineClient.keyName(FlightLineClient.emergencyChecklist()) + " / "
                        + FlightLineClient.keyName(FlightLineClient.executeEmergency()));

        graphics.text(font, "DISPLAYS", panelLeft + x(438), panelTop + y(126), CYAN, true);
        graphics.text(font, FlightLineClient.keyName(FlightLineClient.flightRadar()) + " radar",
                panelLeft + x(438), panelTop + y(144), TEXT, true);
        graphics.text(font, FlightLineClient.keyName(FlightLineClient.togglePfd()) + " PFD",
                panelLeft + x(438), panelTop + y(160), TEXT, true);
        graphics.text(font, FlightLineClient.keyName(FlightLineClient.toggleEicas()) + " EICAS",
                panelLeft + x(438), panelTop + y(176), TEXT, true);
        graphics.text(font, FlightLineClient.keyName(FlightLineClient.toggleNdRadar()) + " ND",
                panelLeft + x(438), panelTop + y(192), TEXT, true);
        graphics.text(font, FlightLineClient.keyName(FlightLineClient.antiIce()) + " anti-ice",
                panelLeft + x(438), panelTop + y(208), TEXT, true);
        graphics.text(font, "ATC RELEASE", panelLeft + x(438), panelTop + y(236), CYAN, true);
        graphics.text(font, "F7 when cleared", panelLeft + x(438), panelTop + y(254), AMBER, true);
        graphics.text(font, "FLAPS add lift and drag", panelLeft + x(438), panelTop + y(282), MUTED, true);
        graphics.text(font, "SPOILERS dump lift", panelLeft + x(438), panelTop + y(298), MUTED, true);
        graphics.text(font, "AIRBRAKES add drag", panelLeft + x(438), panelTop + y(314), MUTED, true);
    }

    private void keyRow(GuiGraphicsExtractor graphics, int x, int y, String label, String key) {
        graphics.text(font, fit(label, w(150)), panelLeft + x(x), panelTop + y(y + 6), TEXT, true);
        int keyX = x + 158;
        int keyWidth = 236;
        graphics.fill(panelLeft + x(keyX), panelTop + y(y), panelLeft + x(keyX + keyWidth),
                panelTop + y(y + 22), 0xFF1B2A33);
        graphics.outline(panelLeft + x(keyX), panelTop + y(y), w(keyWidth), h(22), BORDER);
        graphics.fill(panelLeft + x(keyX), panelTop + y(y + 19), panelLeft + x(keyX + keyWidth),
                panelTop + y(y + 22), 0xFF243743);
        graphics.centeredText(font, fit(key, w(keyWidth) - 8), panelLeft + x(keyX + keyWidth / 2),
                panelTop + y(y + 6), CYAN);
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
        graphics.text(font, title, panelLeft + x(x + 7), panelTop + y(y + 4), CYAN, true);
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
            default -> "KEYS";
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
