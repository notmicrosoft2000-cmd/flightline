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
                if (Math.hypot(dx, dz) > rangeBlocks + CELL_BLOCKS) continue;
                int px = cx + (int) Math.round(dx * scale);
                int py = cy + (int) Math.round(dz * scale);
                int half = Math.max(2, (int) Math.round(CELL_BLOCKS * scale * 0.5f));
                g.fill(px - half, py - half, px + half, py + half, cellColor(cell));
            }
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
