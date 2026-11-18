package dev.flightline.client;

import dev.flightline.network.AtcOrderPayload;
import dev.flightline.network.FlightRadarPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Approach-control console with traffic filters and rich aircraft contact cards. */
public final class AtcScreen extends Screen {
    private enum Filter { ALL, CREWED, AUTO }

    private static final int BASE_WIDTH = 520;
    private static final int BASE_HEIGHT = 360;
    private static final int TEXT = FlightLineTheme.PAPER;
    private static final int MUTED = FlightLineTheme.MUTED;
    private static final int CYAN = FlightLineTheme.CYAN;
    private static final int GREEN = FlightLineTheme.GREEN;
    private static final int AMBER = FlightLineTheme.AMBER;
    private static final int PLAYER = FlightLineTheme.BLUE;

    private final ScreenScaler scaler = new ScreenScaler(BASE_WIDTH, BASE_HEIGHT);
    private int selectedEntityId = FlightLineClient.atcSelectedTarget();
    private int leadEntityId = -1;
    private int contactOffset;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listLeft;
    private int listWidth;
    private int visibleRows;
    private int rowHeight;
    private Filter filter = Filter.ALL;
    private boolean selectingLead;
    private boolean closing;
    private float openProgress;
    private long lastFrameNanos = System.nanoTime();
    private String status = "Select a contact, then issue a clearance or order.";
    private @Nullable AbstractWidget cameraButton;
    private @Nullable EditBox headingInput;
    private @Nullable EditBox altitudeInput;
    private @Nullable EditBox speedInput;

    public AtcScreen() {
        super(Component.literal("Flight Line ATC"));
    }

    @Override
    protected void init() {
        super.init();
        scaler.update(width, height);
        panelWidth = BASE_WIDTH;
        panelHeight = BASE_HEIGHT;
        panelLeft = 8;
        panelTop = 8;
        rowHeight = 30;
        listLeft = x(12);
        listTop = y(54);
        listWidth = w(286);
        visibleRows = Math.max(1, Math.min(8, (h(280) - 22) / Math.max(1, rowHeight)));

        List<FlightRadarPayload.Contact> contacts = filteredContacts();
        if (selectedEntityId < 0 && !contacts.isEmpty()) selectedEntityId = contacts.getFirst().entityId();
        if (selectedEntityId >= 0) FlightLineClient.setAtcSelectedTarget(selectedEntityId);
        FlightRadarPayload.Contact selected = findContact(selectedEntityId);
        int initialHeading = Math.floorMod(Math.round(selected == null
                ? FlightHud.radarOwnHeadingDegrees() : selected.headingDegrees()), 360);

        addRenderableWidget(tabButton("ALL", 0, 12, 58, () -> setFilter(Filter.ALL)));
        addRenderableWidget(tabButton("CREWED", 1, 74, 78, () -> setFilter(Filter.CREWED)));
        addRenderableWidget(tabButton("AUTO", 2, 156, 62, () -> setFilter(Filter.AUTO)));
        addRenderableWidget(FlightLineTheme.button("MAP", x(344), y(28), w(52), h(18), () ->
                Minecraft.getInstance().gui.setScreen(FlightRadarScreen.forAtcSelection())));
        cameraButton = addRenderableWidget(FlightLineTheme.button(
                fit(cameraLabel().getString(), w(106) - 8), x(402), y(28), w(106), h(18), () -> {
                    if (findContact(selectedEntityId) == null) {
                        status = "Select an aircraft contact first.";
                        return;
                    }
                    FlightLineClient.toggleAtcCameraFollow(selectedEntityId);
                    cameraButton.setMessage(Component.literal(
                            fit(cameraLabel().getString(), cameraButton.getWidth() - 8)));
                    status = FlightLineClient.isFollowingAtcTarget(selectedEntityId)
                            ? "Camera following target. Select again to stop."
                            : "Camera follow stopped.";
                }));

        headingInput = numberInput(318, 70, 58, "HDG", initialHeading);
        altitudeInput = numberInput(382, 70, 60, "ALT FT",
                selected == null ? FlightHud.radarOwnAltitudeFeet() : selected.altitudeFeet());
        speedInput = numberInput(448, 70, 58, "KT",
                selected == null ? 130 : Math.max(40, selected.speedKnots()));

        addOrderButton("HOLD", AtcOrderPayload.HOLD, 318, 94, 90);
        addOrderButton("TURN HDG", AtcOrderPayload.TURN, 414, 94, 92);
        addOrderButton("CLIMB", AtcOrderPayload.CLIMB, 318, 120, 90);
        addOrderButton("DESCEND", AtcOrderPayload.DESCEND, 414, 120, 92);
        addOrderButton("SET SPEED", AtcOrderPayload.SPEED, 318, 146, 90);
        addOrderButton("ORBIT ME", AtcOrderPayload.ORBIT, 414, 146, 92);
        addOrderButton("RETURN", AtcOrderPayload.RETURN, 318, 172, 90);
        addLeadButton(414, 172, 92);
        addOrderButton("FOLLOW LEAD", AtcOrderPayload.FOLLOW, 318, 198, 90);
        addOrderButton("RELEASE CTRL", AtcOrderPayload.RELEASE, 414, 198, 92);
        addRenderableWidget(FlightLineTheme.button("TCAS TEST", x(318), y(232), w(188), h(20),
                () -> FlightLineClient.playTcasTest(Minecraft.getInstance())));
        addRenderableWidget(FlightLineTheme.button("CLEAR LEAD", x(318), y(258), w(188), h(20), () -> {
                    leadEntityId = -1;
                    status = "Follow lead cleared.";
                }));
        addRenderableWidget(FlightLineTheme.button("CLOSE", x(418), y(328), w(88), h(18),
                this::onClose));
    }

    private void setFilter(Filter next) {
        if (filter == next) return;
        filter = next;
        contactOffset = 0;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private net.minecraft.client.gui.components.AbstractWidget tabButton(String label, int filterIndex,
                                                                         int baseX, int baseWidth,
                                                                         Runnable action) {
        boolean selected = filter.ordinal() == filterIndex;
        String text = selected ? "[" + label + "]" : label;
        return FlightLineTheme.button(fit(text, w(baseWidth) - 8), x(baseX), y(28), w(baseWidth),
                h(18), action);
    }

    private EditBox numberInput(int baseX, int baseY, int baseWidth, String hint, int value) {
        EditBox input = new EditBox(font, x(baseX), y(baseY), w(baseWidth), h(20), Component.literal(hint));
        input.setHint(Component.literal(hint));
        input.setMaxLength(5);
        input.setValue(String.valueOf(value));
        addRenderableWidget(input);
        return input;
    }

    private void addOrderButton(String label, int order, int baseX, int baseY, int baseWidth) {
        addRenderableWidget(FlightLineTheme.button(fit(label, w(baseWidth) - 8), x(baseX), y(baseY),
                w(baseWidth), h(22), () -> sendOrder(order)));
    }

    private void addLeadButton(int baseX, int baseY, int baseWidth) {
        var holder = new net.minecraft.client.gui.components.AbstractWidget[1];
        holder[0] = FlightLineTheme.button(fit(
                        selectingLead ? "CANCEL LEAD" : "SELECT LEAD", w(baseWidth) - 8),
                x(baseX), y(baseY), w(baseWidth), h(22), () -> {
                    selectingLead = !selectingLead;
                    holder[0].setMessage(Component.literal(fit(
                            selectingLead ? "CANCEL LEAD" : "SELECT LEAD",
                            holder[0].getWidth() - 8)));
                    status = selectingLead ? "Choose an aircraft from the list as the follow lead."
                            : "Lead selection cancelled.";
                });
        addRenderableWidget(holder[0]);
    }

    private void sendOrder(int order) {
        FlightRadarPayload.Contact selected = findContact(selectedEntityId);
        if (selected == null) {
            status = "Select a target aircraft first.";
            return;
        }
        int value;
        try {
            value = switch (order) {
                case AtcOrderPayload.TURN -> parse(headingInput(), 0, 359);
                case AtcOrderPayload.CLIMB, AtcOrderPayload.DESCEND -> parse(altitudeInput(), 0, 40000);
                case AtcOrderPayload.SPEED -> parse(speedInput(), 40, 300);
                default -> 0;
            };
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return;
        }
        if (order == AtcOrderPayload.FOLLOW && leadEntityId < 0) {
            status = "Select a lead aircraft first.";
            return;
        }
        if (order == AtcOrderPayload.RELEASE && !selected.player()) {
            status = "Release applies only to piloted aircraft.";
            return;
        }
        ClientPlayNetworking.send(new AtcOrderPayload(selectedEntityId, order, value, leadEntityId));
        status = order == AtcOrderPayload.RELEASE
                ? "Control released to " + selected.name() + "."
                : selected.player()
                        ? "Clearance loaded into autoflight: " + selected.name() + " is on ATC vectors."
                        : "Vector request sent to " + selected.name() + ".";
    }

    private int parse(EditBox input, int min, int max) {
        try {
            int value = Integer.parseInt(input.getValue().trim());
            if (value < min || value > max) throw new IllegalArgumentException("Value must be " + min + "-" + max + ".");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid number.");
        }
    }

    private List<FlightRadarPayload.Contact> filteredContacts() {
        List<FlightRadarPayload.Contact> all = FlightHud.radarContacts();
        if (filter == Filter.ALL) return all;
        List<FlightRadarPayload.Contact> filtered = new ArrayList<>();
        for (FlightRadarPayload.Contact contact : all) {
            if (filter == Filter.CREWED ? contact.player() : !contact.player()) filtered.add(contact);
        }
        return filtered;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float delta = Math.min(0.05f, Math.max(0.0f, (now - lastFrameNanos) / 1_000_000_000.0f));
        lastFrameNanos = now;
        float targetProgress = closing ? 0.0f : 1.0f;
        openProgress += (targetProgress - openProgress) * (1.0f - (float) Math.exp(-delta * 12.0f));
        if (closing && openProgress < 0.02f) {
            Minecraft.getInstance().gui.setScreen(null);
            return;
        }

        scaler.update(width, height);
        scaler.push(graphics);
        scaler.scrim(graphics);

        FlightLineTheme.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight,
                FlightLineTheme.PANEL, FlightLineTheme.STONE);
        FlightLineTheme.header(graphics, font, panelLeft + 4, panelTop + 4, panelWidth - 8, 24,
                "02", "APPROACH CONTROL  ·  TRAFFIC CONSOLE", AMBER);

        List<FlightRadarPayload.Contact> contacts = filteredContacts();
        if (selectedEntityId >= 0 && !contacts.isEmpty()
                && contacts.stream().noneMatch(contact -> contact.entityId() == selectedEntityId)) {
            selectedEntityId = contacts.getFirst().entityId();
            FlightLineClient.setAtcSelectedTarget(selectedEntityId);
            if (cameraButton != null) cameraButton.setMessage(cameraLabel());
        }
        int maxOffset = Math.max(0, contacts.size() - visibleRows);
        contactOffset = Math.max(0, Math.min(contactOffset, maxOffset));

        drawContactList(graphics, contacts);
        drawOrderPanel(graphics);
        graphics.text(font, fit(status, panelWidth - 24), panelLeft + 12,
                panelTop + panelHeight - 24, CYAN, true);
        graphics.text(font, fit("SELECT A CARD · SCROLL TO BROWSE · RELEASE RETURNS LOCAL CONTROL",
                panelWidth - 24), panelLeft + 12, panelTop + panelHeight - 12, MUTED, true);

        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        scaler.pop(graphics);
    }

    private void drawContactList(GuiGraphicsExtractor graphics, List<FlightRadarPayload.Contact> contacts) {
        int cardTop = y(54);
        int cardHeight = h(280);
        FlightLineTheme.panel(graphics, listLeft, cardTop, listWidth, cardHeight,
                FlightLineTheme.CARD, FlightLineTheme.STONE);
        graphics.fill(listLeft + 2, cardTop + 2, listLeft + listWidth - 2, cardTop + 16,
                FlightLineTheme.HEADER);
        graphics.fill(listLeft + 2, cardTop + 16, listLeft + listWidth - 2, cardTop + 17, CYAN);
        graphics.text(font, fit("CONTACT BOARD  ·  " + filter.name() + "  ·  " + contacts.size(),
                listWidth - 12), listLeft + 7, cardTop + 5, CYAN, false);
        int shown = Math.min(visibleRows, Math.max(0, contacts.size() - contactOffset));
        for (int i = 0; i < shown; i++) {
            FlightRadarPayload.Contact contact = contacts.get(i + contactOffset);
            int rowY = listTop + 18 + i * rowHeight;
            boolean selected = contact.entityId() == selectedEntityId;
            boolean lead = contact.entityId() == leadEntityId;
            if (selected || lead) {
                graphics.fill(listLeft + 4, rowY - 2, listLeft + listWidth - 4, rowY + rowHeight - 3,
                        selected ? 0x8036E6FF : 0x806CC349);
                graphics.fill(listLeft + 4, rowY - 2, listLeft + 7, rowY + rowHeight - 3,
                        selected ? CYAN : GREEN);
            }
            int roleColor = contact.player() ? PLAYER : GREEN;
            String name = String.format(Locale.ROOT, "%s  %s",
                    contact.player() ? "CREW" : "AUTO", contact.name());
            graphics.text(font, fit(name, listWidth - 12), listLeft + 8, rowY, roleColor, true);
            String stats = String.format(Locale.ROOT, "%dKT  %+dFT  HDG %03.0f°  %.1fNM  VS %+d",
                    contact.speedKnots(), contact.altitudeFeet() - FlightHud.radarOwnAltitudeFeet(),
                    contact.headingDegrees(), contactRange(contact) / 10.0f, contact.verticalSpeedFpm());
            graphics.text(font, fit(stats, listWidth - 12), listLeft + 8, rowY + h(10), TEXT, false);
            if (selected) {
                graphics.fill(listLeft + listWidth - 6, rowY + 2, listLeft + listWidth - 3, rowY + rowHeight - 6,
                        AMBER);
            }
        }
        if (contacts.isEmpty()) {
            graphics.text(font, "No contacts match this filter.", listLeft + 8, listTop + 24, MUTED, true);
        }
    }

    private void drawOrderPanel(GuiGraphicsExtractor graphics) {
        int cardX = x(308);
        int cardY = y(54);
        int cardWidth = w(200);
        int cardHeight = h(280);
        FlightLineTheme.panel(graphics, cardX, cardY, cardWidth, cardHeight,
                FlightLineTheme.CARD, FlightLineTheme.STONE);
        graphics.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + 18, FlightLineTheme.HEADER);
        graphics.fill(cardX + 2, cardY + 18, cardX + cardWidth - 2, cardY + 19, AMBER);
        graphics.text(font, fit("CLEARANCE ENTRY", cardWidth - 16), cardX + 9, cardY + 5, AMBER, false);
        FlightRadarPayload.Contact selected = findContact(selectedEntityId);
        String target = selected == null ? "TARGET: NONE"
                : "TARGET: " + selected.name() + (selected.player() ? "  PILOTED" : "  AUTO TRAFFIC");
        graphics.text(font, fit(target, cardWidth - 16), cardX + 8, y(286), selected == null ? MUTED : TEXT, true);
        FlightRadarPayload.Contact lead = findContact(leadEntityId);
        graphics.text(font, fit("LEAD: " + (lead == null ? "not selected" : lead.name()), cardWidth - 16),
                cardX + 8, y(299), MUTED, true);
        graphics.text(font, selected != null && selected.player()
                        ? "Clearance loads into the aircraft's autoflight system."
                        : "Vector requests are advisory for autonomous traffic.",
                cardX + 8, y(312), MUTED, false);
        graphics.text(font, selected != null && selected.airborne() ? "TARGET AIRBORNE" : "TARGET GROUND/UNKNOWN",
                cardX + 8, y(324), selected != null && selected.airborne() ? GREEN : AMBER, true);
    }

    private int contactRange(FlightRadarPayload.Contact contact) {
        return (int) Math.round(Math.hypot(contact.x() - FlightHud.radarOwnX(), contact.z() - FlightHud.radarOwnZ()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        event = scaler.map(event);
        if (event.button() == 0 && event.x() >= listLeft && event.x() <= listLeft + listWidth
                && event.y() >= listTop + 18 && event.y() < listTop + 18 + visibleRows * rowHeight) {
            int row = (int) ((event.y() - listTop - 18) / rowHeight) + contactOffset;
            List<FlightRadarPayload.Contact> contacts = filteredContacts();
            if (row >= 0 && row < contacts.size()) {
                FlightRadarPayload.Contact contact = contacts.get(row);
                if (selectingLead) {
                    leadEntityId = contact.entityId();
                    selectingLead = false;
                    rebuild();
                    status = "Follow lead selected: " + contact.name() + ".";
                } else {
                    selectedEntityId = contact.entityId();
                    FlightLineClient.setAtcSelectedTarget(selectedEntityId);
                    if (cameraButton != null) cameraButton.setMessage(cameraLabel());
                    status = "Selected " + contact.name() + ".";
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private Component cameraLabel() {
        return Component.literal(FlightLineClient.isFollowingAtcTarget(selectedEntityId)
                ? "STOP FOLLOW" : "FOLLOW CAM");
    }

    @Override
    public void onClose() {
        closing = true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
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
        if (logicalX >= listLeft && logicalX <= listLeft + listWidth
                && logicalY >= listTop && logicalY <= listTop + 18 + visibleRows * rowHeight) {
            contactOffset = Math.max(0, Math.min(Math.max(0, filteredContacts().size() - visibleRows),
                    contactOffset + (deltaY < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(logicalX, logicalY, deltaX, deltaY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    private FlightRadarPayload.Contact findContact(int id) {
        return FlightHud.radarContacts().stream().filter(contact -> contact.entityId() == id)
                .findFirst().orElse(null);
    }

    private String fit(String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    private int x(int base) { return panelLeft + base; }
    private int y(int base) { return panelTop + base; }
    private int w(int base) { return Math.max(1, base); }
    private int h(int base) { return Math.max(1, base); }

    private EditBox headingInput() { return java.util.Objects.requireNonNull(headingInput); }
    private EditBox altitudeInput() { return java.util.Objects.requireNonNull(altitudeInput); }
    private EditBox speedInput() { return java.util.Objects.requireNonNull(speedInput); }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
