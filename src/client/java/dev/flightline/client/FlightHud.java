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
    private static float speed, throttle, rotationSpeed, bankDegrees, pitchDegrees, headingDegrees;
    private static int radioAltitudeFeet, verticalSpeedFpm, gearState, systems, warnings;

    private FlightHud() {}
    static float throttle() { return throttle; }
    static void update(FlightStatePayload s) {
        enabled = s.enabled(); phase = FlightPhase.fromId(s.phase()); speed = s.speed();
        throttle = s.throttle(); rotationSpeed = s.rotationSpeed();
    }
    static void updateInstruments(FlightInstrumentPayload i) {
        radioAltitudeFeet = i.radioAltitudeFeet(); verticalSpeedFpm = i.verticalSpeedFpm();
        bankDegrees = i.bankDegrees(); pitchDegrees = i.pitchDegrees(); headingDegrees = i.headingDegrees(); gearState = i.gearState();
        systems = i.systems(); warnings = i.warnings();
    }
    static void clear() {
        enabled = false; phase = FlightPhase.OFF; speed = throttle = rotationSpeed = bankDegrees = pitchDegrees = 0;
        radioAltitudeFeet = verticalSpeedFpm = gearState = systems = warnings = 0; headingDegrees = 0.0f;
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null) return;
        int sw = g.guiWidth(), sh = g.guiHeight(), y = 6;
        panel(g, 6, y, 94, 38);
        int phaseColor = phase == FlightPhase.STALL || phase == FlightPhase.CRASHED
                ? 0xFFFF5548 : 0xFF74E8F5;
        g.text(mc.font, phase.label(), 10, y + 3, phaseColor, true);
        int airspeed = knots(speed);
        g.text(mc.font, String.format(Locale.ROOT, "IAS %03d", airspeed), 10, y + 15, 0xFFFFFFFF, true);
        gauge(g, 10, y + 29, 84, 5, Math.min(1.0f, speed / (float)FlightTuning.MAX_AIRSPEED), phaseColor);

        int ndx = (sw - 108) / 2;
        panel(g, ndx, y, 108, 38);
        int heading = Math.floorMod(Math.round(headingDegrees), 360);
        g.text(mc.font, "HDG " + String.format(Locale.ROOT, "%03d", heading), ndx + 4, y + 3, 0xFF74E8F5, true);
        for (int tick = -2; tick <= 2; tick++) {
            int tickHeading = Math.floorMod(heading + tick * 30, 360);
            int tx = ndx + 54 + tick * 18;
            String tickLabel = tickHeading % 90 == 0 ? switch (tickHeading) {
                case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; default -> "W";
            } : String.format(Locale.ROOT, "%02d", tickHeading / 10);
            int labelWidth = mc.font.width(tickLabel);
            g.text(mc.font, tickLabel, tx - labelWidth / 2, y + 13, 0xFFD5E0E8, true);
            g.fill(tx, y + 24, tx + 1, y + (tick == 0 ? 32 : 28),
                    tick == 0 ? 0xFFFFD060 : 0xFF83F3FF);
        }

        int pfdx = sw - 82;
        panel(g, pfdx, y, 76, 38);
        drawArtificialHorizon(g, pfdx + 38, y + 16, 58, 18, pitchDegrees, bankDegrees);
        g.text(mc.font, String.format(Locale.ROOT, "%+.0f° / %+.0f°", pitchDegrees, bankDegrees),
                pfdx + 4, y + 28, 0xFFFFFFFF, true);

        int leftY = sh - 52;
        panel(g, 6, leftY, 88, 46);
        g.text(mc.font, "RA", 10, leftY + 3, 0xFF74E8F5, true);
        int altColor = radioAltitudeFeet < 300 ? 0xFFFF5548 : 0xFFFFFFFF;
        g.text(mc.font, String.format(Locale.ROOT, "%04d FT", radioAltitudeFeet), 30, leftY + 3, altColor, true);
        gauge(g, 10, leftY + 17, 78, 5, Math.min(1.0f, radioAltitudeFeet / 2500.0f), altColor);
        g.text(mc.font, verticalSpeedFpm < 0 ? "▼" : "▲", 10, leftY + 27,
                verticalSpeedFpm < -1000 ? 0xFFFF5548 : 0xFF83F3FF, true);
        g.text(mc.font, String.format(Locale.ROOT, "%+d FPM", verticalSpeedFpm), 24, leftY + 27,
                verticalSpeedFpm < -1000 ? 0xFFFF5548 : 0xFFD5E0E8, true);

        int systemsX = sw - 146, systemsY = sh - 61;
        panel(g, systemsX, systemsY, 140, 55);
        int eng1 = systems & 7, eng2 = (systems >> 3) & 7;
        int n1 = (systems >> 12) & 0x7F;
        int flap = (systems >> 9) & 7;
        boolean brakes = (systems & (1 << 6)) != 0, pushback = (systems & (1 << 7)) != 0;
        boolean spoilers = (systems & (1 << 8)) != 0;
        int food = mc.player.getFoodData().getFoodLevel();
        int gearColor = gearState == 2 ? 0xFFFF5548 : gearState == 1 ? 0xFFFFD060 : 0xFF82F58A;
        g.text(mc.font, gearState == 2 ? "GEAR FAIL" : gearState == 1 ? "GEAR UP" : "GEAR DN",
                systemsX + 4, systemsY + 3, gearColor, true);
        g.text(mc.font, brakes ? "BRAKE" : pushback ? "PUSH" : spoilers ? "SPLR" : "FLAP",
                systemsX + 99, systemsY + 3, 0xFFFFD060, true);
        g.text(mc.font, "N1", systemsX + 4, systemsY + 16, 0xFFD5E0E8, true);
        gauge(g, systemsX + 24, systemsY + 18, 110, 5, n1 / 100.0f,
                n1 > 90 ? 0xFFFFA04A : 0xFF82F58A);
        g.text(mc.font, "FUEL", systemsX + 4, systemsY + 28, 0xFFD5E0E8, true);
        gauge(g, systemsX + 32, systemsY + 30, 102, 5, food / 20.0f,
                food <= 6 ? 0xFFFF5548 : 0xFFFFD060);
        g.text(mc.font, "FLAP", systemsX + 4, systemsY + 40, 0xFFD5E0E8, true);
        for (int n = 0; n < 4; n++) g.fill(systemsX + 34 + n * 13, systemsY + 41,
                systemsX + 43 + n * 13, systemsY + 47, n < flap ? 0xFFFFD060 : 0xFF364650);
        g.text(mc.font, "E1" + engineTag(eng1) + " E2" + engineTag(eng2), systemsX + 4, systemsY + 40,
                eng1 >= 3 || eng2 >= 3 ? 0xFFFF5548 : 0xFF83F3FF, true);
        boolean enginesOn = eng1 == 1 || eng1 == 2 || eng2 == 1 || eng2 == 2;
        g.text(mc.font, enginesOn ? "INV LOCK" : "INV READY", systemsX + 83, systemsY + 40,
                enginesOn ? 0xFFFFD060 : 0xFF82F58A, true);

        int controlX = 6, controlY = sh - 96, controlW = sw - 12;
        panel(g, controlX, controlY, controlW, 34);
        String engineAction = phase == FlightPhase.CRASHED ? "RESET" : "START";
        String[][] controls = {{"J", engineAction, "L", "CUTOFF", "A/D", "TURN", "E", "INV", "W/S", "THR"},
                {"U", "GEAR", "K", "PUSH", "M", "BRAKE", "X/Z", "FLAP", "O", "SPLR"}};
        int cellW = (controlW - 12) / 5;
        for (int row = 0; row < controls.length; row++) {
            for (int column = 0; column < 5; column++) {
                int cx = controlX + 6 + column * cellW;
                int cy = controlY + 3 + row * 15;
                keyBadge(g, mc, controls[row][column * 2], controls[row][column * 2 + 1], cx, cy);
            }
        }

        String alert = primaryWarning();
        if (!alert.isEmpty()) {
            int width = mc.font.width(alert) + 14, ax = (sw - width) / 2;
            int color = phase == FlightPhase.CRASHED ? 0xFFFF5548 : 0xFFFFB83F;
            g.fill(ax, 48, ax + width, 67, 0xD0501010);
            g.outline(ax, 48, width, 19, color);
            g.text(mc.font, alert, ax + 7, 53, color, true);
        }
    }

    private static void keyBadge(GuiGraphicsExtractor g, Minecraft mc, String key, String label, int x, int y) {
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
    private static void drawArtificialHorizon(GuiGraphicsExtractor g, int cx, int cy, int width, int height,
                                               float pitch, float bank) {
        int left = cx - width / 2, top = cy - height / 2, bottom = top + height;
        double slope = Math.tan(Math.toRadians(Math.max(-60.0f, Math.min(60.0f, bank))));
        int pitchOffset = Math.round(Math.max(-20.0f, Math.min(20.0f, pitch)) * 0.16f);
        for (int offset = -width / 2; offset < width / 2; offset += 2) {
            int px = cx + offset;
            int horizon = Math.max(top + 1, Math.min(bottom - 1,
                    cy + pitchOffset + (int)Math.round(offset * slope * 0.22)));
            g.fill(px, top, px + 2, horizon, 0xFF287CA0);
            g.fill(px, horizon, px + 2, bottom, 0xFF8B6338);
            g.fill(px, horizon - 1, px + 2, horizon + 1, 0xFFFFFFFF);
        }
        g.fill(cx - 11, cy, cx - 3, cy + 2, 0xFFFFD060);
        g.fill(cx + 3, cy, cx + 11, cy + 2, 0xFFFFD060);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 3, 0xFFFFD060);
    }
    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xB8101820); g.outline(x, y, w, h, 0xC0429CAF);
    }
    private static int knots(float speed) { return Math.round(speed * (float) FlightTuning.KNOTS_PER_BLOCK_PER_TICK); }
    private static String engineTag(int state) {
        return switch (state) { case 1 -> "S"; case 2 -> "R"; case 3 -> "F"; case 4 -> "!"; default -> "O"; };
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
