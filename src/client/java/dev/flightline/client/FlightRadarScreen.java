package dev.flightline.client;

import dev.flightline.FlightLine;
import dev.flightline.network.ConfigureIlsPayload;
import dev.flightline.network.FlightRadarPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class FlightRadarScreen extends Screen {
    private static final int BASE_WIDTH = 560;
    private static final int BASE_HEIGHT = 360;
    private static final int[] RANGES = {64, 128, 256, 512, 1024, 2048, 4096};
    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private static int rememberedLeft = -1;
    private static int rememberedTop = -1;
    private static int rememberedWidth = 500;
    private static int rememberedHeight = 320;
    private int rangeIndex = 3;
    private int selectedX;
    private int selectedZ;
    private int selectedHeading;
    private int selectedContactId = -1;
    private boolean thresholdPickMode;
    private String status = "Select a contact, or enable PICK THRESHOLD to choose a runway point.";
    private long lastFrameNanos = System.nanoTime();
    private float scanAngle;
    private float openProgress;
    private final Map<Integer, AnimatedContact> animatedContacts = new HashMap<>();
    private double dragOffsetX;
    private double dragOffsetY;
    private int resizeStartX;
    private int resizeStartY;
    private int resizeStartWidth;
    private int resizeStartHeight;
    private int contactListOffset;
    private boolean draggingWindow;
    private boolean resizingWindow;
    private @Nullable EditBox xInput;
    private @Nullable EditBox zInput;
    private @Nullable EditBox headingInput;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int scopeLeft;
    private int scopeTop;
    private int scopeWidth;
    private int scopeHeight;
    private boolean compactLayout;
    private final boolean atcSelectionMode;
    private boolean headingUp;
    private boolean weatherLayer = true;

    public FlightRadarScreen() {
        this(false);
    }

    private FlightRadarScreen(boolean atcSelectionMode) {
        super(Component.literal(atcSelectionMode ? "Select ATC Target" : "Flight Radar"));
        this.atcSelectionMode = atcSelectionMode;
        var player = Minecraft.getInstance().player;
        selectedX = FlightHud.hasRadarData() ? FlightHud.radarOwnX()
                : player == null ? 0 : player.blockPosition().getX();
        selectedZ = FlightHud.hasRadarData() ? FlightHud.radarOwnZ()
                : player == null ? 0 : player.blockPosition().getZ();
        selectedHeading = Math.floorMod(Math.round(FlightHud.hasRadarData()
                ? FlightHud.radarOwnHeadingDegrees() : player == null ? 0.0f : player.getYRot()), 360);
        if (atcSelectionMode) {
            selectedContactId = FlightLineClient.atcSelectedTarget();
            status = "Click an aircraft contact on the scope to select it for ATC.";
        }
    }

    public static FlightRadarScreen forAtcSelection() {
        return new FlightRadarScreen(true);
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

    @Override
    protected void init() {
        super.init();
        scaler.update(width, height);
        panelWidth = Math.max(Math.min(300, scaler.width() - 16),
                Math.min(rememberedWidth, scaler.width() - 16));
        panelHeight = Math.max(Math.min(220, scaler.height() - 16),
                Math.min(rememberedHeight, scaler.height() - 16));
        if (rememberedLeft < 0 || rememberedTop < 0) {
            panelLeft = (scaler.width() - panelWidth) / 2;
            panelTop = (scaler.height() - panelHeight) / 2;
        } else {
            panelLeft = Math.max(4, Math.min(scaler.width() - panelWidth - 4, rememberedLeft));
            panelTop = Math.max(4, Math.min(scaler.height() - panelHeight - 4, rememberedTop));
        }
        rememberedLeft = panelLeft;
        rememberedTop = panelTop;
        rememberedWidth = panelWidth;
        rememberedHeight = panelHeight;
        compactLayout = panelWidth < 430;

        scopeLeft = panelLeft + 12;
        scopeTop = panelTop + 48;
        scopeWidth = compactLayout ? panelWidth - 24 : Math.max(140, panelWidth - 204);
        scopeHeight = Math.max(42, panelHeight - 165);
        int fieldY = panelTop + panelHeight - 56;
        int fieldWidth = compactLayout ? Math.max(38, Math.min(50, (panelWidth - 116) / 3)) : 68;
        int fieldGap = 4;
        xInput = coordinateInput(panelLeft + 12, fieldY, fieldWidth, "X", selectedX);
        zInput = coordinateInput(panelLeft + 12 + fieldWidth + fieldGap, fieldY, fieldWidth, "Z", selectedZ);
        headingInput = coordinateInput(panelLeft + 12 + (fieldWidth + fieldGap) * 2,
                fieldY, fieldWidth, "HDG", selectedHeading);
        int setButtonX = compactLayout ? panelLeft + panelWidth - 68 : panelLeft + 244;
        int setButtonWidth = compactLayout ? 56 : Math.max(58, panelWidth - 324);
        addRenderableWidget(Button.builder(Component.literal("SET ILS"), button -> setIls())
                .bounds(setButtonX, fieldY, setButtonWidth, 20).build());
        if (compactLayout) {
            int buttonGap = 3;
            int btnWidth = Math.max(40, (panelWidth - 24 - buttonGap * 4) / 5);
            int btnY = panelTop + 26;
            addRenderableWidget(Button.builder(thresholdPickButtonLabel(), button -> toggleThresholdPickMode(button))
                    .bounds(panelLeft + 12, btnY, btnWidth, 18).build());
            addRenderableWidget(Button.builder(Component.literal("USE"), button -> useSelectedContact())
                    .bounds(panelLeft + 12 + btnWidth + buttonGap, btnY, btnWidth, 18).build());
            addRenderableWidget(Button.builder(Component.literal(headingUp ? "HDG" : "NORTH"),
                    button -> toggleOrientation(button)).bounds(panelLeft + 12 + (btnWidth + buttonGap) * 2, btnY, btnWidth, 18).build());
            addRenderableWidget(Button.builder(weatherLayerLabel(), button -> toggleWeatherLayer(button))
                    .bounds(panelLeft + 12 + (btnWidth + buttonGap) * 3, btnY, btnWidth, 18).build());
            addRenderableWidget(Button.builder(rangeLabel(), button -> changeRange(1))
                    .bounds(panelLeft + 12 + (btnWidth + buttonGap) * 4, btnY, btnWidth, 18).build());
            addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                    .bounds(panelLeft + panelWidth - 68, panelTop + panelHeight - 78, 56, 18).build());
        } else {
            addRenderableWidget(Button.builder(pickThresholdLabel(), button -> toggleThresholdPickMode(button))
                    .bounds(panelLeft + panelWidth - 222, panelTop + 26, 104, 18).build());
            addRenderableWidget(Button.builder(Component.literal("USE CONTACT"), button -> useSelectedContact())
                    .bounds(panelLeft + panelWidth - 112, panelTop + 26, 100, 18).build());
            addRenderableWidget(Button.builder(Component.literal(headingUp ? "HDG UP" : "NORTH UP"),
                    button -> toggleOrientation(button)).bounds(panelLeft + 12, panelTop + 26, 78, 18).build());
            addRenderableWidget(Button.builder(weatherLayerLabel(), button -> toggleWeatherLayer(button))
                    .bounds(panelLeft + 96, panelTop + 26, 74, 18).build());
            addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                    .bounds(panelLeft + panelWidth - 72, fieldY, 60, 20).build());
            addRenderableWidget(Button.builder(Component.literal("RANGE -"), button -> changeRange(-1))
                    .bounds(panelLeft + panelWidth - 174, panelTop + panelHeight - 84, 76, 18).build());
            addRenderableWidget(Button.builder(Component.literal("RANGE +"), button -> changeRange(1))
                    .bounds(panelLeft + panelWidth - 94, panelTop + panelHeight - 84, 82, 18).build());
            addRenderableWidget(Button.builder(Component.literal("TCAS TEST"),
                            button -> FlightLineClient.playTcasTest(Minecraft.getInstance()))
                    .bounds(panelLeft + panelWidth - 174, panelTop + panelHeight - 108, 164, 18).build());
        }
    }

    private EditBox coordinateInput(int x, int y, int width, String hint, int value) {
        EditBox input = new EditBox(font, x, y, width, 20, Component.literal(hint));
        input.setHint(Component.literal(hint));
        input.setValue(String.valueOf(value));
        input.setMaxLength(10);
        addRenderableWidget(input);
        return input;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        animateRadar();
        scaler.update(width, height);
        scaler.push(graphics);
        graphics.fill(0, 0, scaler.width(), scaler.height(), 0xB8000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121A22);
        int frameColor = ((long) (System.nanoTime() / 750_000_000L) & 1L) == 0L
                ? 0xFF4EB8C8 : 0xFF347988;
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, frameColor);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 24, 0xFF1C2733);
        graphics.centeredText(font, atcSelectionMode ? "SELECT ATC TARGET  ·  AIRCRAFT SCOPE"
                        : "FLIGHT RADAR  ·  TCAS / TRAFFIC",
                panelLeft + panelWidth / 2, panelTop + 7, 0xFF72E6F5);
        int revealWidth = Math.round(panelWidth * openProgress);
        graphics.fill(panelLeft, panelTop + 23, panelLeft + revealWidth, panelTop + 24, 0xFF72E6F5);

        graphics.fill(scopeLeft, scopeTop, scopeLeft + scopeWidth, scopeTop + scopeHeight, 0xFF08130F);
        graphics.outline(scopeLeft, scopeTop, scopeWidth, scopeHeight, 0xFF426B5C);
        int cx = scopeLeft + scopeWidth / 2;
        int cy = scopeTop + scopeHeight / 2;
        int radius = Math.max(14, Math.min(scopeWidth, scopeHeight) / 2 - 12);
        if (weatherLayer) {
            var level = Minecraft.getInstance().level;
            long gameTime = level == null ? 0L : level.getGameTime();
            WeatherDisplay.drawRadarCells(graphics, cx, cy, radius, RANGES[rangeIndex],
                    ownX(), ownZ(), headingUp, FlightHud.radarOwnHeadingDegrees(),
                    gameTime, WeatherDisplay.CELL_SEED, FlightHud.weatherGuidance());
        }
        drawLine(graphics, scopeLeft + 1, cy, scopeLeft + scopeWidth - 1, cy, 0xFF244538);
        drawLine(graphics, cx, scopeTop + 1, cx, scopeTop + scopeHeight - 1, 0xFF244538);
        for (int ring = 1; ring <= 3; ring++) {
            int r = radius * ring / 3;
            drawCircle(graphics, cx, cy, r, 0xFF315E50);
            graphics.text(font, String.format(Locale.ROOT, "%.0f NM",
                    RANGES[rangeIndex] * ring / 30.0f), cx + 2, cy - r - 4, 0x9956B9D2, false);
        }
        double scanRadians = Math.toRadians(scanAngle);
        int scanX = cx + (int) Math.round(Math.sin(scanRadians) * radius);
        int scanY = cy - (int) Math.round(Math.cos(scanRadians) * radius);
        for (int trail = 5; trail >= 1; trail--) {
            double trailingRadians = scanRadians - Math.toRadians(trail * 3.0);
            int trailX = cx + (int) Math.round(Math.sin(trailingRadians) * radius);
            int trailY = cy - (int) Math.round(Math.cos(trailingRadians) * radius);
            drawLine(graphics, cx, cy, trailX, trailY, trail <= 2 ? 0x604EA87B : 0x304EA87B);
        }
        drawLine(graphics, cx, cy, scanX, scanY, 0xFF65F2A5);

        if (!compactLayout) {
            if (headingUp) {
                graphics.text(font, "HDG UP", scopeLeft + 4, scopeTop + 3, 0xFF72E6F5, true);
                graphics.text(font, String.format(Locale.ROOT, "%03.0f°", FlightHud.radarOwnHeadingDegrees()),
                        cx - 10, scopeTop + 3, 0xFFB6C8D1, true);
            } else {
                graphics.text(font, "NORTH UP", scopeLeft + 4, scopeTop + 3, 0xFF72E6F5, true);
                graphics.text(font, "N", cx - 2, scopeTop + 3, 0xFFB6C8D1, true);
            }
        }
        graphics.text(font, String.format(Locale.ROOT, "RANGE %.0f NM", RANGES[rangeIndex] / 10.0f),
                scopeLeft + 4, scopeTop + scopeHeight - 11, 0xFF86B5A4, true);

        List<FlightRadarPayload.Contact> contacts = FlightHud.radarContacts();
        for (FlightRadarPayload.Contact contact : contacts) {
            int[] point = contactPoint(contact, cx, cy, radius);
            if (point == null) continue;
            if (contact.entityId() == selectedContactId) {
                int pulse = 4 + (int) Math.round((Math.sin(System.nanoTime() / 150_000_000.0) + 1.0) * 2.0);
                drawCircle(graphics, point[0], point[1], pulse, 0xFFFF8B52);
            }
            drawContact(graphics, point[0], point[1], contact);
        }

        // Own aircraft chevron
        if (headingUp) {
            drawLine(graphics, cx, cy - 5, cx - 4, cy + 4, 0xFFFFFFFF);
            drawLine(graphics, cx, cy - 5, cx + 4, cy + 4, 0xFFFFFFFF);
            drawLine(graphics, cx - 4, cy + 4, cx, cy + 2, 0xFFFFFFFF);
            drawLine(graphics, cx + 4, cy + 4, cx, cy + 2, 0xFFFFFFFF);
        } else {
            double hdgRad = Math.toRadians(FlightHud.radarOwnHeadingDegrees());
            int tipX = cx + (int) Math.round(Math.sin(hdgRad) * 5);
            int tipY = cy - (int) Math.round(Math.cos(hdgRad) * 5);
            double leftRad = hdgRad + 2.5;
            double rightRad = hdgRad - 2.5;
            int lx = cx + (int) Math.round(Math.sin(leftRad) * 5);
            int ly = cy - (int) Math.round(Math.cos(leftRad) * 5);
            int rx = cx + (int) Math.round(Math.sin(rightRad) * 5);
            int ry = cy - (int) Math.round(Math.cos(rightRad) * 5);
            drawLine(graphics, tipX, tipY, lx, ly, 0xFFFFFFFF);
            drawLine(graphics, tipX, tipY, rx, ry, 0xFFFFFFFF);
            drawLine(graphics, lx, ly, cx, cy, 0xFFFFFFFF);
            drawLine(graphics, rx, ry, cx, cy, 0xFFFFFFFF);
        }

        int[] selectedPoint = worldPoint(selectedX, selectedZ, cx, cy, radius);
        if (Math.hypot(selectedPoint[0] - cx, selectedPoint[1] - cy) <= radius) {
            graphics.outline(selectedPoint[0] - 4, selectedPoint[1] - 4, 8, 8,
                    thresholdPickMode ? 0xFFFF6CD9 : 0xFFB454B7);
            drawRunwayPreview(graphics, selectedPoint[0], selectedPoint[1], radius);
        }

        if (!compactLayout) {
            int detailX = scopeLeft + scopeWidth + 10;
            int detailWidth = panelLeft + panelWidth - 10 - detailX;
            graphics.text(font, "TRAFFIC " + contacts.size(), detailX, scopeTop + 2,
                    0xFF72E6F5, true);
            graphics.text(font, "OWN " + FlightHud.currentSpeedKnots() + "KT  "
                    + FlightHud.radarOwnAltitudeFeet() + "FT", detailX, scopeTop + 13, 0xFFD5E0E8, true);
            graphics.text(font, String.format(Locale.ROOT, "RANGE %.0f NM  HDG %03.0f°",
                    RANGES[rangeIndex] / 10.0f, FlightHud.radarOwnHeadingDegrees()),
                    detailX, scopeTop + 24, 0xFF9BAAB1, true);
            graphics.horizontalLine(detailX, detailX + detailWidth, scopeTop + 36, 0xFF426B5C);

            int listY = scopeTop + 42;
            int availableRows = Math.max(0, (panelTop + panelHeight - 112 - listY) / 18);
            contactListOffset = Math.min(contactListOffset, Math.max(0, contacts.size() - availableRows));
            int shown = Math.min(availableRows, Math.max(0, contacts.size() - contactListOffset));
            for (int i = 0; i < shown; i++) {
                FlightRadarPayload.Contact contact = contacts.get(i + contactListOffset);
                int color = contact.player() ? 0xFF6BCBFF : contact.airborne() ? 0xFF75E6A5 : 0xFFFFCE52;
                double rangeNm = contactRange(contact) / 10.0f;
                String name = fit((contact.player() ? "CREW " : "AUTO ") + contact.name()
                        + String.format(Locale.ROOT, "  %.1fNM", rangeNm),
                        Math.max(24, detailWidth - 4));
                boolean selected = contact.entityId() == selectedContactId;
                int rowY = listY + i * 18;
                if (selected) {
                    graphics.fill(detailX - 2, rowY - 1, detailX + detailWidth, rowY + 17, 0x8045A7B8);
                }
                graphics.text(font, name, detailX, rowY, selected ? 0xFFFFFFFF : color, true);
                String data = String.format(Locale.ROOT, "%dKT %+dFT %03.0f° %s",
                        contact.speedKnots(), contact.altitudeFeet() - FlightHud.radarOwnAltitudeFeet(),
                        contact.headingDegrees(),
                        contact.airborne() ? "AIR" : "GND");
                graphics.text(font, fit(data, Math.max(24, detailWidth - 4)), detailX,
                        rowY + 9, 0xFFB6C8D1, true);
            }
            if (contacts.isEmpty()) {
                graphics.text(font, "No traffic in range.", detailX, listY, 0xFF9BAAB1, true);
            }
        } else {
            String scopeInfo = "TRAFFIC " + contacts.size()
                    + " · " + (headingUp ? "HDG" : "NORTH") + " · RANGE "
                    + String.format(Locale.ROOT, "%.0f NM", RANGES[rangeIndex] / 10.0f)
                    + " · " + FlightHud.radarOwnAltitudeFeet() + " FT";
            graphics.text(font, fit(scopeInfo, scopeWidth - 8), scopeLeft + 4, scopeTop + 4, 0xFF72E6F5, true);
        }
        if (selectedContactId >= 0) {
            FlightRadarPayload.Contact selected = findContact(selectedContactId);
            if (selected != null) {
                graphics.text(font, fit("SELECTED: " + selected.name() + " · "
                                + selected.speedKnots() + "KT · "
                                + (selected.altitudeFeet() - FlightHud.radarOwnAltitudeFeet()) + "FT · "
                                + String.format(Locale.ROOT, "%03.0f° · %.1f NM",
                                selected.headingDegrees(), contactRange(selected) / 10.0f),
                        Math.max(1, panelWidth - 24)), panelLeft + 12, panelTop + panelHeight - 98,
                        0xFFFFB46A, true);
            }
        }

        int fieldY = panelTop + panelHeight - 76;
        graphics.text(font, "ILS THRESHOLD  X / Z / HEADING", panelLeft + 12, fieldY,
                0xFFFF6CD9, true);
        graphics.text(font, fit(status, panelWidth - 24), panelLeft + 12, panelTop + panelHeight - 28,
                0xFFB6C8D1, true);
        graphics.text(font, fit("DRAG HEADER TO MOVE · CORNER TO RESIZE · SCROLL SCOPE TO ZOOM",
                panelWidth - 40), panelLeft + 12, panelTop + panelHeight - 14, 0xFF71858B, true);
        drawResizeHandle(graphics);
        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        event = scaler.map(event);
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() == 0 && inResizeHandle(mouseX, mouseY)) {
            resizingWindow = true;
            resizeStartX = (int) mouseX;
            resizeStartY = (int) mouseY;
            resizeStartWidth = panelWidth;
            resizeStartHeight = panelHeight;
            return true;
        }
        if (event.button() == 0 && mouseY >= panelTop && mouseY < panelTop + 24
                && mouseX >= panelLeft && mouseX <= panelLeft + panelWidth) {
            draggingWindow = true;
            dragOffsetX = mouseX - panelLeft;
            dragOffsetY = mouseY - panelTop;
            return true;
        }
        if (event.button() == 0 && !compactLayout && clickContactRow(mouseX, mouseY)) return true;
        if (event.button() == 0 && mouseX >= scopeLeft && mouseX <= scopeLeft + scopeWidth
                && mouseY >= scopeTop && mouseY <= scopeTop + scopeHeight) {
            int cx = scopeLeft + scopeWidth / 2;
            int cy = scopeTop + scopeHeight / 2;
            int radius = Math.max(14, Math.min(scopeWidth, scopeHeight) / 2 - 12);
            FlightRadarPayload.Contact bestContact = null;
            double bestDistance = 49.0;
            List<FlightRadarPayload.Contact> contacts = FlightHud.radarContacts();
            for (FlightRadarPayload.Contact contact : contacts) {
                int[] point = contactPoint(contact, cx, cy, radius);
                if (point == null) continue;
                double dx = point[0] - mouseX;
                double dy = point[1] - mouseY;
                double distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestContact = contact;
                }
            }
            if (bestContact != null) {
                selectContact(bestContact);
            } else if (thresholdPickMode) {
                selectedContactId = -1;
                double screenDx = (mouseX - cx) / (double) radius * RANGES[rangeIndex];
                double screenDz = (mouseY - cy) / (double) radius * RANGES[rangeIndex];
                if (headingUp) {
                    double angleRad = Math.toRadians(FlightHud.radarOwnHeadingDegrees());
                    double cos = Math.cos(angleRad);
                    double sin = Math.sin(angleRad);
                    double rx = screenDx * cos - screenDz * sin;
                    double rz = screenDx * sin + screenDz * cos;
                    screenDx = rx;
                    screenDz = rz;
                }
                double worldX = ownX() + screenDx;
                double worldZ = ownZ() + screenDz;
                selectedX = (int) Math.round(worldX);
                selectedZ = (int) Math.round(worldZ);
                xInput().setValue(String.valueOf(selectedX));
                zInput().setValue(String.valueOf(selectedZ));
                status = "Runway threshold selected. Set runway heading and apply ILS.";
            } else {
                status = "No contact selected. Enable PICK THRESHOLD to choose an ILS point.";
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        event = scaler.map(event);
        if (draggingWindow) {
            int newLeft = (int) Math.round(event.x() - dragOffsetX);
            int newTop = (int) Math.round(event.y() - dragOffsetY);
            moveWindow(newLeft, newTop);
            return true;
        }
        if (resizingWindow) {
            int nextWidth = Math.max(300, Math.min(scaler.width() - panelLeft - 4,
                    resizeStartWidth + (int) Math.round(event.x()) - resizeStartX));
            int nextHeight = Math.max(220, Math.min(scaler.height() - panelTop - 4,
                    resizeStartHeight + (int) Math.round(event.y()) - resizeStartY));
            if (nextWidth != panelWidth || nextHeight != panelHeight) {
                saveIlsInputs();
                rememberedWidth = nextWidth;
                rememberedHeight = nextHeight;
                init(width, height);
            }
            return true;
        }
        return super.mouseDragged(event, deltaX / scaler.scale(), deltaY / scaler.scale());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingWindow = false;
        resizingWindow = false;
        return super.mouseReleased(scaler.map(event));
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
        if (!compactLayout && logicalX > scopeLeft + scopeWidth && logicalY >= scopeTop + 58) {
            int maxRows = Math.max(0, FlightHud.radarContacts().size() - 1);
            contactListOffset = Math.max(0, Math.min(maxRows,
                    contactListOffset + (deltaY < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(logicalX, logicalY, deltaX, deltaY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    private void setIls() {
        saveIlsInputs();
        try {
            selectedX = Integer.parseInt(xInput().getValue().trim());
            selectedZ = Integer.parseInt(zInput().getValue().trim());
            selectedHeading = Math.floorMod(Integer.parseInt(headingInput().getValue().trim()), 360);
        } catch (NumberFormatException exception) {
            status = "Enter valid X, Z, and heading values.";
            return;
        }
        if (!FlightLineClient.isFlightEnabled()) {
            status = "Configure an aircraft before setting an ILS runway.";
            return;
        }
        ClientPlayNetworking.send(new ConfigureIlsPayload(true, FlightHud.ilsReferenceGlideSlope(),
                selectedX, selectedZ, selectedHeading));
        status = "ILS runway destination submitted · " + selectedX + ", " + selectedZ
                + " · HDG " + String.format(Locale.ROOT, "%03d", selectedHeading) + "°.";
    }

    private void changeRange(int delta) {
        rangeIndex = Math.max(0, Math.min(RANGES.length - 1, rangeIndex + delta));
        if (compactLayout) {
            for (var child : children()) {
                if (child instanceof Button button && button.getMessage().getString().startsWith("RNG")) {
                    button.setMessage(rangeLabel());
                }
            }
        }
    }

    private Component rangeLabel() {
        return Component.literal(String.format(Locale.ROOT, "RNG %.0f NM", RANGES[rangeIndex] / 10.0f));
    }

    private int[] contactPoint(FlightRadarPayload.Contact contact, int cx, int cy, int radius) {
        AnimatedContact motion = animatedContacts.get(contact.entityId());
        double x = motion == null ? contact.x() : motion.x;
        double z = motion == null ? contact.z() : motion.z;
        double dx = x - ownX();
        double dz = z - ownZ();
        double distance = Math.hypot(dx, dz);
        if (distance > RANGES[rangeIndex]) return null;

        if (headingUp) {
            double angleRad = Math.toRadians(-FlightHud.radarOwnHeadingDegrees());
            double cos = Math.cos(angleRad);
            double sin = Math.sin(angleRad);
            double rx = dx * cos - dz * sin;
            double rz = dx * sin + dz * cos;
            dx = rx;
            dz = rz;
        }

        double scale = radius / (double) RANGES[rangeIndex];
        return new int[]{cx + (int) Math.round(dx * scale), cy + (int) Math.round(dz * scale)};
    }

    private int contactRange(FlightRadarPayload.Contact contact) {
        return (int) Math.round(Math.hypot(contact.x() - ownX(), contact.z() - ownZ()));
    }

    private void toggleOrientation(Button button) {
        headingUp = !headingUp;
        button.setMessage(Component.literal(headingUp ? "HDG UP" : "NORTH UP"));
    }

    private Component weatherLayerLabel() {
        return Component.literal(weatherLayer ? "WX ON" : "WX OFF");
    }

    private void toggleWeatherLayer(Button button) {
        weatherLayer = !weatherLayer;
        button.setMessage(weatherLayerLabel());
        status = weatherLayer ? "Weather cell layer visible under traffic."
                : "Weather cell layer hidden.";
    }

    private void animateRadar() {
        long now = System.nanoTime();
        float deltaSeconds = Math.min(0.1f, Math.max(0.0f, (now - lastFrameNanos) / 1_000_000_000.0f));
        lastFrameNanos = now;
        float blend = 1.0f - (float) Math.exp(-deltaSeconds * 7.0f);
        scanAngle = (scanAngle + deltaSeconds * 105.0f) % 360.0f;
        openProgress = Math.min(1.0f, openProgress + deltaSeconds * 5.0f);
        for (FlightRadarPayload.Contact contact : FlightHud.radarContacts()) {
            AnimatedContact motion = animatedContacts.computeIfAbsent(contact.entityId(),
                    ignored -> new AnimatedContact(contact.x(), contact.z()));
            motion.x += (contact.x() - motion.x) * blend;
            motion.z += (contact.z() - motion.z) * blend;
        }
        animatedContacts.keySet().removeIf(id -> FlightHud.radarContacts().stream()
                .noneMatch(contact -> contact.entityId() == id));
    }

    private void drawRunwayPreview(GuiGraphicsExtractor graphics, int thresholdX, int thresholdY, int radius) {
        double heading = headingUp ? (selectedHeading - FlightHud.radarOwnHeadingDegrees()) : selectedHeading;
        double runway = Math.toRadians(heading);
        double approach = runway + Math.PI;
        int runwayX = thresholdX + (int) Math.round(-Math.sin(runway) * 14);
        int runwayY = thresholdY + (int) Math.round(Math.cos(runway) * 14);
        int approachX = thresholdX + (int) Math.round(-Math.sin(approach) * radius * 0.65);
        int approachY = thresholdY + (int) Math.round(Math.cos(approach) * radius * 0.65);
        drawLine(graphics, thresholdX, thresholdY, runwayX, runwayY, 0xFFFF6CD9);
        drawLine(graphics, thresholdX, thresholdY, approachX, approachY, 0x80FF6CD9);
    }

    private void drawResizeHandle(GuiGraphicsExtractor graphics) {
        int x = panelLeft + panelWidth - 12;
        int y = panelTop + panelHeight - 12;
        for (int line = 0; line < 3; line++) {
            drawLine(graphics, x + 5 + line * 3, y + 11, x + 11, y + 5 + line * 3, 0xFF72E6F5);
        }
    }

    private boolean inResizeHandle(double x, double y) {
        return x >= panelLeft + panelWidth - 16 && x <= panelLeft + panelWidth
                && y >= panelTop + panelHeight - 16 && y <= panelTop + panelHeight;
    }

    private void moveWindow(int left, int top) {
        int nextLeft = Math.max(4, Math.min(scaler.width() - panelWidth - 4, left));
        int nextTop = Math.max(4, Math.min(scaler.height() - panelHeight - 4, top));
        int dx = nextLeft - panelLeft;
        int dy = nextTop - panelTop;
        if (dx == 0 && dy == 0) return;
        panelLeft = nextLeft;
        panelTop = nextTop;
        scopeLeft += dx;
        scopeTop += dy;
        for (var child : children()) {
            if (child instanceof AbstractWidget widget) {
                widget.setX(widget.getX() + dx);
                widget.setY(widget.getY() + dy);
            }
        }
        rememberedLeft = panelLeft;
        rememberedTop = panelTop;
    }

    private void saveIlsInputs() {
        if (xInput != null) {
            try { selectedX = Integer.parseInt(xInput.getValue().trim()); }
            catch (NumberFormatException ignored) { }
        }
        if (zInput != null) {
            try { selectedZ = Integer.parseInt(zInput.getValue().trim()); }
            catch (NumberFormatException ignored) { }
        }
        if (headingInput != null) {
            try { selectedHeading = Math.floorMod(Integer.parseInt(headingInput.getValue().trim()), 360); }
            catch (NumberFormatException ignored) { }
        }
    }

    private boolean clickContactRow(double x, double y) {
        int detailX = scopeLeft + scopeWidth + 10;
        int detailWidth = panelLeft + panelWidth - 10 - detailX;
        int listY = scopeTop + 42;
        int availableRows = Math.max(0, (panelTop + panelHeight - 112 - listY) / 18);
        if (x < detailX - 2 || x > detailX + detailWidth || y < listY || y >= listY + availableRows * 18) {
            return false;
        }
        int row = (int) ((y - listY) / 18) + contactListOffset;
        List<FlightRadarPayload.Contact> contacts = FlightHud.radarContacts();
        if (row >= 0 && row < contacts.size()) {
            selectContact(contacts.get(row));
            return true;
        }
        return false;
    }

    private void selectContact(FlightRadarPayload.Contact contact) {
        selectedContactId = contact.entityId();
        if (atcSelectionMode) {
            FlightLineClient.setAtcSelectedTarget(contact.entityId());
            Minecraft.getInstance().gui.setScreen(new AtcScreen());
            return;
        }
        status = "Selected " + contact.name() + " · "
                + String.format(Locale.ROOT, "%.1f NM", contactRange(contact) / 10.0f) + " · "
                + contact.speedKnots() + " KT · "
                + (contact.altitudeFeet() - FlightHud.radarOwnAltitudeFeet()) + " FT relative · HDG "
                + String.format(Locale.ROOT, "%03.0f", contact.headingDegrees()) + "°.";
    }

    private void useSelectedContact() {
        FlightRadarPayload.Contact contact = findContact(selectedContactId);
        if (contact == null) {
            status = "Select a traffic contact first.";
            return;
        }
        selectedX = contact.x();
        selectedZ = contact.z();
        selectedHeading = Math.floorMod(Math.round(contact.headingDegrees()), 360);
        xInput().setValue(String.valueOf(selectedX));
        zInput().setValue(String.valueOf(selectedZ));
        headingInput().setValue(String.valueOf(selectedHeading));
        status = "Copied contact position to ILS fields. Confirm runway heading before SET ILS.";
    }

    private FlightRadarPayload.Contact findContact(int id) {
        return FlightHud.radarContacts().stream().filter(contact -> contact.entityId() == id)
                .findFirst().orElse(null);
    }

    private Component pickThresholdLabel() {
        return Component.literal(thresholdPickMode ? "CANCEL PICK" : "PICK THRESHOLD");
    }

    private Component thresholdPickButtonLabel() {
        return Component.literal(compactLayout
                ? (thresholdPickMode ? "CANCEL" : "PICK")
                : (thresholdPickMode ? "CANCEL PICK" : "PICK THRESHOLD"));
    }

    private void toggleThresholdPickMode(Button button) {
        thresholdPickMode = !thresholdPickMode;
        button.setMessage(thresholdPickButtonLabel());
        status = thresholdPickMode
                ? "Click the scope to place an ILS runway threshold."
                : "Contact selection mode · click a blip or contact row.";
    }

    private static final class AnimatedContact {
        private double x;
        private double z;

        private AnimatedContact(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    private int[] worldPoint(int x, int z, int cx, int cy, int radius) {
        double dx = x - ownX();
        double dz = z - ownZ();
        if (headingUp) {
            double angleRad = Math.toRadians(-FlightHud.radarOwnHeadingDegrees());
            double cos = Math.cos(angleRad);
            double sin = Math.sin(angleRad);
            double rx = dx * cos - dz * sin;
            double rz = dx * sin + dz * cos;
            dx = rx;
            dz = rz;
        }
        double scale = radius / (double) RANGES[rangeIndex];
        int px = cx + (int) Math.round(dx * scale);
        int py = cy + (int) Math.round(dz * scale);
        return new int[]{px, py};
    }

    private void drawContact(GuiGraphicsExtractor graphics, int x, int y,
                             FlightRadarPayload.Contact contact) {
        int ownAlt = FlightHud.radarOwnAltitudeFeet();
        int relAlt = contact.altitudeFeet() - ownAlt;
        int relAltHundreds = Math.round(relAlt / 100.0f);
        int dist = contactRange(contact);

        boolean isRa = contact.airborne() && dist < 120 && Math.abs(relAlt) < 600;
        boolean isTa = !isRa && contact.airborne() && dist < 250 && Math.abs(relAlt) < 850;
        boolean isProximate = !isRa && !isTa && contact.airborne() && dist < 450 && Math.abs(relAlt) < 1200;

        if (isRa) {
            // Resolution Advisory (RA): Red solid square
            graphics.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFF2525);
            graphics.outline(x - 4, y - 4, 8, 8, 0xFFFFFFFF);
        } else if (isTa) {
            // Traffic Advisory (TA): Amber solid circle
            drawFilledCircle(graphics, x, y, 3, 0xFFFFB000);
            drawCircle(graphics, x, y, 4, 0xFFFFFFFF);
        } else if (isProximate) {
            // Proximate Traffic: Solid cyan diamond
            drawFilledDiamond(graphics, x, y, 3, 0xFF00E5FF);
        } else if (contact.airborne()) {
            // Other Airborne Traffic: Hollow cyan diamond
            drawDiamond(graphics, x, y, 3, 0xFF40C4FF);
        } else {
            // Ground Traffic: Small hollow square
            graphics.outline(x - 2, y - 2, 5, 5, 0xFF9E8A68);
        }

        // Velocity leader line pointing along heading track
        double hdg = headingUp ? (contact.headingDegrees() - FlightHud.radarOwnHeadingDegrees()) : contact.headingDegrees();
        double rad = Math.toRadians(hdg);
        int leaderLen = Math.max(4, Math.min(12, contact.speedKnots() / 15));
        int lx = x + (int) Math.round(Math.sin(rad) * leaderLen);
        int ly = y - (int) Math.round(Math.cos(rad) * leaderLen);
        drawLine(graphics, x, y, lx, ly, isRa ? 0xFFFF2525 : isTa ? 0xFFFFB000 : 0xFF75E6A5);

        // Relative altitude tag and vertical speed trend arrow
        if (contact.airborne() && Math.min(scopeWidth, scopeHeight) >= 60) {
            String altTag = (relAltHundreds >= 0 ? "+" : "") + String.format(Locale.ROOT, "%02d", relAltHundreds);
            String arrow = contact.verticalSpeedFpm() > 100 ? "▲" : contact.verticalSpeedFpm() < -100 ? "▼" : "";
            String fullTag = altTag + arrow;
            int tagColor = isRa ? 0xFFFF2525 : isTa ? 0xFFFFB000 : 0xFF86B5A4;
            graphics.text(font, fullTag, x + 5, y - 4, tagColor, false);
        }
    }

    private void drawDiamond(GuiGraphicsExtractor graphics, int cx, int cy, int size, int color) {
        drawLine(graphics, cx, cy - size, cx + size, cy, color);
        drawLine(graphics, cx + size, cy, cx, cy + size, color);
        drawLine(graphics, cx, cy + size, cx - size, cy, color);
        drawLine(graphics, cx - size, cy, cx, cy - size, color);
    }

    private void drawFilledDiamond(GuiGraphicsExtractor graphics, int cx, int cy, int size, int color) {
        for (int dy = -size; dy <= size; dy++) {
            int width = size - Math.abs(dy);
            graphics.fill(cx - width, cy + dy, cx + width + 1, cy + dy + 1, color);
        }
    }

    private void drawFilledCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.round(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private void drawCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        int x = radius;
        int y = 0;
        int error = 1 - radius;
        while (x >= y) {
            graphics.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
            graphics.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
            graphics.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
            graphics.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
            graphics.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
            graphics.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);
            graphics.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
            graphics.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
            y++;
            if (error < 0) error += 2 * y + 1;
            else {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    private void drawLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
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

    private EditBox xInput() {
        return java.util.Objects.requireNonNull(xInput);
    }

    private EditBox zInput() {
        return java.util.Objects.requireNonNull(zInput);
    }

    private EditBox headingInput() {
        return java.util.Objects.requireNonNull(headingInput);
    }

    @Override
    public void onClose() {
        if (atcSelectionMode) {
            Minecraft.getInstance().gui.setScreen(new AtcScreen());
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
