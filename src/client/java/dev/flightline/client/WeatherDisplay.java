package dev.flightline.client;

import dev.flightline.flight.WeatherSimulation;
import dev.flightline.network.FlightInstrumentPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared deterministic weather-cell renderer for the radar scope and weather map. */
final class WeatherDisplay {
    private static final int CELL_BLOCKS = 128;
    /** Multiplayer clients never receive the world seed, so radar cells use a shared stable seed. */
    static final long CELL_SEED = 0x464C4947485452L;

    private WeatherDisplay() {
    }

    static void drawRadarCells(GuiGraphicsExtractor g, int cx, int cy, int radius, int rangeBlocks,
                               int ownX, int ownZ, boolean headingUp, float heading,
                               long gameTime, long seed,
                               FlightInstrumentPayload.WeatherGuidance guidance) {
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

        double windRadians = Math.toRadians(guidance.windDirectionDegrees());
        double windDx = Math.sin(windRadians);
        double windDz = -Math.cos(windRadians);
        if (headingUp) {
            double rx = windDx * cos - windDz * sin;
            double rz = windDx * sin + windDz * cos;
            windDx = rx;
            windDz = rz;
        }

        g.enableScissor(cx - radius - 1, cy - radius - 1, radius * 2 + 2, radius * 2 + 2);
        try {
            for (int cellX = minX; cellX <= maxX; cellX++) {
                for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                    WeatherSimulation.WeatherCell cell = WeatherSimulation.sampleCell(cellX, cellZ,
                            gameTime, seed, guidance.condition(), guidance.turbulencePercent(),
                            guidance.gustKnots());
                    if (cell.intensity() < 20) continue;
                    double dx = (cellX + 0.5) * CELL_BLOCKS - ownX;
                    double dz = (cellZ + 0.5) * CELL_BLOCKS - ownZ;
                    if (headingUp) {
                        double rx = dx * cos - dz * sin;
                        double rz = dx * sin + dz * cos;
                        dx = rx;
                        dz = rz;
                    }
                    if (Math.hypot(dx, dz) > rangeBlocks + CELL_BLOCKS * 0.5) continue;
                    int px = cx + (int) Math.round(dx * scale);
                    int py = cy + (int) Math.round(dz * scale);
                    int half = Math.max(2, (int) Math.round(CELL_BLOCKS * scale * 0.5f));

                    WeatherSimulation.WeatherCell history = WeatherSimulation.sampleCell(cellX, cellZ,
                            Math.max(0L, gameTime - 240L), seed, guidance.condition(),
                            guidance.turbulencePercent(), guidance.gustKnots());
                    if (history.intensity() >= 28) {
                        int trailX = px - Math.round((float) (windDx * half * 0.85f));
                        int trailY = py - Math.round((float) (windDz * half * 0.85f));
                        drawCellGlyph(g, trailX, trailY, Math.max(1, half - 2), history, true);
                    }
                    drawCellGlyph(g, px, py, half, cell, false);
                }
            }
        } finally {
            g.disableScissor();
        }
    }

    /**
     * Draws one intensity cell as a layered returns blob: a soft outer return, a brighter contour,
     * and a tight core for heavier rain or storm cells. The dim variant is the trailing history.
     */
    static void drawCellGlyph(GuiGraphicsExtractor g, int px, int py, int half,
                              WeatherSimulation.WeatherCell cell, boolean history) {
        int intensity = cell.intensity();
        boolean storm = cell.condition() == 2;
        if (history) {
            g.fill(px - half, py - half, px + half, py + half,
                    storm ? 0x4C8B3140 : 0x405B8E66);
            if (intensity >= 60) {
                int core = Math.max(1, half / 2);
                g.fill(px - core, py - core, px + core, py + core,
                        storm ? 0x66B84330 : 0x6080B060);
            }
            return;
        }

        g.fill(px - half, py - half, px + half, py + half, cellColor(cell));
        int contour = storm ? (intensity >= 80 ? 0xFFF0D7B0 : 0xD8D85838)
                : (intensity >= 50 ? 0xC0A8CF80 : 0x8055A070);
        g.fill(px - half, py - half, px + half, py - half + 1, contour);
        g.fill(px - half, py + half - 1, px + half, py + half, contour);
        g.fill(px - half, py - half, px - half + 1, py + half, contour);
        g.fill(px + half - 1, py - half, px + half, py + half, contour);

        if (intensity >= 44) {
            int inner = Math.max(1, half - 2);
            g.fill(px - inner, py - inner, px + inner, py + inner,
                    storm ? 0xD8D85838 : 0xC090C070);
        }
        if (intensity >= 68) {
            int core = Math.max(1, half / 2);
            g.fill(px - core, py - core, px + core, py + core,
                    storm ? 0xF0F05026 : 0xE0D7A040);
        }
        if (storm && intensity >= 86) {
            g.fill(px - 1, py - Math.max(2, half / 2), px + 1, py + Math.max(2, half / 2), 0xFFFFE8A8);
            g.fill(px - Math.max(1, half / 3), py - 1, px + Math.max(1, half / 3), py + 1, 0xFFFFE8A8);
        }
    }

    private static int cellColor(WeatherSimulation.WeatherCell cell) {
        int intensity = cell.intensity();
        if (cell.condition() == 2) {
            if (intensity >= 88) return 0xC8B31E22;
            if (intensity >= 74) return 0xB8E05026;
            return 0x8AA83D82;
        }
        if (cell.condition() == 1) {
            if (intensity >= 70) return 0xA8D79A26;
            if (intensity >= 48) return 0x96B8A532;
            return 0x805B9B65;
        }
        return 0x4B3E8B62;
    }
}
