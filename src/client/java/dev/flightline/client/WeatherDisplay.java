package dev.flightline.client;

import dev.flightline.flight.WeatherField;
import dev.flightline.flight.WeatherSimulation;
import dev.flightline.network.FlightInstrumentPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared deterministic weather-cell renderer for the radar scope and weather map. */
final class WeatherDisplay {
    static final int CELL_BLOCKS = 128;
    static final long CELL_SEED = WeatherField.WEATHER_SEED;

    static final int[] DBZ_COLORS = {
            0xC43E8E58, // light
            0xD8B8A532, // moderate
            0xE8E05026, // heavy
            0xF0B31E22  // extreme
    };
    private static final int HAIL_COLOR = 0xF0D8B0FF;
    private static final int LIGHT_COLOR = 0x8A3E8E58;

    private WeatherDisplay() {
    }

    static void drawRadarCells(GuiGraphicsExtractor g, int cx, int cy, int radius, int rangeBlocks,
                               int ownX, int ownZ, boolean headingUp, float heading,
                               long gameTime, long seed,
                               FlightInstrumentPayload.WeatherGuidance guidance) {
        drawRadarCells(g, cx, cy, radius, rangeBlocks, ownX, ownZ, headingUp, heading,
                gameTime, seed, guidance, false, null);
    }

    static void drawRadarCells(GuiGraphicsExtractor g, int cx, int cy, int radius, int rangeBlocks,
                               int ownX, int ownZ, boolean headingUp, float heading,
                               long gameTime, long seed,
                               FlightInstrumentPayload.WeatherGuidance guidance,
                               boolean labels, Font font) {
        double scale = radius / (double) rangeBlocks;
        int margin = Math.max(1, (int) Math.round(CELL_BLOCKS * scale));
        int minX = Math.floorDiv(ownX - rangeBlocks - margin, CELL_BLOCKS);
        int maxX = Math.floorDiv(ownX + rangeBlocks + margin, CELL_BLOCKS);
        int minZ = Math.floorDiv(ownZ - rangeBlocks - margin, CELL_BLOCKS);
        int maxZ = Math.floorDiv(ownZ + rangeBlocks + margin, CELL_BLOCKS);

        double cos = 1.0;
        double sin = 0.0;
        if (headingUp) {
            double radians = Math.toRadians(-heading);
            cos = Math.cos(radians);
            sin = Math.sin(radians);
        }

        double[] wind = WeatherField.windVector(guidance.windDirectionDegrees(), 1.0);
        double[] windScreen = rotate(wind[0], wind[1], cos, sin, headingUp);

        g.enableScissor(cx - radius - 1, cy - radius - 1, radius * 2 + 2, radius * 2 + 2);
        try {
            int labelsDrawn = 0;
            for (int cellX = minX; cellX <= maxX; cellX++) {
                for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                    WeatherSimulation.WeatherCell cell = WeatherSimulation.sampleCell(cellX, cellZ,
                            gameTime, seed, guidance.condition(), guidance.turbulencePercent(),
                            guidance.gustKnots());
                    if (cell.intensity() < 14) continue;

                    double dx = (cellX + 0.5) * CELL_BLOCKS - ownX;
                    double dz = (cellZ + 0.5) * CELL_BLOCKS - ownZ;
                    double[] screen = rotate(dx, dz, cos, sin, headingUp);
                    double distancePx = Math.hypot(screen[0] * scale, screen[1] * scale);
                    if (distancePx > radius - 1.0) continue;

                    int px = cx + (int) Math.round(screen[0] * scale);
                    int py = cy + (int) Math.round(screen[1] * scale);
                    int baseHalf = Math.max(3, (int) Math.round(CELL_BLOCKS * scale * 0.5));
                    int half = Math.min(baseHalf, Math.max(2, (int) (radius - distancePx)));
                    if (half < 2) continue;

                    WeatherSimulation.WeatherCell history = WeatherSimulation.sampleCell(cellX, cellZ,
                            Math.max(0L, gameTime - 240L), seed, guidance.condition(),
                            guidance.turbulencePercent(), guidance.gustKnots());
                    if (history.intensity() >= 18) {
                        double[] motion = cell.motionDx() == 0.0 && cell.motionDz() == 0.0
                                ? windScreen : rotate(cell.motionDx(), cell.motionDz(), cos, sin, headingUp);
                        int trailX = px - Math.round((float) (motion[0] * half * 1.8f));
                        int trailY = py - Math.round((float) (motion[1] * half * 1.8f));
                        if (Math.hypot(trailX - cx, trailY - cy) < radius - 2) {
                            drawCellGlyph(g, trailX, trailY, Math.max(1, half - 2), history, true);
                        }
                    }

                    drawCellGlyph(g, px, py, half, cell, false);

                    if (cell.intensity() >= 55) {
                        double[] motion = rotate(cell.motionDx(), cell.motionDz(), cos, sin, headingUp);
                        int mx = px + Math.round((float) (motion[0] * 10.0f));
                        int my = py + Math.round((float) (motion[1] * 10.0f));
                        line(g, px, py, mx, my, 0xB0E8F5FF);
                        line(g, mx, my, mx - Math.round((float) (motion[0] * 3.0f - motion[1] * 2.0f)),
                                my - Math.round((float) (motion[1] * 3.0f + motion[0] * 2.0f)),
                                0xB0E8F5FF);
                    }

                    if (labels && font != null && cell.condition() == 2
                            && cell.intensity() >= 80 && labelsDrawn < 6) {
                        String label = cell.hail() ? "HAIL" : "TS " + cell.intensity();
                        if (px + half + 2 + font.width(label) < cx + radius - 2) {
                            g.text(font, label, px + half + 2, py - half, 0xFFFFE3B0, false);
                            labelsDrawn++;
                        }
                    }
                }
            }
        } finally {
            g.disableScissor();
        }
    }

    /**
     * Draws one intensity cell as layered returns: a soft outer return, a bright contour and a
     * tight core for heavier rain or storm cells. The dim variant is the trailing history.
     */
    static void drawCellGlyph(GuiGraphicsExtractor g, int px, int py, int half,
                              WeatherSimulation.WeatherCell cell, boolean history) {
        int intensity = cell.intensity();
        boolean storm = cell.condition() == 2;
        if (history) {
            octagon(g, px, py, half,
                    cell.hail() ? 0x505FA05C : storm ? 0x54B84330 : 0x485B8E66);
            if (intensity >= 60) {
                octagon(g, px, py, Math.max(1, half / 2),
                        storm ? 0x66E05026 : 0x603E8E58);
            }
            return;
        }

        // Octagonal returns keep the field-manual look without the harsh square-block seams.
        int contour = storm ? 0xFF705010 : intensity >= 50 ? 0xFF7A5A18 : 0xFF1E4A32;
        octagon(g, px, py, half + 1, contour);
        octagon(g, px, py, half, cellColor(cell));

        if (intensity >= 44) {
            octagon(g, px, py, Math.max(1, half - 2),
                    storm ? 0xB8E05026 : 0x90B8A532);
        }
        if (intensity >= 68) {
            octagon(g, px, py, Math.max(1, half / 2),
                    cell.hail() ? HAIL_COLOR : storm ? 0xE8B31E22 : 0xC8B8A532);
        }
        if (cell.lightning()) {
            int span = Math.max(2, half / 2);
            g.fill(px - 1, py - span, px + 1, py + span, 0xFFFFF0B0);
            g.fill(px - span, py - 1, px + span, py + 1, 0xFFFFF0B0);
        }
        if (cell.hail()) {
            g.fill(px - 2, py - 2, px + 2, py + 2, HAIL_COLOR);
        }
    }

    /** Small octagonal fill: bevelled corners read as weather echoes instead of square tiles. */
    private static void octagon(GuiGraphicsExtractor g, int px, int py, int half, int color) {
        if (half <= 1) {
            g.fill(px - half, py - half, px + half + 1, py + half + 1, color);
            return;
        }
        int cut = Math.max(1, half / 3);
        for (int dy = -half; dy <= half; dy++) {
            int excess = Math.abs(dy) - (half - cut);
            int inset = Math.max(0, excess);
            int span = half - inset;
            if (span < 0) continue;
            g.fill(px - span, py + dy, px + span + 1, py + dy + 1, color);
        }
    }

    private static int cellColor(WeatherSimulation.WeatherCell cell) {
        int intensity = cell.intensity();
        if (cell.hail()) return HAIL_COLOR;
        if (cell.condition() == 2) {
            if (intensity >= 90) return DBZ_COLORS[3];
            if (intensity >= 78) return DBZ_COLORS[2];
            return DBZ_COLORS[1];
        }
        if (cell.condition() == 1) {
            if (intensity >= 68) return DBZ_COLORS[1];
            return intensity >= 48 ? DBZ_COLORS[0] : LIGHT_COLOR;
        }
        return LIGHT_COLOR;
    }

    private static double[] rotate(double x, double z, double cos, double sin, boolean enabled) {
        if (!enabled) return new double[]{x, z};
        return new double[]{x * cos - z * sin, x * sin + z * cos};
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }
}
