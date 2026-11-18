package dev.flightline.client;

import dev.flightline.flight.NavGuidance;
import dev.flightline.flight.WeatherField;
import dev.flightline.flight.WeatherSimulation;
import dev.flightline.network.FlightInstrumentPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private boolean routeOverlay;
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
    private net.minecraft.client.gui.components.AbstractWidget layerButton;
    private float scanAngle;
    private long lastFrameNanos = System.nanoTime();

    // Weather stats are re-scanned at most every 400 ms instead of every rendered frame.
    private CellStats cachedStats;
    private long cachedStatsKey = Long.MIN_VALUE;

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
        addRenderableWidget(FlightLineTheme.button("RANGE -", x, buttonY, 66, 20,
                () -> changeRange(-1)));
        x += 72;
        addRenderableWidget(FlightLineTheme.button("RANGE +", x, buttonY, 66, 20,
                () -> changeRange(1)));
        x += 72;
        var headingHolder = new net.minecraft.client.gui.components.AbstractWidget[1];
        headingHolder[0] = FlightLineTheme.button(headingUp ? "HDG UP" : "NORTH",
                x, buttonY, 66, 20, () -> {
                    headingUp = !headingUp;
                    headingHolder[0].setMessage(
                            Component.literal(headingUp ? "HDG UP" : "NORTH"));
                });
        addRenderableWidget(headingHolder[0]);
        x += 72;
        layerButton = FlightLineTheme.button("LAYER: " + LAYER_NAMES[layerIndex],
                x, buttonY, 112, 20, this::cycleLayer);
        addRenderableWidget(layerButton);
        x += 118;
        var routeHolder = new net.minecraft.client.gui.components.AbstractWidget[1];
        routeHolder[0] = FlightLineTheme.button(routeOverlay ? "ROUTE ON" : "ROUTE OFF",
                x, buttonY, 62, 20, () -> {
                    routeOverlay = !routeOverlay;
                    routeHolder[0].setMessage(Component.literal(
                            routeOverlay ? "ROUTE ON" : "ROUTE OFF"));
                });
        addRenderableWidget(routeHolder[0]);
        x += 68;
        addRenderableWidget(FlightLineTheme.button("TRAFFIC", x, buttonY, 58, 20,
                () -> Minecraft.getInstance().gui.setScreen(new FlightRadarScreen())));
        addRenderableWidget(FlightLineTheme.button("CLOSE", panelLeft + panelWidth - 76,
                buttonY, 64, 20, this::onClose));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        animate();
        scaler.update(width, height);
        scaler.push(graphics);
        scaler.scrim(graphics);
        FlightLineTheme.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight,
                FlightLineTheme.PANEL, FlightLineTheme.STONE);
        FlightLineTheme.header(graphics, font, panelLeft + 4, panelTop + 4, panelWidth - 8, 24,
                "03", "WEATHER RADAR  ·  AREA COVERAGE", FlightLineTheme.GREEN);

        int cx = scopeLeft + scopeWidth / 2;
        int cy = scopeTop + scopeHeight / 2;
        int radius = Math.min(scopeWidth, scopeHeight) / 2 - 8;
        int ownX = ownX();
        int ownZ = ownZ();
        var level = Minecraft.getInstance().level;
        long gameTime = level == null ? 0L : level.getGameTime();
        var weather = FlightHud.weatherGuidance();

        // Dark instrument field with a hard, high-contrast frame so the scope reads as one unit.
        graphics.fill(scopeLeft, scopeTop, scopeLeft + scopeWidth, scopeTop + scopeHeight,
                FlightLineTheme.SCOPE);
        graphics.outline(scopeLeft, scopeTop, scopeWidth, scopeHeight, FlightLineTheme.GREEN);
        graphics.outline(scopeLeft + 2, scopeTop + 2, scopeWidth - 4, scopeHeight - 4,
                FlightLineTheme.STONE);

        enableScopeScissor(graphics);
        try {
            drawScopeGrid(graphics, cx, cy, radius);
            drawVisibilityHaze(graphics, weather.visibilityBlocks());
        } finally {
            graphics.disableScissor();
        }

        if (layerIndex == 0) {
            // Radar cells apply their own circular scissor.
            WeatherDisplay.drawRadarCells(graphics, cx, cy, radius, RANGES[rangeIndex], ownX, ownZ,
                    headingUp, FlightHud.radarOwnHeadingDegrees(), gameTime,
                    WeatherDisplay.CELL_SEED, weather, true, font);
        } else {
            enableScopeScissor(graphics);
            try {
                if (layerIndex == 1) {
                    drawPressureContours(graphics, cx, cy, radius, ownX, ownZ, gameTime);
                } else {
                    drawWindField(graphics, cx, cy, radius, ownX, ownZ, gameTime);
                }
            } finally {
                graphics.disableScissor();
            }
        }

        enableScopeScissor(graphics);
        try {
            for (int ring = 1; ring <= 3; ring++) {
                int r = radius * ring / 3;
                drawCircle(graphics, cx, cy, r, ring == 3 ? 0xFF4E8E7C : 0x804E8E7C);
            }
            drawSweep(graphics, cx, cy, radius);
            if (layerIndex == 0) {
                drawTraffic(graphics, cx, cy, ownX, ownZ);
            }
            if (routeOverlay) {
                drawRouteOverlay(graphics, cx, cy, ownX, ownZ);
            }
            graphics.fill(cx - 2, cy - 5, cx + 3, cy + 5, FlightLineTheme.PAPER);
            graphics.fill(cx - 5, cy - 2, cx + 6, cy + 3, FlightLineTheme.PAPER);
            drawCompassRose(graphics, cx, cy, radius);
            for (int ring = 1; ring <= 3; ring++) {
                int r = radius * ring / 3;
                String rangeLabel = String.format(Locale.ROOT, "%d NM",
                        RANGES[rangeIndex] * ring / 3 / 10);
                int labelX = cx + 4;
                int labelY = cy - r - 5;
                graphics.fill(labelX - 2, labelY - 1, labelX + font.width(rangeLabel) + 2,
                        labelY + 9, FlightLineTheme.VOID);
                graphics.text(font, rangeLabel, labelX, labelY, FlightLineTheme.TEXT_DIM, false);
            }
        } finally {
            graphics.disableScissor();
        }

        graphics.fill(scopeLeft + 4, scopeTop + 4, scopeLeft + 84, scopeTop + 17,
                FlightLineTheme.VOID);
        graphics.text(font, headingUp ? "HDG UP" : "NORTH UP", scopeLeft + 6, scopeTop + 5,
                FlightLineTheme.GREEN, false);
        String scopeStatus = String.format(Locale.ROOT, "RANGE %.0f NM  ·  %s",
                RANGES[rangeIndex] / 10.0f, LAYER_NAMES[layerIndex]);
        graphics.fill(scopeLeft + 4, scopeTop + scopeHeight - 15,
                scopeLeft + 8 + font.width(scopeStatus), scopeTop + scopeHeight - 3,
                FlightLineTheme.VOID);
        graphics.text(font, scopeStatus, scopeLeft + 6, scopeTop + scopeHeight - 14,
                FlightLineTheme.TEXT_DIM, false);

        CellStats stats = cachedCellStats(ownX, ownZ, gameTime, weather);
        var player = Minecraft.getInstance().player;
        double altitude = player == null ? 96.0 : player.getY();
        WeatherField.Sample field = WeatherField.sample(ownX, ownZ, altitude,
                weather.condition() == 2, weather.condition() >= 1, 0.8f, gameTime);
        drawSidebar(graphics, field, stats, weather);
        if (routeOverlay) drawRouteReadout(graphics, ownX, ownZ);
        drawFooter(graphics, weather);

        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    private void drawScopeGrid(GuiGraphicsExtractor g, int cx, int cy, int radius) {
        int color = 0xFF1E2F2A;
        for (int offset = -radius + 32; offset < radius; offset += 32) {
            drawLine(g, cx + offset, cy - radius, cx + offset, cy + radius, color);
            drawLine(g, cx - radius, cy + offset, cx + radius, cy + offset, color);
        }
        drawLine(g, cx - radius, cy, cx + radius, cy, 0xFF315E50);
        drawLine(g, cx, cy - radius, cx, cy + radius, 0xFF315E50);
    }

    private void drawSweep(GuiGraphicsExtractor g, int cx, int cy, int radius) {
        double sweep = Math.toRadians(scanAngle);
        for (int trail = 3; trail >= 1; trail--) {
            double angle = sweep - Math.toRadians(trail * 4.0);
            int tx = cx + (int) Math.round(Math.sin(angle) * radius);
            int ty = cy - (int) Math.round(Math.cos(angle) * radius);
            drawLine(g, cx, cy, tx, ty, trail <= 2 ? 0x606CC349 : 0x303C8527);
        }
        drawLine(g, cx, cy, cx + (int) Math.round(Math.sin(sweep) * radius),
                cy - (int) Math.round(Math.cos(sweep) * radius), FlightLineTheme.GREEN);
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
            int color = contact.airborne() ? FlightLineTheme.GREEN : FlightLineTheme.AMBER;
            g.fill(px - 2, py - 2, px + 3, py + 3, color);
            if (contact.airborne()) {
                double heading = Math.toRadians(headingUp
                        ? contact.headingDegrees() - FlightHud.radarOwnHeadingDegrees()
                        : contact.headingDegrees());
                int vx = px + (int) Math.round(Math.sin(heading) * 10);
                int vy = py - (int) Math.round(Math.cos(heading) * 10);
                drawLine(g, px, py, vx, vy, color);
                String altitudeTag = String.format(Locale.ROOT, "%05.0f", contact.altitudeFeet() / 10.0f);
                g.fill(px + 3, py - 11, px + 5 + font.width(altitudeTag), py - 2,
                        FlightLineTheme.VOID);
                g.text(font, altitudeTag, px + 4, py - 10, FlightLineTheme.TEXT_DIM, false);
            }
        }
    }

    private void drawRouteOverlay(GuiGraphicsExtractor g, int cx, int cy, int ownX, int ownZ) {
        var legs = NavigationState.legs();
        if (legs.isEmpty()) return;
        var plan = NavigationState.plan();
        int range = RANGES[rangeIndex];
        double scale = radius() / (double) range;
        int previousX = cx;
        int previousY = cy;
        boolean previousVisible = true;
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            double dx = leg.x() - ownX;
            double dz = leg.z() - ownZ;
            if (headingUp) {
                double radians = Math.toRadians(-FlightHud.radarOwnHeadingDegrees());
                double rx = dx * Math.cos(radians) - dz * Math.sin(radians);
                double rz = dx * Math.sin(radians) + dz * Math.cos(radians);
                dx = rx;
                dz = rz;
            }
            boolean visible = Math.hypot(dx, dz) <= range;
            int px = cx + (int) Math.round(dx * scale);
            int py = cy + (int) Math.round(dz * scale);
            boolean active = plan != null && i == plan.activeLeg();
            int color = active ? FlightLineTheme.GREEN
                    : leg.airport() ? FlightLineTheme.AMBER : FlightLineTheme.CYAN;
            if (visible && previousVisible) drawLine(g, previousX, previousY, px, py, color);
            if (visible) {
                g.fill(px - 2, py - 2, px + 3, py + 3, color);
                if (active || i == legs.size() - 1) {
                    String waypoint = fit(leg.name(), 34);
                    g.fill(px + 3, py - 9, px + 5 + font.width(waypoint), py + 1,
                            FlightLineTheme.VOID);
                    g.text(font, waypoint, px + 4, py - 8, color, false);
                }
            }
            previousX = px;
            previousY = py;
            previousVisible = visible;
        }
    }

    /** Compact route strip below the sidebar, clear of the compass and range labels. */
    private void drawRouteReadout(GuiGraphicsExtractor g, int ownX, int ownZ) {
        var plan = NavigationState.plan();
        var leg = NavigationState.activeLeg();
        int x = sidebarLeft;
        int y = sidebarTop + sidebarHeight + 8;
        int width = sidebarWidth;
        FlightLineTheme.panel(g, x, y, width, 26, FlightLineTheme.PANEL, FlightLineTheme.GREEN);
        g.fill(x + 4, y + 4, x + 7, y + 22, FlightLineTheme.GREEN);
        g.text(font, fit("ROUTE " + NavigationState.statusLabel(), width - 14),
                x + 11, y + 5, FlightLineTheme.GREEN, false);
        if (plan == null || leg == null) {
            g.text(font, "NO ACTIVE LEG", x + 11, y + 15, FlightLineTheme.RED, false);
            return;
        }
        double distance = NavGuidance.distanceBlocks(ownX, ownZ, leg.x(), leg.z());
        double bearing = NavGuidance.bearingDegrees(ownX, ownZ, leg.x(), leg.z());
        double xtk = NavigationState.crossTrackBlocks(ownX, ownZ);
        int speed = Math.max(1, FlightHud.groundSpeedKnots());
        int eteMinutes = NavigationState.eteMinutes(ownX, ownZ, speed);
        String detail = String.format(Locale.ROOT, "%s %03.0f°  %.0fB  XTK %+.0f  %02d:%02d",
                fit(leg.name(), 48), bearing, distance, xtk, eteMinutes / 60, eteMinutes % 60);
        g.text(font, fit(detail, width - 14), x + 11, y + 15, FlightLineTheme.TEXT_DIM, false);
    }

    private void drawVisibilityHaze(GuiGraphicsExtractor g, int visibilityBlocks) {
        int alpha = visibilityBlocks <= 28 ? 0x0C : visibilityBlocks <= 64 ? 0x06 : 0x03;
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
            g.fill(px - 4, py - 4, px + 5, py + 6, FlightLineTheme.VOID);
            g.text(font, labels[i], px - 2, py - 3, FlightLineTheme.PAPER, false);
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, WeatherField.Sample field, CellStats stats,
                             FlightInstrumentPayload.WeatherGuidance weather) {
        FlightLineTheme.panel(g, sidebarLeft, sidebarTop, sidebarWidth, sidebarHeight,
                FlightLineTheme.CARD, FlightLineTheme.STONE);
        int x = sidebarLeft + 8;
        int width = sidebarWidth - 16;
        int y = sidebarTop + 8;

        section(g, x, y, width, "STORM CELLS");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%d CELLS  ·  MAX %d DBZ",
                stats.count(), stats.maxIntensity()), 0xFFDCE8EE);
        y += 10;
        line(g, x, y, width, String.format(Locale.ROOT, "NEAREST %03.0f° / %.0f NM",
                stats.nearestBearing(), stats.nearestBlocks() / 10.0), 0xFF9BC9AE);
        y += 10;
        line(g, x, y, width, String.format(Locale.ROOT, "TOPS FL%03d", stats.maxTop() / 100),
                0xFF9BC9AE);

        y += 14;
        section(g, x, y, width, "SURFACE WIND");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%03d/%02dG%02d KT",
                weather.windDirectionDegrees(), weather.windSpeedKnots(),
                weather.gustKnots()), 0xFFDCE8EE);
        y += 10;
        drawWindBarb(g, x + 8, y + 4, (int) Math.round(field.surfaceWindDirectionDegrees()),
                field.surfaceWindKnots(), 0xFF72E6F5);
        line(g, x + 34, y, width - 34, "GRADIENT FLOW", 0xFF9BC9AE);

        y += 14;
        section(g, x, y, width, "FLIGHT LEVEL");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%03d/%02d KT",
                (int) Math.round(field.flightWindDirectionDegrees()),
                (int) Math.round(field.flightWindKnots())), 0xFFDCE8EE);
        y += 10;
        line(g, x, y, width, String.format(Locale.ROOT, "QNH %04d  ·  FRZLVL %d FT",
                (int) Math.round(field.qnh()), field.freezingLevelFeet()), 0xFF9BC9AE);

        y += 14;
        section(g, x, y, width, "AIR MASS");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "VIS %.1f SM  ·  %s",
                weather.visibilityBlocks() / 64.0f, turbulenceName(weather.turbulencePercent())),
                weather.turbulencePercent() >= 70 ? 0xFFFF8A72 : 0xFFDCE8EE);
        y += 10;
        line(g, x, y, width, String.format(Locale.ROOT, "RH %d%%  ·  INSTABILITY %d%%",
                (int) Math.round(field.humidity() * 100),
                (int) Math.round(field.instability() * 100)), 0xFF9BC9AE);

        y += 14;
        section(g, x, y, width, "LOCAL REPORT");
        y += 16;
        line(g, x, y, width, String.format(Locale.ROOT, "%s %03d%02dG%02dKT",
                conditionName(weather.condition()), weather.windDirectionDegrees(),
                weather.windSpeedKnots(), weather.gustKnots()), 0xFFDCE8EE);
        y += 10;
        line(g, x, y, width, String.format(Locale.ROOT, "%04dM %s  %02d/%02d Q%04d",
                weather.visibilityBlocks() * 16, conditionName(weather.condition()),
                12 - (int) Math.round(field.humidity() * 6),
                8 - (int) Math.round(field.humidity() * 4),
                (int) Math.round(field.qnh())), 0xFF9BC9AE);

        drawLegend(g, x, sidebarTop + sidebarHeight - 12, width);
    }

    private void drawLegend(GuiGraphicsExtractor g, int x, int y, int width) {
        g.fill(x - 2, y - 13, x + width + 2, y + 9, FlightLineTheme.VOID);
        g.outline(x - 2, y - 13, width + 4, 22, FlightLineTheme.STONE);
        g.text(font, "DBZ", x, y - 10, FlightLineTheme.MUTED, false);
        int swatchX = x + 28;
        int swatchWidth = Math.max(11, (width - 30) / WeatherDisplay.DBZ_COLORS.length - 4);
        String[] labels = {"L", "M", "H", "X"};
        for (int i = 0; i < WeatherDisplay.DBZ_COLORS.length; i++) {
            int sx = swatchX + i * (swatchWidth + 4);
            g.fill(sx, y - 3, sx + swatchWidth, y + 5, WeatherDisplay.DBZ_COLORS[i]);
            g.outline(sx, y - 3, swatchWidth, 8, FlightLineTheme.STONE);
            g.text(font, labels[i], sx + swatchWidth / 2 - 1, y - 12, FlightLineTheme.MUTED, false);
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
        g.fill(x, y, x + width, y + 11, FlightLineTheme.HEADER);
        g.fill(x, y, x + 3, y + 11, FlightLineTheme.GREEN);
        g.fill(x, y + 11, x + width, y + 12, FlightLineTheme.STONE);
        g.text(font, title, x + 6, y + 1, FlightLineTheme.GREEN, false);
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

    /**
     * Sidebar numbers only need coarse freshness: reuse the previous scan unless the sampled
     * field bucket, coarse aircraft position, zoom or weather inputs changed.
     */
    private CellStats cachedCellStats(int ownX, int ownZ, long gameTime,
                                      FlightInstrumentPayload.WeatherGuidance weather) {
        long key = (gameTime / 8L) & 0xFFFFL
                | ((long) Math.floorDiv(ownX, WeatherDisplay.CELL_BLOCKS)) << 16
                | ((long) Math.floorDiv(ownZ, WeatherDisplay.CELL_BLOCKS)) << 32
                | ((long) rangeIndex) << 48
                | ((long) weather.condition()) << 52
                | ((long) weather.turbulencePercent()) << 56;
        if (cachedStats != null && cachedStatsKey == key) return cachedStats;
        cachedStats = cellStats(ownX, ownZ, gameTime, weather);
        cachedStatsKey = key;
        return cachedStats;
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

    private void enableScopeScissor(GuiGraphicsExtractor g) {
        g.enableScissor(scopeLeft + 2, scopeTop + 2, scopeWidth - 4, scopeHeight - 4);
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

    /** Binary search the trim point: one width measurement per probe instead of per character. */
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        if (maxWidth <= 0) return "";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.width(text.substring(0, mid)) <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low);
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
