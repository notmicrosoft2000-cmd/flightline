package dev.flightline.client;

import dev.flightline.flight.WeatherSimulation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Standalone weather map using the same deterministic cells as the radar weather layer. */
public final class WeatherMapScreen extends Screen {
    private static final int BASE_WIDTH = 560;
    private static final int BASE_HEIGHT = 380;
    private static final int[] RANGES = {512, 1024, 2048, 4096};
    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private int rangeIndex = 1;
    private boolean headingUp;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int scopeLeft;
    private int scopeTop;
    private int scopeWidth;
    private int scopeHeight;
    private float scanAngle;
    private long lastFrameNanos = System.nanoTime();

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
        scopeTop = panelTop + 40;
        scopeWidth = panelWidth - 24;
        scopeHeight = panelHeight - 104;

        int bottomRow = panelTop + panelHeight - 54;
        addRenderableWidget(Button.builder(Component.literal("RANGE -"), b -> changeRange(-1))
                .bounds(panelLeft + 12, bottomRow, 74, 18).build());
        addRenderableWidget(Button.builder(Component.literal("RANGE +"), b -> changeRange(1))
                .bounds(panelLeft + 92, bottomRow, 74, 18).build());
        addRenderableWidget(Button.builder(Component.literal(headingUp ? "HDG" : "NORTH"), b -> {
                    headingUp = !headingUp;
                    b.setMessage(Component.literal(headingUp ? "HDG" : "NORTH"));
                })
                .bounds(panelLeft + 172, bottomRow, 84, 18).build());
        addRenderableWidget(Button.builder(Component.literal("RADAR"), b ->
                        Minecraft.getInstance().gui.setScreen(new FlightRadarScreen()))
                .bounds(panelLeft + panelWidth - 158, bottomRow, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                .bounds(panelLeft + panelWidth - 82, bottomRow, 70, 18).build());
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
        graphics.centeredText(font, "WEATHER RADAR  ·  AREA COVERAGE",
                panelLeft + panelWidth / 2, panelTop + 7, 0xFF72E6F5);

        graphics.fill(scopeLeft, scopeTop, scopeLeft + scopeWidth, scopeTop + scopeHeight, 0xFF07100D);
        graphics.outline(scopeLeft, scopeTop, scopeWidth, scopeHeight, 0xFF3D6C58);
        int cx = scopeLeft + scopeWidth / 2;
        int cy = scopeTop + scopeHeight / 2;
        int radius = Math.max(20, Math.min(scopeWidth, scopeHeight) / 2 - 10);
        int ownX = ownX();
        int ownZ = ownZ();
        var level = Minecraft.getInstance().level;
        long gameTime = level == null ? 0L : level.getGameTime();

        WeatherDisplay.drawRadarCells(graphics, cx, cy, radius, RANGES[rangeIndex], ownX, ownZ,
                headingUp, FlightHud.radarOwnHeadingDegrees(), gameTime,
                WeatherDisplay.CELL_SEED, FlightHud.weatherGuidance());
        drawVisibilityHaze(graphics);
        drawWindField(graphics, cx, cy, radius);
        drawCompassRose(graphics, cx, cy, radius);

        for (int ring = 1; ring <= 3; ring++) {
            drawCircle(graphics, cx, cy, radius * ring / 3, 0x8A3D6C58);
            graphics.text(font, String.format(Locale.ROOT, "%d NM", RANGES[rangeIndex] * ring / 3 / 10),
                    cx + 3, cy - radius * ring / 3 - 4, 0x9956B9D2, false);
        }
        drawLine(graphics, cx - radius, cy, cx + radius, cy, 0x8A315E50);
        drawLine(graphics, cx, cy - radius, cx, cy + radius, 0x8A315E50);
        double sweep = Math.toRadians(scanAngle);
        for (int trail = 5; trail >= 1; trail--) {
            double angle = sweep - Math.toRadians(trail * 3.0);
            int tx = cx + (int) Math.round(Math.sin(angle) * radius);
            int ty = cy - (int) Math.round(Math.cos(angle) * radius);
            drawLine(graphics, cx, cy, tx, ty, trail <= 2 ? 0x604EA87B : 0x304EA87B);
        }
        drawLine(graphics, cx, cy, cx + (int) Math.round(Math.sin(sweep) * radius),
                cy - (int) Math.round(Math.cos(sweep) * radius), 0xFF65F2A5);

        for (var contact : FlightHud.radarContacts()) {
            double dx = contact.x() - ownX;
            double dz = contact.z() - ownZ;
            if (Math.hypot(dx, dz) > RANGES[rangeIndex]) continue;
            if (headingUp) {
                double radians = Math.toRadians(-FlightHud.radarOwnHeadingDegrees());
                double rx = dx * Math.cos(radians) - dz * Math.sin(radians);
                double rz = dx * Math.sin(radians) + dz * Math.cos(radians);
                dx = rx;
                dz = rz;
            }
            double scale = radius / (double) RANGES[rangeIndex];
            int px = cx + (int) Math.round(dx * scale);
            int py = cy + (int) Math.round(dz * scale);
            int color = contact.airborne() ? 0xFF75E6A5 : 0xFFFFCE52;
            graphics.fill(px - 2, py - 2, px + 3, py + 3, color);
            if (contact.airborne()) {
                double heading = Math.toRadians(headingUp
                        ? contact.headingDegrees() - FlightHud.radarOwnHeadingDegrees()
                        : contact.headingDegrees());
                int vx = px + (int) Math.round(Math.sin(heading) * 10);
                int vy = py - (int) Math.round(Math.cos(heading) * 10);
                drawLine(graphics, px, py, vx, vy, color);
                graphics.text(font, String.format(Locale.ROOT, "%05.0f", contact.altitudeFeet() / 10.0f),
                        px + 4, py - 10, 0xFFA8D8C0, false);
            }
        }

        graphics.fill(cx - 2, cy - 5, cx + 3, cy + 5, 0xFFFFFFFF);
        graphics.fill(cx - 5, cy - 2, cx + 6, cy + 3, 0xFFFFFFFF);
        graphics.text(font, headingUp ? "HDG UP" : "NORTH UP", scopeLeft + 6, scopeTop + 5, 0xFF9BC9AE, true);
        graphics.text(font, String.format(Locale.ROOT, "RANGE %.0f NM", RANGES[rangeIndex] / 10.0f),
                scopeLeft + 6, scopeTop + scopeHeight - 12, 0xFF9BC9AE, true);
        drawWindArrow(graphics, scopeLeft + scopeWidth - 56, scopeTop + 8);
        drawLegend(graphics);

        var weather = FlightHud.weatherGuidance();
        String metar = String.format(Locale.ROOT, "%s  WIND %03d/%02dG%02d  VIS %.1fSM  %s",
                conditionName(weather.condition()), weather.windDirectionDegrees(),
                weather.windSpeedKnots(), weather.gustKnots(), weather.visibilityBlocks() / 64.0f,
                weather.turbulencePercent() >= 70 ? "SEV TURB" : weather.turbulencePercent() >= 35 ? "MOD TURB" : "TURB NIL");
        graphics.text(font, fit(metar, panelWidth - 24), panelLeft + 12,
                panelTop + panelHeight - 34, 0xFFB6C8D1, true);
        graphics.text(font, fit("OWN AIRCRAFT ICING " + weather.icingPercent() + "%  ·  A-ICE "
                + (weather.antiIceEffective() ? "CLEARING" : weather.antiIceActive() ? "ON" : "OFF")
                + "  ·  SCROLL TO CHANGE RANGE", panelWidth - 24),
                panelLeft + 12, panelTop + panelHeight - 18, 0xFF71858B, true);
        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
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

    private void drawLegend(GuiGraphicsExtractor graphics) {
        int x = panelLeft + panelWidth - 86;
        int y = scopeTop + 34;
        int[] colors = {0xFF3E8E58, 0xFFB8A532, 0xFFE05026, 0xFFB31E22};
        String[] labels = {"LIGHT", "MOD", "HEAVY", "EXTREME"};
        graphics.text(font, "DBZ", x - 16, y - 12, 0xFF9BC9AE, false);
        for (int i = 0; i < colors.length; i++) {
            graphics.fill(x, y + i * 12, x + 10, y + i * 12 + 7, colors[i]);
            graphics.text(font, labels[i], x + 14, y + i * 12 - 1, 0xFFB6C8D1, false);
        }
    }

    private void drawVisibilityHaze(GuiGraphicsExtractor graphics) {
        var weather = FlightHud.weatherGuidance();
        int alpha = weather.visibilityBlocks() <= 28 ? 0x38 : weather.visibilityBlocks() <= 64 ? 0x1E : 0x0A;
        int color = (alpha << 24) | 0x9AA7A0;
        int band = 14;
        graphics.fill(scopeLeft, scopeTop, scopeLeft + scopeWidth, scopeTop + band, color);
        graphics.fill(scopeLeft, scopeTop + scopeHeight - band, scopeLeft + scopeWidth,
                scopeTop + scopeHeight, color);
        graphics.fill(scopeLeft, scopeTop, scopeLeft + band, scopeTop + scopeHeight, color);
        graphics.fill(scopeLeft + scopeWidth - band, scopeTop, scopeLeft + scopeWidth,
                scopeTop + scopeHeight, color);
    }

    private void drawWindField(GuiGraphicsExtractor graphics, int cx, int cy, int radius) {
        var weather = FlightHud.weatherGuidance();
        int color = weather.windSpeedKnots() >= 30 ? 0xFF72E6F5 : 0x5E9CB0;
        int step = 58;
        for (int gy = -2; gy <= 2; gy++) {
            for (int gx = -3; gx <= 3; gx++) {
                int px = cx + gx * step;
                int py = cy + gy * step;
                if ((px - cx) * (px - cx) + (py - cy) * (py - cy) > (radius - 8) * (radius - 8)) continue;
                int len = 7 + Math.min(9, weather.windSpeedKnots() / 6);
                double radians = Math.toRadians(weather.windDirectionDegrees()
                        - (headingUp ? FlightHud.radarOwnHeadingDegrees() : 0.0f));
                int dx = (int) Math.round(Math.sin(radians) * len);
                int dy = (int) Math.round(-Math.cos(radians) * len);
                drawLine(graphics, px - dx, py - dy, px + dx, py + dy, color);
                int[] barb = headingPoint(px + dx, py + dy, 4, weather.windDirectionDegrees() + 140
                        - (headingUp ? FlightHud.radarOwnHeadingDegrees() : 0.0f));
                drawLine(graphics, px + dx, py + dy, barb[0], barb[1], color);
            }
        }
    }

    private void drawCompassRose(GuiGraphicsExtractor graphics, int cx, int cy, int radius) {
        String[] labels = {"N", "E", "S", "W"};
        double[] angles = {0, 90, 180, 270};
        float ownHeading = headingUp ? FlightHud.radarOwnHeadingDegrees() : 0.0f;
        for (int i = 0; i < labels.length; i++) {
            double radians = Math.toRadians(angles[i] - ownHeading);
            int px = cx + (int) Math.round(Math.sin(radians) * (radius - 12));
            int py = cy - (int) Math.round(Math.cos(radians) * (radius - 12));
            graphics.text(font, labels[i], px - 2, py - 3, 0xFF9BC9AE, false);
        }
    }

    private int[] headingPoint(int cx, int cy, int radius, double degrees) {
        double radians = Math.toRadians(degrees);
        return new int[]{Math.round(cx + (float) (Math.sin(radians) * radius)),
                Math.round(cy - (float) (Math.cos(radians) * radius))};
    }

    private void drawWindArrow(GuiGraphicsExtractor graphics, int x, int y) {
        var weather = FlightHud.weatherGuidance();
        double radians = Math.toRadians(weather.windDirectionDegrees());
        int dx = (int) Math.round(Math.sin(radians) * 13);
        int dy = (int) Math.round(-Math.cos(radians) * 13);
        drawLine(graphics, x, y, x + dx, y + dy, 0xFF72E6F5);
        graphics.fill(x + dx - 2, y + dy - 2, x + dx + 2, y + dy + 2, 0xFF72E6F5);
        graphics.text(font, "WIND", x - 4, y + 8, 0xFF9BC9AE, false);
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
            int twiceError = error * 2;
            if (twiceError >= dy) { error += dy; x0 += sx; }
            if (twiceError <= dx) { error += dx; y0 += sy; }
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
