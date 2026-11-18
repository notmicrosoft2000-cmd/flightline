package dev.flightline.client;

import dev.flightline.flight.AircraftType;
import dev.flightline.flight.EmergencyStatus;
import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightTuning;
import dev.flightline.flight.FlightWarning;
import dev.flightline.flight.NavGuidance;
import dev.flightline.flight.NavMode;
import dev.flightline.FlightLine;
import dev.flightline.network.EmergencyStatusPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightRadarPayload;
import dev.flightline.network.FlightStatePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Deque;

final class FlightHud {
    private static final int PFD_BASE_WIDTH = 168;
    private static final int PFD_BASE_HEIGHT = 158;
    private static final int EICAS_WIDTH = 128;
    private static final int EICAS_HEIGHT = 138;
    private static final float EICAS_SCALE = 0.84f;
    private static final int EICAS_DISPLAY_WIDTH = Math.round(EICAS_WIDTH * EICAS_SCALE);
    private static final int EICAS_DISPLAY_HEIGHT = Math.round(EICAS_HEIGHT * EICAS_SCALE);
    private static final int TCAS_WARNINGS = FlightWarning.TCAS_TRAFFIC
            | FlightWarning.TCAS_RESOLUTION
            | FlightWarning.TCAS_CLIMB
            | FlightWarning.TCAS_DESCEND
            | FlightWarning.TCAS_CROSSING
            | FlightWarning.TCAS_CLEAR;
    private static final long ALARM_ONSET_NANOS = 800_000_000L;

    /** ND-only proximity tiers; TCAS styling is advisory and never drives the red alarm. */
    private enum TrafficTier { RA, TA, PROXIMATE, FAR }

    record TrafficAdvisory(String label, int color) {}

    private record TrafficInfo(FlightRadarPayload.Contact contact, TrafficTier tier,
                               int distanceBlocks, int relativeAltitudeFeet,
                               int closingKnots, int verticalClosingFpm) {}

    private static final int PFD_GREEN = FlightLineTheme.GREEN;
    private static final int PFD_CYAN = FlightLineTheme.CYAN;
    private static final int PFD_WHITE = FlightLineTheme.PAPER;
    private static final int PFD_AMBER = FlightLineTheme.AMBER;
    private static final int PFD_RED = FlightLineTheme.WARNING;
    private static final int PFD_MUTED = FlightLineTheme.MUTED;
    private static boolean enabled;
    private static FlightPhase phase = FlightPhase.OFF;
    private static NavMode navMode = NavMode.HDG;
    private static float speed, throttle, bankDegrees, pitchDegrees, headingDegrees;
    private static float visualSpeed, visualBank, visualPitch, visualHeading;
    private static float velocityX, velocityY, velocityZ, groundSpeedKnots, trackDegrees;
    private static float gLoad = 1.0f;
    private static float lastGVerticalSpeed;
    private static long lastGUpdateNanos;
    private static int radioAltitudeFeet, verticalSpeedFpm, gearState, systems, warnings;
    private static int previousNonTcasWarnings;
    private static boolean previousCrashed;
    private static long alarmOnsetExpiresNanos;
    private static int fuelPercent;
    private static boolean airbrakes;
    private static boolean autopilot;
    private static FlightInstrumentPayload.AutopilotGuidance autopilotGuidance =
            FlightInstrumentPayload.AutopilotGuidance.defaults();
    private static FlightInstrumentPayload.WeatherGuidance weatherGuidance =
            FlightInstrumentPayload.WeatherGuidance.defaults();
    private static float trafficBearingDegrees;
    private static int trafficRangeBlocks, trafficAltitudeFeet;
    private static int trafficCounts;
    private static FlightRadarPayload radarPayload = new FlightRadarPayload(0, 0, 0, 0.0f, 0, List.of());
    private static boolean showPfd = true;
    private static boolean showEicas = true;
    private static boolean showRadar = true;
    private static boolean showKeybinds = true;
    private static boolean showTelemetry = false;
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
    private static EmergencyStatusPayload emergencyStatus = EmergencyStatusPayload.inactive();
    private static String cockpitNotice = "";
    private static long cockpitNoticeExpiresNanos;
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

    static float currentBank() {
        return visualBank;
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

    static int groundSpeedKnots() {
        return Math.round(groundSpeedKnots);
    }

    static int trackDegrees() {
        return Math.floorMod(Math.round(trackDegrees), 360);
    }

    static float gLoad() {
        return gLoad;
    }

    static FlightPhase phase() {
        return phase;
    }

    static NavMode navMode() {
        return navMode;
    }

    static int flapDetent() {
        return (systems >> 9) & 7;
    }

    static int engineN1() {
        return (systems >> 12) & 0x7F;
    }

    static boolean isAutopilotEngaged() {
        return autopilot;
    }

    static boolean gearDown() {
        return gearState == 0;
    }

    static boolean anyEngineRunning() {
        return (systems & 7) == 2 || ((systems >> 3) & 7) == 2;
    }

    static boolean spoilersDeployed() {
        return (systems & (1 << 8)) != 0;
    }

    static boolean airbrakesDeployed() {
        return airbrakes;
    }

    static int fuelPercent() {
        return fuelPercent;
    }

    /** Authoritative warning bits for in-flight panels; the HUD itself stays wordless. */
    static int activeWarnings() {
        return warnings;
    }

    static boolean isCrashed() {
        return phase == FlightPhase.CRASHED;
    }

    static FlightInstrumentPayload.AutopilotGuidance autopilotGuidance() {
        return autopilotGuidance;
    }

    static boolean isAtcControlled() {
        return autopilotGuidance.atcControlled();
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

    /** Short neutral rows for the flight-deck ALERT tab, derived only from radar contacts. */
    static List<TrafficAdvisory> trafficAdvisories() {
        if (!hasRadarData()) return List.of();
        List<TrafficInfo> ranked = rankTraffic(radarPayload.ownX(), radarPayload.ownZ(),
                radarPayload.ownAltitudeFeet(), ndRangeBlocks());
        List<TrafficAdvisory> advisories = new ArrayList<>();
        for (TrafficInfo info : ranked) {
            if (advisories.size() >= 4) break;
            if (info.tier() == TrafficTier.FAR) continue;
            String altitude = String.format(Locale.ROOT, "%+d", Math.round(info.relativeAltitudeFeet() / 100.0f));
            String label = switch (info.tier()) {
                case RA -> "TCAS RA " + info.distanceBlocks() + "B " + altitude + "00";
                case TA -> "TCAS TA " + info.distanceBlocks() + "B " + altitude + "00";
                case PROXIMATE -> "PROX " + info.distanceBlocks() + "B " + altitude + "00";
                case FAR -> "";
            };
            int color = info.tier() == TrafficTier.RA ? FlightLineTheme.TCAS_RA
                    : info.tier() == TrafficTier.TA ? FlightLineTheme.TCAS_TA : FlightLineTheme.CYAN;
            advisories.add(new TrafficAdvisory(label, color));
        }
        return advisories;
    }

    private static int ndRangeBlocks() {
        return Math.min(256, Math.max(64, radarPayload.scopeBlocks()));
    }

    private static List<TrafficInfo> rankTraffic(int ownX, int ownZ, int ownAltFt, double rangeBlocks) {
        List<TrafficInfo> ranked = new ArrayList<>();
        if (!hasRadarData()) return ranked;
        for (FlightRadarPayload.Contact contact : radarPayload.contacts()) {
            double dx = contact.x() - ownX;
            double dz = contact.z() - ownZ;
            double distance = Math.hypot(dx, dz);
            if (distance > rangeBlocks) continue;
            int relativeAltitude = contact.altitudeFeet() - ownAltFt;
            int closingKnots = closingKnots(contact, dx, dz, distance);
            int verticalClosing = -Integer.signum(relativeAltitude)
                    * (contact.verticalSpeedFpm() - verticalSpeedFpm);
            TrafficTier tier = contact.airborne()
                    ? classifyAirborne(distance, relativeAltitude, closingKnots, verticalClosing)
                    : TrafficTier.FAR;
            ranked.add(new TrafficInfo(contact, tier, (int) Math.round(distance), relativeAltitude,
                    closingKnots, verticalClosing));
        }
        ranked.sort(Comparator.comparingInt((TrafficInfo info) -> info.tier().ordinal())
                .thenComparingInt(TrafficInfo::distanceBlocks));
        return ranked;
    }

    private static TrafficTier classifyAirborne(double distance, int relativeAltitude,
                                                int closingKnots, int verticalClosingFpm) {
        int altitudeGap = Math.abs(relativeAltitude);
        if (distance < 120 && altitudeGap < 600) return TrafficTier.RA;
        if (distance < 160 && altitudeGap < 350 && closingKnots > 60 && verticalClosingFpm > 200) {
            return TrafficTier.RA;
        }
        if (distance < 250 && altitudeGap < 850) return TrafficTier.TA;
        if (distance < 300 && altitudeGap < 550 && (closingKnots > 40 || verticalClosingFpm > 250)) {
            return TrafficTier.TA;
        }
        if (distance < 450 && altitudeGap < 1200) return TrafficTier.PROXIMATE;
        if (distance < 600 && altitudeGap < 800 && closingKnots > 30) return TrafficTier.PROXIMATE;
        return TrafficTier.FAR;
    }

    /** Closing speed along the line of sight; positive means the separation is shrinking. */
    private static int closingKnots(FlightRadarPayload.Contact contact, double dx, double dz,
                                    double distance) {
        if (distance < 0.001) return 0;
        float ownHeading = hasRadarData() ? radarPayload.ownHeadingDegrees() : visualHeading;
        double ownSpeed = Math.max(0.0, groundSpeedKnots());
        double ownVx = Math.sin(Math.toRadians(ownHeading)) * ownSpeed;
        double ownVz = Math.cos(Math.toRadians(ownHeading)) * ownSpeed;
        double otherVx = Math.sin(Math.toRadians(contact.headingDegrees())) * contact.speedKnots();
        double otherVz = Math.cos(Math.toRadians(contact.headingDegrees())) * contact.speedKnots();
        double separating = (otherVx - ownVx) * (dx / distance) + (otherVz - ownVz) * (dz / distance);
        return (int) Math.round(-separating);
    }

    private static int[] ndPoint(TrafficInfo info, int cx, int cy, double rangeBlocks,
                                 float heading, int ownX, int ownZ) {
        double dx = info.contact().x() - ownX;
        double dz = info.contact().z() - ownZ;
        double relative = Math.toRadians(Math.toDegrees(Math.atan2(-dx, dz)) - heading);
        double scale = Math.min(1.0, info.distanceBlocks() / Math.max(1.0, rangeBlocks));
        return new int[]{
                cx - (int) Math.round(Math.sin(relative) * 17.0 * scale),
                cy - (int) Math.round(Math.cos(relative) * 17.0 * scale)
        };
    }

    private static boolean overlaps(List<int[]> points, int x, int y, int radius) {
        for (int[] point : points) {
            if (Math.abs(point[0] - x) <= radius && Math.abs(point[1] - y) <= radius) return true;
        }
        return false;
    }

    private static void drawTrafficVelocity(GuiGraphicsExtractor g, int px, int py, float contactHeading,
                                            float ownHeading, int speedKnots, int color) {
        double relative = Math.toRadians(contactHeading - ownHeading);
        int length = Math.max(3, Math.min(9, speedKnots / 25));
        int endX = px - (int) Math.round(Math.sin(relative) * length);
        int endY = py - (int) Math.round(Math.cos(relative) * length);
        drawLine(g, px, py, endX, endY, color);
    }

    private static void drawVerticalTrend(GuiGraphicsExtractor g, int px, int py,
                                          int verticalSpeedFpm, int color) {
        int direction = verticalSpeedFpm > 0 ? -1 : 1;
        int tipY = py + direction * 5;
        drawLine(g, px - 2, tipY - direction * 2, px, tipY, color);
        drawLine(g, px + 2, tipY - direction * 2, px, tipY, color);
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
            showKeybinds = readPreference(preferences, "showKeybinds", showKeybinds);
            showTelemetry = readPreference(preferences, "showTelemetryDetail", false);
        } catch (IOException exception) {
            FlightLine.LOGGER.warn("Could not load Flight Line HUD preferences from {}", path, exception);
        }
    }

    static void togglePfd() { showPfd = !showPfd; savePreferences(); }
    static void toggleEicas() { showEicas = !showEicas; savePreferences(); }
    static void toggleRadar() { showRadar = !showRadar; savePreferences(); }
    static void toggleKeybinds() { showKeybinds = !showKeybinds; savePreferences(); }
    static void toggleTelemetry() { showTelemetry = !showTelemetry; savePreferences(); }
    static boolean isTelemetryVisible() { return showTelemetry; }

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
        preferences.setProperty("showKeybinds", Boolean.toString(showKeybinds));
        preferences.setProperty("showTelemetryDetail", Boolean.toString(showTelemetry));
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

    static EmergencyStatusPayload emergencyStatus() {
        return emergencyStatus;
    }

    static void updateEmergency(EmergencyStatusPayload payload) {
        emergencyStatus = payload;
    }

    static void showNotice(String message) {
        cockpitNotice = message;
        cockpitNoticeExpiresNanos = System.nanoTime() + 5_000_000_000L;
    }

    static void update(FlightStatePayload state) {
        enabled = state.enabled();
        phase = FlightPhase.fromId(state.phase());
        navMode = NavMode.fromId(state.navMode());
        speed = state.speed();
        throttle = state.throttle();
        velocityX = state.velocityX();
        velocityY = state.velocityY();
        velocityZ = state.velocityZ();
        double horizontalVelocity = Math.hypot(velocityX, velocityZ);
        groundSpeedKnots = (float) (horizontalVelocity * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
        if (horizontalVelocity > 0.002) {
            double track = Math.toDegrees(Math.atan2(-velocityX, velocityZ)) % 360.0;
            if (track < 0.0) track += 360.0;
            trackDegrees = (float) track;
        } else {
            trackDegrees = headingDegrees;
        }
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
        fuelPercent = instruments.fuelPercent();
        airbrakes = instruments.airbrakes();
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
        updateGLoad();
    }

    private static void updateGLoad() {
        long now = System.nanoTime();
        if (lastGUpdateNanos == 0L || now - lastGUpdateNanos < 60_000_000L) return;
        double seconds = (now - lastGUpdateNanos) / 1_000_000_000.0;
        double deltaMetersPerSecond = (verticalSpeedFpm - lastGVerticalSpeed) * 0.00508;
        double acceleration = deltaMetersPerSecond / Math.max(0.05, seconds);
        float target = Math.max(0.0f, Math.min(6.0f, 1.0f + (float) (acceleration / 9.80665)));
        gLoad += (target - gLoad) * 0.35f;
        lastGVerticalSpeed = verticalSpeedFpm;
        lastGUpdateNanos = now;
    }

    static void clear() {
        enabled = false;
        phase = FlightPhase.OFF;
        navMode = NavMode.HDG;
        speed = throttle = bankDegrees = pitchDegrees = headingDegrees = 0;
        visualSpeed = visualBank = visualPitch = visualHeading = 0;
        velocityX = velocityY = velocityZ = groundSpeedKnots = trackDegrees = 0;
        gLoad = 1.0f;
        lastGVerticalSpeed = 0.0f;
        lastGUpdateNanos = 0L;
        radioAltitudeFeet = verticalSpeedFpm = gearState = systems = warnings = 0;
        previousNonTcasWarnings = 0;
        previousCrashed = false;
        alarmOnsetExpiresNanos = 0L;
        fuelPercent = 0;
        airbrakes = false;
        autopilot = false;
        autopilotGuidance = FlightInstrumentPayload.AutopilotGuidance.defaults();
        weatherGuidance = FlightInstrumentPayload.WeatherGuidance.defaults();
        trafficBearingDegrees = 0.0f;
        trafficRangeBlocks = trafficAltitudeFeet = 0;
        trafficCounts = 0;
        radarPayload = new FlightRadarPayload(0, 0, 0, 0.0f, 0, List.of());
        radarDataReceived = false;
        lastRadarUpdateNanos = 0L;
        ilsActive = false;
        atcMessage = "";
        atcControllerEntityId = -1;
        atcMessageExpiresNanos = 0L;
        atcRadioLog.clear();
        emergencyStatus = EmergencyStatusPayload.inactive();
        cockpitNotice = "";
        cockpitNoticeExpiresNanos = 0L;
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
        long nowNanos = System.nanoTime();
        double clock = nowNanos / 1_000_000_000.0;
        updateAlarmOnset(nowNanos);

        float pfdScale = 1.0f;
        int pfdDisplayWidth = 0;
        int pfdDisplayHeight = 0;
        if (showPfd) {
            pfdScale = Math.min(0.72f, Math.min(
                    (screenWidth * 0.34f) / PFD_BASE_WIDTH,
                    (screenHeight * 0.46f) / PFD_BASE_HEIGHT));
            pfdScale = Math.max(0.44f, pfdScale);
            pfdDisplayWidth = Math.round(PFD_BASE_WIDTH * pfdScale);
            pfdDisplayHeight = Math.round(PFD_BASE_HEIGHT * pfdScale);
        }
        int pfdLeft = 6;
        int pfdTop = 6;
        int pfdRight = pfdLeft + pfdDisplayWidth;
        int pfdBottom = pfdTop + pfdDisplayHeight;

        int eicasX = screenWidth - EICAS_DISPLAY_WIDTH - 6;
        boolean eicasVisible = showEicas && (!showPfd || eicasX >= pfdRight + 6);

        int ndWidth = 104;
        int ndHeight = 96;
        int ndX = 0;
        int ndY = 6;
        boolean ndVisible = showRadar;
        if (ndVisible) {
            int availableLeft = showPfd ? pfdRight + 6 : 6;
            int availableRight = eicasVisible ? eicasX - 6 : screenWidth - 6;
            if (availableRight - availableLeft >= ndWidth) {
                ndX = (availableLeft + availableRight - ndWidth) / 2;
            } else {
                ndX = availableLeft;
                ndY = pfdBottom + 4;
                if (ndX + ndWidth > screenWidth - 6 || ndY + ndHeight > screenHeight - 4) {
                    ndVisible = false;
                }
            }
        }

        if (showPfd) {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(pfdLeft, pfdTop);
            pose.scale(pfdScale, pfdScale);
            drawPfd(g, mc, player);
            pose.popMatrix();
        }

        if (ndVisible) {
            // Heading-up ND using authoritative TCAS radar payload contacts
            panel(g, ndX, ndY, ndWidth, ndHeight, FlightLineTheme.CYAN);
            int heading = Math.floorMod(Math.round(visualHeading), 360);
            int ndRangeBlocks = hasRadarData() ? Math.min(256, Math.max(64, radarPayload.scopeBlocks())) : 128;
            var activeLeg = NavigationState.activeLeg();
            int ownAltFt = hasRadarData() ? radarPayload.ownAltitudeFeet() : (int) Math.round(player.getY() * 3.28084);
            int ownX = hasRadarData() ? radarPayload.ownX() : player.blockPosition().getX();
            int ownZ = hasRadarData() ? radarPayload.ownZ() : player.blockPosition().getZ();
            int groundSpeedKnots = Math.max(1, groundSpeedKnots());

            String ndHeader = activeLeg == null
                    ? String.format(Locale.ROOT, "TCAS %03d° %dB", heading, ndRangeBlocks)
                    : "NAV " + activeLeg.name();
            g.text(mc.font, fitText(mc.font, ndHeader, ndWidth - 8),
                    ndX + 4, ndY + 4,
                    FlightLineTheme.CYAN, true);
            String legDistance = activeLeg == null
                    ? "GS " + groundSpeedKnots + "KT"
                    : String.format(Locale.ROOT, "BRG %03.0f°  %.0fB",
                            NavGuidance.bearingDegrees(ownX, ownZ, activeLeg.x(), activeLeg.z()),
                            NavGuidance.distanceBlocks(ownX, ownZ, activeLeg.x(), activeLeg.z()));
            g.text(mc.font, fitText(mc.font, legDistance, ndWidth - 8),
                    ndX + 4, ndY + 14, FlightLineTheme.TEXT_DIM, false);
            int eteMinutes = activeLeg == null ? 0 : NavigationState.eteMinutes(ownX, ownZ, groundSpeedKnots);
            String legGuidance = activeLeg == null
                    ? "ROUTE " + NavigationState.statusLabel()
                    : String.format(Locale.ROOT, "XTK %+.0f  ETE %02d:%02d",
                            NavigationState.crossTrackBlocks(ownX, ownZ),
                            eteMinutes / 60, eteMinutes % 60);
            g.text(mc.font, fitText(mc.font, legGuidance, ndWidth - 8),
                    ndX + 4, ndY + 24, FlightLineTheme.GREEN, false);

            int radarCx = ndX + ndWidth / 2;
            int radarCy = ndY + 45;
            drawRadarGrid(g, radarCx, radarCy, 18, clock, visualHeading);
            drawAircraftSymbol(g, radarCx, radarCy);

            drawRouteOverlay(g, mc, radarCx, radarCy, 16, ndRangeBlocks, visualHeading, ownX, ownZ);

            // Rank the payload contacts, then drop overlapping blips so the small ND stays readable.
            List<TrafficInfo> ranked = rankTraffic(ownX, ownZ, ownAltFt, ndRangeBlocks);
            List<TrafficInfo> airborne = new ArrayList<>();
            List<TrafficInfo> ground = new ArrayList<>();
            for (TrafficInfo info : ranked) {
                if (info.contact().airborne()) airborne.add(info);
                else ground.add(info);
            }
            List<int[]> drawnPixels = new ArrayList<>();

            int groundCount = 0;
            for (TrafficInfo info : ground) {
                if (groundCount >= 3) break;
                int[] point = ndPoint(info, radarCx, radarCy, ndRangeBlocks, visualHeading, ownX, ownZ);
                if (overlaps(drawnPixels, point[0], point[1], 2)) continue;
                g.fill(point[0] - 1, point[1] - 1, point[0] + 1, point[1] + 1, 0x80C8B060);
                drawnPixels.add(point);
                groundCount++;
            }

            int airborneDrawn = 0;
            for (TrafficInfo info : airborne) {
                if (airborneDrawn >= 8) break;
                int[] point = ndPoint(info, radarCx, radarCy, ndRangeBlocks, visualHeading, ownX, ownZ);
                if (overlaps(drawnPixels, point[0], point[1], 3)) continue;
                int px = point[0];
                int py = point[1];
                var contact = info.contact();
                TrafficTier tier = info.tier();
                int tierColor = tier == TrafficTier.RA ? FlightLineTheme.TCAS_RA
                        : tier == TrafficTier.TA ? FlightLineTheme.TCAS_TA : FlightLineTheme.CYAN;

                switch (tier) {
                    case RA -> {
                        g.fill(px - 2, py - 2, px + 3, py + 3, FlightLineTheme.TCAS_RA);
                        g.outline(px - 2, py - 2, 5, 5, FlightLineTheme.PAPER);
                    }
                    case TA -> {
                        g.fill(px - 1, py - 2, px + 2, py + 3, FlightLineTheme.TCAS_TA);
                        g.fill(px - 2, py - 1, px + 3, py + 2, FlightLineTheme.TCAS_TA);
                    }
                    case PROXIMATE -> {
                        drawDiamond(g, px, py, 2, FlightLineTheme.CYAN);
                        g.fill(px, py, px + 1, py + 1, FlightLineTheme.CYAN);
                    }
                    case FAR -> drawDiamond(g, px, py, 2, FlightLineTheme.CYAN);
                }

                if (tier == TrafficTier.RA || tier == TrafficTier.TA) {
                    drawTrafficVelocity(g, px, py, contact.headingDegrees(), visualHeading,
                            contact.speedKnots(), tierColor);
                    if (Math.abs(contact.verticalSpeedFpm()) >= 300) {
                        drawVerticalTrend(g, px, py, contact.verticalSpeedFpm(), tierColor);
                    }
                }

                if (tier == TrafficTier.RA || tier == TrafficTier.TA
                        || (tier == TrafficTier.PROXIMATE && airborne.size() <= 3)) {
                    int relHundreds = Math.round(info.relativeAltitudeFeet() / 100.0f);
                    String tag = (relHundreds >= 0 ? "+" : "") + String.format(Locale.ROOT, "%02d", relHundreds);
                    g.text(mc.font, tag, px + 4, py - 4, tierColor, false);
                }
                drawnPixels.add(point);
                airborneDrawn++;
            }

            // Clean ND footer rows
            g.text(mc.font, String.format(Locale.ROOT, "AIR %02d", trafficCounts >>> 8), ndX + 6, ndY + 70,
                    FlightLineTheme.GREEN, true);
            g.text(mc.font, String.format(Locale.ROOT, "GND %02d", trafficCounts & 0xFF), ndX + 62, ndY + 70,
                    FlightLineTheme.AMBER, true);

            if (trafficRangeBlocks > 0) {
                int targetColor = (warnings & FlightWarning.TCAS_RESOLUTION) != 0 ? FlightLineTheme.TCAS_RA : FlightLineTheme.TCAS_TA;
                String target = required(String.format(Locale.ROOT, "TGT %+dFT %dB",
                        trafficAltitudeFeet, trafficRangeBlocks));
                g.text(mc.font, fitText(mc.font, target, ndWidth - 12),
                        ndX + 6, ndY + 80, targetColor, true);
            } else {
                g.text(mc.font, "TCAS NORM", ndX + 6, ndY + 80, FlightLineTheme.GREEN_DARK, true);
            }
        }

        if (eicasVisible) {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(eicasX, 6);
            pose.scale(EICAS_SCALE, EICAS_SCALE);
            drawEicas(g, mc, player, 0, 0, clock);
            pose.popMatrix();

            if (emergencyStatus.active()) {
                pose.pushMatrix();
                pose.translate(eicasX, 6 + EICAS_DISPLAY_HEIGHT + 4);
                pose.scale(EICAS_SCALE, EICAS_SCALE);
                drawEmergencyPanel(g, mc, 0, 0, clock);
                pose.popMatrix();
            }
        }

        if (showTelemetry) drawTelemetryDetail(g, mc, eicasX, eicasVisible, screenWidth, screenHeight, clock);

        updateKeybindPanelAnimation();
        if (showKeybinds && keybindPanelProgress > 0.01f) {
            drawKeybindList(g, mc, screenWidth, screenHeight);
        }
        drawAtcBanner(g, mc, screenWidth);
        drawNotice(g, mc, screenWidth);
        FlightTutorial.drawTracker(g, mc, screenWidth, screenHeight);
        drawBrakePopup(g, screenWidth, screenHeight);

        // Warnings are conveyed by a wordless edge alarm. TCAS advisories deliberately never
        // trigger it; they remain on the navigation display as traffic symbols/readouts only.
        boolean alarm = phase == FlightPhase.CRASHED || nonTcasWarnings() != 0;
        if (alarm) {
            FlightLineTheme.alarmFlash(g, 0, 0, screenWidth, screenHeight,
                    alarmIntensity(nowNanos, clock));
        }
    }

    private static int nonTcasWarnings() {
        return warnings & ~TCAS_WARNINGS;
    }

    /** One two-pulse cue on onset or a newly arrived warning, then the established slow blink. */
    private static void updateAlarmOnset(long nowNanos) {
        int nonTcas = nonTcasWarnings();
        boolean crashed = phase == FlightPhase.CRASHED;
        boolean wasActive = previousNonTcasWarnings != 0 || previousCrashed;
        boolean active = nonTcas != 0 || crashed;
        boolean newWarning = (nonTcas & ~previousNonTcasWarnings) != 0 || (crashed && !previousCrashed);
        if (active && (!wasActive || newWarning)) {
            alarmOnsetExpiresNanos = nowNanos + ALARM_ONSET_NANOS;
        } else if (!active) {
            alarmOnsetExpiresNanos = 0L;
        }
        previousNonTcasWarnings = nonTcas;
        previousCrashed = crashed;
    }

    private static float alarmIntensity(long nowNanos, double clock) {
        long remaining = alarmOnsetExpiresNanos - nowNanos;
        if (remaining > 0) {
            long elapsed = ALARM_ONSET_NANOS - remaining;
            boolean pulse = elapsed < 180_000_000L
                    || (elapsed >= 320_000_000L && elapsed < 500_000_000L);
            return pulse ? 1.0f : 0.18f;
        }
        boolean blinkOn = ((long) (clock * 2.5) & 1L) == 0L;
        return phase == FlightPhase.CRASHED || blinkOn ? 1.0f : 0.32f;
    }

    /** One compact radio/control strip instead of stacked banners. */
    private static void drawAtcBanner(GuiGraphicsExtractor g, Minecraft mc, int screenWidth) {
        boolean controlled = enabled && isAtcControlled();
        boolean hasMessage = !atcMessage.isEmpty() && System.nanoTime() < atcMessageExpiresNanos;
        if (!controlled && !hasMessage) return;
        String text = hasMessage ? atcMessage : "ATC ACTIVE · F7 RELEASE";
        int width = Math.min(screenWidth - 24, 196);
        int x = (screenWidth - width) / 2;
        int y = 6;
        int accent = hasMessage ? FlightLineTheme.CYAN : FlightLineTheme.AMBER;
        g.fill(x + 3, y + 3, x + width + 3, y + 19, FlightLineTheme.SHADOW);
        g.fill(x, y, x + width, y + 16, FlightLineTheme.COAL);
        g.outline(x, y, width, 16, accent);
        g.fill(x, y, x + 3, y + 16, accent);
        g.centeredText(mc.font, fitText(mc.font, text, width - 10),
                screenWidth / 2 + 1, y + 4, hasMessage ? FlightLineTheme.PAPER : accent);
        if (!hasMessage) atcMessage = "";
    }

    /** Optional expanded readout: hidden by default; Y opens it as a compact right-column card. */
    private static void drawTelemetryDetail(GuiGraphicsExtractor g, Minecraft mc, int eicasX,
                                            boolean eicasVisible, int screenWidth,
                                            int screenHeight, double clock) {
        Font font = mc.font;
        int width = EICAS_WIDTH;
        int height = 70;
        int displayWidth = Math.round(width * EICAS_SCALE);
        int displayHeight = Math.round(height * EICAS_SCALE);
        int screenX = eicasVisible ? eicasX : screenWidth - displayWidth - 6;
        int screenY = eicasVisible ? 6 + EICAS_DISPLAY_HEIGHT + 4 : 6;
        if (eicasVisible && emergencyStatus.active()) {
            screenY += Math.round(emergencyPanelHeight() * EICAS_SCALE) + 4;
        }
        if (screenY + displayHeight > screenHeight - 6) {
            screenY = Math.max(6, screenHeight - displayHeight - 6);
        }
        if (screenY + displayHeight > screenHeight - 6) return;

        g.pose().pushMatrix();
        g.pose().translate(screenX, screenY);
        g.pose().scale(EICAS_SCALE, EICAS_SCALE);
        int x = 0;
        int y = 0;
        g.fill(x + 4, y + 4, x + width + 4, y + height + 4, FlightLineTheme.SHADOW);
        g.fill(x, y, x + width, y + height, FlightLineTheme.PANEL);
        g.outline(x, y, width, height, FlightLineTheme.CYAN);
        g.fill(x + 2, y + 2, x + width - 2, y + 12, FlightLineTheme.COAL);
        g.fill(x + 2, y + 12, x + width - 2, y + 13, FlightLineTheme.CYAN);
        g.text(font, "FLIGHT DATA", x + 5, y + 2, FlightLineTheme.CYAN, true);
        String key = FlightLineClient.keyName(FlightLineClient.telemetry());
        g.text(font, key, x + width - 4 - font.width(key), y + 2, PFD_MUTED, true);

        int col = (width - 8) / 4;
        int row1 = y + 14;
        int row2 = y + 29;
        String wind = String.format(Locale.ROOT, "%03d/%02dG%02d",
                weatherGuidance.windDirectionDegrees(), weatherGuidance.windSpeedKnots(),
                weatherGuidance.gustKnots());
        String[] labels = {"GS", "TRK", "G", "WIND", "N1", "FLAP", "GEAR", "A/B"};
        String[] values = {
                groundSpeedKnots() + "KT",
                String.format(Locale.ROOT, "%03d°", trackDegrees()),
                String.format(Locale.ROOT, "%.1f", gLoad()),
                wind,
                engineN1() + "%",
                "F" + flapDetent(),
                gearState == 0 ? "DN" : gearState == 1 ? "UP" : "FAIL",
                (airbrakes ? "BRK" : "-") + (spoilersDeployed() ? " SPOIL" : "")
        };
        for (int i = 0; i < labels.length; i++) {
            int cx = x + 4 + (i % 4) * col;
            int cy = i < 4 ? row1 : row2;
            g.text(font, labels[i], cx, cy, PFD_MUTED, true);
            int valueColor = PFD_WHITE;
            if (i == 2 && (gLoad() > 2.5f || gLoad() < 0.3f)) valueColor = PFD_AMBER;
            if (i == 4 && engineN1() > 90) valueColor = PFD_AMBER;
            if (i == 6 && gearState == 2) valueColor = PFD_RED;
            g.text(font, fitText(font, values[i], col - 3), cx, cy + 8, valueColor, true);
        }

        String approach = ilsActive
                ? String.format(Locale.ROOT, "ILS LOC%+.1f GS%+.1f DME %.1f",
                ilsLocalizerDegrees, ilsGlideslopeDegrees, ilsThresholdDistanceBlocks * 0.054f)
                : "ILS ----  RA " + radioAltitudeFeet + "FT";
        g.text(font, fitText(font, approach, width - 8), x + 4, y + 50,
                ilsActive ? PFD_CYAN : PFD_MUTED, true);
        String airframe = String.format(Locale.ROOT, "VS%+d  FUEL %d%%  WX %s",
                verticalSpeedFpm, fuelPercent, weatherName(weatherGuidance.condition()));
        g.text(font, fitText(font, airframe, width - 8), x + 4, y + 60,
                weatherGuidance.condition() == 2 ? PFD_AMBER : PFD_WHITE, true);
        if (((long) (clock * 2.0) & 1L) == 0L) {
            g.fill(x + width - 5, y + 4, x + width - 2, y + 7, PFD_GREEN);
        }
        g.pose().popMatrix();
    }

    /**
     * Compact glass EICAS: engine N1, fuel, configuration, weather and a status strip.
     * Uses the same palette and panel proportions as the PFD so both displays read as one suite.
     */
    private static void drawEicas(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player,
                                  int x, int y, double clock) {
        Font font = mc.font;
        int e1 = systems & 7;
        int e2 = (systems >> 3) & 7;
        int n1 = (systems >> 12) & 0x7F;
        int flap = (systems >> 9) & 7;
        boolean spoilers = (systems & (1 << 8)) != 0;
        boolean engineFire = e1 == 4 || e2 == 4;
        boolean blinkOn = ((long) (clock * 3.0) & 1L) == 0L;

        g.fill(x + 4, y + 4, x + EICAS_WIDTH + 4, y + EICAS_HEIGHT + 4, FlightLineTheme.SHADOW);
        g.fill(x, y, x + EICAS_WIDTH, y + EICAS_HEIGHT, FlightLineTheme.PANEL);
        g.outline(x, y, EICAS_WIDTH, EICAS_HEIGHT, FlightLineTheme.STONE);
        g.fill(x + 2, y + 2, x + EICAS_WIDTH - 2, y + 16, FlightLineTheme.HEADER);
        g.fill(x + 2, y + 16, x + EICAS_WIDTH - 2, y + 17, FlightLineTheme.CYAN);
        AircraftType plane = FlightLineClient.getAircraftType();
        String eicasTitle = plane == AircraftType.GINA_787 ? "EICAS · B787" : "EICAS · A380";
        g.text(font, eicasTitle, x + 5, y + 5, FlightLineTheme.CYAN, true);
        String apMode = autopilotGuidance.autolandArmed() ? "A/L" : autopilot ? "A/P" : "MAN";
        int apColor = autopilot || autopilotGuidance.autolandArmed() ? PFD_GREEN : PFD_MUTED;
        g.text(font, apMode, x + EICAS_WIDTH - font.width(apMode) - 5, y + 5, apColor, true);

        if (plane == AircraftType.GINA_787) {
            int tileW = 38;
            int tileH = 16;
            for (int i = 0; i < 6; i++) {
                int col = i % 3;
                int row = i / 3;
                int tx = x + 5 + col * 40;
                int ty = y + 17 + row * 18;
                int engState = (i % 2 == 0) ? e1 : e2;
                drawMiniEngineTile(g, font, tx, ty, tileW, tileH, "E" + (i + 1), engState, n1, blinkOn);
            }
        } else {
            drawEicasEngineTile(g, font, x + 5, y + 18, 58, "E1", e1, n1, blinkOn);
            drawEicasEngineTile(g, font, x + 65, y + 18, 58, "E2", e2, n1, blinkOn);
        }

        int fuelColor = fuelPercent <= 15 ? PFD_RED : fuelPercent <= 30 ? PFD_AMBER : PFD_GREEN;
        g.text(font, "FUEL", x + 5, y + 54, PFD_MUTED, true);
        gauge(g, x + 34, y + 56, 54, 5, fuelPercent / 100.0f, fuelColor);
        String fuelText = String.format(Locale.ROOT, "%d%%", fuelPercent);
        g.text(font, fuelText, x + EICAS_WIDTH - font.width(fuelText) - 5, y + 54,
                fuelPercent <= 15 ? PFD_RED : PFD_WHITE, true);

        g.text(font, "GEAR", x + 5, y + 68, PFD_MUTED, true);
        String gearText = gearState == 2 ? "FAIL" : gearState == 1 ? "UP" : "DOWN";
        g.text(font, gearText, x + 34, y + 68, gearColor(), true);
        int gearLight = gearState == 2 ? PFD_RED : gearState == 0 ? PFD_GREEN : PFD_AMBER;
        for (int i = 0; i < 3; i++) {
            g.fill(x + 68 + i * 8, y + 69, x + 73 + i * 8, y + 74, gearLight);
        }
        g.text(font, "ABRK", x + 94, y + 68, airbrakes ? PFD_AMBER : PFD_MUTED, true);

        g.text(font, "FLAPS", x + 5, y + 80, PFD_MUTED, true);
        for (int i = 0; i < 4; i++) {
            g.fill(x + 38 + i * 8, y + 80, x + 44 + i * 8, y + 85,
                    i < flap ? PFD_AMBER : FlightLineTheme.LINE);
        }
        g.text(font, "SPLR", x + 75, y + 80, PFD_MUTED, true);
        g.text(font, spoilers ? "EXT" : "RET", x + 103, y + 80,
                spoilers ? PFD_AMBER : PFD_MUTED, true);

        String weather = "WX " + weatherName(weatherGuidance.condition());
        g.text(font, fitText(font, weather, 54), x + 5, y + 92,
                weatherColor(weatherGuidance.condition()), true);
        String windReadout = weatherGuidance.gustKnots() > weatherGuidance.windSpeedKnots() + 3
                ? required(String.format(Locale.ROOT, "%03d/%02dG%02d",
                        weatherGuidance.windDirectionDegrees(), weatherGuidance.windSpeedKnots(),
                        weatherGuidance.gustKnots()))
                : required(String.format(Locale.ROOT, "%03d/%02d",
                        weatherGuidance.windDirectionDegrees(), weatherGuidance.windSpeedKnots()));
        g.text(font, fitText(font, windReadout, 62), x + 61, y + 92, PFD_WHITE, true);

        String antiIceReadout = weatherGuidance.antiIceActive()
                ? (weatherGuidance.antiIceEffective() ? "A-ICE CLR" : "A-ICE WAIT") : "A-ICE OFF";
        String weatherSeverity = required(String.format(Locale.ROOT, "ICE %d%%  TURB %d%%  %s",
                weatherGuidance.icingPercent(), weatherGuidance.turbulencePercent(), antiIceReadout));
        int turbulenceColor = weatherGuidance.turbulencePercent() >= 70 ? PFD_RED
                : weatherGuidance.turbulencePercent() >= 35 ? PFD_AMBER : PFD_GREEN;
        int icingColor = weatherGuidance.icingPercent() >= 20 ? PFD_AMBER : turbulenceColor;
        g.text(font, fitText(font, weatherSeverity, EICAS_WIDTH - 10), x + 5, y + 103,
                icingColor, true);

        // Wordless system status: the full-screen edge flash carries the alarm, this strip only
        // distinguishes normal from caution. TCAS bits never affect it.
        boolean caution = nonTcasWarnings() != 0 || engineFire || emergencyStatus.active();
        boolean urgent = engineFire || phase == FlightPhase.CRASHED
                || (warnings & (FlightWarning.STALL | FlightWarning.FUEL_EMPTY
                        | FlightWarning.PULL_UP | FlightWarning.WINDSHEAR)) != 0;
        int statusColor = !caution ? PFD_GREEN
                : urgent ? (blinkOn ? PFD_RED : PFD_WHITE) : PFD_AMBER;
        g.fill(x + 4, y + 115, x + EICAS_WIDTH - 4, y + 134,
                caution ? withAlpha(statusColor, 0.18f) : FlightLineTheme.INSET);
        g.outline(x + 4, y + 115, EICAS_WIDTH - 8, 19,
                caution ? statusColor : FlightLineTheme.STONE);
        int barWidth = Math.round((EICAS_WIDTH - 18) * 0.62f);
        g.fill(x + 9, y + 122, x + 9 + barWidth, y + 127, statusColor);
    }

    /** Compact checklist summary below EICAS; the full procedure list opens with O. */
    private static List<String> emergencyLines() {
        EmergencyStatusPayload status = emergencyStatus;
        List<String> lines = new ArrayList<>();
        boolean grouped = FlightLineClient.getAircraftType() == AircraftType.GINA_787;
        String engine1 = EmergencyStatus.engineName(0, grouped);
        String engine2 = EmergencyStatus.engineName(1, grouped);
        if (status.hasCondition(EmergencyStatus.FIRE_1)) {
            lines.add(engine1 + " FIRE " + Math.max(0, 30 - status.fireTicks1() / 20) + "S");
        }
        if (status.hasCondition(EmergencyStatus.FIRE_2)) {
            lines.add(engine2 + " FIRE " + Math.max(0, 30 - status.fireTicks2() / 20) + "S");
        }
        if (status.hasCondition(EmergencyStatus.FAILURE_1)) lines.add(engine1 + " FAILED");
        if (status.hasCondition(EmergencyStatus.FAILURE_2)) lines.add(engine2 + " FAILED");
        if (status.hasCondition(EmergencyStatus.FUEL_EMPTY)) lines.add("FUEL EMPTY");
        else if (status.hasCondition(EmergencyStatus.FUEL_LOW)) lines.add("LOW FUEL");
        if (status.hasCondition(EmergencyStatus.HEAVY_FUEL)) lines.add("HEAVY FUEL");
        if (status.hasCondition(EmergencyStatus.GEAR_FAILED)) lines.add("GEAR FAILED");
        if (status.hasCondition(EmergencyStatus.HYDRAULIC)) lines.add("HYDRAULIC FAIL");
        if (status.hasCondition(EmergencyStatus.PRESSURISATION)) lines.add("CABIN ALTITUDE");
        if (status.hasCondition(EmergencyStatus.ICING)) lines.add("AIRFRAME ICE");
        if (status.hasCondition(EmergencyStatus.OVERSPEED)) lines.add("OVERSPEED");
        if (status.hasCondition(EmergencyStatus.EMERGENCY_DECLARED)) lines.add("MAYDAY ACTIVE");
        if (status.hasCondition(EmergencyStatus.FUEL_DUMPING)) lines.add("FUEL JETTISON");
        return lines;
    }

    private static int emergencyPanelHeight() {
        if (!emergencyStatus.active()) return 0;
        return 18 + Math.min(2, emergencyLines().size()) * 10
                + (emergencyStatus.recommendation().present() ? 20 : 0);
    }

    private static void drawEmergencyPanel(GuiGraphicsExtractor g, Minecraft mc, int x, int y, double clock) {
        EmergencyStatusPayload status = emergencyStatus;
        if (!status.active()) return;
        Font font = mc.font;
        boolean grouped = FlightLineClient.getAircraftType() == AircraftType.GINA_787;
        List<String> lines = emergencyLines();
        var recommendation = status.recommendation();
        int rows = Math.min(2, lines.size());
        int height = 18 + rows * 10 + (recommendation.present() ? 20 : 0);
        boolean urgent = status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2
                | EmergencyStatus.FAILURE_1 | EmergencyStatus.FAILURE_2
                | EmergencyStatus.FUEL_EMPTY);
        int border = urgent && (((long) (clock * 3.0) & 1L) == 0L) ? PFD_RED : PFD_AMBER;

        g.fill(x + 4, y + 4, x + EICAS_WIDTH + 4, y + height + 4, FlightLineTheme.SHADOW);
        g.fill(x, y, x + EICAS_WIDTH, y + height, FlightLineTheme.VOID);
        g.outline(x, y, EICAS_WIDTH, height, border);
        g.fill(x, y, x + 4, y + height, border);
        String title = "EMERGENCY " + FlightLineClient.keyName(FlightLineClient.emergencyChecklist());
        g.text(font, fitText(font, title, EICAS_WIDTH - 13), x + 7, y + 3, border, true);
        if (status.hasCondition(EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2)) {
            String bottles = "BTL " + status.extinguisherBottles();
            g.text(font, bottles, x + EICAS_WIDTH - font.width(bottles) - 4, y + 3, PFD_WHITE, true);
        }
        for (int i = 0; i < rows; i++) {
            int rowColor = lines.get(i).contains("FIRE") || lines.get(i).contains("EMPTY")
                    || lines.get(i).contains("FAILED") ? PFD_RED : PFD_AMBER;
            g.text(font, fitText(font, lines.get(i), EICAS_WIDTH - 13), x + 7, y + 14 + i * 10,
                    rowColor, true);
        }
        if (recommendation.present()) {
            int actionY = y + height - 18;
            g.fill(x + 3, actionY, x + EICAS_WIDTH - 3, actionY + 15, FlightLineTheme.COAL);
            g.outline(x + 3, actionY, EICAS_WIDTH - 6, 15, FlightLineTheme.GREEN);
            String action = EmergencyStatus.actionLabel(recommendation.action());
            if (recommendation.target() >= 0 && action.startsWith("Extinguish")) {
                action = EmergencyStatus.engineName(recommendation.target(), grouped) + " FIRE";
            } else if (recommendation.target() >= 0 && action.startsWith("Restart")) {
                action = EmergencyStatus.engineName(recommendation.target(), grouped) + " RELIGHT";
            }
            String prompt = FlightLineClient.keyName(FlightLineClient.executeEmergency()) + " " + action;
            g.centeredText(font, fitText(font, prompt, EICAS_WIDTH - 10), x + EICAS_WIDTH / 2,
                    actionY + 3, PFD_GREEN);
        }
    }

    /** Server action feedback as a short cockpit toast, independent of the radio/chat stack. */
    private static void drawNotice(GuiGraphicsExtractor g, Minecraft mc, int screenWidth) {
        long remaining = cockpitNoticeExpiresNanos - System.nanoTime();
        if (remaining <= 0 || cockpitNotice.isEmpty()) {
            cockpitNotice = "";
            return;
        }
        float alpha = Math.min(1.0f, remaining / 500_000_000.0f);
        int width = Math.min(screenWidth - 24, 300);
        int x = (screenWidth - width) / 2;
        int y = 128;
        g.fill(x + 4, y + 4, x + width + 4, y + 22, withAlpha(FlightLineTheme.SHADOW, alpha));
        g.fill(x, y, x + width, y + 18, withAlpha(FlightLineTheme.COAL, alpha));
        g.outline(x, y, width, 18, withAlpha(FlightLineTheme.CYAN, alpha));
        g.fill(x, y, x + 4, y + 18, withAlpha(FlightLineTheme.CYAN, alpha));
        g.centeredText(mc.font, fitText(mc.font, cockpitNotice, width - 12), screenWidth / 2 + 2,
                y + 5, withAlpha(FlightLineTheme.PAPER, alpha));
    }

    private static void drawEicasEngineTile(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                                            String label, int state, int n1, boolean blinkOn) {
        if (state == 4 && blinkOn) {
            g.fill(x, y, x + width, y + 32, 0xCC701B20);
        } else {
            g.fill(x, y, x + width, y + 32, FlightLineTheme.INSET);
        }
        g.outline(x, y, width, 32, state >= 3 ? FlightLineTheme.RED : FlightLineTheme.STONE);
        g.text(font, label, x + 3, y + 2, PFD_CYAN, true);
        String tag = engineTag(state);
        g.text(font, tag, x + width - font.width(tag) - 3, y + 2,
                state == 4 && blinkOn ? PFD_WHITE : engineColor(state), true);
        String value = String.format(Locale.ROOT, "%d%%", n1);
        g.text(font, value, x + 3, y + 11, n1 > 90 ? PFD_AMBER : PFD_WHITE, true);
        gauge(g, x + 3, y + 24, width - 6, 4, n1 / 100.0f, n1 > 90 ? PFD_AMBER : PFD_GREEN);
    }

    private static void drawMiniEngineTile(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                                           int height, String label, int state, int n1, boolean blinkOn) {
        if (state == 4 && blinkOn) {
            g.fill(x, y, x + width, y + height, 0xCC701B20);
        } else {
            g.fill(x, y, x + width, y + height, FlightLineTheme.INSET);
        }
        g.outline(x, y, width, height, state >= 3 ? FlightLineTheme.RED : FlightLineTheme.STONE);
        g.text(font, label, x + 2, y + 1, PFD_CYAN, false);
        String val = String.format(Locale.ROOT, "%d%%", n1);
        g.text(font, val, x + width - font.width(val) - 2, y + 1, n1 > 90 ? PFD_AMBER : PFD_WHITE, false);
        gauge(g, x + 2, y + 10, width - 4, 3, n1 / 100.0f, n1 > 90 ? PFD_AMBER : PFD_GREEN);
    }

    /**
     * Draws a compact glass-cockpit PFD in a 168x146 logical panel. The caller scales the pose to
     * fit the screen, so every coordinate here is stable across GUI scales.
     */
    private static void drawPfd(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player) {
        Font font = mc.font;
        g.fill(3, 3, PFD_BASE_WIDTH + 3, PFD_BASE_HEIGHT + 3, FlightLineTheme.SHADOW);
        g.fill(0, 0, PFD_BASE_WIDTH, PFD_BASE_HEIGHT, FlightLineTheme.PANEL);
        g.outline(0, 0, PFD_BASE_WIDTH, PFD_BASE_HEIGHT, FlightLineTheme.STONE);
        g.fill(0, 0, PFD_BASE_WIDTH, 2, FlightLineTheme.CYAN);

        drawPfdFma(g, font);

        int adiLeft = 29;
        int adiTop = 16;
        int adiWidth = 110;
        int adiHeight = 90;
        int adiRight = adiLeft + adiWidth;
        int adiBottom = adiTop + adiHeight;
        int adiCx = (adiLeft + adiRight) / 2;
        int adiCy = (adiTop + adiBottom) / 2;
        drawArtificialHorizon(g, adiCx, adiCy, adiWidth, adiHeight, visualPitch, visualBank,
                ilsActive, ilsLocalizerDegrees, ilsGlideslopeDegrees);

        drawSpeedTape(g, font, 1, adiTop, 26, adiHeight);
        drawAltitudeTape(g, font, 141, adiTop, 26, adiHeight, player);
        int lowerTop = 108;
        int lowerHeight = 18;
        // Only draw the standalone RA box when NOT airborne below 2500 ft — in that case
        // the ADI already shows an integrated RA readout inside the horizon, avoiding duplication.
        if (!isAirbornePhase() || radioAltitudeFeet >= 2500) {
            drawRadioAltitudeBox(g, font, 1, lowerTop, 38, lowerHeight);
        }
        drawHeadingTape(g, font, 41, lowerTop, 126, lowerHeight);
        drawPfdStatus(g, font);
    }

    private static void drawPfdFma(GuiGraphicsExtractor g, Font font) {
        boolean autoland = autopilotGuidance.autolandArmed();
        String atMode = autoland && radioAltitudeFeet < 50 && isAirbornePhase()
                ? "RETARD" : autopilot ? autopilotGuidance.targetAirspeedKnots() + "KT" : "---";
        String rollMode = autoland
                ? (ilsActive ? "LOC" : "LOC AR")
                : autopilot ? String.format(Locale.ROOT, "HDG%03d", (int) autopilotGuidance.targetHeadingDegrees()) : "---";
        String pitchMode = autoland
                ? (ilsActive ? "GS" : "GS AR")
                : autopilot ? (autopilotGuidance.targetVerticalSpeedFpm() != 0
                        ? String.format(Locale.ROOT, "%+d", autopilotGuidance.targetVerticalSpeedFpm()) : "ALT") : "---";
        int active = autopilot || autoland ? PFD_GREEN : PFD_MUTED;
        int armed = autoland && !ilsActive && autopilot ? PFD_CYAN : active;
        drawFmaCell(g, font, 2, 2, 54, "A/T", atMode, active);
        drawFmaCell(g, font, 57, 2, 54, "ROLL", rollMode, armed);
        drawFmaCell(g, font, 112, 2, 54, "PITCH", pitchMode, armed);
        if (autopilot || autoland) {
            String source = autoland ? "ILS" : "FMS";
            g.text(font, source, 2, 14, autoland ? PFD_CYAN : PFD_GREEN, false);
        }
    }

    private static void drawFmaCell(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                                    String label, String mode, int modeColor) {
        g.fill(x, y, x + width, y + 12, FlightLineTheme.HEADER);
        g.outline(x, y, width, 12, FlightLineTheme.STONE);
        g.text(font, label, x + 3, y + 2, PFD_MUTED, false);
        g.text(font, mode, x + width - font.width(mode) - 3, y + 2, modeColor, true);
    }

    private static void drawSpeedTape(GuiGraphicsExtractor g, Font font, int x, int y, int width, int height) {
        g.fill(x, y, x + width, y + height, FlightLineTheme.INSET);
        g.outline(x, y, width, height, FlightLineTheme.STONE);
        int current = knots(visualSpeed);
        int centerY = y + height / 2;
        double pixelsPerKnot = height / 160.0;

        // V-speed bands (Stall band, Flap operating band, Overspeed barber pole)
        AircraftType plane = FlightLineClient.getAircraftType();
        int overspeedKts = plane.maxAirspeedKnots() + 20;
        int stallKts = plane.stallSpeedKnots() - 40;
        int flapTopKts = plane.maxAirspeedKnots() - 35;
        int flapBotKts = plane.stallSpeedKnots() - 25;

        int stallTopY = centerY - (int) Math.round((stallKts - current) * pixelsPerKnot);
        int flapTopY = centerY - (int) Math.round((flapTopKts - current) * pixelsPerKnot);
        int flapBottomY = centerY - (int) Math.round((flapBotKts - current) * pixelsPerKnot);
        int overspeedBottomY = centerY - (int) Math.round((overspeedKts - current) * pixelsPerKnot);

        // Low speed stall band (red)
        int sTop = Math.max(y + 10, Math.min(y + height, stallTopY));
        if (sTop < y + height) {
            g.fill(x + 1, sTop, x + 3, y + height, 0xFFFF2525);
        }
        // Flap operating band (white)
        int fTop = Math.max(y + 10, Math.min(y + height, flapTopY));
        int fBot = Math.max(y + 10, Math.min(y + height, flapBottomY));
        if (fTop < fBot) {
            g.fill(x + 1, fTop, x + 2, fBot, 0xFFE0E0E0);
        }
        // High speed overspeed barber pole (red/white)
        int oBot = Math.max(y + 10, Math.min(y + height, overspeedBottomY));
        if (oBot > y + 10) {
            for (int py = y + 10; py < oBot; py += 4) {
                g.fill(x + 1, py, x + 3, Math.min(oBot, py + 2), 0xFFFF2525);
                g.fill(x + 1, Math.min(oBot, py + 2), x + 3, Math.min(oBot, py + 4), 0xFFFFFFFF);
            }
        }

        // Target speed readout at top when autopilot is engaged; otherwise IAS label
        if (autopilot) {
            int target = autopilotGuidance.targetAirspeedKnots();
            String targetText = Integer.toString(target);
            g.fill(x + 1, y + 1, x + width - 1, y + 9, FlightLineTheme.HEADER);
            g.outline(x + 1, y + 1, width - 2, 9, PFD_CYAN);
            g.centeredText(font, targetText, x + width / 2, y + 2, PFD_CYAN);
        } else {
            g.centeredText(font, "IAS", x + width / 2, y + 2, PFD_CYAN);
        }

        int firstTick = (int) (Math.floor((current - 80) / 10.0) * 10);
        for (int value = firstTick; value <= current + 80; value += 10) {
            int tickY = centerY - (int) Math.round((value - current) * pixelsPerKnot);
            if (tickY < y + 10 || tickY > y + height - 2) continue;
            boolean major = Math.floorMod(value, 20) == 0;
            String label = Integer.toString(value);
            boolean showLabel = major && Math.abs(value - current) > 12;
            if (!showLabel) {
                g.fill(x + width - (major ? 8 : 5), tickY, x + width, tickY + 1, FlightLineTheme.TEXT_DIM);
            } else {
                g.text(font, label, x + width - 2 - font.width(label), tickY - 4, PFD_WHITE, false);
            }
        }

        // Speed bug
        if (autopilot) {
            int target = autopilotGuidance.targetAirspeedKnots();
            int bugY = centerY - (int) Math.round((target - current) * pixelsPerKnot);
            if (bugY > y + 8 && bugY < y + height - 3) {
                g.fill(x + width - 7, bugY, x + width, bugY + 2, PFD_CYAN);
                g.fill(x + width - 4, bugY - 1, x + width, bugY + 3, PFD_CYAN);
            }
        }

        // Speed trend vector (projected speed in 6 seconds based on acceleration)
        double speedAcc = (speed - visualSpeed) * 30.0;
        int trendY = centerY - (int) Math.round(speedAcc * pixelsPerKnot);
        trendY = Math.max(y + 10, Math.min(y + height - 2, trendY));
        if (Math.abs(trendY - centerY) > 3) {
            g.fill(x + width - 2, Math.min(centerY, trendY), x + width, Math.max(centerY, trendY), PFD_GREEN);
            int arrowY = trendY < centerY ? trendY : trendY - 2;
            g.fill(x + width - 4, arrowY, x + width, arrowY + 2, PFD_GREEN);
        }

        // Center rolling drum readout box
        g.fill(x + 1, centerY - 7, x + width - 1, centerY + 7, FlightLineTheme.INSET);
        g.outline(x + 1, centerY - 7, width - 2, 14, PFD_WHITE);
        g.centeredText(font, Integer.toString(current), x + width / 2, centerY - 4, PFD_WHITE);
    }

    private static void drawAltitudeTape(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                                         int height, LocalPlayer player) {
        g.fill(x, y, x + width, y + height, FlightLineTheme.INSET);
        g.outline(x, y, width, height, FlightLineTheme.STONE);
        int current = (int) Math.round(player.getY() * 3.28084);
        int centerY = y + height / 2;
        double pixelsPerFoot = height / 1200.0;
        // Target altitude readout at top when autopilot is engaged; otherwise ALT label
        if (autopilot) {
            int selected = autopilotGuidance.targetAltitudeFeet();
            String targetText = (selected >= 10000 && selected % 1000 == 0)
                    ? (selected / 1000) + "K"
                    : Integer.toString(selected);
            g.fill(x + 1, y + 1, x + width - 1, y + 9, FlightLineTheme.HEADER);
            g.outline(x + 1, y + 1, width - 2, 9, PFD_CYAN);
            g.centeredText(font, targetText, x + width / 2, y + 2, PFD_CYAN);
        } else {
            g.centeredText(font, "ALT", x + width / 2, y + 2, PFD_CYAN);
        }

        int firstTick = (int) (Math.floor((current - 600) / 100.0) * 100);
        for (int value = firstTick; value <= current + 600; value += 100) {
            int tickY = centerY - (int) Math.round((value - current) * pixelsPerFoot);
            if (tickY < y + 10 || tickY > y + height - 2) continue;
            boolean major = Math.floorMod(value, 200) == 0;
            String label = Integer.toString(value);
            boolean showLabel = major && Math.abs(value - current) > 90;
            if (!showLabel) {
                g.fill(x, tickY, x + (major ? 8 : 5), tickY + 1, FlightLineTheme.TEXT_DIM);
            } else {
                g.text(font, label, x + width - 2 - font.width(label), tickY - 4, PFD_WHITE, false);
            }
        }

        if (autopilot) {
            int selected = autopilotGuidance.targetAltitudeFeet();
            int bugY = centerY - (int) Math.round((selected - current) * pixelsPerFoot);
            if (bugY > y + 8 && bugY < y + height - 3) {
                g.fill(x, bugY, x + 6, bugY + 2, PFD_CYAN);
                g.fill(x, bugY - 1, x + 3, bugY + 3, PFD_CYAN);
            }
        }

        // VSI indicator tape and pointer on the right edge
        int vsOffset = Math.max(-28, Math.min(28, (int) Math.round(verticalSpeedFpm / 100.0)));
        int vsY = centerY - vsOffset;
        g.fill(x + width - 2, Math.min(centerY, vsY), x + width, Math.max(centerY, vsY),
                verticalSpeedFpm < 0 ? PFD_AMBER : PFD_GREEN);
        g.fill(x + width - 4, vsY - 1, x + width, vsY + 1,
                verticalSpeedFpm < 0 ? PFD_AMBER : PFD_GREEN);

        // Center rolling drum readout box
        g.fill(x + 1, centerY - 7, x + width - 1, centerY + 7, FlightLineTheme.INSET);
        g.outline(x + 1, centerY - 7, width - 2, 14, PFD_WHITE);
        g.centeredText(font, Integer.toString(current), x + width / 2, centerY - 4, PFD_WHITE);
        String vsiText = String.format(Locale.ROOT, "%+d", verticalSpeedFpm);
        g.text(font, fitText(font, vsiText, width - 3), x + 2, y + height - 8,
                verticalSpeedFpm < -1500 ? PFD_AMBER : PFD_CYAN, true);
    }

    private static void drawRadioAltitudeBox(GuiGraphicsExtractor g, Font font, int x, int y,
                                             int width, int height) {
        g.fill(x, y, x + width, y + height, 0xDD070C11);
        g.outline(x, y, width, height, radioAltitudeFeet < 500 ? PFD_AMBER : FlightLineTheme.STONE);
        g.text(font, "RA", x + 3, y + 2, PFD_CYAN, true);
        String text = radioAltitudeFeet < 2500
                ? String.format(Locale.ROOT, "%04d", radioAltitudeFeet) : "----";
        g.text(font, fitText(font, text, width - 6), x + 3, y + 9,
                radioAltitudeFeet < 500 ? PFD_AMBER : PFD_GREEN, true);
    }

    private static void drawHeadingTape(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                                        int height) {
        g.fill(x, y, x + width, y + height, FlightLineTheme.INSET);
        g.outline(x, y, width, height, FlightLineTheme.STONE);
        int centerX = x + width / 2;
        double pixelsPerDegree = 1.6;
        for (int heading = 0; heading < 360; heading += 10) {
            double relative = wrapDegrees(heading - visualHeading);
            int tickX = centerX + (int) Math.round(relative * pixelsPerDegree);
            if (tickX < x + 2 || tickX > x + width - 2) continue;
            boolean major = heading % 30 == 0;
            g.fill(tickX, y + 2, tickX + 1, y + (major ? 7 : 5), FlightLineTheme.TEXT_DIM);
            if (major) {
                String label = cardinalHeadingOrHeading(heading);
                // Keep the centre readout box clear: it covers +/-18 px around the centre.
                if (Math.abs(tickX - centerX) > 19 + font.width(label) / 2) {
                    g.centeredText(font, label, tickX, y + 8, PFD_WHITE);
                }
            }
        }
        if (autopilot || autopilotGuidance.autolandArmed()) {
            double relative = wrapDegrees(autopilotGuidance.targetHeadingDegrees() - visualHeading);
            int bugX = centerX + (int) Math.round(relative * pixelsPerDegree);
            if (bugX > x + 3 && bugX < x + width - 3) {
                g.fill(bugX, y + 1, bugX + 2, y + 4, PFD_CYAN);
            }
            String tgtText = String.format(Locale.ROOT, "H%03d", (int) autopilotGuidance.targetHeadingDegrees());
            g.text(font, tgtText, x + 3, y + 2, PFD_CYAN, false);
        }
        int trackX = centerX + (int) Math.round(wrapDegrees(trackDegrees() - visualHeading) * pixelsPerDegree);
        if (trackX > x + 2 && trackX < x + width - 2) {
            g.fill(trackX - 1, y + 2, trackX + 1, y + 4, PFD_GREEN);
        }
        if (ilsActive) {
            int courseX = centerX + (int) Math.round(wrapDegrees(ilsRunwayHeadingDegrees - visualHeading) * pixelsPerDegree);
            if (courseX > x + 2 && courseX < x + width - 2) {
                g.fill(courseX - 1, y + 2, courseX + 1, y + 4, 0xFFFF6CD9);
            }
        }
        g.fill(centerX, y + 1, centerX + 1, y + 5, PFD_WHITE);
        int heading = Math.floorMod(Math.round(visualHeading), 360);
        g.fill(centerX - 17, y + 7, centerX + 17, y + height - 1, FlightLineTheme.INSET);
        g.outline(centerX - 17, y + 7, 34, height - 8, PFD_WHITE);
        g.centeredText(font, String.format(Locale.ROOT, "%03d", heading), centerX, y + 8, PFD_WHITE);
    }

    private static String cardinalHeadingOrHeading(int heading) {
        return switch (Math.floorMod(heading, 360)) {
            case 0 -> "N";
            case 90 -> "E";
            case 180 -> "S";
            case 270 -> "W";
            default -> String.format(Locale.ROOT, "%03d", heading);
        };
    }

    private static void drawPfdStatus(GuiGraphicsExtractor g, Font font) {
        int statusTop = 127;
        int statusBottom = PFD_BASE_HEIGHT - 1;
        g.fill(1, statusTop, PFD_BASE_WIDTH - 1, statusBottom, 0xDD070C11);
        g.outline(1, statusTop, PFD_BASE_WIDTH - 2, statusBottom - statusTop, FlightLineTheme.STONE);
        // Row 1: Wind | Attitude or ILS | VS
        String wind = String.format(Locale.ROOT, "W%03d/%02d",
                weatherGuidance.windDirectionDegrees(), weatherGuidance.windSpeedKnots());
        String lateral = ilsActive
                ? String.format(Locale.ROOT, "LOC%+.1f GS%+.1f", ilsLocalizerDegrees, ilsGlideslopeDegrees)
                : String.format(Locale.ROOT, "P%+.0f° B%+.0f°", visualPitch, visualBank);
        String vs = String.format(Locale.ROOT, "VS%+d", verticalSpeedFpm);
        g.text(font, fitText(font, wind, 50), 4, statusTop + 2, PFD_MUTED, true);
        g.text(font, fitText(font, lateral, 62), 58, statusTop + 2, ilsActive ? PFD_CYAN : PFD_WHITE, true);
        g.text(font, fitText(font, vs, 42), 122, statusTop + 2,
                verticalSpeedFpm < -1500 ? PFD_AMBER : PFD_WHITE, true);

        // Row 2: Ground speed | DME distance | QNH (standard for Minecraft altitude)
        String gs = String.format(Locale.ROOT, "GS%dKT", groundSpeedKnots());
        String dmeStr = ilsActive
                ? String.format(Locale.ROOT, "DME %.1fNM", ilsThresholdDistanceBlocks * 0.054f)
                : "DME ----";
        g.text(font, fitText(font, gs, 48), 4, statusTop + 12, PFD_WHITE, true);
        g.text(font, fitText(font, dmeStr, 62), 56, statusTop + 12,
                ilsActive ? PFD_GREEN : PFD_MUTED, true);
        g.text(font, "STD 1013", 122, statusTop + 12, PFD_MUTED, true);

        // Row 3: Track | load factor and autoflight mode | outside air temperature
        String trk = String.format(Locale.ROOT, "TRK%03d", trackDegrees());
        String apMode = isAutopilotEngaged()
                ? (isAtcControlled() ? "ATC" : "AP") : "MAN";
        String gText = String.format(Locale.ROOT, "G%.1f %s", gLoad(), apMode);
        int satC = weatherGuidance.condition() == 2 ? -40 : weatherGuidance.condition() == 1 ? 5 : 15;
        int tatC = satC + Math.max(0, (int) Math.round(knots(visualSpeed) * 0.0017));
        String tempStr = String.format(Locale.ROOT, "TAT%+d°", tatC);
        g.text(font, fitText(font, trk, 48), 4, statusTop + 22, PFD_CYAN, true);
        g.text(font, fitText(font, gText, 62), 56, statusTop + 22,
                gLoad() > 2.5f || gLoad() < 0.3f ? PFD_AMBER : PFD_WHITE, true);
        g.text(font, fitText(font, tempStr, 42), 122, statusTop + 22, PFD_MUTED, true);
    }

    private static void drawKeybindList(GuiGraphicsExtractor graphics, Minecraft mc,
                                        int screenWidth, int screenHeight) {
        final int maxRows = 13;
        boolean airborne = FlightLineClient.isAirborne();
        boolean emergencyAction = emergencyStatus.recommendation().present();
        boolean atcControlled = isAtcControlled();
        boolean atcMessage = hasAtcMessage();

        // Priority rows survive phase changes and alphabetical sorting would otherwise hide them.
        List<String> rows = new ArrayList<>(maxRows);
        addUniqueHint(rows, pairBinding(FlightLineClient.throttleUp(), FlightLineClient.throttleDown(),
                "THROTTLE"));
        addUniqueHint(rows, pairBinding(FlightLineClient.rudderLeft(), FlightLineClient.rudderRight(),
                "RUDDER"));
        addUniqueHint(rows, binding(FlightLineClient.brakes(), "BRAKES"));
        addUniqueHint(rows, binding(FlightLineClient.toggleGear(), "LANDING GEAR"));
        if (airborne) {
            addUniqueHint(rows, pairBinding(FlightLineClient.flapsIncrease(),
                    FlightLineClient.flapsDecrease(), "FLAPS (+ / -)"));
        }
        addUniqueHint(rows, binding(FlightLineClient.toggleAutopilot(), "AUTOPILOT PANEL"));
        addUniqueHint(rows, binding(FlightLineClient.emergencyChecklist(), "EMERGENCY CHECKLIST"));
        if (emergencyAction) {
            addUniqueHint(rows, binding(FlightLineClient.executeEmergency(), "EXECUTE NEXT ACTION"));
        }
        if (airborne) {
            addUniqueHint(rows, binding(FlightLineClient.flightRadar(), "FLIGHT RADAR"));
            addUniqueHint(rows, binding(FlightLineClient.weatherMap(), "WEATHER MAP"));
        }
        if (atcControlled || atcMessage) {
            addUniqueHint(rows, binding(FlightLineClient.ackAtc(),
                    atcControlled ? "RELEASE ATC" : "ACK ATC"));
        }
        addUniqueHint(rows, binding(FlightLineClient.openControls(), "ALL CONTROLS"));
        addUniqueHint(rows, binding(FlightLineClient.toggleKeybindHints(), "HIDE PROMPTS"));

        // Everything else fills the remaining rows in a stable, readable order.
        List<String> remaining = new ArrayList<>();
        remaining.add(binding(FlightLineClient.telemetry(), "FLIGHT DATA PANEL"));
        remaining.add(binding(FlightLineClient.flightTutorial(), "FLIGHT TUTORIAL"));
        if (airborne) {
            remaining.addAll(List.of(
                    binding(FlightLineClient.spoilersToggle(), "SPOILERS"),
                    binding(FlightLineClient.airbrakeToggle(), "AIRBRAKES"),
                    binding(FlightLineClient.antiIce(), "ANTI-ICE")));
        } else {
            remaining.add(binding(FlightLineClient.pushback(), "PUSHBACK"));
            if (FlightLineClient.areEnginesActive()) {
                remaining.add(binding(FlightLineClient.shutdownEngines(), "SHUT DOWN ENGINES"));
            } else if (isCrashed()) {
                remaining.add(binding(FlightLineClient.inventory(), "RECONFIGURE AIRCRAFT"));
            } else {
                remaining.add(binding(FlightLineClient.inventory(), "START ENGINES"));
            }
        }
        remaining.sort(String.CASE_INSENSITIVE_ORDER);
        for (String hint : remaining) {
            if (rows.size() >= maxRows) break;
            addUniqueHint(rows, hint);
        }

        int contentWidth = Math.min(160, Math.max(70, screenWidth - 12));
        int contentHeight = rows.size() * 9;
        float availableHeight = Math.max(0.0f, screenHeight - 18.0f);
        float scale = Math.min(0.56f, Math.min(availableHeight / contentHeight,
                (screenWidth - 12.0f) / contentWidth));
        if (scale < 0.30f) return;
        int x = screenWidth - 6 - Math.round(contentWidth * scale);
        int y = screenHeight - 6 - Math.round(contentHeight * scale);
        int slide = Math.round((1.0f - keybindPanelProgress) * 8.0f);
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + slide, y);
        pose.scale(scale, scale);
        for (int i = 0; i < rows.size(); i++) {
            String text = fitText(mc.font, rows.get(i), contentWidth);
            graphics.text(mc.font, text, contentWidth - mc.font.width(text), i * 9,
                    withAlpha(FlightLineTheme.PAPER, keybindPanelProgress), true);
        }
        pose.popMatrix();
    }

    private static void addUniqueHint(List<String> hints, String hint) {
        if (!hints.contains(hint)) hints.add(hint);
    }

    private static String binding(net.minecraft.client.KeyMapping mapping, String label) {
        return FlightLineClient.keyName(mapping) + " - " + label;
    }

    private static String pairBinding(net.minecraft.client.KeyMapping first,
                                      net.minecraft.client.KeyMapping second, String label) {
        return FlightLineClient.keyName(first) + "/" + FlightLineClient.keyName(second) + " - " + label;
    }

    static boolean shouldHideVanillaHud() {
        return enabled && (isAirbornePhase() || (FlightLineClient.areEnginesActive() && speed > 0.35f * (float) FlightTuning.WORLD_SPEED_SCALE));
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
        graphics.fill(x + 4, y + 4, x + popupWidth + 4, y + popupHeight + 4,
                withAlpha(FlightLineTheme.SHADOW, brakePopupProgress));
        graphics.fill(x, y, x + popupWidth, y + popupHeight,
                withAlpha(FlightLineTheme.COAL, brakePopupProgress));
        graphics.outline(x, y, popupWidth, popupHeight,
                withAlpha(active ? FlightLineTheme.AMBER : FlightLineTheme.STONE, brakePopupProgress));
        graphics.fill(x, y, x + 4, y + popupHeight,
                withAlpha(active ? FlightLineTheme.AMBER : FlightLineTheme.STONE, brakePopupProgress));
        graphics.centeredText(Minecraft.getInstance().font, active ? "BRAKES ON" : "BRAKES RELEASED",
                screenWidth / 2 + 2, y + 4,
                withAlpha(active ? FlightLineTheme.AMBER : FlightLineTheme.TEXT_DIM, brakePopupProgress));
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
                g.fill(px, py, px + 1, py + 1, ring == radius ? FlightLineTheme.GREEN_DARK : FlightLineTheme.LINE);
            }
        }
        g.fill(cx - radius, cy, cx + radius + 1, cy + 1, FlightLineTheme.LINE);
        g.fill(cx, cy - radius, cx + 1, cy + radius + 1, FlightLineTheme.LINE);
        for (int cardinal = 0; cardinal < 360; cardinal += 90) {
            double relative = Math.toRadians(cardinal - heading);
            int outerX = cx - (int)Math.round(Math.sin(relative) * radius);
            int outerY = cy - (int)Math.round(Math.cos(relative) * radius);
            int innerX = cx - (int)Math.round(Math.sin(relative) * (radius - 3));
            int innerY = cy - (int)Math.round(Math.cos(relative) * (radius - 3));
            drawLine(g, innerX, innerY, outerX, outerY, FlightLineTheme.TEXT_DIM);
        }
        double sweep = clock * Math.PI / 2.2;
        for (int trail = 5; trail >= 1; trail--) {
            double trailingAngle = sweep - Math.toRadians(trail * 3.0);
            int trailX = cx + (int)Math.round(Math.sin(trailingAngle) * radius);
            int trailY = cy - (int)Math.round(Math.cos(trailingAngle) * radius);
            int trailColor = trail <= 2 ? 0x606CC349 : 0x303C8527;
            drawLine(g, cx, cy, trailX, trailY, trailColor);
        }
        int endX = cx + (int)Math.round(Math.sin(sweep) * radius);
        int endY = cy - (int)Math.round(Math.cos(sweep) * radius);
        drawLine(g, cx, cy, endX, endY, FlightLineTheme.GREEN);
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

    private static void drawDiamond(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
        drawLine(g, cx, cy - size, cx + size, cy, color);
        drawLine(g, cx + size, cy, cx, cy + size, color);
        drawLine(g, cx, cy + size, cx - size, cy, color);
        drawLine(g, cx - size, cy, cx, cy - size, color);
    }

    private static void drawRouteOverlay(GuiGraphicsExtractor g, Minecraft mc, int cx, int cy,
                                         int radius, double rangeBlocks, float heading,
                                         int ownX, int ownZ) {
        var plan = NavigationState.plan();
        if (plan == null) return;
        var legs = NavigationState.legs();
        int previousX = cx;
        int previousY = cy;
        boolean previousVisible = true;
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            double distance = NavGuidance.distanceBlocks(ownX, ownZ, leg.x(), leg.z());
            boolean visible = distance <= rangeBlocks;
            double relative = Math.toRadians(NavGuidance.bearingDegrees(ownX, ownZ, leg.x(), leg.z())
                    - heading);
            double scale = Math.min(1.0, distance / rangeBlocks);
            int px = cx - (int) Math.round(Math.sin(relative) * radius * scale);
            int py = cy - (int) Math.round(Math.cos(relative) * radius * scale);
            boolean active = i == plan.activeLeg();
            int color = active ? FlightLineTheme.GREEN : leg.airport() ? FlightLineTheme.AMBER : FlightLineTheme.CYAN;
            if (visible && previousVisible) drawLine(g, previousX, previousY, px, py, color);
            if (visible) {
                drawDiamond(g, px, py, 2, color);
                if (active || i == legs.size() - 1) {
                    g.text(mc.font, fitText(mc.font, leg.name(), 28), px + 3, py - 4, color, false);
                }
            }
            previousX = px;
            previousY = py;
            previousVisible = visible;
        }
    }

    private static void drawAircraftSymbol(GuiGraphicsExtractor g, int cx, int cy) {
        g.fill(cx, cy - 4, cx + 1, cy + 4, FlightLineTheme.PAPER);
        g.fill(cx - 4, cy, cx + 5, cy + 1, FlightLineTheme.PAPER);
        g.fill(cx - 2, cy + 2, cx + 3, cy + 3, FlightLineTheme.PAPER);
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
        Font font = Minecraft.getInstance().font;
        int left = cx - width / 2;
        int right = cx + width / 2;
        int top = cy - height / 2;
        int bottom = top + height;

        double bankRad = Math.toRadians(Math.max(-60.0f, Math.min(60.0f, bank)));
        double slope = Math.tan(bankRad);
        double cosBank = Math.cos(bankRad);
        double sinBank = Math.sin(bankRad);
        double pitchScale = height / 60.0;
        int pitchOffset = (int) Math.round(Math.max(-40.0f, Math.min(40.0f, pitch)) * pitchScale);

        // Sky and ground fill, banked around the aircraft reference point.
        for (int px = left; px < right; px += 2) {
            int dx = px - cx;
            int horizon = Math.max(top, Math.min(bottom,
                    cy + pitchOffset + (int) Math.round(dx * slope)));
            g.fill(px, top, px + 2, horizon, FlightLineTheme.BLUE);
            g.fill(px, horizon, px + 2, bottom, 0xFF6B4A28);
            g.fill(px, Math.max(top, horizon - 1), px + 2, Math.min(bottom, horizon + 1),
                    0xFFF2F7FA);
        }

        // 2.5-degree pitch ladder; ten-degree bars are longer and labelled like a glass ADI.
        for (int ladderStep = -14; ladderStep <= 14; ladderStep++) {
            double ladderPitch = ladderStep * 2.5;
            if (ladderStep == 0) continue;
            double dp = (pitch - ladderPitch) * pitchScale;
            int rungCenterX = cx - (int) Math.round(dp * sinBank);
            int rungCenterY = cy + (int) Math.round(dp * cosBank);
            if (rungCenterY < top + 4 || rungCenterY > bottom - 4) continue;
            boolean major = Math.floorMod(ladderStep, 4) == 0;
            int rungHalfWidth = major ? 20 : Math.floorMod(ladderStep, 2) == 0 ? 13 : 7;
            int rx1 = rungCenterX - (int) Math.round(rungHalfWidth * cosBank);
            int ry1 = rungCenterY - (int) Math.round(rungHalfWidth * sinBank);
            int rx2 = rungCenterX + (int) Math.round(rungHalfWidth * cosBank);
            int ry2 = rungCenterY + (int) Math.round(rungHalfWidth * sinBank);
            drawLineClipped(g, rx1, ry1, rx2, ry2, left, top, right, bottom,
                    ladderPitch > 0 ? 0xFFF2F7FA : 0xFFD0D0D0);
            if (major) {
                String label = Integer.toString((int) Math.abs(ladderPitch));
                int labelX = rungCenterX + (int) Math.round((rungHalfWidth + 2) * cosBank);
                int labelY = rungCenterY - 4;
                if (labelX > left + 2 && labelX + font.width(label) < right - 2
                        && labelY > top + 1 && labelY + 8 < bottom) {
                    g.text(font, label, labelX, labelY, 0xFFDDE8EC, false);
                }
            }
        }

        // Bank scale across the top arc with fixed marks and a moving roll pointer.
        int radius = Math.min(width, height) / 2 - 2;
        for (int mark : new int[]{0, 10, 20, 30, 45, 60}) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (mark == 0 && sign < 0) continue;
                double radians = Math.toRadians(mark * sign);
                int outerX = cx + (int) Math.round(Math.sin(radians) * radius);
                int outerY = cy - (int) Math.round(Math.cos(radians) * radius);
                int innerRadius = radius - (mark == 0 || mark == 30 || mark == 60 ? 5 : 3);
                int innerX = cx + (int) Math.round(Math.sin(radians) * innerRadius);
                int innerY = cy - (int) Math.round(Math.cos(radians) * innerRadius);
                drawLineClipped(g, outerX, outerY, innerX, innerY, left, top, right, bottom, 0xFFE8F1F4);
            }
        }
        int rollPointerX = cx + (int) Math.round(Math.sin(bankRad) * (radius - 8));
        int rollPointerY = cy - (int) Math.round(Math.cos(bankRad) * (radius - 8));
        g.fill(rollPointerX - 1, rollPointerY - 1, rollPointerX + 2, rollPointerY + 3, PFD_AMBER);

        if (showIls) {
            int localizerOffset = Math.max(-width / 2 + 4,
                    Math.min(width / 2 - 4, Math.round(localizerDegrees * 6.0f)));
            int glideslopeOffset = Math.max(-height / 2 + 4,
                    Math.min(height / 2 - 4, -Math.round(glideslopeDegrees * 6.0f)));
            drawLineClipped(g, cx + localizerOffset, cy - 22, cx + localizerOffset, cy + 22,
                    left, top, right, bottom, 0x80FF6CD9);
            drawLineClipped(g, cx - 22, cy + glideslopeOffset, cx + 22, cy + glideslopeOffset,
                    left, top, right, bottom, 0x8079E8FF);
            drawDiamond(g, cx + localizerOffset, cy - 22, 3, 0xFFFF6CD9);
            drawDiamond(g, cx + 22, cy + glideslopeOffset, 3, 0xFF79E8FF);
            g.fill(cx + localizerOffset - 2, cy - 1, cx + localizerOffset + 3, cy + 2, 0xFFFF6CD9);
            g.fill(cx - 1, cy + glideslopeOffset - 2, cx + 2, cy + glideslopeOffset + 3, 0xFF79E8FF);
            for (int dot = -2; dot <= 2; dot++) {
                int x = cx + dot * 10;
                g.fill(x, bottom - 6, x + 1, bottom - 3, 0xFFB04A93);
                int y = cy + dot * 10;
                g.fill(right - 6, y, right - 3, y + 1, 0xFF4A93B0);
            }
        }

        // Flight Director crossbars (magenta) when autopilot is engaged
        if (autopilot) {
            double targetPitch = ilsActive
                    ? Math.max(-10.0, Math.min(15.0, -glideslopeDegrees * 2.8))
                    : Math.max(-12.0, Math.min(18.0, verticalSpeedFpm / 140.0));
            double targetBankHdg = ilsActive
                    ? Math.max(-25.0, Math.min(25.0, localizerDegrees * 3.2))
                    : Math.max(-25.0, Math.min(25.0, wrapDegrees(autopilotGuidance.targetHeadingDegrees() - visualHeading)));
            int fdY = cy + (int) Math.round((pitch - targetPitch) * pitchScale);
            int fdX = cx + (int) Math.round((targetBankHdg - visualBank) * 1.5);
            fdY = Math.max(top + 8, Math.min(bottom - 8, fdY));
            fdX = Math.max(left + 12, Math.min(right - 12, fdX));
            g.fill(fdX - 20, fdY - 1, fdX + 20, fdY + 1, 0xFFFF33D4);
            g.fill(fdX - 1, fdY - 15, fdX + 1, fdY + 15, 0xFFFF33D4);
        }

        // Flight path vector: where the aircraft is actually going versus where the nose points.
        double iasKnots = Math.max(20.0, knots(visualSpeed));
        double flightPathDegrees = Math.toDegrees(Math.atan2(verticalSpeedFpm, iasKnots * 101.27));
        if (isAirbornePhase() && Math.abs(pitch - flightPathDegrees) > 1.0) {
            int fpvY = cy + (int) Math.round(Math.max(-height / 2.0 + 8,
                    Math.min(height / 2.0 - 8, (pitch - flightPathDegrees) * pitchScale)));
            g.fill(cx - 2, fpvY - 3, cx + 3, fpvY - 2, PFD_GREEN);
            g.fill(cx - 2, fpvY + 2, cx + 3, fpvY + 3, PFD_GREEN);
            g.fill(cx - 3, fpvY - 2, cx - 2, fpvY + 2, PFD_GREEN);
            g.fill(cx + 2, fpvY - 2, cx + 3, fpvY + 2, PFD_GREEN);
            g.fill(cx - 9, fpvY - 1, cx - 4, fpvY, PFD_GREEN);
            g.fill(cx + 3, fpvY - 1, cx + 8, fpvY, PFD_GREEN);
        }

        // Slip/skid indicator beneath the fixed aircraft symbol.
        g.fill(cx - 4, cy + 7, cx - 3, cy + 10, 0xFFE8F1F4);
        g.fill(cx + 3, cy + 7, cx + 4, cy + 10, 0xFFE8F1F4);
        g.fill(cx - 1, cy + 8, cx + 1, cy + 10, 0xFFE8F1F4);

        // Fixed Aircraft Reference Symbol (gull wings and centre pip).
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, PFD_AMBER);
        g.fill(cx - 16, cy, cx - 4, cy + 2, PFD_AMBER);
        g.fill(cx - 6, cy + 2, cx - 4, cy + 4, PFD_AMBER);
        g.fill(cx + 3, cy, cx + 15, cy + 2, PFD_AMBER);
        g.fill(cx + 3, cy + 2, cx + 5, cy + 4, PFD_AMBER);

        // Digital Radio Altitude readout integrated at bottom of horizon when < 2500 ft AGL
        if (radioAltitudeFeet < 2500 && isAirbornePhase()) {
            int raBoxWidth = 36;
            int raBoxHeight = 11;
            int raBoxX = cx - raBoxWidth / 2;
            int raBoxY = bottom - 14;
            g.fill(raBoxX, raBoxY, raBoxX + raBoxWidth, raBoxY + raBoxHeight, FlightLineTheme.VOID);
            g.outline(raBoxX, raBoxY, raBoxWidth, raBoxHeight, radioAltitudeFeet < 500 ? PFD_AMBER : FlightLineTheme.STONE);
            String raText = Integer.toString(radioAltitudeFeet);
            int color = radioAltitudeFeet < 200 ? 0xFFFF3B30 : radioAltitudeFeet < 500 ? PFD_AMBER : PFD_GREEN;
            g.centeredText(font, raText, cx, raBoxY + 2, color);
        }
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
        FlightLineTheme.bar(g, x, y, width, height, value, color, FlightLineTheme.INSET);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int borderColor) {
        // HUD panels stay compact: hard shadow + 2px frame instead of the full 4px screen frame.
        g.fill(x + 4, y + 4, x + width + 4, y + height + 4, FlightLineTheme.SHADOW);
        g.fill(x, y, x + width, y + height, borderColor);
        g.fill(x + 2, y + 2, x + width - 2, y + height - 2, FlightLineTheme.PANEL);
    }

    private static int gearColor() {
        return gearState == 2 ? 0xFFFF5148 : gearState == 1 ? 0xFFFFCE52 : 0xFF62D89B;
    }

    private static int engineColor(int state) {
        return state >= 3 ? 0xFFFF5148 : state == 0 ? 0xFF83959B : 0xFF62D89B;
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

}
