package dev.flightline.client;

import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightTuning;
import dev.flightline.flight.FlightWarning;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightStatePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

final class FlightHud {
    private static boolean enabled;
    private static FlightPhase phase = FlightPhase.OFF;
    private static float speed, throttle, bankDegrees, pitchDegrees, headingDegrees;
    private static float visualSpeed, visualBank, visualPitch, visualHeading;
    private static int radioAltitudeFeet, verticalSpeedFpm, gearState, systems, warnings;
    private static float trafficBearingDegrees;
    private static int trafficRangeBlocks, trafficAltitudeFeet;
    private static long lastFrameNanos = System.nanoTime();

    private FlightHud() {
    }

    static float throttle() {
        return throttle;
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
        trafficBearingDegrees = instruments.trafficBearingDegrees();
        trafficRangeBlocks = instruments.trafficRangeBlocks();
        trafficAltitudeFeet = instruments.trafficAltitudeFeet();
    }

    static void clear() {
        enabled = false;
        phase = FlightPhase.OFF;
        speed = throttle = bankDegrees = pitchDegrees = headingDegrees = 0;
        visualSpeed = visualBank = visualPitch = visualHeading = 0;
        radioAltitudeFeet = verticalSpeedFpm = gearState = systems = warnings = 0;
        trafficBearingDegrees = 0.0f;
        trafficRangeBlocks = trafficAltitudeFeet = 0;
        lastFrameNanos = System.nanoTime();
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null) return;
        animateInstruments();

        int screenWidth = g.guiWidth();
        int screenHeight = g.guiHeight();
        double clock = System.nanoTime() / 1_000_000_000.0;

        // PFD: rolling attitude, speed tape and a moving climb marker.
        int pfdX = 6;
        panel(g, pfdX, 6, 112, 91, 0xFF56B9D2);
        g.text(mc.font, "PFD", pfdX + 5, 10, 0xFF72E6F5, true);
        g.text(mc.font, String.format(Locale.ROOT, "IAS %03d", knots(visualSpeed)),
                pfdX + 5, 23, 0xFFFFFFFF, true);
        g.text(mc.font, phase.label(), pfdX + 58, 10, phaseColor(), true);
        drawArtificialHorizon(g, pfdX + 56, 61, 78, 44, visualPitch, visualBank);
        int pitchMarkY = 61 - Math.round(visualPitch * 0.38f);
        g.fill(pfdX + 9, pitchMarkY, pfdX + 19, pitchMarkY + 2, 0xFFFFD060);
        g.fill(pfdX + 9, 44, pfdX + 12, 79, 0xFF364650);
        int speedMarker = 79 - Math.round(Math.min(1.0f, visualSpeed / (float)FlightTuning.MAX_AIRSPEED) * 35.0f);
        g.fill(pfdX + 8, speedMarker - 1, pfdX + 13, speedMarker + 2, 0xFF68E8A5);
        g.text(mc.font, verticalSpeedFpm < 0 ? "▼" : "▲", pfdX + 7, 83,
                verticalSpeedFpm < -1000 ? 0xFFFF5148 : 0xFF82F58A, true);
        g.text(mc.font, String.format(Locale.ROOT, "%+.0f°", visualPitch), pfdX + 23, 83, 0xFFD5E0E8, true);

        // ND: rotating compass rose, aircraft symbol, traffic target and animated sweep.
        int ndWidth = 106;
        int ndX = (screenWidth - ndWidth) / 2;
        int ndY = screenWidth < 420 ? 104 : 6;
        panel(g, ndX, ndY, ndWidth, 91, 0xFF56B9D2);
        int heading = Math.floorMod(Math.round(visualHeading), 360);
        g.text(mc.font, "ND  " + String.format(Locale.ROOT, "%03d°", heading), ndX + 5, ndY + 4, 0xFF72E6F5, true);
        int radarCx = ndX + ndWidth / 2;
        int radarCy = ndY + 52;
        drawRadarGrid(g, radarCx, radarCy, 31, clock);
        drawAircraftSymbol(g, radarCx, radarCy);
        if (trafficRangeBlocks > 0) {
            double bearing = Math.toRadians(trafficBearingDegrees);
            double range = Math.min(1.0, trafficRangeBlocks / 64.0);
            int targetX = radarCx - (int)Math.round(Math.sin(bearing) * 27.0 * range);
            int targetY = radarCy - (int)Math.round(Math.cos(bearing) * 27.0 * range);
            int targetColor = (warnings & FlightWarning.TCAS_RESOLUTION) != 0 ? 0xFFFF5148 : 0xFFFFCE52;
            g.fill(targetX - 2, targetY - 2, targetX + 3, targetY + 3, targetColor);
            g.text(mc.font, String.format(Locale.ROOT, "%+d", trafficAltitudeFeet), ndX + 4, ndY + 76, targetColor, true);
        } else {
            g.text(mc.font, "NO TFC", ndX + 4, ndY + 76, 0xFF82F58A, true);
        }
        g.text(mc.font, "N", radarCx - 3, ndY + 18, 0xFFBFEAF0, true);

        // EICAS: paired engine status, N1, fuel, gear and flap position.
        int eicasX = screenWidth - 148;
        panel(g, eicasX, 6, 142, 91, 0xFF56B9D2);
        int engine1 = systems & 7;
        int engine2 = (systems >> 3) & 7;
        int n1 = (systems >> 12) & 0x7F;
        int flap = (systems >> 9) & 7;
        boolean spoilers = (systems & (1 << 8)) != 0;
        int windKnots = (systems >> 19) & 0x7F;
        int windDirection = ((systems >> 26) & 0x3F) * 5625 / 1000;
        int food = mc.player.getFoodData().getFoodLevel();
        g.text(mc.font, "EICAS", eicasX + 5, 10, 0xFF72E6F5, true);
        g.text(mc.font, "ENG 1 " + engineTag(engine1), eicasX + 5, 23, engineColor(engine1), true);
        g.text(mc.font, "ENG 2 " + engineTag(engine2), eicasX + 75, 23, engineColor(engine2), true);
        g.text(mc.font, "N1", eicasX + 5, 37, 0xFFD5E0E8, true);
        gauge(g, eicasX + 25, 39, 109, 6, n1 / 100.0f, n1 > 90 ? 0xFFFFA04A : 0xFF62D89B);
        g.text(mc.font, "FUEL", eicasX + 5, 51, 0xFFD5E0E8, true);
        gauge(g, eicasX + 33, 53, 101, 6, food / 20.0f, food <= 6 ? 0xFFFF5148 : 0xFFFFCE52);
        g.text(mc.font, gearState == 2 ? "GEAR FAIL" : gearState == 1 ? "GEAR UP" : "GEAR DOWN",
                eicasX + 5, 65, gearColor(), true);
        g.text(mc.font, "FLAP", eicasX + 5, 79, 0xFFD5E0E8, true);
        for (int i = 0; i < 4; i++) {
            g.fill(eicasX + 35 + i * 12, 80, eicasX + 43 + i * 12, 86,
                    i < flap ? 0xFFFFCE52 : 0xFF364650);
        }
        g.text(mc.font, spoilers ? "SPLR" : "W" + windKnots + " " + windDirection + "°",
                eicasX + 87, 79, spoilers ? 0xFFFFCE52 : 0xFFB9CDD4, true);

        // Radio altimeter / vertical speed display.
        int lowerY = screenHeight - 54;
        panel(g, 6, lowerY, 112, 48, 0xFF56B9D2);
        g.text(mc.font, "RAD ALT", 11, lowerY + 4, 0xFF72E6F5, true);
        int altitudeColor = radioAltitudeFeet < 300 ? 0xFFFF5148 : 0xFFFFFFFF;
        g.text(mc.font, String.format(Locale.ROOT, "%04d FT", radioAltitudeFeet), 61, lowerY + 4, altitudeColor, true);
        gauge(g, 11, lowerY + 19, 102, 6, Math.min(1.0f, radioAltitudeFeet / 2500.0f), altitudeColor);
        g.text(mc.font, verticalSpeedFpm < 0 ? "▼" : "▲", 11, lowerY + 31,
                verticalSpeedFpm < -1000 ? 0xFFFF5148 : 0xFF68E8A5, true);
        g.text(mc.font, String.format(Locale.ROOT, "%+d FPM", verticalSpeedFpm), 25, lowerY + 31,
                verticalSpeedFpm < -1000 ? 0xFFFF5148 : 0xFFD5E0E8, true);

        // Controls sit in their own narrow strip so the instrument screens stay clear.
        int controlsY = screenHeight - 96;
        int controlsWidth = screenWidth - 12;
        panel(g, 6, controlsY, controlsWidth, 34, 0xFF56B9D2);
        String engineAction = phase == FlightPhase.CRASHED ? "RESET" : "START";
        String[][] controls = {
                {"J", engineAction, "L", "CUTOFF", "A/D", "TURN", "E", "INV", "W/S", "THR"},
                {"U", "GEAR", "K", "PUSH", "M", "BRAKE", "X/Z", "FLAP", "O", "SPLR"}
        };
        int cellWidth = (controlsWidth - 12) / 5;
        for (int row = 0; row < controls.length; row++) {
            for (int column = 0; column < 5; column++) {
                drawKeyBadge(g, mc, controls[row][column * 2], controls[row][column * 2 + 1],
                        12 + column * cellWidth, controlsY + 3 + row * 15);
            }
        }

        String alert = primaryWarning();
        if (!alert.isEmpty()) {
            int width = mc.font.width(alert) + 16;
            int alertX = (screenWidth - width) / 2;
            int blink = ((long)(clock * 2.5) & 1L) == 0L ? 0xFFFF5148 : 0xFFB84442;
            int alertColor = phase == FlightPhase.CRASHED ? 0xFFFF5148 : blink;
            int alertY = ndY + 102;
            g.fill(alertX, alertY, alertX + width, alertY + 21, 0xD0181010);
            g.outline(alertX, alertY, width, 21, alertColor);
            g.text(mc.font, alert, alertX + 8, alertY + 6, alertColor, true);
        }
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

    private static void drawRadarGrid(GuiGraphicsExtractor g, int cx, int cy, int radius, double clock) {
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

    private static void drawArtificialHorizon(GuiGraphicsExtractor g, int cx, int cy, int width, int height,
                                               float pitch, float bank) {
        int top = cy - height / 2;
        int bottom = top + height;
        double slope = Math.tan(Math.toRadians(Math.max(-55.0f, Math.min(55.0f, bank))));
        int pitchOffset = Math.round(Math.max(-35.0f, Math.min(35.0f, pitch)) * 0.30f);
        for (int offset = -width / 2; offset < width / 2; offset += 2) {
            int px = cx + offset;
            int horizon = Math.max(top + 1, Math.min(bottom - 1,
                    cy + pitchOffset + (int)Math.round(offset * slope * 0.30)));
            g.fill(px, top, px + 2, horizon, 0xFF287CA0);
            g.fill(px, horizon, px + 2, bottom, 0xFF8B6338);
            g.fill(px, horizon - 1, px + 2, horizon + 1, 0xFFE5F2F2);
        }
        g.fill(cx - 13, cy, cx - 4, cy + 2, 0xFFFFD060);
        g.fill(cx + 4, cy, cx + 13, cy + 2, 0xFFFFD060);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 3, 0xFFFFD060);
    }

    private static void drawKeyBadge(GuiGraphicsExtractor g, Minecraft mc, String key, String label, int x, int y) {
        int keyWidth = Math.max(15, mc.font.width(key) + 6);
        g.fill(x, y, x + keyWidth, y + 13, 0xFF27424D);
        g.outline(x, y, keyWidth, 13, 0xFF57BBD1);
        g.text(mc.font, key, x + 3, y + 3, 0xFFFFFFFF, true);
        g.text(mc.font, label, x + keyWidth + 3, y + 3, 0xFFD5E0E8, true);
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
        if ((warnings & FlightWarning.TCAS_RESOLUTION) != 0) {
            return (warnings & FlightWarning.TCAS_DESCEND) != 0 ? "TCAS: DESCEND" : "TCAS: CLIMB";
        }
        if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) return "TRAFFIC";
        if ((warnings & FlightWarning.DONT_SINK) != 0) return "DON'T SINK";
        if ((warnings & FlightWarning.FUEL_EMPTY) != 0) return "FUEL EMPTY - ENGINES OFF";
        if ((warnings & FlightWarning.ENGINE_FIRE) != 0) return "ENGINE FIRE";
        if ((warnings & FlightWarning.WINDSHEAR) != 0) return "WINDSHEAR";
        if ((warnings & FlightWarning.PULL_UP) != 0) return "PULL UP";
        if ((warnings & FlightWarning.STALL) != 0) return "STALL";
        if ((warnings & FlightWarning.ENGINE_FAILURE) != 0) return "ENGINE FAILURE";
        if ((warnings & FlightWarning.FUEL_LOW) != 0) return "LOW FUEL";
        if ((warnings & FlightWarning.SINK_RATE) != 0) return "SINK RATE";
        if ((warnings & FlightWarning.GLIDESLOPE_HIGH) != 0) return "GLIDESLOPE HIGH";
        if ((warnings & FlightWarning.GLIDESLOPE_LOW) != 0) return "GLIDESLOPE";
        if ((warnings & FlightWarning.BANK_ANGLE) != 0) return "BANK ANGLE";
        if ((warnings & FlightWarning.GEAR_FAILED) != 0) return "GEAR FAILURE";
        if ((warnings & FlightWarning.GEAR_HOT) != 0) return "GEAR HOT";
        if ((warnings & FlightWarning.GEAR_UP) != 0) return "GEAR";
        if ((warnings & FlightWarning.TOO_LOW_FLAPS) != 0) return "TOO LOW FLAPS";
        if ((warnings & FlightWarning.TAKEOFF_CONFIG) != 0) return "TAKEOFF CONFIG";
        if ((warnings & FlightWarning.OVERSPEED) != 0) return "OVERSPEED";
        return "";
    }
}
