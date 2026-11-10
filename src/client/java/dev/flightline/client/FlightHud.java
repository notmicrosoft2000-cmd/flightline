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
        int sw = g.guiWidth(), sh = g.guiHeight(), x = 6, y = 6;
        panel(g, x, y, 96, 40);
        g.text(mc.font, phase.label(), x + 4, y + 3,
                phase == FlightPhase.STALL || phase == FlightPhase.CRASHED ? 0xFFFF5548 : 0xFF74E8F5, true);
        g.text(mc.font, String.format(Locale.ROOT, "IAS %03d KT", knots(speed)), x + 4, y + 15, 0xFFFFFFFF, true);
        g.text(mc.font, phase == FlightPhase.ROTATE ? "ROTATE ↑" : String.format(Locale.ROOT, "VR %03d KT", knots(rotationSpeed)),
                x + 4, y + 27, 0xFFFFE28A, true);

        int ndx = (sw - 88) / 2;
        panel(g, ndx, y, 88, 40);
        int heading = Math.floorMod(Math.round(headingDegrees), 360);
        g.text(mc.font, "ND  HDG " + String.format(Locale.ROOT, "%03d", heading), ndx + 4, y + 3, 0xFF74E8F5, true);
        for (int tick = -2; tick <= 2; tick++) {
            int tickHeading = Math.floorMod(heading + tick * 30, 360);
            int tx = ndx + 44 + tick * 15;
            String tickLabel = tickHeading % 90 == 0 ? switch (tickHeading) {
                case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; default -> "W";
            } : String.format(Locale.ROOT, "%02d", tickHeading / 10);
            int labelWidth = mc.font.width(tickLabel);
            g.text(mc.font, tickLabel, tx - labelWidth / 2, y + 15, 0xFFD5E0E8, true);
            g.fill(tx, y + 25, tx + 1, y + (tick == 0 ? 32 : 29),
                    tick == 0 ? 0xFFFFD060 : 0xFF83F3FF);
        }

        x = sw - 98; panel(g, x, y, 92, 40);
        g.text(mc.font, "PFD / ATTITUDE", x + 4, y + 3, 0xFF74E8F5, true);
        drawArtificialHorizon(g, x + 46, y + 22, 62, 18, pitchDegrees, bankDegrees);
        g.text(mc.font, String.format(Locale.ROOT, "P %+.0f° B %+.0f°", pitchDegrees, bankDegrees), x + 4, y + 31, 0xFFFFFFFF, true);

        y = sh - 62; x = 6; panel(g, x, y, 112, 54);
        g.text(mc.font, "RAD ALT", x + 4, y + 3, 0xFF74E8F5, true);
        g.text(mc.font, String.format(Locale.ROOT, "%04d FT", radioAltitudeFeet), x + 4, y + 15, 0xFFFFFFFF, true);
        g.text(mc.font, String.format(Locale.ROOT, "V/S %+05d FPM", verticalSpeedFpm), x + 4, y + 27,
                verticalSpeedFpm < -1000 ? 0xFFFF5C4D : 0xFFD5E0E8, true);
        boolean gsHigh = (warnings & FlightWarning.GLIDESLOPE_HIGH) != 0;
        boolean gsLow = (warnings & FlightWarning.GLIDESLOPE_LOW) != 0;
        String glide = gsHigh ? "GS HIGH" : gsLow ? "GS LOW"
                : (phase == FlightPhase.AIRBORNE || phase == FlightPhase.STALL)
                && gearState == 0 && radioAltitudeFeet < 1500 && verticalSpeedFpm < -150 ? "GS ON" : "GS ---";
        g.text(mc.font, glide, x + 4, y + 40, gsHigh || gsLow ? 0xFFFFD060 : 0xFF82F58A, true);

        int wx = sw - 148, wy = sh - 98; panel(g, wx, wy, 142, 92);
        int eng1 = systems & 7, eng2 = (systems >> 3) & 7;
        int n1 = (systems >> 12) & 0x7F, wind = (systems >> 19) & 0x7F;
        int direction = (systems >> 26) & 0x3F, flap = (systems >> 9) & 7;
        boolean brakes = (systems & (1 << 6)) != 0, pushback = (systems & (1 << 7)) != 0;
        boolean spoilers = (systems & (1 << 8)) != 0;
        int food = mc.player.getFoodData().getFoodLevel();
        int gearColor = gearState == 2 ? 0xFFFF5548 : gearState == 1 ? 0xFFFFD060 : 0xFF82F58A;
        String gear = gearState == 2 ? "GEAR FAIL" : gearState == 1 ? "GEAR UP" : "GEAR DOWN";
        g.text(mc.font, gear, wx + 4, wy + 3, gearColor, true);
        if (brakes || pushback) g.text(mc.font, brakes ? "BRAKE" : "PUSH", wx + 91, wy + 3, 0xFFFFD060, true);
        bar(g, mc, "THR", throttle, wx + 4, wy + 14, 132, 0xFF58C9E8);
        bar(g, mc, "N1", n1 / 100.0f, wx + 4, wy + 25, 132, n1 > 90 ? 0xFFFFA04A : 0xFF82F58A);
        bar(g, mc, "FUEL", food / 20.0f, wx + 4, wy + 36, 132,
                food <= 6 ? 0xFFFF5548 : 0xFFFFD060);
        g.text(mc.font, "FLAP", wx + 4, wy + 49, 0xFFD5E0E8, true);
        for (int n = 0; n < 4; n++) g.fill(wx + 35 + n * 10, wy + 50, wx + 42 + n * 10, wy + 56,
                n < flap ? 0xFFFFD060 : 0xFF364650);
        g.text(mc.font, "SPLR " + (spoilers ? "ON" : "OFF"), wx + 82, wy + 49,
                spoilers ? 0xFFFFD060 : 0xFF9DAEB8, true);
        g.text(mc.font, String.format(Locale.ROOT, "E1%s E2%s W%02d %03d°",
                        engineTag(eng1), engineTag(eng2), wind, Math.round(direction * 5.625)), wx + 4, wy + 61,
                eng1 >= 3 || eng2 >= 3 ? 0xFFFF5548 : 0xFFD5E0E8, true);
        boolean enginesOn = eng1 == 1 || eng1 == 2 || eng2 == 1 || eng2 == 2;
        g.text(mc.font, enginesOn ? "INV LOCK · L CUTOFF" : "E INVENTORY · A/D BANK",
                wx + 4, wy + 74, 0xFF83F3FF, true);

        String alert = primaryWarning();
        if (!alert.isEmpty()) {
            int width = mc.font.width(alert) + 14, ax = (sw - width) / 2;
            g.fill(ax, 6, ax + width, 25, 0xD0501010); g.outline(ax, 6, width, 19, 0xFFFF5548);
            g.text(mc.font, alert, ax + 7, 11, 0xFFFF665C, true);
        }
    }

    private static void bar(GuiGraphicsExtractor g, Minecraft mc, String label, float value, int x, int y, int width, int color) {
        g.text(mc.font, label, x, y, 0xFFD5E0E8, true);
        int bx = x + 27, by = y + 2, bw = width - 28;
        g.fill(bx, by, bx + bw, by + 6, 0xFF26343D);
        g.fill(bx, by, bx + Math.max(0, Math.min(bw, Math.round(bw * value))), by + 6, color);
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
        if (phase == FlightPhase.CRASHED) return "AIRFRAME CRASH";
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
