package dev.flightline.client;

import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightTuning;
import dev.flightline.flight.FlightWarning;
import dev.flightline.FlightLine;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightRadarPayload;
import dev.flightline.network.FlightStatePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Deque;

final class FlightHud {
    private static boolean enabled;
    private static FlightPhase phase = FlightPhase.OFF;
    private static float speed, throttle, bankDegrees, pitchDegrees, headingDegrees;
    private static float visualSpeed, visualBank, visualPitch, visualHeading;
    private static int radioAltitudeFeet, verticalSpeedFpm, gearState, systems, warnings;
    private static boolean autopilot;
    private static FlightInstrumentPayload.AutopilotGuidance autopilotGuidance =
            FlightInstrumentPayload.AutopilotGuidance.defaults();
    private static FlightInstrumentPayload.WeatherGuidance weatherGuidance =
            FlightInstrumentPayload.WeatherGuidance.defaults();
    private static float trafficBearingDegrees;
    private static int trafficRangeBlocks, trafficAltitudeFeet;
    private static int trafficCounts;
    private static FlightRadarPayload radarPayload = new FlightRadarPayload(0, 0, 0, 0.0f, List.of());
    private static boolean showPfd = true;
    private static boolean showEicas = true;
    private static boolean showRadar = true;
    private static boolean showRadioAltimeter = true;
    private static boolean showKeybinds = true;
    private static boolean radarDataReceived;
    private static long lastRadarUpdateNanos;
    private static boolean ilsActive;
    private static float ilsLocalizerDegrees, ilsGlideslopeDegrees;
    private static float ilsReferenceGlideSlopeDegrees = 3.0f;
    private static float ilsRunwayHeadingDegrees, ilsThresholdDistanceBlocks;
    private static long lastFrameNanos = System.nanoTime();
    private static float keybindPanelProgress = 1.0f;
    private static float brakePopupProgress;
    private static long brakePopupExpiresNanos;
    private static String atcMessage = "";
    private static long atcMessageExpiresNanos;
    private static int atcControllerEntityId = -1;
    private static final Deque<String> atcRadioLog = new ArrayDeque<>();
    private static final String PREFERENCES_FILE = "flightline-client.properties";

    private FlightHud() {
    }

    static float throttle() {
        return throttle;
    }

    static float currentHeading() {
        return visualHeading;
    }

    static float currentPitch() {
        return visualPitch;
    }

    static int currentAltitudeFeet() {
        return radioAltitudeFeet;
    }

    static int currentVsFpm() {
        return verticalSpeedFpm;
    }

    static int currentSpeedKnots() {
        return knots(visualSpeed);
    }

    static boolean isAutopilotEngaged() {
        return autopilot;
    }

    static FlightInstrumentPayload.AutopilotGuidance autopilotGuidance() {
        return autopilotGuidance;
    }

    static boolean isIlsActive() {
        return ilsActive;
    }

    static List<FlightRadarPayload.Contact> radarContacts() {
        return radarPayload.contacts();
    }

    static boolean hasRadarData() {
        return radarDataReceived && System.nanoTime() - lastRadarUpdateNanos < 2_000_000_000L;
    }

    static int radarOwnX() {
        return radarPayload.ownX();
    }

    static int radarOwnZ() {
        return radarPayload.ownZ();
    }

    static int radarOwnAltitudeFeet() {
        return radarPayload.ownAltitudeFeet();
    }

    static float radarOwnHeadingDegrees() {
        return radarPayload.ownHeadingDegrees();
    }

    static void updateRadar(FlightRadarPayload payload) {
        radarPayload = payload;
        radarDataReceived = true;
        lastRadarUpdateNanos = System.nanoTime();
    }

    static void showAtcMessage(String message, int controllerEntityId) {
        atcMessage = message;
        atcControllerEntityId = controllerEntityId;
        atcMessageExpiresNanos = System.nanoTime() + 8_000_000_000L;
        atcRadioLog.addFirst(message);
        while (atcRadioLog.size() > 5) atcRadioLog.removeLast();
    }

    static boolean hasAtcMessage() {
        return !atcMessage.isEmpty() && System.nanoTime() < atcMessageExpiresNanos;
    }

    static int atcControllerEntityId() {
        return hasAtcMessage() ? atcControllerEntityId : -1;
    }

    static List<String> atcRadioLog() {
        return List.copyOf(atcRadioLog);
    }

    static void loadPreferences() {
        Path path = preferencesPath();
        if (!Files.exists(path)) return;
        Properties preferences = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            preferences.load(reader);
            showPfd = readPreference(preferences, "showPfd", showPfd);
            showEicas = readPreference(preferences, "showEicas", showEicas);
            showRadar = readPreference(preferences, "showRadar", showRadar);
            showRadioAltimeter = readPreference(preferences, "showRadioAltimeter", showRadioAltimeter);
            showKeybinds = readPreference(preferences, "showKeybinds", showKeybinds);
        } catch (IOException exception) {
            FlightLine.LOGGER.warn("Could not load Flight Line HUD preferences from {}", path, exception);
        }
    }

    static void togglePfd() { showPfd = !showPfd; savePreferences(); }
    static void toggleEicas() { showEicas = !showEicas; savePreferences(); }
    static void toggleRadar() { showRadar = !showRadar; savePreferences(); }
    static void toggleRadioAltimeter() { showRadioAltimeter = !showRadioAltimeter; savePreferences(); }
    static void toggleKeybinds() { showKeybinds = !showKeybinds; savePreferences(); }

    private static boolean readPreference(Properties preferences, String key, boolean defaultValue) {
        String value = preferences.getProperty(key);
        if (value == null) return defaultValue;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        FlightLine.LOGGER.warn("Ignoring invalid Flight Line HUD preference {}={}", key, value);
        return defaultValue;
    }

    private static void savePreferences() {
        Path path = preferencesPath();
        Properties preferences = new Properties();
        preferences.setProperty("showPfd", Boolean.toString(showPfd));
        preferences.setProperty("showEicas", Boolean.toString(showEicas));
        preferences.setProperty("showRadar", Boolean.toString(showRadar));
        preferences.setProperty("showRadioAltimeter", Boolean.toString(showRadioAltimeter));
        preferences.setProperty("showKeybinds", Boolean.toString(showKeybinds));
        try {
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(path)) {
                preferences.store(writer, "Flight Line HUD visibility");
            }
        } catch (IOException exception) {
            FlightLine.LOGGER.warn("Could not save Flight Line HUD preferences to {}", path, exception);
        }
    }

    private static Path preferencesPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(PREFERENCES_FILE);
    }

    static float localizerDeviation() {
        return ilsLocalizerDegrees;
    }

    static float glideslopeDeviation() {
        return ilsGlideslopeDegrees;
    }

    static float runwayHeading() {
        return ilsRunwayHeadingDegrees;
    }

    static float thresholdDistanceBlocks() {
        return ilsThresholdDistanceBlocks;
    }

    static float ilsReferenceGlideSlope() {
        return ilsReferenceGlideSlopeDegrees;
    }

    static FlightInstrumentPayload.WeatherGuidance weatherGuidance() {
        return weatherGuidance;
    }

    static void update(FlightStatePayload state) {
        enabled = state.enabled();
        phase = FlightPhase.fromId(state.phase());
        speed = state.speed();
        throttle = state.throttle();
    }

    static void updateInstruments(FlightInstrumentPayload instruments) {
        radioAltitudeFeet = instruments.radioAltitudeFeet();
        verticalSpeedFpm = instruments.verticalSpeedFpm();
        bankDegrees = instruments.bankDegrees();
        pitchDegrees = instruments.pitchDegrees();
        headingDegrees = instruments.headingDegrees();
        gearState = instruments.gearState();
        systems = instruments.systems();
        if ((systems & (1 << 6)) != 0) brakePopupExpiresNanos = System.nanoTime() + 700_000_000L;
        warnings = instruments.warnings();
        autopilot = instruments.autopilot();
        autopilotGuidance = instruments.autopilotGuidance();
        weatherGuidance = instruments.cockpitGuidance().weatherGuidance();
        trafficBearingDegrees = instruments.trafficBearingDegrees();
        trafficRangeBlocks = instruments.trafficRangeBlocks();
        trafficAltitudeFeet = instruments.trafficAltitudeFeet();
        trafficCounts = instruments.trafficCounts();
        ilsActive = instruments.ilsGuidance().active();
        ilsLocalizerDegrees = instruments.ilsGuidance().localizerDegrees();
        ilsGlideslopeDegrees = instruments.ilsGuidance().glideslopeDegrees();
        ilsReferenceGlideSlopeDegrees = instruments.ilsGuidance().referenceGlideSlopeDegrees();
        ilsRunwayHeadingDegrees = instruments.ilsGuidance().runwayHeadingDegrees();
        ilsThresholdDistanceBlocks = instruments.ilsGuidance().thresholdDistanceBlocks();
    }

    static void clear() {
        enabled = false;
        phase = FlightPhase.OFF;
        speed = throttle = bankDegrees = pitchDegrees = headingDegrees = 0;
        visualSpeed = visualBank = visualPitch = visualHeading = 0;
        radioAltitudeFeet = verticalSpeedFpm = gearState = systems = warnings = 0;
        autopilot = false;
        autopilotGuidance = FlightInstrumentPayload.AutopilotGuidance.defaults();
        weatherGuidance = FlightInstrumentPayload.WeatherGuidance.defaults();
        trafficBearingDegrees = 0.0f;
        trafficRangeBlocks = trafficAltitudeFeet = 0;
        trafficCounts = 0;
        radarPayload = new FlightRadarPayload(0, 0, 0, 0.0f, List.of());
        radarDataReceived = false;
        lastRadarUpdateNanos = 0L;
        ilsActive = false;
        atcMessage = "";
        atcControllerEntityId = -1;
        atcMessageExpiresNanos = 0L;
        atcRadioLog.clear();
        ilsLocalizerDegrees = ilsGlideslopeDegrees = 0.0f;
        ilsRunwayHeadingDegrees = ilsThresholdDistanceBlocks = 0.0f;
        ilsReferenceGlideSlopeDegrees = 3.0f;
        lastFrameNanos = System.nanoTime();
        brakePopupProgress = 0.0f;
        brakePopupExpiresNanos = 0L;
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        var level = mc.level;
        if (!enabled || player == null || level == null) return;
        animateInstruments();

        int screenWidth = g.guiWidth();
        int screenHeight = g.guiHeight();
        if (screenWidth < 240 || screenHeight < 184) return;
        double clock = System.nanoTime() / 1_000_000_000.0;
        int ndY = screenWidth < 370 ? 126 : 6;
        int lowerY = screenHeight - 72;
        int radioPanelWidth = 148;
        int radioPanelHeight = 52;
        boolean lowerPanelVisible = showRadioAltimeter && screenWidth >= radioPanelWidth + 12 && lowerY >= 0
                && lowerY + radioPanelHeight <= screenHeight
                && (!showRadar || screenWidth >= 370 || lowerY >= ndY + 88 + 4);

        if (showPfd) {
        // Professional Glass Cockpit PFD: Attitude, Pitch Ladder, Airspeed & Altitude tapes
        int pfdX = 6;
        int pfdY = 6;
        int pfdWidth = 116;
        int pfdHeight = 100;
        panel(g, pfdX, pfdY, pfdWidth, pfdHeight, 0xAA56B9D2);

        // PFD Top Header / FMA
        g.text(mc.font, "PFD", pfdX + 4, pfdY + 3, 0xFF72E6F5, true);
        String flightMode = autopilotGuidance.autolandArmed() ? "AL" : autopilot ? "AP" : "FD";
        g.text(mc.font, flightMode, pfdX + 31, pfdY + 3,
                autopilot ? 0xFF62D89B : 0xFF70808A, true);
        g.text(mc.font, fitText(mc.font, phase.label(), pfdWidth - 64), pfdX + 59, pfdY + 3,
                phaseColor(), true);

        // Center Artificial Horizon with dynamic pitch ladder and roll arc
        int horizonCx = pfdX + 55;
        int horizonCy = pfdY + 41;
        drawArtificialHorizon(g, horizonCx, horizonCy, 58, 48, visualPitch, visualBank,
                ilsActive, ilsLocalizerDegrees, ilsGlideslopeDegrees);

        // Airspeed Tape (Left)
        g.fill(pfdX + 3, pfdY + 15, pfdX + 24, pfdY + 70, 0xD0121A22);
        g.outline(pfdX + 3, pfdY + 15, 21, 55, 0x8035A5B8);
        int currentIas = knots(visualSpeed);
        // Current speed digital readout box
        g.fill(pfdX + 2, pfdY + 36, pfdX + 25, pfdY + 48, 0xFF0E141B);
        g.outline(pfdX + 2, pfdY + 36, 23, 12, 0xFF72E6F5);
        g.centeredText(mc.font, fitText(mc.font, required(String.valueOf(currentIas)), 21),
                pfdX + 13, pfdY + 38, 0xFFFFFFFF);
        g.text(mc.font, "IAS", pfdX + 4, pfdY + 17, 0xFF72E6F5, true);

        // Altitude Tape & VSI (Right)
        g.fill(pfdX + 86, pfdY + 15, pfdX + 107, pfdY + 70, 0xD0121A22);
        g.outline(pfdX + 86, pfdY + 15, 21, 55, 0x8035A5B8);
        // Current altitude digital readout box
        g.fill(pfdX + 84, pfdY + 36, pfdX + 109, pfdY + 48, 0xFF0E141B);
        g.outline(pfdX + 84, pfdY + 36, 25, 12, 0xFF72E6F5);
        g.centeredText(mc.font, fitText(mc.font, required(String.valueOf(radioAltitudeFeet)), 23), pfdX + 96, pfdY + 38,
                radioAltitudeFeet < 300 ? 0xFFFF5148 : 0xFFFFFFFF);
        g.text(mc.font, "ALT", pfdX + 88, pfdY + 17, 0xFF72E6F5, true);

        // PFD Bottom Pitch & Bank readouts
        String pitchStr = String.format(Locale.ROOT, "P%+.0f°", visualPitch);
        String bankStr = String.format(Locale.ROOT, "R%+.0f°", visualBank);
        g.text(mc.font, fitText(mc.font, pitchStr, 38), pfdX + 27, pfdY + 72, 0xFFD5E0E8, true);
        g.text(mc.font, fitText(mc.font, bankStr, 38), pfdX + 68, pfdY + 72, 0xFFD5E0E8, true);
        String ilsText = ilsActive
                ? String.format(Locale.ROOT, "LOC%+.1f GS%+.1f", ilsLocalizerDegrees, ilsGlideslopeDegrees)
                : "ILS STBY";
        g.text(mc.font, fitText(mc.font, ilsText, pfdWidth - 8), pfdX + 4, pfdY + 79,
                ilsActive ? 0xFFFF6CD9 : 0xFF83959B, true);
        if (ilsActive) {
            String approachText = String.format(Locale.ROOT, "CRS %03.0f  %.1fNM",
                    Math.floorMod(Math.round(ilsRunwayHeadingDegrees), 360),
                    ilsThresholdDistanceBlocks / 1852.0f);
            g.text(mc.font, fitText(mc.font, approachText, pfdWidth - 8), pfdX + 4, pfdY + 89,
                    0xFF79E8FF, true);
        }
        }

        if (showRadar) {
        // Heading-up ND with a scaled traffic scope and a rotating compass reference
        int ndWidth = 104;
        int ndX = (screenWidth - ndWidth) / 2;
        panel(g, ndX, ndY, ndWidth, 84, 0xAA56B9D2);
        int radarRangeBlocks = Math.max(16, Math.min(128, weatherGuidance.visibilityBlocks()));
        int heading = Math.floorMod(Math.round(visualHeading), 360);
        g.text(mc.font, fitText(mc.font, String.format(Locale.ROOT, "ND %03d° %dB", heading,
                radarRangeBlocks), ndWidth - 8),
                ndX + 4, ndY + 3,
                0xFF72E6F5, true);
        int radarCx = ndX + ndWidth / 2;
        int radarCy = ndY + 38;
        drawRadarGrid(g, radarCx, radarCy, 19, clock, visualHeading);
        drawAircraftSymbol(g, radarCx, radarCy);
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(radarRangeBlocks), candidate -> candidate.isAlive())) {
            double dx = mob.getX() - player.getX();
            double dz = mob.getZ() - player.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance > radarRangeBlocks) continue;
            double relativeBearing = Math.toRadians(Math.toDegrees(Math.atan2(-dx, dz)) - visualHeading);
            double scale = Math.min(1.0, distance / radarRangeBlocks);
            drawTrafficBlip(g, radarCx, radarCy, relativeBearing, scale,
                    mob.onGround(), false, mob.getBlockY() - player.getBlockY());
        }
        for (var otherPlayer : level.players()) {
            if (otherPlayer == player || otherPlayer.isSpectator()) continue;
            double dx = otherPlayer.getX() - player.getX();
            double dz = otherPlayer.getZ() - player.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance > radarRangeBlocks) continue;
            double relativeBearing = Math.toRadians(Math.toDegrees(Math.atan2(-dx, dz)) - visualHeading);
            drawTrafficBlip(g, radarCx, radarCy, relativeBearing, distance / radarRangeBlocks,
                    otherPlayer.onGround(), true, otherPlayer.getBlockY() - player.getBlockY());
        }

        // Draw closest traffic contact blip on radar
        if (trafficRangeBlocks > 0 && trafficRangeBlocks <= radarRangeBlocks) {
            double bearing = Math.toRadians(trafficBearingDegrees);
            double range = Math.min(1.0, trafficRangeBlocks / (double) radarRangeBlocks);
            int targetX = radarCx - (int)Math.round(Math.sin(bearing) * 17.0 * range);
            int targetY = radarCy - (int)Math.round(Math.cos(bearing) * 17.0 * range);
            int targetColor = (warnings & FlightWarning.TCAS_RESOLUTION) != 0 ? 0xFFFF5148 : 0xFFFFCE52;
            drawLine(g, targetX, targetY - 2, targetX + 2, targetY, targetColor);
            drawLine(g, targetX + 2, targetY, targetX, targetY + 2, targetColor);
            drawLine(g, targetX, targetY + 2, targetX - 2, targetY, targetColor);
            drawLine(g, targetX - 2, targetY, targetX, targetY - 2, targetColor);
        }

        // Clean ND footer rows - No text overflow!
        g.text(mc.font, String.format(Locale.ROOT, "AIR %02d", trafficCounts >>> 8), ndX + 6, ndY + 63,
                0xFF75E6A5, true);
        g.text(mc.font, String.format(Locale.ROOT, "GND %02d", trafficCounts & 0xFF), ndX + 62, ndY + 63,
                0xFFFFCE52, true);

        if (trafficRangeBlocks > 0) {
            int targetColor = (warnings & FlightWarning.TCAS_RESOLUTION) != 0 ? 0xFFFF5148 : 0xFFFFCE52;
            String target = required(String.format(Locale.ROOT, "TGT %+dFT %dB",
                    trafficAltitudeFeet, trafficRangeBlocks));
            g.text(mc.font, fitText(mc.font, target, ndWidth - 12),
                    ndX + 6, ndY + 73, targetColor, true);
        } else {
            g.text(mc.font, "TCAS NORM", ndX + 6, ndY + 73, 0xFF62B0A0, true);
        }
        }

        if (showEicas) {
        // EICAS: Engine, Fuel, Gear, Flaps and Spoilers
        int eicasX = screenWidth - 118;
        panel(g, eicasX, 6, 112, 117, 0xAA56B9D2);
        int engine1 = systems & 7;
        int engine2 = (systems >> 3) & 7;
        int n1 = (systems >> 12) & 0x7F;
        int flap = (systems >> 9) & 7;
        boolean spoilers = (systems & (1 << 8)) != 0;
        int food = player.getFoodData().getFoodLevel();
        boolean engineFire = engine1 == 4 || engine2 == 4;
        boolean blinkOn = ((long) (clock * 3.0) & 1L) == 0L;
        g.fill(eicasX + 1, 7, eicasX + 111, 20, 0xFF1C2733);
        g.text(mc.font, "EICAS", eicasX + 5, 10, 0xFF72E6F5, true);
        g.text(mc.font, autopilotGuidance.autolandArmed() ? "A/L ON" : autopilot ? "A/P ON" : "A/P OFF",
                eicasX + 60, 10,
                autopilot ? 0xFF62D89B : 0xFF83959B, true);
        drawEngineStatus(g, mc, eicasX + 4, 22, "E1", engine1, blinkOn);
        drawEngineStatus(g, mc, eicasX + 59, 22, "E2", engine2, blinkOn);
        g.text(mc.font, "N1", eicasX + 4, 34, 0xFFD5E0E8, true);
        gauge(g, eicasX + 22, 36, 56, 5, n1 / 100.0f, n1 > 90 ? 0xFFFFA04A : 0xFF62D89B);
        g.text(mc.font, String.format(Locale.ROOT, "%d%%", n1), eicasX + 82, 33,
                n1 > 90 ? 0xFFFFA04A : 0xFFB6C8D1, true);
        g.text(mc.font, "FUEL", eicasX + 4, 45, 0xFFD5E0E8, true);
        gauge(g, eicasX + 30, 47, 54, 5, food / 20.0f, food <= 6 ? 0xFFFF5148 : 0xFFFFCE52);
        g.text(mc.font, String.format(Locale.ROOT, "%d%%", food * 5), eicasX + 87, 44,
                food <= 6 ? 0xFFFF5148 : 0xFFB6C8D1, true);
        g.text(mc.font, gearState == 2 ? "GEAR FAIL" : gearState == 1 ? "GEAR UP" : "GEAR DN",
                eicasX + 4, 57, gearColor(), true);
        g.text(mc.font, spoilers ? "SP ON" : "SP OFF", eicasX + 54, 57,
                spoilers ? 0xFFFFCE52 : 0xFFB9CDD4, true);
        int gearLight = gearState == 2 ? 0xFFFF5148 : gearState == 0 ? 0xFF62D89B : 0xFFFFCE52;
        for (int i = 0; i < 3; i++) {
            g.fill(eicasX + 88 + i * 7, 58, eicasX + 93 + i * 7, 63, gearLight);
        }
        g.text(mc.font, "FL", eicasX + 4, 65, 0xFFD5E0E8, true);
        for (int i = 0; i < 4; i++) {
            g.fill(eicasX + 19 + i * 9, 66, eicasX + 25 + i * 9, 71,
                    i < flap ? 0xFFFFCE52 : 0xFF364650);
        }
        g.text(mc.font, "WX " + weatherName(weatherGuidance.condition()),
                eicasX + 4, 77, weatherColor(weatherGuidance.condition()), true);
        String windReadout = required(String.format(Locale.ROOT, "%03d° %dKT",
                weatherGuidance.windDirectionDegrees(), weatherGuidance.windSpeedKnots()));
        g.text(mc.font, fitText(mc.font, windReadout, 54), eicasX + 54, 77, 0xFFB6C8D1, true);
        String antiIceReadout = weatherGuidance.antiIceActive()
                ? (weatherGuidance.antiIceEffective() ? "AI CLR" : "AI WAIT") : "AI OFF";
        String weatherSeverity = required(String.format(Locale.ROOT, "ICE %d%% TURB %d%% %s",
                weatherGuidance.icingPercent(), weatherGuidance.turbulencePercent(), antiIceReadout));
        int turbulenceColor = weatherGuidance.turbulencePercent() >= 70 ? 0xFFFF5148
                : weatherGuidance.turbulencePercent() >= 35 ? 0xFFFFCE52 : 0xFF62D89B;
        int icingColor = weatherGuidance.icingPercent() >= 20 ? 0xFFFFCE52 : turbulenceColor;
        g.text(mc.font, fitText(mc.font, weatherSeverity, 104), eicasX + 4, 89, icingColor, true);
        if (engineFire) {
            int fireColor = blinkOn ? 0xFFFF322D : 0xFF701B20;
            g.fill(eicasX + 3, 101, eicasX + 109, 114, fireColor);
            g.outline(eicasX + 3, 101, 106, 13, 0xFFFFB7A0);
            g.centeredText(mc.font, fitText(mc.font, "ENGINE FIRE · SHUT DOWN", 100),
                    eicasX + 56, 103, 0xFFFFFFFF);
        } else if ((warnings & FlightWarning.ENGINE_FAILURE) != 0) {
            g.fill(eicasX + 3, 101, eicasX + 109, 114, 0xFF70251F);
            g.centeredText(mc.font, "ENGINE FAILURE", eicasX + 56, 103, 0xFFFFC2A8);
        } else if ((warnings & (FlightWarning.FUEL_LOW | FlightWarning.FUEL_EMPTY)) != 0) {
            g.fill(eicasX + 3, 101, eicasX + 109, 114, 0xFF604B16);
            g.centeredText(mc.font, (warnings & FlightWarning.FUEL_EMPTY) != 0
                    ? "FUEL EMPTY" : "FUEL LOW", eicasX + 56, 103, 0xFFFFE09A);
        } else if ((warnings & FlightWarning.ICE_ACCUMULATION) != 0) {
            g.fill(eicasX + 3, 101, eicasX + 109, 114, 0xFF34546B);
            String iceAlert = weatherGuidance.antiIceEffective() ? "ANTI-ICE · ICE CLEARING"
                    : weatherGuidance.antiIceActive() ? "ANTI-ICE SET · ENGINE NEEDED"
                    : "ICE BUILDUP · ANTI-ICE OFF";
            g.centeredText(mc.font, iceAlert,
                    eicasX + 56, 103, 0xFFE0F3FF);
        } else if ((engine1 == 0 || engine1 == 3) && (engine2 == 0 || engine2 == 3)
                && !isAirbornePhase()) {
            g.text(mc.font, "ENGINES OFF · READY", eicasX + 5, 104, 0xFFB6C8D1, true);
        } else if (isAirbornePhase() && gearState == 1 && radioAltitudeFeet < 1000) {
            g.text(mc.font, "GEAR UP · APPROACH", eicasX + 5, 104, 0xFFFFCE52, true);
        } else if (isAirbornePhase() && flap == 0 && radioAltitudeFeet < 500) {
            g.text(mc.font, "FLAPS RETRACTED", eicasX + 5, 104, 0xFFB6C8D1, true);
        } else if (isAirbornePhase() && spoilers) {
            g.text(mc.font, "SPOILERS EXTENDED", eicasX + 5, 104, 0xFFFFCE52, true);
        }
        }

        if (showRadioAltimeter) {
        // Radio altimeter with a focused low-altitude tape and vertical-speed trend readout.
        if (lowerPanelVisible) {
            int lowAltitudeWarnings = FlightWarning.PULL_UP | FlightWarning.TERRAIN
                    | FlightWarning.SINK_RATE | FlightWarning.WINDSHEAR
                    | FlightWarning.GEAR_UP | FlightWarning.TOO_LOW_FLAPS
                    | FlightWarning.TOO_LOW_TERRAIN;
            boolean warningBlink = (warnings & lowAltitudeWarnings) != 0
                    && ((long)(clock * 3.0) & 1L) == 0L;
            int panelBorder = warningBlink ? 0xFFFF5148 : 0xAA56B9D2;
            panel(g, 6, lowerY, radioPanelWidth, radioPanelHeight, panelBorder);
            g.fill(7, lowerY + 1, radioPanelWidth + 5, lowerY + 13,
                    warningBlink ? 0xFF5C1F22 : 0xFF1C2733);
            g.text(mc.font, "RAD ALT",
                    11, lowerY + 3,
                    warningBlink ? 0xFFFFA29C : 0xFF72E6F5, true);
            String altitudeText = required(String.format(Locale.ROOT, "%04d", radioAltitudeFeet));
            int altitudeColor = radioAltitudeColor(radioAltitudeFeet);
            g.text(mc.font, fitText(mc.font, altitudeText, 42), 11, lowerY + 16, altitudeColor, true);
            g.text(mc.font, "FT AGL", 42, lowerY + 17, 0xFFB6C8D1, true);

            String trendText = required(String.format(Locale.ROOT, "%s %d FPM",
                    verticalSpeedFpm < 0 ? "▼" : "▲", Math.abs(verticalSpeedFpm)));
            int trendColor = verticalSpeedFpm < -1000 ? 0xFFFF5148
                    : verticalSpeedFpm < 0 ? 0xFFFFCE52 : 0xFF68E8A5;
            g.text(mc.font, fitText(mc.font, trendText, 88), 58, lowerY + 17, trendColor, true);

            int tapeX = 11;
            int tapeY = lowerY + 31;
            int tapeWidth = 136;
            gauge(g, tapeX, tapeY, tapeWidth, 7,
                    Math.min(1.0f, radioAltitudeFeet / 500.0f), altitudeColor);
            for (int tickFeet : new int[]{10, 20, 50, 100, 200, 300, 400, 500}) {
                int tickX = tapeX + Math.round(tapeWidth * tickFeet / 500.0f);
                g.fill(tickX, tapeY - 1, tickX + 1, tapeY + (tickFeet % 100 == 0 ? 9 : 6),
                        tickFeet <= 100 ? 0xFFE7EEF2 : 0xFF82949B);
            }
            g.text(mc.font, "0", tapeX, lowerY + 40, 0xFF9BAAB1, true);
            for (int tickFeet : new int[]{100, 200, 300, 400, 500}) {
                int tickX = tapeX + Math.round(tapeWidth * tickFeet / 500.0f);
                g.centeredText(mc.font, String.valueOf(tickFeet), tickX, lowerY + 40, 0xFF9BAAB1);
            }
        }
        }

        updateKeybindPanelAnimation();
        if (showKeybinds && keybindPanelProgress > 0.01f) {
            drawKeybindList(g, mc, screenWidth, screenHeight);
        }
        if (!atcMessage.isEmpty() && System.nanoTime() < atcMessageExpiresNanos) {
            int messageWidth = Math.min(screenWidth - 24, 260);
            int messageX = (screenWidth - messageWidth) / 2;
            g.fill(messageX, 100, messageX + messageWidth, 120, 0xE51A2630);
            g.outline(messageX, 100, messageWidth, 20, 0xFF72E6F5);
            g.centeredText(mc.font, fitText(mc.font, atcMessage, messageWidth - 12),
                    screenWidth / 2, 106, 0xFFD8F4FF);
        } else {
            atcMessage = "";
        }

        List<String> alerts = warningLabels();
        if (!alerts.isEmpty()) {
            if (alerts.size() > 4) {
                alerts = new ArrayList<>(alerts.subList(0, 3));
                alerts.add("+" + (warningLabels().size() - 3) + " MORE");
            }
            int stackHeight = alerts.size() * 12;
            int alertX = 6;
            int alertY = lowerPanelVisible ? lowerY - stackHeight - 3 : screenHeight - stackHeight - 8;
            alertY = Math.max(ndY + 88, alertY);
            int alertWidth = Math.min(188, screenWidth - 12);
            int blink = ((long)(clock * 2.5) & 1L) == 0L ? 0xFFFF5148 : 0xFFD84442;
            for (int i = 0; i < alerts.size(); i++) {
                int rowY = alertY + i * 12;
                String alert = fitText(mc.font, alerts.get(i), alertWidth - 12);
                g.fill(alertX, rowY, alertX + alertWidth, rowY + 11, 0xDD120808);
                g.outline(alertX, rowY, alertWidth, 11,
                        phase == FlightPhase.CRASHED ? 0xFFFF5148 : blink);
                g.text(mc.font, alert, alertX + 6, rowY + 1,
                        phase == FlightPhase.CRASHED ? 0xFFFF5148 : blink, true);
            }
        }
        drawBrakePopup(g, screenWidth, screenHeight);
    }

    private static void drawKeybindList(GuiGraphicsExtractor graphics, Minecraft mc,
                                        int screenWidth, int screenHeight) {
        List<String> bindings = new ArrayList<>(List.of(
                binding(FlightLineClient.bankLeft(), "BANK LEFT"),
                binding(FlightLineClient.bankRight(), "BANK RIGHT"),
                binding(FlightLineClient.brakes(), "BRAKES"),
                binding(FlightLineClient.toggleAutopilot(), "AUTOPILOT PANEL"),
                binding(FlightLineClient.openControls(), "ALL CONTROLS"),
                binding(FlightLineClient.toggleKeybindHints(), "HIDE PROMPTS")));
        if (FlightLineClient.isAirborne()) {
            bindings.addAll(List.of(
                    binding(FlightLineClient.flapsUp(), "FLAPS (+)"),
                    binding(FlightLineClient.flapsDown(), "FLAPS (-)"),
                    binding(FlightLineClient.spoilersExtend(), "SPOILERS (+)"),
                    binding(FlightLineClient.spoilersRetract(), "SPOILERS (-)"),
                    binding(FlightLineClient.toggleGear(), "LANDING GEAR"),
                    binding(FlightLineClient.antiIce(), "ANTI-ICE")));
        } else {
            bindings.add(binding(FlightLineClient.toggleGear(), "LANDING GEAR"));
            bindings.add(binding(FlightLineClient.pushback(), "PUSHBACK"));
            if (FlightLineClient.areEnginesActive()) {
                bindings.add(binding(FlightLineClient.shutdownEngines(), "SHUT DOWN ENGINES"));
            } else {
                bindings.add(binding(FlightLineClient.startEngines(), "START ENGINES"));
            }
        }
        if (FlightLineClient.isAirborne()) bindings.add(binding(FlightLineClient.flightRadar(), "FLIGHT RADAR"));
        if (hasAtcMessage()) bindings.add(binding(FlightLineClient.ackAtc(), "ACK ATC"));
        bindings.sort(String.CASE_INSENSITIVE_ORDER);
        int contentWidth = Math.min(188, Math.max(70, screenWidth - 12));
        int contentHeight = bindings.size() * 9;
        float availableHeight = Math.max(0.0f, screenHeight - 18.0f);
        float scale = Math.min(0.68f, Math.min(availableHeight / contentHeight,
                (screenWidth - 12.0f) / contentWidth));
        if (scale < 0.34f) return;
        int x = screenWidth - 6 - Math.round(contentWidth * scale);
        int y = screenHeight - 6 - Math.round(contentHeight * scale);
        int slide = Math.round((1.0f - keybindPanelProgress) * 8.0f);
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + slide, y);
        pose.scale(scale, scale);
        for (int i = 0; i < bindings.size(); i++) {
            String text = fitText(mc.font, bindings.get(i), contentWidth);
            graphics.text(mc.font, text, contentWidth - mc.font.width(text), i * 9,
                    withAlpha(0xFFE0E7EC, keybindPanelProgress), true);
        }
        pose.popMatrix();
    }

    private static String binding(net.minecraft.client.KeyMapping mapping, String label) {
        return FlightLineClient.keyName(mapping) + " - " + label;
    }

    private static boolean isAirbornePhase() {
        return phase == FlightPhase.AIRBORNE || phase == FlightPhase.STALL;
    }

    private static int withAlpha(int color, float alpha) {
        int opacity = Math.max(0, Math.min(255, Math.round(((color >>> 24) & 0xFF) * alpha)));
        return (color & 0x00FFFFFF) | (opacity << 24);
    }

    private static void updateKeybindPanelAnimation() {
        float delta = 1.0f / 60.0f;
        float target = showKeybinds ? 1.0f : 0.0f;
        keybindPanelProgress += (target - keybindPanelProgress) * (1.0f - (float) Math.exp(-delta * 10.0f));
    }

    private static void drawBrakePopup(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight) {
        boolean active = (systems & (1 << 6)) != 0;
        if (active) brakePopupExpiresNanos = System.nanoTime() + 700_000_000L;
        float target = active || System.nanoTime() < brakePopupExpiresNanos ? 1.0f : 0.0f;
        brakePopupProgress += (target - brakePopupProgress)
                * (1.0f - (float) Math.exp(-1.0f / 60.0f * 12.0f));
        if (brakePopupProgress < 0.015f) return;
        int popupWidth = 110;
        int popupHeight = 17;
        int x = (screenWidth - popupWidth) / 2;
        int y = screenHeight - 44 + Math.round((1.0f - brakePopupProgress) * 8.0f);
        graphics.fill(x, y, x + popupWidth, y + popupHeight,
                withAlpha(0xE51A1208, brakePopupProgress));
        graphics.outline(x, y, popupWidth, popupHeight,
                withAlpha(active ? 0xFFFFCE52 : 0xFF9C7A39, brakePopupProgress));
        graphics.centeredText(Minecraft.getInstance().font, active ? "BRAKES ON" : "BRAKES RELEASED",
                screenWidth / 2, y + 4,
                withAlpha(active ? 0xFFFFD66B : 0xFFD8C59B, brakePopupProgress));
    }

    private static void animateInstruments() {
        long now = System.nanoTime();
        float delta = Math.min(0.05f, Math.max(0.0f, (now - lastFrameNanos) / 1_000_000_000.0f));
        lastFrameNanos = now;
        float alpha = Math.min(1.0f, delta * 9.0f);
        visualSpeed += (speed - visualSpeed) * alpha;
        visualPitch += (pitchDegrees - visualPitch) * alpha;
        visualBank += (bankDegrees - visualBank) * alpha;
        visualHeading = (float)wrapDegrees(visualHeading + wrapDegrees(headingDegrees - visualHeading) * alpha);
    }

    private static void drawRadarGrid(GuiGraphicsExtractor g, int cx, int cy, int radius,
                                      double clock, float heading) {
        for (int angle = 0; angle < 360; angle += 3) {
            double radians = Math.toRadians(angle);
            for (int ring : new int[]{radius, radius * 2 / 3, radius / 3}) {
                int px = cx + (int)Math.round(Math.sin(radians) * ring);
                int py = cy - (int)Math.round(Math.cos(radians) * ring);
                g.fill(px, py, px + 1, py + 1, ring == radius ? 0xFF315E50 : 0xFF26483D);
            }
        }
        g.fill(cx - radius, cy, cx + radius + 1, cy + 1, 0xFF26483D);
        g.fill(cx, cy - radius, cx + 1, cy + radius + 1, 0xFF26483D);
        for (int cardinal = 0; cardinal < 360; cardinal += 90) {
            double relative = Math.toRadians(cardinal - heading);
            int outerX = cx - (int)Math.round(Math.sin(relative) * radius);
            int outerY = cy - (int)Math.round(Math.cos(relative) * radius);
            int innerX = cx - (int)Math.round(Math.sin(relative) * (radius - 3));
            int innerY = cy - (int)Math.round(Math.cos(relative) * (radius - 3));
            drawLine(g, innerX, innerY, outerX, outerY, 0xFF9BC9AE);
        }
        double sweep = clock * Math.PI / 2.2;
        for (int trail = 5; trail >= 1; trail--) {
            double trailingAngle = sweep - Math.toRadians(trail * 3.0);
            int trailX = cx + (int)Math.round(Math.sin(trailingAngle) * radius);
            int trailY = cy - (int)Math.round(Math.cos(trailingAngle) * radius);
            int trailColor = trail <= 2 ? 0x604EA87B : 0x304EA87B;
            drawLine(g, cx, cy, trailX, trailY, trailColor);
        }
        int endX = cx + (int)Math.round(Math.sin(sweep) * radius);
        int endY = cy - (int)Math.round(Math.cos(sweep) * radius);
        drawLine(g, cx, cy, endX, endY, 0xFF62A97B);
    }

    private static void drawLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int twiceError = 2 * error;
            if (twiceError >= dy) { error += dy; x0 += sx; }
            if (twiceError <= dx) { error += dx; y0 += sy; }
        }
    }

    private static void drawAircraftSymbol(GuiGraphicsExtractor g, int cx, int cy) {
        g.fill(cx, cy - 4, cx + 1, cy + 4, 0xFF76E8B0);
        g.fill(cx - 4, cy, cx + 5, cy + 1, 0xFF76E8B0);
        g.fill(cx - 2, cy + 2, cx + 3, cy + 3, 0xFF76E8B0);
    }

    private static void drawTrafficBlip(GuiGraphicsExtractor g, int cx, int cy, double bearing,
                                        double range, boolean onGround, boolean isPlayer, int relativeAltitude) {
        double scaledRange = Math.min(1.0, range);
        int x = cx - (int)Math.round(Math.sin(bearing) * 17.0 * scaledRange);
        int y = cy - (int)Math.round(Math.cos(bearing) * 17.0 * scaledRange);
        int color = onGround ? 0xFFFFCE52 : isPlayer ? 0xFF6BCBFF : 0xFF75E6A5;
        if (onGround) {
            g.fill(x, y, x + 2, y + 2, color);
        } else if (isPlayer) {
            drawLine(g, x, y - 1, x + 1, y, color);
            drawLine(g, x + 1, y, x, y + 1, color);
            drawLine(g, x, y + 1, x - 1, y, color);
            drawLine(g, x - 1, y, x, y - 1, color);
        } else {
            drawLine(g, x, y - 1, x + 1, y + 1, color);
            drawLine(g, x + 1, y + 1, x - 1, y + 1, color);
            drawLine(g, x - 1, y + 1, x, y - 1, color);
        }
        if (!onGround && Math.abs(relativeAltitude) >= 5) {
            g.fill(x + 2, y - 2, x + 3, y - 1,
                    relativeAltitude > 0 ? 0xFFFFCE52 : 0xFFFF8B52);
        }
    }

    private static void drawArtificialHorizon(GuiGraphicsExtractor g, int cx, int cy, int width, int height,
                                               float pitch, float bank, boolean showIls,
                                               float localizerDegrees, float glideslopeDegrees) {
        int left = cx - width / 2;
        int right = cx + width / 2;
        int top = cy - height / 2;
        int bottom = top + height;

        double bankRad = Math.toRadians(Math.max(-60.0f, Math.min(60.0f, bank)));
        double slope = Math.tan(bankRad);
        double cosBank = Math.cos(bankRad);
        double sinBank = Math.sin(bankRad);
        int pitchOffset = Math.round(Math.max(-45.0f, Math.min(45.0f, pitch)) * 0.40f);

        // Sky and ground fill
        for (int px = left; px < right; px += 2) {
            int dx = px - cx;
            int horizon = Math.max(top, Math.min(bottom,
                    cy + pitchOffset + (int)Math.round(dx * slope)));
            g.fill(px, top, px + 2, horizon, 0xFF1D6792); // Sky blue
            g.fill(px, horizon, px + 2, bottom, 0xFF6A4A28); // Earth brown
            g.fill(px, Math.max(top, horizon - 1), px + 2, Math.min(bottom, horizon + 1),
                    0xFFFFFFFF); // White horizon line
        }

        // Five-degree pitch ladder; ten-degree bars are longer for quick attitude reads.
        for (int ladderPitch = -25; ladderPitch <= 25; ladderPitch += 5) {
            if (ladderPitch == 0) continue;
            double dp = (pitch - ladderPitch) * 0.40;
            int rungCenterX = cx - (int)Math.round(dp * sinBank);
            int rungCenterY = cy + (int)Math.round(dp * cosBank);
            if (rungCenterY >= top + 4 && rungCenterY <= bottom - 4) {
                int rungHalfWidth = Math.abs(ladderPitch) % 10 == 0 ? 10 : 5;
                int rx1 = rungCenterX - (int)Math.round(rungHalfWidth * cosBank);
                int ry1 = rungCenterY - (int)Math.round(rungHalfWidth * sinBank);
                int rx2 = rungCenterX + (int)Math.round(rungHalfWidth * cosBank);
                int ry2 = rungCenterY + (int)Math.round(rungHalfWidth * sinBank);
                drawLineClipped(g, rx1, ry1, rx2, ry2, left, top, right, bottom,
                        ladderPitch > 0 ? 0xFFFFFFFF : 0xFFD0D0D0);
            }
        }

        for (int bankMark = -60; bankMark <= 60; bankMark += 15) {
            int markX = cx + (int)Math.round(Math.sin(Math.toRadians(bankMark)) * (height / 2 - 2));
            int markY = cy - (int)Math.round(Math.cos(Math.toRadians(bankMark)) * (height / 2 - 2));
            int innerX = cx + (int)Math.round(Math.sin(Math.toRadians(bankMark)) * (height / 2 - 5));
            int innerY = cy - (int)Math.round(Math.cos(Math.toRadians(bankMark)) * (height / 2 - 5));
            drawLineClipped(g, markX, markY, innerX, innerY, left, top, right, bottom, 0xFFD9E5E8);
        }

        if (showIls) {
            int localizerOffset = Math.max(-width / 2 + 3,
                    Math.min(width / 2 - 3, Math.round(localizerDegrees * 5.0f)));
            int glideslopeOffset = Math.max(-height / 2 + 3,
                    Math.min(height / 2 - 3, -Math.round(glideslopeDegrees * 7.0f)));
            drawLineClipped(g, cx + localizerOffset, cy - 15, cx + localizerOffset, cy + 15,
                    left, top, right, bottom, 0xFFFF6CD9);
            drawLineClipped(g, cx - 15, cy + glideslopeOffset, cx + 15, cy + glideslopeOffset,
                    left, top, right, bottom, 0xFF79E8FF);
            g.fill(cx + localizerOffset - 2, cy - 1, cx + localizerOffset + 3, cy + 2, 0xFFFF6CD9);
            g.fill(cx - 1, cy + glideslopeOffset - 2, cx + 2, cy + glideslopeOffset + 3, 0xFF79E8FF);
        }

        // Fixed Aircraft Reference Symbol (Gull Wings & Center Pip)
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFCE52);
        g.fill(cx - 14, cy - 1, cx - 4, cy + 1, 0xFFFFCE52);
        g.fill(cx - 5, cy + 1, cx - 3, cy + 3, 0xFFFFCE52);
        g.fill(cx + 4, cy - 1, cx + 14, cy + 1, 0xFFFFCE52);
        g.fill(cx + 3, cy + 1, cx + 5, cy + 3, 0xFFFFCE52);

        // Roll Pointer on top
        int rollPointerX = cx - (int)Math.round(Math.sin(bankRad) * (height / 2 - 2));
        int rollPointerY = top + 2;
        g.fill(rollPointerX - 1, rollPointerY, rollPointerX + 2, rollPointerY + 3, 0xFFFFCE52);
    }

    private static void drawLineClipped(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                                        int left, int top, int right, int bottom, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            if (x0 >= left && x0 < right && y0 >= top && y0 < bottom) {
                g.fill(x0, y0, x0 + 1, y0 + 1, color);
            }
            if (x0 == x1 && y0 == y1) break;
            int twiceError = 2 * error;
            if (twiceError >= dy) { error += dy; x0 += sx; }
            if (twiceError <= dx) { error += dx; y0 += sy; }
        }
    }

    private static String fitText(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        while (!text.isEmpty() && font.width(text + ellipsis) > maxWidth) {
            text = required(text.substring(0, text.length() - 1));
        }
        return text.isEmpty() ? "" : text + ellipsis;
    }

    private static String required(@Nullable String value) {
        return java.util.Objects.requireNonNull(value);
    }

    private static void gauge(GuiGraphicsExtractor g, int x, int y, int width, int height, float value, int color) {
        g.fill(x, y, x + width, y + height, 0xFF26343D);
        int fill = Math.max(0, Math.min(width, Math.round(width * value)));
        g.fill(x, y, x + fill, y + height, color);
        g.outline(x, y, width, height, 0x805D7781);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int borderColor) {
        g.fill(x, y, x + width, y + height, 0xC8101820);
        g.outline(x, y, width, height, borderColor);
    }

    private static int phaseColor() {
        return phase == FlightPhase.STALL || phase == FlightPhase.CRASHED ? 0xFFFF5148 : 0xFF72E6F5;
    }

    private static int gearColor() {
        return gearState == 2 ? 0xFFFF5148 : gearState == 1 ? 0xFFFFCE52 : 0xFF62D89B;
    }

    private static int radioAltitudeColor(int altitudeFeet) {
        return altitudeFeet < 100 ? 0xFFFF5148
                : altitudeFeet < 500 ? 0xFFFFCE52 : 0xFF62D89B;
    }

    private static int engineColor(int state) {
        return state >= 3 ? 0xFFFF5148 : state == 0 ? 0xFF83959B : 0xFF62D89B;
    }

    private static void drawEngineStatus(GuiGraphicsExtractor graphics, Minecraft client, int x, int y,
                                         String label, int state, boolean blinkOn) {
        if (state == 4) {
            graphics.fill(x - 1, y - 1, x + 53, y + 10, blinkOn ? 0xFFFF322D : 0xFF701B20);
        }
        graphics.text(client.font, label + " " + engineTag(state), x, y,
                state == 4 && blinkOn ? 0xFFFFFFFF : engineColor(state), true);
    }

    private static String weatherName(int condition) {
        return switch (condition) {
            case 2 -> "TS";
            case 1 -> "RAIN";
            default -> "CLEAR";
        };
    }

    private static int weatherColor(int condition) {
        return switch (condition) {
            case 2 -> 0xFFFF8B52;
            case 1 -> 0xFF72C8F0;
            default -> 0xFF62D89B;
        };
    }

    private static int knots(float speed) {
        return Math.round(speed * (float)FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
    }

    private static String engineTag(int state) {
        return switch (state) { case 1 -> "START"; case 2 -> "RUN"; case 3 -> "FAIL"; case 4 -> "FIRE"; default -> "OFF"; };
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static List<String> warningLabels() {
        List<String> labels = new ArrayList<>();
        if (phase == FlightPhase.CRASHED) labels.add("AIRFRAME CRASH - J TO RESET");
        if ((warnings & FlightWarning.STALL) != 0) labels.add("STALL");
        if ((warnings & FlightWarning.WINDSHEAR) != 0) labels.add("WINDSHEAR");
        if ((warnings & FlightWarning.PULL_UP) != 0) labels.add("PULL UP");
        if ((warnings & FlightWarning.TERRAIN) != 0) labels.add("TERRAIN, TERRAIN");
        if ((warnings & FlightWarning.TCAS_RESOLUTION) != 0) {
            String direction = (warnings & FlightWarning.TCAS_DESCEND) != 0 ? "DESCEND NOW" : "CLIMB NOW";
            labels.add((warnings & FlightWarning.TCAS_CROSSING) != 0
                    ? "TCAS: " + direction + " - CROSSING" : "TCAS: " + direction);
        }
        if ((warnings & FlightWarning.TCAS_CLEAR) != 0) labels.add("CLEAR OF CONFLICT");
        if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) labels.add("TRAFFIC, TRAFFIC");
        if ((warnings & FlightWarning.GEAR_SELECTED_ON_GROUND) != 0) labels.add("GEAR SELECT - AIRBORNE ONLY");
        if ((warnings & FlightWarning.SINK_RATE) != 0) labels.add("SINK RATE");
        if ((warnings & FlightWarning.DONT_SINK) != 0) labels.add("DON'T SINK");
        if ((warnings & FlightWarning.TOO_LOW_TERRAIN) != 0) labels.add("TOO LOW TERRAIN");
        if ((warnings & FlightWarning.GEAR_UP) != 0) labels.add("TOO LOW GEAR");
        if ((warnings & FlightWarning.TOO_LOW_FLAPS) != 0) labels.add("TOO LOW FLAPS");
        if ((warnings & FlightWarning.BANK_ANGLE) != 0) labels.add("BANK ANGLE");
        if ((warnings & FlightWarning.GLIDESLOPE_HIGH) != 0) labels.add("GLIDESLOPE HIGH");
        if ((warnings & FlightWarning.GLIDESLOPE_LOW) != 0) labels.add("GLIDESLOPE");
        if ((warnings & FlightWarning.ENGINE_FIRE) != 0) labels.add("ENGINE FIRE");
        if ((warnings & FlightWarning.ENGINE_FAILURE) != 0) labels.add("ENGINE FAILURE");
        if ((warnings & FlightWarning.FUEL_EMPTY) != 0) labels.add("FUEL EMPTY - ENGINES OFF");
        if ((warnings & FlightWarning.FUEL_LOW) != 0) labels.add("LOW FUEL");
        if ((warnings & FlightWarning.GEAR_FAILED) != 0) labels.add("GEAR FAILURE");
        if ((warnings & FlightWarning.GEAR_HOT) != 0) labels.add("GEAR HOT");
        if ((warnings & FlightWarning.TAKEOFF_CONFIG) != 0) labels.add("TAKEOFF CONFIG");
        if ((warnings & FlightWarning.AIRFRAME_STRESS) != 0) labels.add("AIRFRAME STRESS");
        if ((warnings & FlightWarning.OVERSPEED) != 0) labels.add("OVERSPEED");
        return labels;
    }
}
