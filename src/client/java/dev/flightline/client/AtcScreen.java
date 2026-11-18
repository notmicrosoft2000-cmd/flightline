package dev.flightline.client;

import dev.flightline.network.AtcOrderPayload;
import dev.flightline.network.FlightRadarPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Operator ATC console with player/mob filters and rich contact cards. */
public final class AtcScreen extends Screen {
    private enum Filter { ALL, PLAYERS, MOBS }

    private static final int BASE_WIDTH = 520;
    private static final int BASE_HEIGHT = 360;
    private static final int TEXT = 0xFFDCE8EE;
    private static final int MUTED = 0xFF8198A3;
    private static final int CYAN = 0xFF72E6F5;
    private static final int GREEN = 0xFF75E6A5;
    private static final int AMBER = 0xFFFFCE52;
    private static final int PLAYER = 0xFF6BCBFF;

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
    private float scaleX;
    private float scaleY;
    private Filter filter = Filter.ALL;
    private boolean selectingLead;
    private boolean closing;
    private float openProgress;
    private long lastFrameNanos = System.nanoTime();
    private String status = "Select a contact, then issue a clearance or order.";
    private @Nullable Button cameraButton;
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
        scaleX = 1.0f;
        scaleY = 1.0f;
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
        addRenderableWidget(tabButton("PLAYERS", 1, 74, 78, () -> setFilter(Filter.PLAYERS)));
        addRenderableWidget(tabButton("MOBS", 2, 156, 62, () -> setFilter(Filter.MOBS)));
        addRenderableWidget(Button.builder(Component.literal("MAP"), button ->
                        Minecraft.getInstance().gui.setScreen(FlightRadarScreen.forAtcSelection()))
                .bounds(x(344), y(28), w(52), h(18)).build());
        cameraButton = addRenderableWidget(Button.builder(Component.literal(fit(
                cameraLabel().getString(), w(106) - 8)), button -> {
                    if (findContact(selectedEntityId) == null) {
                        status = "Select an aircraft contact first.";
                        return;
                    }
                    FlightLineClient.toggleAtcCameraFollow(selectedEntityId);
                    button.setMessage(Component.literal(fit(cameraLabel().getString(), button.getWidth() - 8)));
                    status = FlightLineClient.isFollowingAtcTarget(selectedEntityId)
                            ? "Camera following target. Select again to stop."
                            : "Camera follow stopped.";
                })
                .bounds(x(402), y(28), w(106), h(18)).build());

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
        addRenderableWidget(Button.builder(Component.literal("TCAS TEST"),
                        button -> FlightLineClient.playTcasTest(Minecraft.getInstance()))
                .bounds(x(318), y(232), w(188), h(20)).build());
        addRenderableWidget(Button.builder(Component.literal("CLEAR LEAD"), button -> {
                    leadEntityId = -1;
                    status = "Follow lead cleared.";
                })
                .bounds(x(318), y(258), w(188), h(20)).build());
        addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                .bounds(x(418), y(328), w(88), h(18)).build());
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

    private Button tabButton(String label, int filterIndex, int baseX, int baseWidth, Runnable action) {
        boolean selected = filter.ordinal() == filterIndex;
        String text = selected ? "[" + label + "]" : label;
        return Button.builder(Component.literal(fit(text, w(baseWidth) - 8)), button -> action.run())
                .bounds(x(baseX), y(28), w(baseWidth), h(18)).build();
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
        addRenderableWidget(Button.builder(Component.literal(fit(label, w(baseWidth) - 8)),
                        button -> sendOrder(order))
                .bounds(x(baseX), y(baseY), w(baseWidth), h(22)).build());
    }

    private void addLeadButton(int baseX, int baseY, int baseWidth) {
        addRenderableWidget(Button.builder(Component.literal(fit(
                        selectingLead ? "CANCEL LEAD" : "SELECT LEAD", w(baseWidth) - 8)),
                        button -> {
                            selectingLead = !selectingLead;
                            button.setMessage(Component.literal(fit(
                                    selectingLead ? "CANCEL LEAD" : "SELECT LEAD", button.getWidth() - 8)));
                            status = selectingLead ? "Choose an aircraft from the list as the follow lead."
                                    : "Lead selection cancelled.";
                        })
                .bounds(x(baseX), y(baseY), w(baseWidth), h(22)).build());
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
            status = "Release applies to player aircraft only.";
            return;
        }
        ClientPlayNetworking.send(new AtcOrderPayload(selectedEntityId, order, value, leadEntityId));
        status = order == AtcOrderPayload.RELEASE
                ? "Release sent to " + selected.name() + "."
                : selected.player()
                        ? "Clearance sent: pilot autopilot is now under ATC control."
                        : "Order sent to " + selected.name() + ".";
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
            if (filter == Filter.PLAYERS ? contact.player() : !contact.player()) filtered.add(contact);
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
        graphics.fill(0, 0, scaler.width(), scaler.height(), 0x30000000);
        var pose = graphics.pose();
        pose.pushMatrix();
        float scale = 0.96f + openProgress * 0.04f;
        pose.translate(scaler.width() / 2.0f, scaler.height() / 2.0f);
        pose.scale(scale, scale);
        pose.translate(-scaler.width() / 2.0f, -scaler.height() / 2.0f);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121A22);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF4EB8C8);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 24, 0xFF1C2733);
        graphics.centeredText(font, fit("OPERATOR ATC  ·  TRAFFIC CONTROL CONSOLE", panelWidth - 16),
                panelLeft + panelWidth / 2, panelTop + 7, CYAN);

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
        graphics.text(font, fit("Click a card to target · scroll to browse · F7 releases pilot control",
                panelWidth - 24), panelLeft + 12, panelTop + panelHeight - 12, MUTED, true);

        super.extractRenderState(graphics, scaler.mouseX(mouseX), scaler.mouseY(mouseY), partialTick);
        pose.popMatrix();
        scaler.pop(graphics);
    }

    private void drawContactList(GuiGraphicsExtractor graphics, List<FlightRadarPayload.Contact> contacts) {
        int cardTop = y(54);
        int cardHeight = h(280);
        graphics.fill(listLeft, cardTop, listLeft + listWidth, cardTop + cardHeight, 0xE3101920);
        graphics.outline(listLeft, cardTop, listWidth, cardHeight, 0xFF2A3D49);
        graphics.text(font, fit("CONTACT BOARD  ·  " + filter.name() + "  ·  " + contacts.size(),
                listWidth - 12), listLeft + 6, cardTop + 5, CYAN, true);
        int shown = Math.min(visibleRows, Math.max(0, contacts.size() - contactOffset));
        for (int i = 0; i < shown; i++) {
            FlightRadarPayload.Contact contact = contacts.get(i + contactOffset);
            int rowY = listTop + 18 + i * rowHeight;
            boolean selected = contact.entityId() == selectedEntityId;
            boolean lead = contact.entityId() == leadEntityId;
            if (selected || lead) {
                graphics.fill(listLeft + 3, rowY - 2, listLeft + listWidth - 3, rowY + rowHeight - 3,
                        selected ? 0x8052B8C8 : 0x805982AB);
            }
            int roleColor = contact.player() ? PLAYER : GREEN;
            String name = String.format(Locale.ROOT, "%s  %s", contact.player() ? "P" : "M", contact.name());
            graphics.text(font, fit(name, listWidth - 12), listLeft + 8, rowY, roleColor, true);
            String stats = String.format(Locale.ROOT, "%dKT  %+dFT  HDG %03.0f°  %dB  VS %+d",
                    contact.speedKnots(), contact.altitudeFeet() - FlightHud.radarOwnAltitudeFeet(),
                    contact.headingDegrees(), contactRange(contact), contact.verticalSpeedFpm());
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
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xE3101920);
        graphics.outline(cardX, cardY, cardWidth, cardHeight, 0xFF2A3D49);
        graphics.text(font, fit("CLEARANCE ENTRY", cardWidth - 16), cardX + 8, cardY + 5, AMBER, true);
        FlightRadarPayload.Contact selected = findContact(selectedEntityId);
        String target = selected == null ? "TARGET: NONE"
                : "TARGET: " + selected.name() + (selected.player() ? "  (PLAYER)" : "  (AI)");
        graphics.text(font, fit(target, cardWidth - 16), cardX + 8, y(286), selected == null ? MUTED : TEXT, true);
        FlightRadarPayload.Contact lead = findContact(leadEntityId);
        graphics.text(font, fit("LEAD: " + (lead == null ? "not selected" : lead.name()), cardWidth - 16),
                cardX + 8, y(299), MUTED, true);
        graphics.text(font, selected != null && selected.player()
                        ? "Player clearance takes over the pilot's autopilot."
                        : "Mob orders are advisory flight directions.",
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

    private int x(int base) { return panelLeft + Math.round(base * scaleX); }
    private int y(int base) { return panelTop + Math.round(base * scaleY); }
    private int w(int base) { return Math.max(1, Math.round(base * scaleX)); }
    private int h(int base) { return Math.max(1, Math.round(base * scaleY)); }

    private EditBox headingInput() { return java.util.Objects.requireNonNull(headingInput); }
    private EditBox altitudeInput() { return java.util.Objects.requireNonNull(altitudeInput); }
    private EditBox speedInput() { return java.util.Objects.requireNonNull(speedInput); }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
