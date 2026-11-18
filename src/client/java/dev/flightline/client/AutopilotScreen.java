package dev.flightline.client;

import dev.flightline.flight.FlightWarning;
import dev.flightline.flight.NavMode;
import dev.flightline.flight.WeatherSimulation;
import dev.flightline.network.ConfigureAutopilotPayload;
import dev.flightline.network.ConfigureIlsPayload;
import dev.flightline.network.FileFlightPlanPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.NavDataPayload;
import dev.flightline.network.ToggleAutolandPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Graphical mode control panel with live flight-director, ILS and weather pages. */
public class AutopilotScreen extends Screen {
    private static final int BASE_WIDTH = 600;
    private static final int BASE_HEIGHT = 400;

    private static final int TEXT = FlightLineTheme.PAPER;
    private static final int MUTED = FlightLineTheme.MUTED;
    private static final int CYAN = FlightLineTheme.CYAN;
    private static final int GREEN = FlightLineTheme.GREEN;
    private static final int AMBER = FlightLineTheme.AMBER;
    private static final int RED = FlightLineTheme.RED;
    private static final int PURPLE = 0xFFC678FF;
    private static final int PANEL_FILL = FlightLineTheme.PANEL;
    private static final int CARD_FILL = FlightLineTheme.CARD;
    private static final int BORDER = FlightLineTheme.STONE;
    private static final int ALARM = FlightLineTheme.WARNING;
    private static final int ALARM_DARK = FlightLineTheme.WARNING_DARK;
    private static final int TCAS_RA = FlightLineTheme.TCAS_RA;
    private static final int TCAS_TA = FlightLineTheme.TCAS_TA;

    private static final int PAGE_ALERTS = 5;

    private static final int MCP_COLUMN_X = 24;
    private static final int MCP_COLUMN_WIDTH = 129;
    private static final int MCP_COLUMN_GAP = 12;
    private static final int MCP_INSTRUMENT_TOP = 100;
    private static final int MCP_INSTRUMENT_HEIGHT = 140;
    private static final int MCP_SELECT_TOP = 250;
    private static final int MCP_SELECT_HEIGHT = 90;

    private static final int TCAS_WARNINGS = FlightWarning.TCAS_TRAFFIC
            | FlightWarning.TCAS_RESOLUTION
            | FlightWarning.TCAS_CLIMB
            | FlightWarning.TCAS_DESCEND
            | FlightWarning.TCAS_CROSSING
            | FlightWarning.TCAS_CLEAR;

    private record AlertEntry(int bit, String label, boolean urgent) {}

    private record AdvisoryEntry(String label, int color) {}

    private record FocusSnapshot(int page, int field, int cursor) {}

    /** Priority order for the flight-deck alert list; TCAS is handled separately. */
    private static final List<AlertEntry> ALERT_ENTRIES = List.of(
            new AlertEntry(FlightWarning.STALL, "STALL", true),
            new AlertEntry(FlightWarning.WINDSHEAR, "WINDSHEAR", true),
            new AlertEntry(FlightWarning.PULL_UP, "PULL UP", true),
            new AlertEntry(FlightWarning.TERRAIN, "TERRAIN", true),
            new AlertEntry(FlightWarning.TOO_LOW_TERRAIN, "TOO LOW TERRAIN", true),
            new AlertEntry(FlightWarning.SINK_RATE, "SINK RATE", true),
            new AlertEntry(FlightWarning.DONT_SINK, "DON'T SINK", true),
            new AlertEntry(FlightWarning.BANK_ANGLE, "BANK ANGLE", true),
            new AlertEntry(FlightWarning.GEAR_UP, "TOO LOW GEAR", true),
            new AlertEntry(FlightWarning.TOO_LOW_FLAPS, "TOO LOW FLAPS", true),
            new AlertEntry(FlightWarning.OVERSPEED, "OVERSPEED", true),
            new AlertEntry(FlightWarning.AIRFRAME_STRESS, "AIRFRAME STRESS", true),
            new AlertEntry(FlightWarning.ENGINE_FIRE, "ENGINE FIRE", true),
            new AlertEntry(FlightWarning.ENGINE_FAILURE, "ENGINE FAILURE", true),
            new AlertEntry(FlightWarning.FUEL_EMPTY, "FUEL EMPTY", true),
            new AlertEntry(FlightWarning.FUEL_LOW, "LOW FUEL", false),
            new AlertEntry(FlightWarning.GEAR_FAILED, "GEAR FAILURE", true),
            new AlertEntry(FlightWarning.GEAR_HOT, "GEAR HOT", false),
            new AlertEntry(FlightWarning.GEAR_SELECTED_ON_GROUND, "GEAR SELECT ON GROUND", false),
            new AlertEntry(FlightWarning.TAKEOFF_CONFIG, "TAKEOFF CONFIG", false),
            new AlertEntry(FlightWarning.GLIDESLOPE_HIGH, "GLIDESLOPE HIGH", false),
            new AlertEntry(FlightWarning.GLIDESLOPE_LOW, "GLIDESLOPE LOW", false),
            new AlertEntry(FlightWarning.ICE_ACCUMULATION, "AIRFRAME ICE", false),
            new AlertEntry(FlightWarning.HYDRAULIC_FAIL, "HYDRAULIC FAIL", false),
            new AlertEntry(FlightWarning.PRESSURISATION, "CABIN ALTITUDE", false)
    );

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
    private @Nullable AbstractWidget apMasterBtn;
    private boolean engaged;
    private int activePage;
    private String ilsMessage = "";
    private int fplnOrigin;
    private int fplnDestination;
    private int fplnAlternate;
    private int fplnAltitude = 12_000;
    private int fplnFixIndex;
    private int fplnWaypointIndex;
    private List<Integer> fplnWaypoints = new ArrayList<>();
    private String fplnMessage = "";

    // Page-local drafts. Widgets are rebuilt often; these plain fields stay authoritative.
    private String mcpHeadingText = "";
    private String mcpAltitudeText = "";
    private String mcpVsText = "";
    private String mcpSpeedText = "";
    private String appliedMcpHeadingText = "";
    private String appliedMcpAltitudeText = "";
    private String appliedMcpVsText = "";
    private String appliedMcpSpeedText = "";
    private String ilsXText = "";
    private String ilsZText = "";
    private String ilsHeadingText = "";
    private String appliedIlsXText = "";
    private String appliedIlsZText = "";
    private String appliedIlsHeadingText = "";
    private float appliedGlideSlope = 3.0f;
    private int appliedFplnOrigin;
    private int appliedFplnDestination;
    private int appliedFplnAlternate;
    private int appliedFplnAltitude = 12_000;
    private List<Integer> appliedFplnWaypoints = new ArrayList<>();
    private @Nullable List<NavDataPayload.Airport> knownAirports;
    private String knownPlanSignature = "";
    private @Nullable EditBox lastFocusedBox;

    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public AutopilotScreen(boolean currentlyEngaged, int currentHeading, int currentAltitudeFeet,
                           int currentVsFpm, int currentSpeedKnots) {
        this(currentlyEngaged, currentHeading, currentAltitudeFeet, currentVsFpm, currentSpeedKnots, 0);
    }

    public AutopilotScreen(boolean currentlyEngaged, int currentHeading, int currentAltitudeFeet,
                           int currentVsFpm, int currentSpeedKnots, int initialPage) {
        super(Component.literal("Mode Control Panel"));
        this.engaged = currentlyEngaged;
        this.activePage = Math.max(0, Math.min(PAGE_ALERTS, initialPage));
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
        initializeDrafts();
    }

    private void initializeDrafts() {
        mcpHeadingText = String.format(Locale.ROOT, "%03d", cachedHeading);
        mcpAltitudeText = String.valueOf(cachedAltitude);
        mcpVsText = String.valueOf(cachedVs);
        mcpSpeedText = String.valueOf(cachedSpeed);
        appliedMcpHeadingText = mcpHeadingText;
        appliedMcpAltitudeText = mcpAltitudeText;
        appliedMcpVsText = mcpVsText;
        appliedMcpSpeedText = mcpSpeedText;
        ilsXText = String.valueOf(cachedThresholdX);
        ilsZText = String.valueOf(cachedThresholdZ);
        ilsHeadingText = String.valueOf(cachedRunwayHeading);
        appliedIlsXText = ilsXText;
        appliedIlsZText = ilsZText;
        appliedIlsHeadingText = ilsHeadingText;
        appliedGlideSlope = cachedGlideSlope;
        resetFlightPlanDraft();
        captureAppliedFlightPlan();
        knownAirports = NavigationState.airports();
        knownPlanSignature = planSignature();
    }

    @Override
    protected void init() {
        super.init();
        scaler.update(width, height);
        panelWidth = BASE_WIDTH;
        panelHeight = BASE_HEIGHT;
        panelLeft = 8;
        panelTop = 8;

        addRenderableWidget(tabButton("MCP", 0, 12, 96));
        addRenderableWidget(tabButton("FPLN", 1, 114, 78));
        addRenderableWidget(tabButton("NAV / ILS", 2, 198, 112));
        addRenderableWidget(tabButton("WX", 3, 316, 70));
        addRenderableWidget(tabButton("SYS", 4, 392, 84));
        addRenderableWidget(tabButton("ALERT", PAGE_ALERTS, 482, 100));

        if (activePage == 0) {
            initMcpPage();
        } else if (activePage == 1) {
            initFlightPlanPage();
        } else if (activePage == 2) {
            initNavPage();
        } else if (activePage == 3) {
            initWeatherPage();
        } else if (activePage == PAGE_ALERTS) {
            initAlertsPage();
        } else {
            initSystemsPage();
        }

        addRenderableWidget(FlightLineTheme.button("APPLY", x(494), y(370), w(94), h(20),
                this::onClose));
    }

    private void initMcpPage() {
        headingBox = numberInput(mcpColumnCenter(0) - 29, 274, 58, "HDG", mcpHeadingText, 3);
        altitudeBox = numberInput(mcpColumnCenter(1) - 29, 274, 58, "ALT", mcpAltitudeText, 5);
        vsBox = numberInput(mcpColumnCenter(2) - 29, 274, 58, "V/S", mcpVsText, 5);
        speedBox = numberInput(mcpColumnCenter(3) - 29, 274, 58, "IAS", mcpSpeedText, 3);

        addRenderableWidget(stepButton("-10", mcpColumnX(0) + 7, 298, 55, () -> adjustHeading(-10)));
        addRenderableWidget(stepButton("+10", mcpColumnX(0) + 66, 298, 55, () -> adjustHeading(10)));
        addRenderableWidget(compactActionButton("SYNC", mcpColumnX(0) + 26, 320, 76,
                this::syncHeading));

        addRenderableWidget(stepButton("-500", mcpColumnX(1) + 7, 298, 55,
                () -> adjustAltitude(-500)));
        addRenderableWidget(stepButton("+500", mcpColumnX(1) + 66, 298, 55,
                () -> adjustAltitude(500)));
        addRenderableWidget(compactActionButton("SYNC", mcpColumnX(1) + 26, 320, 76,
                this::syncAltitude));

        addRenderableWidget(stepButton("-500", mcpColumnX(2) + 7, 298, 55, () -> adjustVs(-500)));
        addRenderableWidget(stepButton("+500", mcpColumnX(2) + 66, 298, 55, () -> adjustVs(500)));
        addRenderableWidget(compactActionButton("LEVEL", mcpColumnX(2) + 26, 320, 76,
                this::setVsZero));

        addRenderableWidget(stepButton("-10", mcpColumnX(3) + 7, 298, 55, () -> adjustSpeed(-10)));
        addRenderableWidget(stepButton("+10", mcpColumnX(3) + 66, 298, 55, () -> adjustSpeed(10)));
        addRenderableWidget(compactActionButton("SYNC", mcpColumnX(3) + 26, 320, 76,
                this::syncSpeed));

        apMasterBtn = FlightLineTheme.button(apButtonText(), x(mcpColumnX(0)), y(350),
                w(MCP_COLUMN_WIDTH), h(20), this::toggleAp);
        addRenderableWidget(apMasterBtn);
    }

    private void initFlightPlanPage() {
        addRenderableWidget(compactActionButton(airportLabel(fplnOrigin, "ORIGIN"), 28, 120, 108,
                () -> cycleAirport(true)));
        addRenderableWidget(compactActionButton(airportLabel(fplnDestination, "DEST"), 144, 120, 108,
                () -> cycleAirport(false)));
        addRenderableWidget(compactActionButton(airportLabel(fplnAlternate, "ALTN"), 28, 146, 108,
                this::cycleAlternate));
        addRenderableWidget(compactActionButton("-1000", 144, 146, 52, () -> adjustCruiseAltitude(-1000)));
        addRenderableWidget(compactActionButton("+1000", 200, 146, 52, () -> adjustCruiseAltitude(1000)));

        addRenderableWidget(compactActionButton("< FIX", 276, 120, 60, this::previousFix));
        addRenderableWidget(compactActionButton("ADD", 340, 120, 60, this::addWaypoint));
        addRenderableWidget(compactActionButton("FIX >", 404, 120, 60, this::nextFix));
        addRenderableWidget(compactActionButton("REMOVE", 468, 120, 100, this::removeWaypoint));
        addRenderableWidget(compactActionButton("WP UP", 276, 146, 76, () -> moveWaypoint(-1)));
        addRenderableWidget(compactActionButton("WP DOWN", 356, 146, 84, () -> moveWaypoint(1)));
        addRenderableWidget(compactActionButton("CLEAR ROUTE", 444, 146, 124, () -> {
            fplnWaypoints.clear();
            fplnWaypointIndex = 0;
            fplnMessage = "WAYPOINT LIST CLEARED";
            rebuild();
        }));

        addRenderableWidget(compactActionButton("FILE", 28, 316, 74, () -> sendPlanAction(
                FileFlightPlanPayload.Action.FILE)));
        addRenderableWidget(compactActionButton("REQUEST CLR", 108, 316, 100, () -> sendPlanAction(
                FileFlightPlanPayload.Action.CLEARANCE)));
        addRenderableWidget(compactActionButton("LNAV", 214, 316, 68, () -> sendPlanAction(
                FileFlightPlanPayload.Action.LNAV)));
        addRenderableWidget(compactActionButton("ILS APP", 288, 316, 80, () -> sendPlanAction(
                FileFlightPlanPayload.Action.APPROACH)));
        addRenderableWidget(compactActionButton("CANCEL", 374, 316, 80, () -> sendPlanAction(
                FileFlightPlanPayload.Action.CANCEL)));
        addRenderableWidget(compactActionButton("SYNC ALT", 460, 316, 108, () -> sendPlanAction(
                FileFlightPlanPayload.Action.SYNC)));
    }

    private void initNavPage() {
        thresholdXBox = numberInput(382, 124, 76, "X", ilsXText, 10);
        thresholdZBox = numberInput(466, 124, 76, "Z", ilsZText, 10);
        runwayHeadingBox = numberInput(382, 188, 76, "HDG", ilsHeadingText, 3);

        addRenderableWidget(compactActionButton("- 0.1°", 382, 156, 76, () -> adjustGlideSlope(-0.1f)));
        addRenderableWidget(compactActionButton("+ 0.1°", 466, 156, 76, () -> adjustGlideSlope(0.1f)));
        addRenderableWidget(compactActionButton("SET RUNWAY", 382, 222, 160, this::setIls));
        addRenderableWidget(compactActionButton("USE MY POSITION", 382, 246, 160, this::useCurrentPosition));
        addRenderableWidget(compactActionButton("CLEAR ILS", 382, 270, 160, this::clearIls));

        String armLabel = FlightHud.autopilotGuidance().autolandArmed()
                ? "DISARM AUTOLAND" : "ARM ILS AUTOLAND";
        var autoland = actionButton(armLabel, 382, 324, 160, () -> {
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

    private void initAlertsPage() {
        // The alerts page is a readonly EICAS/TCAS readout with no editable controls.
    }

    private net.minecraft.client.gui.components.AbstractWidget tabButton(String label, int page, int x, int width) {
        String shortLabel = label.equals("NAV / ILS") && panelWidth < 420 ? "NAV" : label;
        String text = activePage == page ? "[" + shortLabel + "]" : shortLabel;
        return FlightLineTheme.button(fit(text, w(width) - 8), x(x), y(30), w(width), h(20),
                () -> switchPage(page));
    }

    private net.minecraft.client.gui.components.AbstractWidget stepButton(String label, int x, int y, int width,
                                                                         Runnable action) {
        return FlightLineTheme.button(fit(label, w(width) - 6), x(x), y(y), w(width), h(20), action);
    }

    private net.minecraft.client.gui.components.AbstractWidget actionButton(String label, int x, int y, int width,
                                                                           Runnable action) {
        return FlightLineTheme.button(fit(label, w(width) - 8), x(x), y(y), w(width), h(22), action);
    }

    private net.minecraft.client.gui.components.AbstractWidget compactActionButton(String label, int x, int y,
                                                                                  int width, Runnable action) {
        return FlightLineTheme.button(fit(label, w(width) - 8), x(x), y(y), w(width), h(18), action);
    }

    private EditBox numberInput(int x, int y, int width, String label, String value, int maxLength) {
        EditBox input = new EditBox(font, x(x), y(y), w(width), h(20), Component.literal(label));
        input.setValue(value);
        input.setMaxLength(maxLength);
        addRenderableWidget(input);
        return input;
    }

    private void switchPage(int page) {
        if (activePage == page) return;
        FocusSnapshot focus = captureFocus();
        commitPage(activePage);
        activePage = page;
        ilsMessage = "";
        syncAuthoritativeDraft();
        rebuild();
        restoreFocus(focus);
    }

    private void commitPage(int page) {
        if (page == 0) saveAutopilotValues();
        else if (page == 2) commitIlsValues();
    }

    private void saveAutopilotValues() {
        if (headingBox == null) return;
        mcpHeadingText = headingInput().getValue();
        mcpAltitudeText = altitudeInput().getValue();
        mcpVsText = verticalSpeedInput().getValue();
        mcpSpeedText = speedInput().getValue();
        cachedHeading = parseHeading();
        cachedAltitude = parseAltitude();
        cachedVs = parseVs();
        cachedSpeed = parseSpeed();
    }

    /** Strict save used by SET RUNWAY so invalid text still gets the existing message. */
    private void saveIlsValues() {
        if (thresholdXBox == null) return;
        ilsXText = thresholdXInput().getValue();
        ilsZText = thresholdZInput().getValue();
        ilsHeadingText = runwayHeadingInput().getValue();
        cachedThresholdX = parseCoordinate(thresholdXInput(), "THRESHOLD X");
        cachedThresholdZ = parseCoordinate(thresholdZInput(), "THRESHOLD Z");
        cachedRunwayHeading = Math.floorMod(parseCoordinate(runwayHeadingInput(), "RWY HDG"), 360);
    }

    /** Quiet save for focus loss and tab changes: keep the last good numbers on bad text. */
    private void commitIlsValues() {
        if (thresholdXBox == null) return;
        ilsXText = thresholdXInput().getValue();
        ilsZText = thresholdZInput().getValue();
        ilsHeadingText = runwayHeadingInput().getValue();
        try {
            cachedThresholdX = parseCoordinate(thresholdXInput(), "THRESHOLD X");
            cachedThresholdZ = parseCoordinate(thresholdZInput(), "THRESHOLD Z");
            cachedRunwayHeading = Math.floorMod(parseCoordinate(runwayHeadingInput(), "RWY HDG"), 360);
        } catch (IllegalArgumentException ignored) {
            // The text draft survives; the next explicit SET RUNWAY will report the problem.
        }
    }

    private void rebuild() {
        clearFocus();
        clearWidgets();
        headingBox = null;
        altitudeBox = null;
        vsBox = null;
        speedBox = null;
        thresholdXBox = null;
        thresholdZBox = null;
        runwayHeadingBox = null;
        apMasterBtn = null;
        lastFocusedBox = null;
        init();
    }

    private @Nullable EditBox focusedInput() {
        for (EditBox box : editBoxes()) {
            if (box.isFocused()) return box;
        }
        return null;
    }

    private List<EditBox> editBoxes() {
        List<EditBox> boxes = new ArrayList<>();
        if (headingBox != null) boxes.add(headingBox);
        if (altitudeBox != null) boxes.add(altitudeBox);
        if (vsBox != null) boxes.add(vsBox);
        if (speedBox != null) boxes.add(speedBox);
        if (thresholdXBox != null) boxes.add(thresholdXBox);
        if (thresholdZBox != null) boxes.add(thresholdZBox);
        if (runwayHeadingBox != null) boxes.add(runwayHeadingBox);
        return boxes;
    }

    private int fieldIndex(EditBox box) {
        if (box == headingBox) return 0;
        if (box == altitudeBox) return 1;
        if (box == vsBox) return 2;
        if (box == speedBox) return 3;
        if (box == thresholdXBox) return 0;
        if (box == thresholdZBox) return 1;
        if (box == runwayHeadingBox) return 2;
        return -1;
    }

    private @Nullable EditBox inputFor(int page, int field) {
        if (page == 0) {
            return switch (field) {
                case 0 -> headingBox;
                case 1 -> altitudeBox;
                case 2 -> vsBox;
                case 3 -> speedBox;
                default -> null;
            };
        }
        if (page == 2) {
            return switch (field) {
                case 0 -> thresholdXBox;
                case 1 -> thresholdZBox;
                case 2 -> runwayHeadingBox;
                default -> null;
            };
        }
        return null;
    }

    private @Nullable FocusSnapshot captureFocus() {
        EditBox box = focusedInput();
        if (box == null) return null;
        return new FocusSnapshot(activePage, fieldIndex(box), box.getCursorPosition());
    }

    private void restoreFocus(@Nullable FocusSnapshot focus) {
        if (focus == null || focus.page() != activePage || focus.field() < 0) return;
        EditBox box = inputFor(focus.page(), focus.field());
        if (box == null) return;
        setInitialFocus(box);
        box.setFocused(true);
        int cursor = Math.max(0, Math.min(focus.cursor(), box.getValue().length()));
        box.setCursorPosition(cursor);
        box.setHighlightPos(cursor);
        lastFocusedBox = box;
    }

    private void updateFocusCommit() {
        EditBox focused = focusedInput();
        if (focused == lastFocusedBox) return;
        if (lastFocusedBox != null) commitPage(activePage);
        lastFocusedBox = focused;
    }

    private String planSignature() {
        var plan = NavigationState.plan();
        if (plan == null) return "none";
        return plan.revision() + ":" + plan.originId() + ":" + plan.destinationId() + ":"
                + plan.alternateId() + ":" + plan.cruiseAltitudeFeet() + ":" + plan.waypointIds();
    }

    /** A fresh nav snapshot or an authoritative plan revision replaces stale local drafts. */
    private boolean syncAuthoritativeDraft() {
        List<NavDataPayload.Airport> airports = NavigationState.airports();
        String signature = planSignature();
        if (airports == knownAirports && signature.equals(knownPlanSignature)) return false;
        knownAirports = airports;
        knownPlanSignature = signature;
        resetFlightPlanDraft();
        captureAppliedFlightPlan();
        fplnMessage = "NAV DATA UPDATED";
        return true;
    }

    private void captureAppliedFlightPlan() {
        appliedFplnOrigin = fplnOrigin;
        appliedFplnDestination = fplnDestination;
        appliedFplnAlternate = fplnAlternate;
        appliedFplnAltitude = fplnAltitude;
        appliedFplnWaypoints = new ArrayList<>(fplnWaypoints);
    }

    private void captureAppliedIls() {
        appliedIlsXText = ilsXText;
        appliedIlsZText = ilsZText;
        appliedIlsHeadingText = ilsHeadingText;
        appliedGlideSlope = cachedGlideSlope;
    }

    private void captureAppliedMcp() {
        appliedMcpHeadingText = mcpHeadingText;
        appliedMcpAltitudeText = mcpAltitudeText;
        appliedMcpVsText = mcpVsText;
        appliedMcpSpeedText = mcpSpeedText;
    }

    private boolean hasUnsentChanges() {
        return switch (activePage) {
            case 0 -> !mcpHeadingText.equals(appliedMcpHeadingText)
                    || !mcpAltitudeText.equals(appliedMcpAltitudeText)
                    || !mcpVsText.equals(appliedMcpVsText)
                    || !mcpSpeedText.equals(appliedMcpSpeedText);
            case 1 -> fplnOrigin != appliedFplnOrigin
                    || fplnDestination != appliedFplnDestination
                    || fplnAlternate != appliedFplnAlternate
                    || fplnAltitude != appliedFplnAltitude
                    || !fplnWaypoints.equals(appliedFplnWaypoints);
            case 2 -> !ilsXText.equals(appliedIlsXText)
                    || !ilsZText.equals(appliedIlsZText)
                    || !ilsHeadingText.equals(appliedIlsHeadingText)
                    || Float.compare(cachedGlideSlope, appliedGlideSlope) != 0;
            default -> false;
        };
    }

    private void resetFlightPlanDraft() {
        List<NavDataPayload.Airport> airports = NavigationState.airports();
        var current = NavigationState.plan();
        if (airports.isEmpty()) {
            fplnOrigin = fplnDestination = fplnAlternate = 0;
            fplnWaypoints = new ArrayList<>();
            return;
        }
        if (current != null) {
            fplnOrigin = Math.max(0, airportIndex(current.originId()));
            fplnDestination = Math.max(0, airportIndex(current.destinationId()));
            fplnAlternate = Math.max(0, airportIndex(current.alternateId()));
            fplnAltitude = current.cruiseAltitudeFeet();
            fplnWaypoints = new ArrayList<>(current.waypointIds());
        } else {
            fplnOrigin = 0;
            fplnDestination = Math.min(1, airports.size() - 1);
            fplnAlternate = Math.min(2, airports.size() - 1);
            fplnAltitude = 12_000;
            fplnWaypoints = new ArrayList<>();
        }
        fplnWaypointIndex = 0;
        fplnFixIndex = 0;
    }

    private int airportIndex(int airportId) {
        List<NavDataPayload.Airport> airports = NavigationState.airports();
        for (int i = 0; i < airports.size(); i++) {
            if (airports.get(i).id() == airportId) return i;
        }
        return 0;
    }

    private int airportId(int index) {
        List<NavDataPayload.Airport> airports = NavigationState.airports();
        return airports.isEmpty() ? 0 : airports.get(Math.floorMod(index, airports.size())).id();
    }

    private String airportLabel(int index, String prefix) {
        List<NavDataPayload.Airport> airports = NavigationState.airports();
        String value = airports.isEmpty() ? "----" : airports.get(Math.floorMod(index, airports.size())).name();
        return prefix + " " + value;
    }

    private void cycleAirport(boolean origin) {
        int count = NavigationState.airports().size();
        if (count == 0) {
            fplnMessage = "NAV DATA UNAVAILABLE";
            return;
        }
        if (origin) fplnOrigin = Math.floorMod(fplnOrigin + 1, count);
        else fplnDestination = Math.floorMod(fplnDestination + 1, count);
        if (fplnOrigin == fplnDestination) fplnDestination = Math.floorMod(fplnDestination + 1, count);
        rebuild();
    }

    private void cycleAlternate() {
        int count = NavigationState.airports().size();
        if (count == 0) {
            fplnMessage = "NAV DATA UNAVAILABLE";
            return;
        }
        fplnAlternate = Math.floorMod(fplnAlternate + 1, count);
        rebuild();
    }

    private void adjustCruiseAltitude(int delta) {
        fplnAltitude = clamp(fplnAltitude + delta, 1000, 40_000);
        rebuild();
    }

    private void previousFix() {
        int count = NavigationState.fixes().size();
        if (count == 0) {
            fplnMessage = "NAV DATA UNAVAILABLE";
            return;
        }
        fplnFixIndex = Math.floorMod(fplnFixIndex - 1, count);
        rebuild();
    }

    private void nextFix() {
        int count = NavigationState.fixes().size();
        if (count == 0) {
            fplnMessage = "NAV DATA UNAVAILABLE";
            return;
        }
        fplnFixIndex = Math.floorMod(fplnFixIndex + 1, count);
        rebuild();
    }

    private void addWaypoint() {
        var fixes = NavigationState.fixes();
        if (fixes.isEmpty() || fplnWaypoints.size() >= FileFlightPlanPayload.MAX_WAYPOINTS) {
            fplnMessage = fixes.isEmpty() ? "NAV DATA UNAVAILABLE" : "WAYPOINT LIMIT REACHED";
            return;
        }
        var fix = fixes.get(Math.floorMod(fplnFixIndex, fixes.size()));
        fplnWaypoints.add(fix.id());
        fplnWaypointIndex = fplnWaypoints.size() - 1;
        fplnMessage = "ADDED " + fix.name();
        rebuild();
    }

    private void removeWaypoint() {
        if (fplnWaypoints.isEmpty()) {
            fplnMessage = "NO WAYPOINTS";
            return;
        }
        int removed = fplnWaypoints.remove(Math.min(fplnWaypointIndex, fplnWaypoints.size() - 1));
        var fix = NavigationState.fix(removed);
        fplnWaypointIndex = Math.min(fplnWaypointIndex, Math.max(0, fplnWaypoints.size() - 1));
        fplnMessage = "REMOVED " + (fix == null ? removed : fix.name());
        rebuild();
    }

    private void moveWaypoint(int delta) {
        int size = fplnWaypoints.size();
        if (size < 2) {
            fplnMessage = "NEED TWO WAYPOINTS TO REORDER";
            return;
        }
        int from = Math.min(fplnWaypointIndex, size - 1);
        int to = from + delta;
        if (to < 0 || to >= size) {
            fplnMessage = "WAYPOINT AT LIST EDGE";
            return;
        }
        int value = fplnWaypoints.remove(from);
        fplnWaypoints.add(to, value);
        fplnWaypointIndex = to;
        rebuild();
    }

    private void sendPlanAction(FileFlightPlanPayload.Action action) {
        if (!NavigationState.hasNavData()) {
            fplnMessage = "NAV DATA UNAVAILABLE";
            return;
        }
        ClientPlayNetworking.send(new FileFlightPlanPayload(airportId(fplnOrigin),
                airportId(fplnDestination), List.copyOf(fplnWaypoints), fplnAltitude,
                airportId(fplnAlternate), action));
        captureAppliedFlightPlan();
        fplnMessage = switch (action) {
            case FILE -> "PLAN SENT TO ATC";
            case CLEARANCE -> "CLEARANCE REQUESTED";
            case CANCEL -> "CANCEL REQUESTED";
            case LNAV -> "LNAV REQUESTED";
            case APPROACH -> "APPROACH REQUESTED";
            case SYNC -> "ALTITUDE SYNC REQUESTED";
        };
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
        ilsXText = String.valueOf(player.blockPosition().getX());
        ilsZText = String.valueOf(player.blockPosition().getZ());
        ilsHeadingText = String.valueOf(Math.floorMod(Math.round(player.getYRot()), 360));
        thresholdXInput().setValue(ilsXText);
        thresholdZInput().setValue(ilsZText);
        runwayHeadingInput().setValue(ilsHeadingText);
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
        captureAppliedIls();
        ilsMessage = "ILS TUNED";
    }

    private void clearIls() {
        if (!FlightLineClient.isFlightEnabled()) {
            ilsMessage = "AIRCRAFT NOT CONFIGURED";
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(false, cachedGlideSlope,
                cachedThresholdX, cachedThresholdZ, cachedRunwayHeading));
        captureAppliedIls();
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
    public void tick() {
        super.tick();
        if (!syncAuthoritativeDraft()) return;
        FocusSnapshot focus = captureFocus();
        commitPage(activePage);
        rebuild();
        restoreFocus(focus);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateFocusCommit();
        updateGeometry();
        scaler.update(width, height);
        scaler.push(graphics);
        scaler.scrim(graphics);
        FlightLineTheme.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL_FILL, BORDER);
        FlightLineTheme.header(graphics, font, panelLeft + 4, panelTop + 4, panelWidth - 8, h(24),
                "01", "AUTOPILOT / FLIGHT DIRECTOR", GREEN);
        drawHeaderStatus(graphics);

        drawFma(graphics);
        FlightLineTheme.card(graphics, panelLeft + x(12), panelTop + y(92), w(576), h(280), GREEN);

        switch (activePage) {
            case 0 -> drawMcpPage(graphics);
            case 1 -> drawFlightPlanPage(graphics);
            case 2 -> drawNavPage(graphics);
            case 3 -> drawWeatherPage(graphics);
            case PAGE_ALERTS -> drawAlertsPage(graphics);
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
    }

    /** Small header readout: alerts live on their own tab, not over the HUD. */
    private void drawHeaderStatus(GuiGraphicsExtractor graphics) {
        int warnings = FlightHud.activeWarnings();
        int nonTcas = Integer.bitCount(warnings & ~TCAS_WARNINGS) + (FlightHud.isCrashed() ? 1 : 0);
        int tcas = Integer.bitCount(warnings & TCAS_WARNINGS);
        String label;
        int color;
        if (nonTcas > 0) {
            label = "ALERTS " + nonTcas;
            color = ALARM;
        } else if (tcas > 0) {
            label = "TCAS " + tcas;
            color = TCAS_TA;
        } else {
            label = "ILS SETUP: NAV/ILS 1-4";
            color = MUTED;
        }
        fittedText(graphics, label, panelLeft + x(464), panelTop + y(20), color, w(136), true);
        drawUnsentMark(graphics);
    }

    /** Small amber index in the header: only the active page's shown values are compared. */
    private void drawUnsentMark(GuiGraphicsExtractor graphics) {
        if (!hasUnsentChanges()) return;
        int markX = panelLeft + x(294);
        int markY = panelTop + y(19);
        graphics.fill(markX, markY, markX + 2, markY + 12, AMBER);
        fittedText(graphics, "UNSENT CHANGES", markX + 7, markY + 1, AMBER, w(112), true);
    }

    private static int mcpColumnX(int index) {
        return MCP_COLUMN_X + index * (MCP_COLUMN_WIDTH + MCP_COLUMN_GAP);
    }

    private static int mcpColumnCenter(int index) {
        return mcpColumnX(index) + MCP_COLUMN_WIDTH / 2;
    }

    private void drawFma(GuiGraphicsExtractor graphics) {
        var guidance = FlightHud.autopilotGuidance();
        boolean ap = FlightHud.isAutopilotEngaged() || engaged;
        boolean at = ap;
        boolean lnav = ap && FlightHud.navMode() == NavMode.LNAV
                && !guidance.autolandArmed() && !guidance.atcControlled();
        boolean hdg = ap && !lnav && !guidance.autolandArmed() && !guidance.atcControlled();
        boolean alt = (hdg || lnav) && guidance.targetVerticalSpeedFpm() == 0;
        boolean vs = (hdg || lnav) && guidance.targetVerticalSpeedFpm() != 0;
        boolean ias = ap;
        boolean ils = FlightHud.isIlsActive();
        boolean autoland = guidance.autolandArmed();
        boolean atc = guidance.atcControlled();

        graphics.fill(panelLeft + x(12), panelTop + y(54), panelLeft + x(588),
                panelTop + y(84), FlightLineTheme.INSET);
        graphics.outline(panelLeft + x(12), panelTop + y(54), w(576), h(30), FlightLineTheme.STONE);
        fmaChip(graphics, 18, 57, 58, "A/P", ap ? GREEN : MUTED, ap);
        fmaChip(graphics, 80, 57, 58, "A/T", at ? GREEN : MUTED, at);
        fmaChip(graphics, 142, 57, 58, lnav ? "LNAV" : "HDG", lnav || hdg ? GREEN : MUTED,
                lnav || hdg);
        fmaChip(graphics, 204, 57, 58, alt ? "ALT" : "V/S", alt || vs ? GREEN : MUTED, alt || vs);
        fmaChip(graphics, 266, 57, 58, "IAS", ias ? GREEN : MUTED, ias);
        fmaChip(graphics, 328, 57, 58, "ILS", ils ? autoland ? GREEN : CYAN : MUTED, ils);
        fmaChip(graphics, 390, 57, 58, "A/L", autoland ? GREEN : MUTED, autoland);
        fmaChip(graphics, 452, 57, 126, atc ? "ATC CONTROL" : "ATC STANDBY",
                atc ? AMBER : MUTED, atc);
    }

    private void fmaChip(GuiGraphicsExtractor graphics, int x, int y, int width,
                         String label, int color, boolean active) {
        int fill = active ? FlightLineTheme.COAL : FlightLineTheme.INSET;
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + 24), fill);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(24), color);
        if (active) graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + 4),
                panelTop + y(y + 24), color);
        fittedCenteredText(graphics, label, panelLeft + x(x + width / 2), panelTop + y(y + 7),
                color, w(width) - 6);
    }

    private void drawMcpPage(GuiGraphicsExtractor graphics) {
        int currentHeading = Math.floorMod(Math.round(FlightHud.currentHeading()), 360);
        int currentAltitude = FlightHud.currentAltitudeFeet();
        int currentSpeed = FlightHud.currentSpeedKnots();
        int currentVs = FlightHud.currentVsFpm();

        panel(graphics, mcpColumnX(0), MCP_INSTRUMENT_TOP, MCP_COLUMN_WIDTH,
                MCP_INSTRUMENT_HEIGHT, "HEADING");
        drawHeadingDial(graphics, mcpColumnCenter(0), 174, 40, currentHeading, parseHeading());
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %03d°  SEL %03d°",
                currentHeading, parseHeading()), panelLeft + x(mcpColumnCenter(0)),
                panelTop + y(218), TEXT, w(MCP_COLUMN_WIDTH - 6));

        panel(graphics, mcpColumnX(1), MCP_INSTRUMENT_TOP, MCP_COLUMN_WIDTH,
                MCP_INSTRUMENT_HEIGHT, "ALTITUDE");
        drawTape(graphics, mcpColumnX(1) + 14, 126, 101, 84, currentAltitude, parseAltitude(),
                500, "FT", 1000);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %,d  SEL %,d",
                currentAltitude, parseAltitude()), panelLeft + x(mcpColumnCenter(1)),
                panelTop + y(218), TEXT, w(MCP_COLUMN_WIDTH - 6));

        panel(graphics, mcpColumnX(2), MCP_INSTRUMENT_TOP, MCP_COLUMN_WIDTH,
                MCP_INSTRUMENT_HEIGHT, "VERTICAL SPEED");
        drawVerticalSpeed(graphics, mcpColumnCenter(2), 168, 84, currentVs, parseVs());
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %+d  SEL %+d FPM",
                currentVs, parseVs()), panelLeft + x(mcpColumnCenter(2)),
                panelTop + y(218), TEXT, w(MCP_COLUMN_WIDTH - 4));

        panel(graphics, mcpColumnX(3), MCP_INSTRUMENT_TOP, MCP_COLUMN_WIDTH,
                MCP_INSTRUMENT_HEIGHT, "AIRSPEED");
        drawTape(graphics, mcpColumnX(3) + 14, 126, 101, 84, currentSpeed, parseSpeed(),
                10, "KT", 50);
        fittedCenteredText(graphics, String.format(Locale.ROOT, "CUR %d  SEL %d",
                currentSpeed, parseSpeed()), panelLeft + x(mcpColumnCenter(3)),
                panelTop + y(218), TEXT, w(MCP_COLUMN_WIDTH - 6));

        panel(graphics, mcpColumnX(0), MCP_SELECT_TOP, MCP_COLUMN_WIDTH,
                MCP_SELECT_HEIGHT, "HDG SELECT");
        panel(graphics, mcpColumnX(1), MCP_SELECT_TOP, MCP_COLUMN_WIDTH,
                MCP_SELECT_HEIGHT, "ALT SELECT");
        panel(graphics, mcpColumnX(2), MCP_SELECT_TOP, MCP_COLUMN_WIDTH,
                MCP_SELECT_HEIGHT, "V/S SELECT");
        panel(graphics, mcpColumnX(3), MCP_SELECT_TOP, MCP_COLUMN_WIDTH,
                MCP_SELECT_HEIGHT, "IAS SELECT");

        var guidance = FlightHud.autopilotGuidance();
        String config = String.format(Locale.ROOT, "A/T %s  ·  THRUST ±%.0f KT  ·  N1 %d%%",
                FlightHud.isAutopilotEngaged() ? "AUTO" : "STBY",
                guidance.autoThrottleDeadbandKnots(), FlightHud.engineN1());
        fittedCenteredText(graphics, config, panelLeft + x(mcpColumnCenter(2)),
                panelTop + y(354), MUTED, freeWidth(mcpColumnX(1), mcpColumnX(3) + MCP_COLUMN_WIDTH));
    }

    private void drawHeadingDial(GuiGraphicsExtractor graphics, int cx, int cy, int radius,
                                 int current, int selected) {
        graphics.fill(panelLeft + x(cx - 2), panelTop + y(cy - 2),
                panelLeft + x(cx + 2), panelTop + y(cy + 2), FlightLineTheme.INSET);
        graphics.outline(panelLeft + x(cx - radius), panelTop + y(cy - radius),
                w(radius * 2), h(radius * 2), BORDER);

        graphics.outline(panelLeft + x(cx - radius + 9), panelTop + y(cy - radius + 9),
                w((radius - 9) * 2), h((radius - 9) * 2), FlightLineTheme.INSET);
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
                panelTop + y(y + height), FlightLineTheme.INSET);
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
                panelLeft + x(x + 55), panelTop + y(y + height - 2), FlightLineTheme.COAL);
        graphics.outline(panelLeft + x(x + 3), panelTop + y(y + height - 15), w(52), h(13), AMBER);
        fittedCenteredText(graphics, selected + " " + unit, panelLeft + x(x + 29),
                panelTop + y(y + height - 13), AMBER, w(48));
    }

    private void drawVerticalSpeed(GuiGraphicsExtractor graphics, int cx, int cy, int height,
                                   int current, int selected) {
        int top = cy - height / 2;
        graphics.fill(panelLeft + x(cx - 34), panelTop + y(top),
                panelLeft + x(cx + 34), panelTop + y(top + height), FlightLineTheme.INSET);
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

    /** Readonly EICAS-style alert page. TCAS is an advisory here, never a red alarm. */
    private void drawAlertsPage(GuiGraphicsExtractor graphics) {
        int warnings = FlightHud.activeWarnings();
        List<AlertEntry> alerts = activeAlertEntries(warnings);
        List<AdvisoryEntry> advisories = tcasAdvisories(warnings);
        double clock = System.nanoTime() / 1_000_000_000.0;
        int blink = ((long) (clock * 2.5) & 1L) == 0L ? ALARM : ALARM_DARK;
        boolean hasAlerts = !alerts.isEmpty();

        panel(graphics, 18, 100, 344, 260, "ACTIVE ALERTS", hasAlerts ? blink : GREEN);
        int columns = 2;
        int rows = 13;
        int cellWidth = 158;
        int cellGap = 8;
        int gridX = 28;
        int gridY = 126;
        int rowHeight = 11;
        int capacity = columns * rows;

        if (!hasAlerts) {
            fittedText(graphics, "ALL MONITORED SYSTEMS NOMINAL", panelLeft + x(gridX),
                    panelTop + y(gridY + 4), GREEN, w(316), true);
            fittedText(graphics, "TCAS ADVISORIES, IF ANY, ARE ON THE RIGHT", panelLeft + x(gridX),
                    panelTop + y(gridY + 20), MUTED, w(316), true);
        } else {
            graphics.fill(panelLeft + x(190), panelTop + y(gridY),
                    panelLeft + x(191), panelTop + y(gridY + rows * rowHeight - 2), BORDER);
            for (int i = 0; i < Math.min(alerts.size(), capacity); i++) {
                AlertEntry entry = alerts.get(i);
                int cellX = gridX + (i % columns) * (cellWidth + cellGap);
                int cellY = gridY + (i / columns) * rowHeight;
                int color = entry.urgent() ? blink : AMBER;
                drawAlertRow(graphics, cellX, cellY, cellWidth, entry.label(), color);
            }
            if (alerts.size() > capacity) {
                int last = capacity - 1;
                drawAlertRow(graphics,
                        gridX + (last % columns) * (cellWidth + cellGap),
                        gridY + (last / columns) * rowHeight,
                        cellWidth, "+" + (alerts.size() - capacity + 1) + " MORE", ALARM);
            }
        }
        fittedText(graphics, "ALARM FRAME ON HUD: NON-TCAS ONLY", panelLeft + x(gridX),
                panelTop + y(330), MUTED, w(316), true);
        fittedText(graphics, "FULL MESSAGE LIST LIVES IN THIS TAB", panelLeft + x(gridX),
                panelTop + y(344), MUTED, w(316), true);

        panel(graphics, 372, 100, 210, 150, "TCAS ADVISORY", CYAN);
        if (advisories.isEmpty()) {
            fittedText(graphics, "NO ACTIVE ADVISORY", panelLeft + x(382), panelTop + y(130),
                    GREEN, w(190), true);
        } else {
            for (int i = 0; i < Math.min(advisories.size(), 7); i++) {
                AdvisoryEntry advisory = advisories.get(i);
                int rowY = 128 + i * 12;
                graphics.fill(panelLeft + x(382), panelTop + y(rowY + 1),
                        panelLeft + x(385), panelTop + y(rowY + 9), advisory.color());
                fittedText(graphics, advisory.label(), panelLeft + x(391), panelTop + y(rowY),
                        advisory.color(), w(181), true);
            }
        }
        fittedText(graphics, "ADVISORY ONLY · NOT AN ALARM", panelLeft + x(382),
                panelTop + y(232), MUTED, w(190), true);

        int urgent = 0;
        int caution = 0;
        for (AlertEntry entry : alerts) {
            if (entry.urgent()) urgent++;
            else caution++;
        }
        panel(graphics, 372, 258, 210, 102, "ALERT STATUS",
                urgent > 0 ? blink : caution > 0 ? AMBER : GREEN);
        fittedText(graphics, String.format(Locale.ROOT, "WARNING %d   CAUTION %d", urgent, caution),
                panelLeft + x(382), panelTop + y(286),
                urgent > 0 ? ALARM : caution > 0 ? AMBER : GREEN, w(190), true);
        fittedText(graphics, "TCAS ROWS " + advisories.size(), panelLeft + x(382),
                panelTop + y(304), advisories.isEmpty() ? MUTED : TCAS_TA, w(190), true);
        fittedText(graphics, "HUD: EDGE FLASH ONLY", panelLeft + x(382),
                panelTop + y(322), MUTED, w(190), true);
        fittedText(graphics, "NO WARNING TEXT ON HUD", panelLeft + x(382),
                panelTop + y(338), MUTED, w(190), true);
    }

    private void drawAlertRow(GuiGraphicsExtractor graphics, int x, int y, int width,
                              String label, int color) {
        graphics.fill(panelLeft + x(x), panelTop + y(y + 2),
                panelLeft + x(x + 3), panelTop + y(y + 8), color);
        graphics.text(font, FlightLineTheme.fit(font, label, width - 10),
                panelLeft + x(x + 8), panelTop + y(y), color, true);
    }

    private List<AlertEntry> activeAlertEntries(int warnings) {
        List<AlertEntry> active = new ArrayList<>();
        if (FlightHud.isCrashed()) active.add(new AlertEntry(0, "AIRFRAME CRASH", true));
        for (AlertEntry entry : ALERT_ENTRIES) {
            if ((warnings & entry.bit()) != 0) active.add(entry);
        }
        return active;
    }

    private List<AdvisoryEntry> tcasAdvisories(int warnings) {
        List<AdvisoryEntry> advisories = new ArrayList<>();
        if ((warnings & FlightWarning.TCAS_RESOLUTION) != 0) {
            String direction = (warnings & FlightWarning.TCAS_DESCEND) != 0 ? "DESCEND"
                    : (warnings & FlightWarning.TCAS_CLIMB) != 0 ? "CLIMB" : "RESOLUTION";
            String crossing = (warnings & FlightWarning.TCAS_CROSSING) != 0 ? " · CROSSING" : "";
            advisories.add(new AdvisoryEntry("TCAS RA · " + direction + crossing, TCAS_RA));
        } else {
            if ((warnings & FlightWarning.TCAS_CLIMB) != 0) {
                advisories.add(new AdvisoryEntry("TCAS CLIMB", TCAS_RA));
            }
            if ((warnings & FlightWarning.TCAS_DESCEND) != 0) {
                advisories.add(new AdvisoryEntry("TCAS DESCEND", TCAS_RA));
            }
            if ((warnings & FlightWarning.TCAS_CROSSING) != 0) {
                advisories.add(new AdvisoryEntry("TCAS CROSSING", TCAS_RA));
            }
        }
        if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) {
            advisories.add(new AdvisoryEntry("TRAFFIC ADVISORY", TCAS_TA));
        }
        if ((warnings & FlightWarning.TCAS_CLEAR) != 0) {
            advisories.add(new AdvisoryEntry("CLEAR OF CONFLICT", TCAS_TA));
        }
        boolean raBit = (warnings & (FlightWarning.TCAS_RESOLUTION | FlightWarning.TCAS_CLIMB
                | FlightWarning.TCAS_DESCEND | FlightWarning.TCAS_CROSSING)) != 0;
        boolean taBit = (warnings & FlightWarning.TCAS_TRAFFIC) != 0;
        for (FlightHud.TrafficAdvisory advisory : FlightHud.trafficAdvisories()) {
            if (advisories.size() >= 7) break;
            if (raBit && advisory.label().startsWith("TCAS RA")) continue;
            if (taBit && advisory.label().startsWith("TCAS TA")) continue;
            advisories.add(new AdvisoryEntry(advisory.label(), advisory.color()));
        }
        return advisories;
    }

    private void drawFlightPlanPage(GuiGraphicsExtractor graphics) {
        panel(graphics, 18, 100, 240, 196, "FLIGHT PLAN / ROUTE");
        panel(graphics, 266, 100, 316, 196, "WAYPOINT SEQUENCE");
        panel(graphics, 18, 304, 564, 58, "ATC CLEARANCE / STATUS");

        var plan = NavigationState.plan();
        fittedText(graphics, "CRUISE", panelLeft + x(28), panelTop + y(176), MUTED, w(108), true);
        fittedText(graphics, String.format(Locale.ROOT, "FL%03d", fplnAltitude / 100),
                panelLeft + x(144), panelTop + y(176), AMBER, w(108), true);
        fittedText(graphics, "STATUS", panelLeft + x(28), panelTop + y(194), MUTED, w(108), true);
        fittedText(graphics, NavigationState.statusLabel(), panelLeft + x(144), panelTop + y(194),
                NavigationState.hasPlan() ? GREEN : MUTED, w(108), true);
        fittedText(graphics, "ACTIVE LEG", panelLeft + x(28), panelTop + y(212), MUTED, w(108), true);
        fittedText(graphics, activeLegLabel(), panelLeft + x(144), panelTop + y(212), CYAN, w(108), true);
        fittedText(graphics, "RUNWAYS", panelLeft + x(28), panelTop + y(230), MUTED, w(108), true);
        fittedText(graphics, runwayLabel(plan == null ? 0 : plan.departureRunwayId())
                        + " / " + runwayLabel(plan == null ? 0 : plan.arrivalRunwayId()),
                panelLeft + x(144), panelTop + y(230), TEXT, w(108), true);
        if (!NavigationState.hasNavData()) {
            fittedCenteredText(graphics, "WAITING FOR NAVIGATION DATA", panelLeft + x(138),
                    panelTop + y(258), RED, w(220));
        }

        var fixes = NavigationState.fixes();
        String selectedFix = fixes.isEmpty() ? "----"
                : fixes.get(Math.floorMod(fplnFixIndex, fixes.size())).name();
        fittedText(graphics, "SELECTED FIX: " + selectedFix, panelLeft + x(276), panelTop + y(176),
                CYAN, w(300), true);
        for (int row = 0; row < 6; row++) {
            int index = row;
            int y = 192 + row * 16;
            if (index >= fplnWaypoints.size()) {
                fittedText(graphics, String.format(Locale.ROOT, "%d  --------", index + 1),
                        panelLeft + x(276), panelTop + y(y), FlightLineTheme.MUTED, w(300), true);
                continue;
            }
            var fix = NavigationState.fix(fplnWaypoints.get(index));
            String name = fix == null ? " FIX" + fplnWaypoints.get(index) : fix.name();
            String marker = plan != null && index == plan.activeLeg() ? ">" : " ";
            int color = index == fplnWaypointIndex ? AMBER : marker.equals(">") ? GREEN : TEXT;
            fittedText(graphics, String.format(Locale.ROOT, "%d%s %s", index + 1, marker, name),
                    panelLeft + x(276), panelTop + y(y), color, w(300), true);
        }
        fittedText(graphics, String.format(Locale.ROOT, "%d / %d WAYPOINTS",
                        fplnWaypoints.size(), FileFlightPlanPayload.MAX_WAYPOINTS),
                panelLeft + x(276), panelTop + y(288), MUTED, w(300), true);

        String summary = plan == null ? "NO ACTIVE FLIGHT PLAN"
                : String.format(Locale.ROOT, "%s  REV %d  LEG %d/%d",
                        NavigationState.statusLabel(), plan.revision(), plan.activeLeg() + 1,
                        NavigationState.legs().size() + 1);
        fittedText(graphics, summary, panelLeft + x(28), panelTop + y(312),
                plan == null ? MUTED : GREEN, w(430), true);
        fittedText(graphics, fplnMessage, panelLeft + x(28), panelTop + y(330), CYAN, w(540), true);
        fittedText(graphics, "ILS SETUP  1 SET THRESHOLD X/Z  2 SET RWY HDG",
                panelLeft + x(28), panelTop + y(344), MUTED, w(540), true);
        fittedText(graphics, "3 SET RUNWAY  4 ARM ILS AUTOLAND",
                panelLeft + x(28), panelTop + y(356), MUTED, w(540), true);
    }

    private String activeLegLabel() {
        var leg = NavigationState.activeLeg();
        if (leg == null) return "DEST";
        return leg.name();
    }

    private String runwayLabel(int runwayId) {
        var runway = NavigationState.runway(runwayId);
        return runway == null ? "----" : runway.name();
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
                panelLeft + x(342), panelTop + y(centerY + 52), FlightLineTheme.INSET);
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

        fittedText(graphics, "THRESHOLD", panelLeft + x(382), panelTop + y(112), MUTED,
                freeWidth(382, 466), true);
        fittedText(graphics, "POSITION", panelLeft + x(466), panelTop + y(112), MUTED,
                freeWidth(466, 550), true);
        fittedText(graphics, String.format(Locale.ROOT, "GLIDE SLOPE  %.1f°", cachedGlideSlope),
                panelLeft + x(382), panelTop + y(146), CYAN, freeWidth(382, 550), true);
        graphics.text(font, "RUNWAY HDG", panelLeft + x(382), panelTop + y(178), MUTED, true);
        drawMiniCompass(graphics, 520, 208, 20, cachedRunwayHeading);

        String[] status = navStatus(ils);
        int color = statusColor(status[0]);
        graphics.fill(panelLeft + x(26), panelTop + y(304), panelLeft + x(574), panelTop + y(362),
                color == GREEN ? 0xFF1C2E16 : color == AMBER ? 0xFF2E2610 : 0xFF2A1918);
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

        String[] steps = {
                "1 SET THRESHOLD X/Z",
                "2 SET RWY HDG",
                "3 SET RUNWAY",
                "4 ARM ILS AUTOLAND"
        };
        for (int i = 0; i < steps.length; i++) {
            fittedText(graphics, steps[i], panelLeft + x(382), panelTop + y(310 + i * 12),
                    i == 3 && guidanceAutolandArmed() ? GREEN : TEXT,
                    freeWidth(382, 568), true);
        }
    }

    private boolean guidanceAutolandArmed() {
        return FlightHud.autopilotGuidance().autolandArmed();
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
                panelTop + y(y + height), FlightLineTheme.INSET);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), BORDER);
        graphics.enableScissor(panelLeft + x(x), panelTop + y(y), w(width), h(height));
        for (int lineX = x + 30; lineX < x + width; lineX += 30) {
            graphics.fill(panelLeft + x(lineX), panelTop + y(y), panelLeft + x(lineX + 1),
                    panelTop + y(y + height), FlightLineTheme.COAL);
        }
        for (int lineY = y + 30; lineY < y + height; lineY += 30) {
            graphics.fill(panelLeft + x(x), panelTop + y(lineY), panelLeft + x(x + width),
                    panelTop + y(lineY + 1), FlightLineTheme.COAL);
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
                panelTop + y(y + height), 0x806CC349);
        drawWindArrow(graphics, x + 34, y + 28, weather.windDirectionDegrees() + 180,
                weather.windSpeedKnots(), conditionColor(weather));
        graphics.disableScissor();
    }

    private void drawWeatherLegend(GuiGraphicsExtractor graphics, int x, int y) {
        for (int i = 0; i < WeatherDisplay.DBZ_COLORS.length; i++) {
            graphics.fill(panelLeft + x(x + i * 12), panelTop + y(y),
                    panelLeft + x(x + i * 12 + 9), panelTop + y(y + 7),
                    WeatherDisplay.DBZ_COLORS[i]);
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
                panelTop + y(y + 22), FlightLineTheme.COAL);
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
        graphics.fill(panelLeft + x(x) + 4, panelTop + y(y) + 4, panelLeft + x(x + width) + 4,
                panelTop + y(y + height) + 4, FlightLineTheme.SHADOW);
        graphics.fill(panelLeft + x(x), panelTop + y(y), panelLeft + x(x + width),
                panelTop + y(y + height), CARD_FILL);
        graphics.outline(panelLeft + x(x), panelTop + y(y), w(width), h(height), borderColor);
    }

    private void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String title) {
        panel(graphics, x, y, width, height, title, CYAN);
    }

    private void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                       String title, int accent) {
        FlightLineTheme.panel(graphics, panelLeft + x(x), panelTop + y(y), w(width), h(height),
                CARD_FILL, BORDER);
        graphics.fill(panelLeft + x(x) + 2, panelTop + y(y) + 2, panelLeft + x(x + width) - 2,
                panelTop + y(y + 19), FlightLineTheme.HEADER);
        graphics.fill(panelLeft + x(x) + 2, panelTop + y(y + 19), panelLeft + x(x + width) - 2,
                panelTop + y(y + 20), accent);
        graphics.fill(panelLeft + x(x) + 2, panelTop + y(y) + 2, panelLeft + x(x) + 6,
                panelTop + y(y + 19), accent);
        fittedText(graphics, title, panelLeft + x(x + 9), panelTop + y(y + 5), accent,
                w(width) - 16, true);
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
            case 1 -> "FLIGHT PLAN";
            case 2 -> "NAV / ILS";
            case 3 -> "WX";
            case PAGE_ALERTS -> "ALERTS";
            default -> "SYS";
        };
    }

    private int x(int value) {
        return value;
    }

    private int y(int value) {
        return value;
    }

    private int w(int value) {
        return Math.max(1, value);
    }

    private int h(int value) {
        return Math.max(1, value);
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
        if (activePage == 0 && headingBox != null) saveAutopilotValues();
        else if (activePage == 2 && thresholdXBox != null) commitIlsValues();
        captureAppliedMcp();
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
