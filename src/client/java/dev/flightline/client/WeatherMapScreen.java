package dev.flightline.client;

import dev.flightline.flight.WeatherField;
import dev.flightline.flight.WeatherSimulation;
import dev.flightline.network.FlightInstrumentPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Standalone weather map with radar, pressure and wind layers over one deterministic field. */
public final class WeatherMapScreen extends Screen {
    private static final int BASE_WIDTH = 560;
    private static final int BASE_HEIGHT = 380;
    private static final int[] RANGES = {512, 1024, 2048, 4096};
    private static final String[] LAYER_NAMES = {"RADAR", "PRESSURE", "WIND"};

    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private int rangeIndex = 1;
    private int layerIndex;
    private boolean headingUp;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int scopeLeft;
    private int scopeTop;
    private int scopeWidth;
    private int scopeHeight;
    private int sidebarLeft;
    private int sidebarTop;
    private int sidebarWidth;
    private int sidebarHeight;
    private Button layerButton;
    private float scanAngle;
    private long lastFrameNanos = System.nanoTime();

    private record CellStats(int count, int maxIntensity, int maxTop, double nearestBlocks,
                             double nearestBearing) {
    }

    public WeatherMapScreen() {
        super(Component.literal("Flight Line Weather Map"));
    }

    @Override
    protected void init() {
        super.init();
        scaler.update(width, height);
        panelWidth = BASE_WIDTH;
        panelHeight = BASE_HEIGHT;
        panelLeft = 8;
        panelTop = 8;

        scopeLeft = panelLeft + 12;
        scopeTop = panelTop + 34;
        scopeWidth = 250;
        scopeHeight = 250;
        sidebarLeft = scopeLeft + scopeWidth + 14;
        sidebarTop = scopeTop;
        sidebarWidth = panelWidth - (sidebarLeft - panelLeft) - 12;
        sidebarHeight = scopeHeight;

        int buttonY = panelTop + panelHeight - 36;
        int x = panelLeft + 12;
        addRenderableWidget(Button.builder(Component.literal("RANGE -"), b -> changeRange(-1))
                .bounds(x, buttonY, 74, 20).build());
        x += 80;
        addRenderableWidget(Button.builder(Component.literal("RANGE +"), b -> changeRange(1))
                .bounds(x, buttonY, 74, 20).build());
        x += 80;
        addRenderableWidget(Button.builder(Component.literal(headingUp ? "HDG UP" : "NORTH"), b -> {
                    headingUp = !headingUp;
                    b.setMessage(Component.literal(headingUp ? "HDG UP" : "NORTH"));
                })
                .bounds(x, buttonY, 74, 20).build());
        x += 80;
        layerButton = Button.builder(Component.literal("LAYER: " + LAYER_NAMES[layerIndex]),
                        b -> cycleLayer())
                .bounds(x, buttonY, 118, 20).build();
        addRenderableWidget(layerButton);
        x += 124;
        addRenderableWidget(Button.builder(Component.literal("TRAFFIC"), b ->
                        Minecraft.getInstance().gui.setScreen(new FlightRadarScreen()))
                .bounds(x, buttonY, 74, 20).build());
        addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                .bounds(panelLeft + panelWidth - 82, buttonY, 70, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        animate();
        scaler.update(width, height);
        scaler.push(graphics);
        graphics.fill(0, 0, scaler.width(), scaler.height(), 0xB8000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121A22);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF4EB8C8);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 24, 0xFF1C2733);
        graphics.fill(panelLeft, panelTop + 23, panelLeft + panelWidth, panelTop + 24, 0xFF4EB8C8);
        graphics.centeredText(font, "WEATHER RADAR  ·  AREA COVERAGE",
                panelLeft + panelWidth / 2, panelTop + 7, 0xFF72E6F5);

        int cx = scopeLeft + scopeWidth / 2;
        int cy = scopeTop + scopeHeight / 2;
        int radius = Math.min(scopeWidth, scopeHeight) / 2 - 8;
        int ownX = ownX();
        int ownZ = ownZ();
        var level = Minecraft.getInstance().level;
        long gameTime = level == null ? 0L : level.getGameTime();
        var weather = FlightHud.weatherGuidance();

        graphics.fill(scopeLeft, scopeTop, scopeLeft + scopeWidth, scopeTop + scopeHeight, 0xFF07100D);
        graphics.outline(scopeLeft, scopeTop, scopeWidth, scopeHeight, 0xFF3D6C58);
        drawScopeGrid(graphics, cx, cy, radius);
        drawVisibilityHaze(graphics, weather.visibilityBlocks());

        if (layerIndex == 0) {
            WeatherDisplay.drawRadarCells(graphics, cx, cy, radius, RANGES[rangeIndex], ownX, ownZ,
                    headingUp, FlightHud.radarOwnHeadingDegrees(), gameTime,
                    WeatherDisplay.CELL_SEED, weather, true, font);
        } else if (layerIndex == 1) {
            drawPressureContours(graphics, cx, cy, radius, ownX, ownZ, gameTime);
        } else {
            drawWindField(graphics, cx, cy, radius, ownX, ownZ, gameTime);
        }

        drawCompassRose(graphics, cx, cy, radius);
        for (int ring = 1; ring <= 3; ring++) {
            int r = radius * ring / 3;
            drawCircle(graphics, cx, cy, r, 0x8A3D6C58);
            graphics.text(font, String.format(Locale.ROOT, "%d NM", RANGES[rangeIndex] * ring / 3 / 10),
                    cx + 3, cy - r - 4, 0x9956B9D2, false);
        }
        drawSweep(graphics, cx, cy, radius);

        if (layerIndex == 0) {
            drawTraffic(graphics, cx, cy, ownX, ownZ);
        }

        graphics.fill(cx - 2, cy - 5, cx + 3, cy + 5, 0xFFFFFFFF);
        graphics.fill(cx - 5, cy - 2, cx + 6, cy + 3, 0xFFFFFFFF);
        graphics.text(font, headingUp ? "HDG UP" : "NORTH UP", scopeLeft + 6, scopeTop + 5,
                0xFF9BC9AE, true);
        graphics.text(font, String.format(Locale.ROOT, "RANGE %.0f NM  ·  %s",
                        RANGES[rangeIndex] / 10.0f, LAYER_NAMES[layerIndex]),
                scopeLeft + 6, scopeTop + scopeHeight - 12, 0xFF9BC9AE, true);

        CellStats stats = cellStats(ownX, ownZ, gameTime, weather);
        var player = Minecraft.getInstance().player;
        double altitude = player == null ? 96.0 : player.getY();
        WeatherField.Sample field = WeatherField.sample(ownX, ownZ, altitude,
                weather.condition() == 2, weather.condition() >= 1, 0.8f, gameTime);
        drawSidebar(graphics, field, stats, weather);
        drawFooter(graphics, weather);

        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    private void drawScopeGrid(GuiGraphicsExtractor g, int cx, int cy, int radius) {
        int color = 0xFF12251F;
        for (int offset = -radius + 32; offset < radius; offset += 32) {
            drawLine(g, cx + offset, cy - radius, cx + offset, cy + radius, color);
            drawLine(g, cx - radius, cy + offset, cx + radius, cy + offset, color);
        }
        drawLine(g, cx - radius, cy, cx + radius, cy, 0x6A315E50);
        drawLine(g, cx, cy - radius, cx, cy + radius, 0x6A315E50);
    }

    private void drawSweep(GuiGraphicsExtractor g, int cx, int cy, int radius) {
        double sweep = Math.toRadians(scanAngle);
        for (int trail = 5; trail >= 1; trail--) {
            double angle = sweep - Math.toRadians(trail * 3.0);
            int tx = cx + (int) Math.round(Math.sin(angle) * radius);
            int ty = cy - (int) Math.round(Math.cos(angle) * radius);
            drawLine(g, cx, cy, tx, ty, trail <= 2 ? 0x604EA87B : 0x304EA87B);
        }
        drawLine(g, cx, cy, cx + (int) Math.round(Math.sin(sweep) * radius),
                cy - (int) Math.round(Math.cos(sweep) * radius), 0xFF65F2A5);
    }

    private void drawTraffic(GuiGraphicsExtractor g, int cx, int cy, int ownX, int ownZ) {
        int range = RANGES[rangeIndex];
        double scale = radius() / (double) range;
        for (var contact : FlightHud.radarContacts()) {
            double dx = contact.x() - ownX;
            double dz = contact.z() - ownZ;
            if (Math.hypot(dx, dz) > range) continue;
            if (headingUp) {
                double radians = Math.toRadians(-FlightHud.radarOwnHeadingDegrees());
                double rx = dx * Math.cos(radians) - dz * Math.sin(radians);
                double rz = dx * Math.sin(radians) + dz * Math.cos(radians);
                dx = rx;
                dz = rz;
            }
            int px = cx + (int) Math.round(dx * scale);
            int py = cy + (int) Math.round(dz * scale);
            int color = contact.airborne() ? 0xFF75E6A5 : 0xFFFFCE52;
            g.fill(px - 2, py - 2, px + 3, py + 3, color);
            if (contact.airborne()) {
                double heading = Math.toRadians(headingUp
                        ? contact.headingDegrees() - FlightHud.radarOwnHeadingDegrees()
                        : contact.headingDegrees());
                int vx = px + (int) Math.round(Math.sin(heading) * 10);
                int vy = py - (int) Math.round(Math.cos(heading) * 10);
                drawLine(g, px, py, vx, vy, color);
                g.text(font, String.format(Locale.ROOT, "%05.0f", contact.altitudeFeet() / 10.0f),
                        px + 4, py - 10, 0xFFA8D8C0, false);
            }
        }
    }

    private void drawVisibilityHaze(GuiGraphicsExtractor g, int visibilityBlocks) {
        int alpha = visibilityBlocks <= 28 ? 0x18 : visibilityBlocks <= 64 ? 0x0E : 0x06;
        int color = (alpha << 24) | 0x9AA7A0;
        int band = 10;
        g.fill(scopeLeft, scopeTop, scopeLeft + scopeWidth, scopeTop + band, color);
        g.fill(scopeLeft, scopeTop + scopeHeight - band, scopeLeft + scopeWidth,
                scopeTop + scopeHeight, color);
        g.fill(scopeLeft, scopeTop, scopeLeft + band, scopeTop + scopeHeight, color);
        g.fill(scopeLeft + scopeWidth - band, scopeTop, scopeLeft + scopeWidth,
                scopeTop + scopeHeight, color);
    }

    private void drawPressureContours(GuiGraphicsExtractor g, int cx, int cy, int radius,
                                      int ownX, int ownZ, long gameTime) {
        int step = 16;
        int steps = radius * 2 / step;
        double[][] pressure = new double[steps + 1][steps + 1];
        double[][] fronts = new double[steps + 1][steps + 1];
        double scale = radius / (double) RANGES[rangeIndex];
        int originX = cx - radius;
        int originY = cy - radius;
        var player = Minecraft.getInstance().player;
        double altitude = player == null ? 96.0 : player.getY();

        for (int gy = 0; gy <= steps; gy++) {
            for (int gx = 0; gx <= steps; gx++) {
                int px = originX + gx * step;
                int py = originY + gy * step;
                if ((px - cx) * (px - cx) + (py - cy) * (py - cy) > radius * radius) {
                    pressure[gx][gy] = Double.NaN;
                    fronts[gx][gy] = Double.NaN;
                    continue;
                }
                double[] world = screenToWorld(px - cx, py - cy, ownX, ownZ, scale);
                WeatherField.Sample sample = WeatherField.sample((int) Math.round(world[0]),
                        (int) Math.round(world[1]), altitude, false, false, 0.8f, gameTime);
                pressure[gx][gy] = sample.lowPressure();
                fronts[gx][gy] = sample.front();
                if (fronts[gx][gy] > 0.46) {
                    g.fill(px - 2, py - 2, px + 2, py + 2, 0x60E08A3C);
                }
            }
        }

        double[] levels = {0.25, 0.40, 0.55, 0.70};
        for (int levelIndex = 0; levelIndex < levels.length; levelIndex++) {
            double level = levels[levelIndex];
            int color = levelIndex == 0 ? 0x805E9CB0 : levelIndex == 1 ? 0xA04EB8C8
                    : levelIndex == 2 ? 0xC0C678FF : 0xD0F06AB8;
            for (int gy = 0; gy < steps; gy++) {
                for (int gx = 0; gx < steps; gx++) {
                    double tl = pressure[gx][gy];
                    double tr = pressure[gx + 1][gy];
                    double br = pressure[gx + 1][gy + 1];
                    double bl = pressure[gx][gy + 1];
                    if (Double.isNaN(tl) || Double.isNaN(tr) || Double.isNaN(br)
                            || Double.isNaN(bl)) continue;
                    double[] points = new double[8];
                    int count = 0;
                    count = addCrossing(points, count, originX + gx * step, originY + gy * step,
                            originX + (gx + 1) * step, originY + gy * step, tl, tr, level);
                    count = addCrossing(points, count, originX + (gx + 1) * step, originY + gy * step,
                            originX + (gx + 1) * step, originY + (gy + 1) * step, tr, br, level);
                    count = addCrossing(points, count, originX + (gx + 1) * step, originY + (gy + 1) * step,
                            originX + gx * step, originY + (gy + 1) * step, br, bl, level);
                    count = addCrossing(points, count, originX + gx * step, originY + (gy + 1) * step,
                            originX + gx * step, originY + gy * step, bl, tl, level);
                    if (count >= 2) drawLine(g, (int) points[0], (int) points[1],
                            (int) points[2], (int) points[3], color);
                    if (count >= 4) drawLine(g, (int) points[4], (int) points[5],
                            (int) points[6], (int) points[7], color);
                }
            }
        }
    }

    private int addCrossing(double[] points, int count, int x0, int y0, int x1, int y1,
                            double v0, double v1, double level) {
        if ((v0 < level) == (v1 < level)) return count;
        double amount = (level - v0) / (v1 - v0);
        points[count++] = x0 + (x1 - x0) * amount;
        points[count++] = y0 + (y1 - y0) * amount;
        return count;
    }

    private void drawWindField(GuiGraphicsExtractor g, int cx, int cy, int radius,
                               int ownX, int ownZ, long gameTime) {
        int step = 28;
        double scale = radius / (double) RANGES[rangeIndex];
        var player = Minecraft.getInstance().player;
        double altitude = player == null ? 96.0 : player.getY();
        for (int gy = -4; gy <= 4; gy++) {
            for (int gx = -5; gx <= 5; gx++) {
                int px = cx + gx * step;
                int py = cy + gy * step;
                if ((px - cx) * (px - cx) + (py - cy) * (py - cy) > (radius - 8) * (radius - 8)) continue;
                double[] world = screenToWorld(px - cx, py - cy, ownX, ownZ, scale);
                WeatherField.Sample sample = WeatherField.sample((int) Math.round(world[0]),
                        (int) Math.round(world[1]), altitude, false, false, 0.8f, gameTime);
                double[] vector = WeatherField.windVector(sample.surfaceWindDirectionDegrees(), 1.0);
                if (headingUp) {
                    double radians = Math.toRadians(-FlightHud.radarOwnHeadingDegrees());
                    double cos = Math.cos(radians);
                    double sin = Math.sin(radians);
                    vector = new double[]{vector[0] * cos - vector[1] * sin,
                            vector[0] * sin + vector[1] * cos};
                }
                int length = 6 + Math.min(12, (int) Math.round(sample.surfaceWindKnots() / 4.0));
                int dx = (int) Math.round(vector[0] * length);
                int dy = (int) Math.round(vector[1] * length);
                int color = sample.surfaceWindKnots() >= 30 ? 0xFF72E6F5
                        : sample.surfaceWindKnots() >= 16 ? 0xFF62D89B : 0x7E8FA8;
                drawLine(g, px - dx / 2, py - dy / 2, px + dx / 2, py + dy / 2, color);
                drawLine(g, px + dx / 2, py + dy / 2,
                        px + dx / 2 - Math.round((float) (vector[0] * 4.0 - vector[1] * 3.0)),
                        py + dy / 2 - Math.round((float) (vector[1] * 4.0 + vector[0] * 3.0)),
                        color);
            }
        }
    }

    private double[] screenToWorld(int dxPx, int dyPx, int ownX, int ownZ, double scale) {
        double dx = dxPx / scale;
        double dz = dyPx / scale;
        if (headingUp) {
            double radians = Math.toRadians(FlightHud.radarOwnHeadingDegrees());
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return new double[]{ownX + dx * cos - dz * sin, ownZ + dx * sin + dz * cos};
        }
        return new double[]{ownX + dx, ownZ + dz};
    }

    private void drawCompassRose(GuiGraphicsExtractor g, int cx, int cy, int radius) {
        String[] labels = {"N", "E", "S", "W"};
        double[] angles = {0, 90, 180, 270};
        float ownHeading = headingUp ? FlightHud.radarOwnHeadingDegrees() : 0.0f;
        for (int i = 0; i < labels.length; i++) {
            double radians = Math.toRadians(angles[i] - ownHeading);
            int px = cx + (int) Math.round(Math.sin(radians) * (radius - 12));
            int py = cy - (int) Math.round(Math.cos(radians) * (radius - 12));
            g.text(font, labels[i], px - 2, py - 3, 0xFF9BC9AE, false);
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, WeatherField.Sample field, CellStats stats,
                             FlightInstrumentPayload.WeatherGuidance weather) {
        g.fill(sidebarLeft, sidebarTop, sidebarLeft + sidebarWidth,
                sidebarTop + sidebarHeight, 0xE3101920);
        g.outline(sidebarLeft, sidebarTop, sidebarWidth, sidebarHeight, 0xFF2A3D49);
        int x = sidebarLeft + 8;
        int width = sidebarWidth - 16;
        int y = sidebarTop + 8;

        section(g, x, y, width, "STORM CELLS");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%d CELLS  ·  MAX %d DBZ",
                stats.count(), stats.maxIntensity()), 0xFFDCE8EE);
        y += 12;
        line(g, x, y, width, String.format(Locale.ROOT, "NEAREST %03.0f° / %.0f NM",
                stats.nearestBearing(), stats.nearestBlocks() / 10.0), 0xFF9BC9AE);
        y += 12;
        line(g, x, y, width, String.format(Locale.ROOT, "TOPS FL%03d", stats.maxTop() / 100),
                0xFF9BC9AE);

        y += 18;
        section(g, x, y, width, "SURFACE WIND");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%03d/%02dG%02d KT",
                weather.windDirectionDegrees(), weather.windSpeedKnots(),
                weather.gustKnots()), 0xFFDCE8EE);
        y += 12;
        drawWindBarb(g, x + 8, y + 4, (int) Math.round(field.surfaceWindDirectionDegrees()),
                field.surfaceWindKnots(), 0xFF72E6F5);
        line(g, x + 34, y, width - 34, "GRADIENT FLOW", 0xFF9BC9AE);

        y += 18;
        section(g, x, y, width, "FLIGHT LEVEL");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%03d/%02d KT",
                (int) Math.round(field.flightWindDirectionDegrees()),
                (int) Math.round(field.flightWindKnots())), 0xFFDCE8EE);
        y += 12;
        line(g, x, y, width, String.format(Locale.ROOT, "QNH %04d  ·  FRZLVL %d FT",
                (int) Math.round(field.qnh()), field.freezingLevelFeet()), 0xFF9BC9AE);

        y += 18;
        section(g, x, y, width, "AIR MASS");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "VIS %.1f SM  ·  %s",
                weather.visibilityBlocks() / 64.0f, turbulenceName(weather.turbulencePercent())),
                weather.turbulencePercent() >= 70 ? 0xFFFF8A72 : 0xFFDCE8EE);
        y += 12;
        line(g, x, y, width, String.format(Locale.ROOT, "RH %d%%  ·  INSTABILITY %d%%",
                (int) Math.round(field.humidity() * 100),
                (int) Math.round(field.instability() * 100)), 0xFF9BC9AE);

        y += 18;
        section(g, x, y, width, "LOCAL REPORT");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%s %03d%02dG%02dKT",
                conditionName(weather.condition()), weather.windDirectionDegrees(),
                weather.windSpeedKnots(), weather.gustKnots()), 0xFFDCE8EE);
        y += 12;
        line(g, x, y, width, String.format(Locale.ROOT, "%04dM %s  %02d/%02d Q%04d",
                weather.visibilityBlocks() * 16, conditionName(weather.condition()),
                12 - (int) Math.round(field.humidity() * 6),
                8 - (int) Math.round(field.humidity() * 4),
                (int) Math.round(field.qnh())), 0xFF9BC9AE);

        drawLegend(g, x, sidebarTop + sidebarHeight - 12, width);
    }

    private void drawLegend(GuiGraphicsExtractor g, int x, int y, int width) {
        g.text(font, "DBZ", x, y - 1, 0xFF9BC9AE, false);
        int swatchX = x + 28;
        int swatchWidth = Math.max(9, (width - 30) / WeatherDisplay.DBZ_COLORS.length - 3);
        String[] labels = {"L", "M", "H", "X"};
        for (int i = 0; i < WeatherDisplay.DBZ_COLORS.length; i++) {
            int sx = swatchX + i * (swatchWidth + 3);
            g.fill(sx, y, sx + swatchWidth, y + 7, WeatherDisplay.DBZ_COLORS[i]);
            g.text(font, labels[i], sx + swatchWidth / 2 - 1, y - 1, 0xFF9BC9AE, false);
        }
    }

    private void drawFooter(GuiGraphicsExtractor g,
                            FlightInstrumentPayload.WeatherGuidance weather) {
        int y = panelTop + panelHeight - 58;
        int x = panelLeft + 12;
        int width = panelWidth - 24;
        line(g, x, y, width, String.format(Locale.ROOT,
                        "OWN AIRCRAFT  ·  ICE %d%%  ·  A-ICE %s  ·  TURB %d%%",
                        weather.icingPercent(),
                        weather.antiIceEffective() ? "CLEARING"
                                : weather.antiIceActive() ? "ON" : "OFF",
                        weather.turbulencePercent()), 0xFFB6C8D1);
        line(g, x, y + 13, width, "SCROLL SCOPE FOR RANGE  ·  LAYER CYCLES RADAR / PRESSURE / WIND",
                0xFF71858B);
    }

    private void section(GuiGraphicsExtractor g, int x, int y, int width, String title) {
        g.fill(x, y, x + width, y + 11, 0xFF1C2733);
        g.text(font, title, x + 4, y + 1, 0xFF72E6F5, true);
    }

    private void line(GuiGraphicsExtractor g, int x, int y, int width, String text, int color) {
        g.text(font, fit(text, width), x, y, color, false);
    }

    private void drawWindBarb(GuiGraphicsExtractor g, int x, int y, int direction, double speed,
                              int color) {
        double[] vector = WeatherField.windVector(direction, 1.0);
        int length = 7 + Math.min(7, (int) Math.round(speed / 5.0));
        int dx = (int) Math.round(vector[0] * length);
        int dy = (int) Math.round(vector[1] * length);
        drawLine(g, x - dx, y - dy, x + dx, y + dy, color);
        drawLine(g, x + dx, y + dy,
                x + dx - Math.round((float) (vector[0] * 4.0 - vector[1] * 3.0)),
                y + dy - Math.round((float) (vector[1] * 4.0 + vector[0] * 3.0)), color);
    }

    private CellStats cellStats(int ownX, int ownZ, long gameTime,
                                FlightInstrumentPayload.WeatherGuidance weather) {
        int range = RANGES[rangeIndex];
        int margin = WeatherDisplay.CELL_BLOCKS;
        int minX = Math.floorDiv(ownX - range - margin, WeatherDisplay.CELL_BLOCKS);
        int maxX = Math.floorDiv(ownX + range + margin, WeatherDisplay.CELL_BLOCKS);
        int minZ = Math.floorDiv(ownZ - range - margin, WeatherDisplay.CELL_BLOCKS);
        int maxZ = Math.floorDiv(ownZ + range + margin, WeatherDisplay.CELL_BLOCKS);
        int count = 0;
        int maxIntensity = 0;
        int maxTop = 0;
        double nearest = Double.MAX_VALUE;
        double bearing = 0.0;
        for (int cellX = minX; cellX <= maxX; cellX++) {
            for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                WeatherSimulation.WeatherCell cell = WeatherSimulation.sampleCell(cellX, cellZ,
                        gameTime, WeatherDisplay.CELL_SEED, weather.condition(),
                        weather.turbulencePercent(), weather.gustKnots());
                if (cell.intensity() < 14) continue;
                double dx = (cellX + 0.5) * WeatherDisplay.CELL_BLOCKS - ownX;
                double dz = (cellZ + 0.5) * WeatherDisplay.CELL_BLOCKS - ownZ;
                double distance = Math.hypot(dx, dz);
                if (distance > range) continue;
                count++;
                maxIntensity = Math.max(maxIntensity, cell.intensity());
                maxTop = Math.max(maxTop, cell.topFeet());
                if (distance < nearest) {
                    nearest = distance;
                    bearing = wrapDegrees(Math.toDegrees(Math.atan2(dx, -dz)));
                }
            }
        }
        return new CellStats(count, maxIntensity, maxTop, nearest == Double.MAX_VALUE ? 0 : nearest,
                bearing);
    }

    private int radius() {
        return Math.min(scopeWidth, scopeHeight) / 2 - 8;
    }

    private void animate() {
        long now = System.nanoTime();
        float delta = Math.min(0.1f, Math.max(0.0f, (now - lastFrameNanos) / 1_000_000_000.0f));
        lastFrameNanos = now;
        scanAngle = (scanAngle + delta * 90.0f) % 360.0f;
    }

    private void changeRange(int delta) {
        rangeIndex = Math.max(0, Math.min(RANGES.length - 1, rangeIndex + delta));
    }

    private void cycleLayer() {
        layerIndex = (layerIndex + 1) % LAYER_NAMES.length;
        if (layerButton != null) {
            layerButton.setMessage(Component.literal("LAYER: " + LAYER_NAMES[layerIndex]));
        }
    }

    private int ownX() {
        var player = Minecraft.getInstance().player;
        return FlightHud.hasRadarData() ? FlightHud.radarOwnX()
                : player == null ? 0 : player.blockPosition().getX();
    }

    private int ownZ() {
        var player = Minecraft.getInstance().player;
        return FlightHud.hasRadarData() ? FlightHud.radarOwnZ()
                : player == null ? 0 : player.blockPosition().getZ();
    }

    private String conditionName(int condition) {
        return switch (condition) {
            case 2 -> "TSRA";
            case 1 -> "RA";
            default -> "CAVOK";
        };
    }

    private String turbulenceName(int percent) {
        return percent >= 70 ? "SEV TURB" : percent >= 35 ? "MOD TURB" : "TURB NIL";
    }

    private double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }

    private void drawCircle(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
        int x = radius;
        int y = 0;
        int error = 1 - radius;
        while (x >= y) {
            g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
            g.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
            g.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
            g.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
            g.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
            g.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);
            g.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
            g.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
            y++;
            if (error < 0) error += 2 * y + 1;
            else {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    private void drawLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
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

    private String fit(String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
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
        int logicalX = scaler.mouseX(mouseX);
        int logicalY = scaler.mouseY(mouseY);
        if (logicalX >= scopeLeft && logicalX <= scopeLeft + scopeWidth
                && logicalY >= scopeTop && logicalY <= scopeTop + scopeHeight) {
            changeRange(deltaY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(logicalX, logicalY, deltaX, deltaY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
