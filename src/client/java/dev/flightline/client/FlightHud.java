package dev.flightline.client;

import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightTuning;
import dev.flightline.flight.FlightWarning;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightStatePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

final class FlightHud {
    private static boolean enabled;
    private static FlightPhase phase = FlightPhase.OFF;
    private static float speed, throttle, bankDegrees, pitchDegrees, headingDegrees;
    private static float visualSpeed, visualBank, visualPitch, visualHeading;
    private static int radioAltitudeFeet, verticalSpeedFpm, gearState, systems, warnings;
    private static boolean autopilot;
    private static FlightInstrumentPayload.AutopilotGuidance autopilotGuidance =
            FlightInstrumentPayload.AutopilotGuidance.defaults();
    private static float trafficBearingDegrees;
    private static int trafficRangeBlocks, trafficAltitudeFeet;
    private static int trafficCounts;
    private static boolean ilsActive;
    private static float ilsLocalizerDegrees, ilsGlideslopeDegrees;
    private static float ilsReferenceGlideSlopeDegrees = 3.0f;
    private static long lastFrameNanos = System.nanoTime();

    private FlightHud() {
    }

    static float throttle() {
        return throttle;
    }

    static float currentHeading() {
        return visualHeading;
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

    static float localizerDeviation() {
        return ilsLocalizerDegrees;
    }

    static float glideslopeDeviation() {
        return ilsGlideslopeDegrees;
    }

    static float ilsReferenceGlideSlope() {
        return ilsReferenceGlideSlopeDegrees;
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
        warnings = instruments.warnings();
        autopilot = instruments.autopilot();
        autopilotGuidance = instruments.autopilotGuidance();
        trafficBearingDegrees = instruments.trafficBearingDegrees();
        trafficRangeBlocks = instruments.trafficRangeBlocks();
        trafficAltitudeFeet = instruments.trafficAltitudeFeet();
        trafficCounts = instruments.trafficCounts();
        ilsActive = instruments.ilsGuidance().active();
        ilsLocalizerDegrees = instruments.ilsGuidance().localizerDegrees();
        ilsGlideslopeDegrees = instruments.ilsGuidance().glideslopeDegrees();
        ilsReferenceGlideSlopeDegrees = instruments.ilsGuidance().referenceGlideSlopeDegrees();
    }

    static void clear() {
        enabled = false;
        phase = FlightPhase.OFF;
        speed = throttle = bankDegrees = pitchDegrees = headingDegrees = 0;
        visualSpeed = visualBank = visualPitch = visualHeading = 0;
        radioAltitudeFeet = verticalSpeedFpm = gearState = systems = warnings = 0;
        autopilot = false;
        autopilotGuidance = FlightInstrumentPayload.AutopilotGuidance.defaults();
        trafficBearingDegrees = 0.0f;
        trafficRangeBlocks = trafficAltitudeFeet = 0;
        trafficCounts = 0;
        ilsActive = false;
        ilsLocalizerDegrees = ilsGlideslopeDegrees = 0.0f;
        ilsReferenceGlideSlopeDegrees = 3.0f;
        lastFrameNanos = System.nanoTime();
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

        // Professional Glass Cockpit PFD: Attitude, Pitch Ladder, Airspeed & Altitude tapes
        int pfdX = 6;
        int pfdY = 6;
        int pfdWidth = 116;
        int pfdHeight = 88;
        panel(g, pfdX, pfdY, pfdWidth, pfdHeight, 0xAA56B9D2);

        // PFD Top Header / FMA
        g.text(mc.font, "PFD", pfdX + 4, pfdY + 3, 0xFF72E6F5, true);
        String flightMode = autopilotGuidance.autolandArmed() ? "AL" : autopilot ? "AP" : "FD";
        g.text(mc.font, flightMode, pfdX + 30, pfdY + 3,
                autopilot ? 0xFF62D89B : 0xFF70808A, true);
        g.text(mc.font, fitText(mc.font, phase.label(), pfdWidth - 58), pfdX + 54, pfdY + 3,
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

        // Heading-up ND with a scaled traffic scope and a rotating compass reference
        int ndWidth = 104;
        int ndX = (screenWidth - ndWidth) / 2;
        int ndY = screenWidth < 370 ? 96 : 6;
        panel(g, ndX, ndY, ndWidth, 84, 0xAA56B9D2);
        int heading = Math.floorMod(Math.round(visualHeading), 360);
        g.text(mc.font, String.format(Locale.ROOT, "ND HDG %03d°", heading), ndX + 4, ndY + 3,
                0xFF72E6F5, true);
        int radarCx = ndX + ndWidth / 2;
        int radarCy = ndY + 38;
        drawRadarGrid(g, radarCx, radarCy, 19, clock, visualHeading);
        drawAircraftSymbol(g, radarCx, radarCy);
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(128.0), candidate -> candidate.isAlive())) {
            double dx = mob.getX() - player.getX();
            double dz = mob.getZ() - player.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance > 128.0) continue;
            double relativeBearing = Math.toRadians(Math.toDegrees(Math.atan2(-dx, dz)) - visualHeading);
            double scale = Math.min(1.0, distance / 128.0);
            drawTrafficBlip(g, radarCx, radarCy, relativeBearing, scale,
                    mob.onGround() ? 0xFFFFCE52 : 0xFF75E6A5);
        }
        for (var otherPlayer : level.players()) {
            if (otherPlayer == player || otherPlayer.isSpectator()) continue;
            double dx = otherPlayer.getX() - player.getX();
            double dz = otherPlayer.getZ() - player.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance > 128.0) continue;
            double relativeBearing = Math.toRadians(Math.toDegrees(Math.atan2(-dx, dz)) - visualHeading);
            drawTrafficBlip(g, radarCx, radarCy, relativeBearing, distance / 128.0,
                    otherPlayer.onGround() ? 0xFFFFCE52 : 0xFF75E6A5);
        }

        // Draw closest traffic contact blip on radar
        if (trafficRangeBlocks > 0) {
            double bearing = Math.toRadians(trafficBearingDegrees);
            double range = Math.min(1.0, trafficRangeBlocks / 128.0);
            int targetX = radarCx - (int)Math.round(Math.sin(bearing) * 17.0 * range);
            int targetY = radarCy - (int)Math.round(Math.cos(bearing) * 17.0 * range);
            int targetColor = (warnings & FlightWarning.TCAS_RESOLUTION) != 0 ? 0xFFFF5148 : 0xFFFFCE52;
            g.fill(targetX - 2, targetY - 2, targetX + 3, targetY + 3, targetColor);
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

        // EICAS: Engine, Fuel, Gear, Flaps and Spoilers
        int eicasX = screenWidth - 118;
        panel(g, eicasX, 6, 112, 68, 0xAA56B9D2);
        int engine1 = systems & 7;
        int engine2 = (systems >> 3) & 7;
        int n1 = (systems >> 12) & 0x7F;
        int flap = (systems >> 9) & 7;
        boolean spoilers = (systems & (1 << 8)) != 0;
        int food = player.getFoodData().getFoodLevel();
        g.text(mc.font, "EICAS", eicasX + 5, 10, 0xFF72E6F5, true);
        g.text(mc.font, autopilotGuidance.autolandArmed() ? "A/L ON" : autopilot ? "A/P ON" : "A/P OFF",
                eicasX + 60, 10,
                autopilot ? 0xFF62D89B : 0xFF83959B, true);
        g.text(mc.font, "E1 " + engineTag(engine1), eicasX + 4, 22, engineColor(engine1), true);
        g.text(mc.font, "E2 " + engineTag(engine2), eicasX + 59, 22, engineColor(engine2), true);
        g.text(mc.font, "N1", eicasX + 4, 34, 0xFFD5E0E8, true);
        gauge(g, eicasX + 22, 36, 84, 5, n1 / 100.0f, n1 > 90 ? 0xFFFFA04A : 0xFF62D89B);
        g.text(mc.font, "FUEL", eicasX + 4, 45, 0xFFD5E0E8, true);
        gauge(g, eicasX + 30, 47, 76, 5, food / 20.0f, food <= 6 ? 0xFFFF5148 : 0xFFFFCE52);
        g.text(mc.font, gearState == 2 ? "GEAR FAIL" : gearState == 1 ? "GEAR UP" : "GEAR DN",
                eicasX + 4, 57, gearColor(), true);
        g.text(mc.font, spoilers ? "SP ON" : "SP OFF", eicasX + 54, 57,
                spoilers ? 0xFFFFCE52 : 0xFFB9CDD4, true);
        g.text(mc.font, "FL", eicasX + 4, 65, 0xFFD5E0E8, true);
        for (int i = 0; i < 4; i++) {
            g.fill(eicasX + 19 + i * 9, 66, eicasX + 25 + i * 9, 71,
                    i < flap ? 0xFFFFCE52 : 0xFF364650);
        }

        // Radio altimeter with a focused low-altitude tape and vertical-speed trend readout.
        int lowerY = screenHeight - 60;
        int radioPanelWidth = 132;
        int radioPanelHeight = 54;
        boolean lowerPanelVisible = lowerY >= 0 && lowerY + radioPanelHeight <= screenHeight
                && (screenWidth >= 370 || lowerY >= ndY + 88 + 4);
        if (lowerPanelVisible) {
            panel(g, 6, lowerY, radioPanelWidth, radioPanelHeight, 0xAA56B9D2);
            g.text(mc.font, "RADIO ALT", 11, lowerY + 3, 0xFF72E6F5, true);
            String altitudeText = required(String.format(Locale.ROOT, "%04d FT", radioAltitudeFeet));
            int altitudeColor = radioAltitudeColor(radioAltitudeFeet);
            g.text(mc.font, fitText(mc.font, altitudeText, 58), 11, lowerY + 14, altitudeColor, true);

            String trendText = required(String.format(Locale.ROOT, "%s %d FPM",
                    verticalSpeedFpm < 0 ? "▼" : "▲", Math.abs(verticalSpeedFpm)));
            int trendColor = verticalSpeedFpm < -1000 ? 0xFFFF5148
                    : verticalSpeedFpm < 0 ? 0xFFFFCE52 : 0xFF68E8A5;
            g.text(mc.font, fitText(mc.font, trendText, 60), 73, lowerY + 14, trendColor, true);

            int tapeX = 11;
            int tapeY = lowerY + 29;
            int tapeWidth = 112;
            gauge(g, tapeX, tapeY, tapeWidth, 7,
                    Math.min(1.0f, radioAltitudeFeet / 500.0f), altitudeColor);
            for (int tickFeet : new int[]{10, 20, 50, 100, 200, 300, 400, 500}) {
                int tickX = tapeX + Math.round(tapeWidth * tickFeet / 500.0f);
                g.fill(tickX, tapeY - 1, tickX + 1, tapeY + (tickFeet % 100 == 0 ? 9 : 6),
                        tickFeet <= 100 ? 0xFFE7EEF2 : 0xFF82949B);
            }
            g.text(mc.font, "0", tapeX, lowerY + 41, 0xFF9BAAB1, true);
            for (int tickFeet : new int[]{100, 200, 300, 400, 500}) {
                int tickX = tapeX + Math.round(tapeWidth * tickFeet / 500.0f);
                g.centeredText(mc.font, String.valueOf(tickFeet), tickX, lowerY + 41, 0xFF9BAAB1);
            }
        }

        int controlsWidth = 132;
        int controlsHeight = 108;
        int controlsX = screenWidth - controlsWidth - 6;
        int controlsY = screenHeight - controlsHeight - 6;
        boolean controlsVisible = screenWidth >= 320 && screenHeight >= 210
                && controlsY >= ndY + 88 + 3;
        if (controlsVisible) {
            drawKeyHint(g, mc, controlsX, controlsY, "Gear", FlightLineClient.toggleGear());
            drawKeyHint(g, mc, controlsX, controlsY + 9, "Engine Startup", FlightLineClient.startEngines());
            drawKeyHint(g, mc, controlsX, controlsY + 18, "Engine Shutdown", FlightLineClient.shutdownEngines());
            drawKeyHint(g, mc, controlsX, controlsY + 27, "Pushback", FlightLineClient.pushback());
            drawKeyHint(g, mc, controlsX, controlsY + 36, "Wheel Brakes", FlightLineClient.brakes());
            drawKeyHint(g, mc, controlsX, controlsY + 45, "Flaps Up", FlightLineClient.flapsUp());
            drawKeyHint(g, mc, controlsX, controlsY + 54, "Flaps Down", FlightLineClient.flapsDown());
            drawKeyHint(g, mc, controlsX, controlsY + 63, "Spoilers Extend", FlightLineClient.spoilersExtend());
            drawKeyHint(g, mc, controlsX, controlsY + 72, "Spoilers Retract", FlightLineClient.spoilersRetract());
            drawKeyHint(g, mc, controlsX, controlsY + 81, "Flight Deck", FlightLineClient.toggleAutopilot());
            drawKeyHint(g, mc, controlsX, controlsY + 90, "Inventory", FlightLineClient.inventory());
            drawKeyHint(g, mc, controlsX, controlsY + 99, "Steering / Throttle", "WASD");
        }

        // Sleek Action-Bar alert presentation: unobtrusive, high-visibility, clean cockpit view
        String alert = primaryWarning();
        if (!alert.isEmpty()) {
            int textWidth = mc.font.width(alert);
            alert = fitText(mc.font, alert, screenWidth - 24);
            textWidth = mc.font.width(alert);
            int pillWidth = textWidth + 16;
            int alertX = (screenWidth - pillWidth) / 2;
            int alertY = lowerPanelVisible ? lowerY - 14 : screenHeight - 14;
            if (controlsVisible && alertX < controlsX + controlsWidth && alertX + pillWidth > controlsX
                    && alertY < controlsY + controlsHeight) {
                alertY = controlsY - 14;
            }
            int blink = ((long)(clock * 2.5) & 1L) == 0L ? 0xFFFF5148 : 0xFFD84442;
            int alertColor = phase == FlightPhase.CRASHED ? 0xFFFF5148 : blink;
            g.fill(alertX, alertY - 2, alertX + pillWidth, alertY + 11, 0xDD120808);
            g.outline(alertX, alertY - 2, pillWidth, 11, alertColor);
            g.centeredText(mc.font, alert, screenWidth / 2, alertY, alertColor);
        }
    }

    private static void drawKeyHint(GuiGraphicsExtractor graphics, Minecraft mc, int x, int y, String label,
                                    net.minecraft.client.KeyMapping mapping) {
        String text = label + " - " + FlightLineClient.keyName(mapping);
        graphics.text(mc.font, fitText(mc.font, text, 132), x, y,
                mapping.isDown() ? 0xFFFFFFFF : 0xFF8A939B, true);
    }

    private static void drawKeyHint(GuiGraphicsExtractor graphics, Minecraft mc, int x, int y, String label,
                                    String keys) {
        graphics.text(mc.font, fitText(mc.font, label + " - " + keys, 132), x, y, 0xFF8A939B, true);
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
        g.fill(cx - 1, cy - 7, cx + 2, cy + 7, 0xFF76E8B0);
        g.fill(cx - 7, cy - 1, cx + 8, cy + 2, 0xFF76E8B0);
        g.fill(cx - 4, cy + 4, cx + 5, cy + 6, 0xFF76E8B0);
    }

    private static void drawTrafficBlip(GuiGraphicsExtractor g, int cx, int cy, double bearing,
                                        double range, int color) {
        double scaledRange = Math.min(1.0, range);
        int x = cx - (int)Math.round(Math.sin(bearing) * 17.0 * scaledRange);
        int y = cy - (int)Math.round(Math.cos(bearing) * 17.0 * scaledRange);
        g.fill(x - 1, y - 1, x + 2, y + 2, color);
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

    private static String primaryWarning() {
        if (phase == FlightPhase.CRASHED) return "AIRFRAME CRASH · J TO RESET";
        if ((warnings & FlightWarning.STALL) != 0) return "STALL";
        if ((warnings & FlightWarning.WINDSHEAR) != 0) return "WINDSHEAR";
        if ((warnings & FlightWarning.PULL_UP) != 0) return "PULL UP";
        if ((warnings & FlightWarning.TERRAIN) != 0) return "TERRAIN, TERRAIN";
        if ((warnings & FlightWarning.TCAS_RESOLUTION) != 0) {
            String direction = (warnings & FlightWarning.TCAS_DESCEND) != 0 ? "DESCEND NOW" : "CLIMB NOW";
            return (warnings & FlightWarning.TCAS_CROSSING) != 0
                    ? "TCAS: " + direction + " · CROSSING" : "TCAS: " + direction;
        }
        if ((warnings & FlightWarning.TCAS_CLEAR) != 0) return "CLEAR OF CONFLICT";
        if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) return "TRAFFIC, TRAFFIC";
        if ((warnings & FlightWarning.GEAR_SELECTED_ON_GROUND) != 0) return "GEAR SELECT - AIRBORNE ONLY";
        if ((warnings & FlightWarning.SINK_RATE) != 0) return "SINK RATE";
        if ((warnings & FlightWarning.DONT_SINK) != 0) return "DON'T SINK";
        if ((warnings & FlightWarning.TOO_LOW_TERRAIN) != 0) return "TOO LOW TERRAIN";
        if ((warnings & FlightWarning.GEAR_UP) != 0) return "TOO LOW GEAR";
        if ((warnings & FlightWarning.TOO_LOW_FLAPS) != 0) return "TOO LOW FLAPS";
        if ((warnings & FlightWarning.BANK_ANGLE) != 0) return "BANK ANGLE";
        if ((warnings & FlightWarning.GLIDESLOPE_HIGH) != 0) return "GLIDESLOPE HIGH";
        if ((warnings & FlightWarning.GLIDESLOPE_LOW) != 0) return "GLIDESLOPE";
        if ((warnings & FlightWarning.ENGINE_FIRE) != 0) return "ENGINE FIRE";
        if ((warnings & FlightWarning.ENGINE_FAILURE) != 0) return "ENGINE FAILURE";
        if ((warnings & FlightWarning.FUEL_EMPTY) != 0) return "FUEL EMPTY - ENGINES OFF";
        if ((warnings & FlightWarning.FUEL_LOW) != 0) return "LOW FUEL";
        if ((warnings & FlightWarning.GEAR_FAILED) != 0) return "GEAR FAILURE";
        if ((warnings & FlightWarning.GEAR_HOT) != 0) return "GEAR HOT";
        if ((warnings & FlightWarning.TAKEOFF_CONFIG) != 0) return "TAKEOFF CONFIG";
        if ((warnings & FlightWarning.OVERSPEED) != 0) return "OVERSPEED";
        return "";
    }
}
