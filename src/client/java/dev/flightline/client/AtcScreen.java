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

import java.util.List;
import java.util.Locale;

public final class AtcScreen extends Screen {
    private int selectedEntityId = FlightLineClient.atcSelectedTarget();
    private int leadEntityId = -1;
    private int contactOffset;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int visibleRows;
    private boolean selectingLead;
    private boolean compactLayout;
    private boolean compactDense;
    private boolean closing;
    private float openProgress;
    private long lastFrameNanos = System.nanoTime();
    private String status = "Select an aircraft to issue a control clearance.";
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
        panelWidth = Math.max(1, Math.min(380, width - 16));
        panelHeight = Math.max(1, Math.min(300, height - 16));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        listTop = panelTop + 48;
        compactLayout = panelWidth < 430 || panelHeight < 310;
        compactDense = compactLayout && panelHeight < 310;
        List<FlightRadarPayload.Contact> contacts = FlightHud.radarContacts();
        if (selectedEntityId < 0 && !contacts.isEmpty()) selectedEntityId = contacts.getFirst().entityId();
        if (selectedEntityId >= 0) FlightLineClient.setAtcSelectedTarget(selectedEntityId);
        FlightRadarPayload.Contact selected = findContact(selectedEntityId);
        int initialHeading = Math.floorMod(Math.round(selected == null
                ? FlightHud.radarOwnHeadingDegrees() : selected.headingDegrees()), 360);

        int colWidth = 96;
        int gap = 6;
        int rowHeight = 20;
        int right = panelLeft + 194;
        int actionsY;
        if (compactLayout) {
            visibleRows = compactDense ? 1 : Math.min(4, Math.max(1, (panelHeight - 205) / 20));
            int fieldWidth = Math.max(38, (panelWidth - 36) / 3);
            int fieldY = listTop + (compactDense ? 16 : visibleRows * 20) + 5;
            headingInput = numberInput(panelLeft + 12, fieldY, fieldWidth, "HDG",
                    initialHeading);
            altitudeInput = numberInput(panelLeft + 18 + fieldWidth, fieldY, fieldWidth, "ALT FT",
                    selected == null ? FlightHud.radarOwnAltitudeFeet() : selected.altitudeFeet());
            speedInput = numberInput(panelLeft + 24 + fieldWidth * 2, fieldY, fieldWidth, "KT",
                    selected == null ? 130 : Math.max(40, selected.speedKnots()));
            actionsY = fieldY + (compactDense ? 21 : 24);
            int actionWidth = Math.max(46, (panelWidth - 30) / 2);
            int buttonHeight = compactDense ? 18 : 20;
            int rowSpacing = buttonHeight + (compactDense ? 2 : 3);
            int secondX = panelLeft + 12 + actionWidth + 6;
            addOrderButton("HOLD", AtcOrderPayload.HOLD, panelLeft + 12, actionsY, actionWidth, buttonHeight);
            addOrderButton("TURN HDG", AtcOrderPayload.TURN, secondX, actionsY, actionWidth, buttonHeight);
            addOrderButton("CLIMB", AtcOrderPayload.CLIMB, panelLeft + 12, actionsY + rowSpacing, actionWidth, buttonHeight);
            addOrderButton("DESCEND", AtcOrderPayload.DESCEND, secondX, actionsY + rowSpacing, actionWidth, buttonHeight);
            addOrderButton("SET SPEED", AtcOrderPayload.SPEED, panelLeft + 12, actionsY + rowSpacing * 2, actionWidth, buttonHeight);
            addOrderButton("ORBIT ME", AtcOrderPayload.ORBIT, secondX, actionsY + rowSpacing * 2, actionWidth, buttonHeight);
            addOrderButton("RETURN", AtcOrderPayload.RETURN, panelLeft + 12, actionsY + rowSpacing * 3, actionWidth, buttonHeight);
            addLeadButton(secondX, actionsY + rowSpacing * 3, actionWidth, buttonHeight);
            addOrderButton("FOLLOW LEAD", AtcOrderPayload.FOLLOW, panelLeft + 12,
                    actionsY + rowSpacing * 4, actionWidth, buttonHeight);
            addOrderButton("RELEASE CTRL", AtcOrderPayload.RELEASE, secondX,
                    actionsY + rowSpacing * 4, actionWidth, buttonHeight);
        } else {
            visibleRows = Math.max(1, (panelHeight - 116) / 20);
            headingInput = numberInput(right, panelTop + 48, 62, "HDG",
                    initialHeading);
            altitudeInput = numberInput(right + 67, panelTop + 48, 70, "ALT FT",
                    selected == null ? FlightHud.radarOwnAltitudeFeet() : selected.altitudeFeet());
            speedInput = numberInput(right + 142, panelTop + 48, 58, "KT",
                    selected == null ? 130 : Math.max(40, selected.speedKnots()));
            actionsY = panelTop + 82;
            addOrderButton("HOLD", AtcOrderPayload.HOLD, right, actionsY, colWidth);
            addOrderButton("TURN HDG", AtcOrderPayload.TURN, right + colWidth + gap, actionsY, colWidth);
            addOrderButton("CLIMB", AtcOrderPayload.CLIMB, right, actionsY + rowHeight + 3, colWidth);
            addOrderButton("DESCEND", AtcOrderPayload.DESCEND, right + colWidth + gap, actionsY + rowHeight + 3, colWidth);
            addOrderButton("SET SPEED", AtcOrderPayload.SPEED, right, actionsY + (rowHeight + 3) * 2, colWidth);
            addOrderButton("ORBIT ME", AtcOrderPayload.ORBIT, right + colWidth + gap,
                    actionsY + (rowHeight + 3) * 2, colWidth);
            addOrderButton("RETURN TO ME", AtcOrderPayload.RETURN, right,
                    actionsY + (rowHeight + 3) * 3, colWidth);
            addLeadButton(right + colWidth + gap, actionsY + (rowHeight + 3) * 3, colWidth, 20);
            addOrderButton("FOLLOW LEAD", AtcOrderPayload.FOLLOW, right,
                    actionsY + (rowHeight + 3) * 4, colWidth);
            addOrderButton("RELEASE CTRL", AtcOrderPayload.RELEASE, right + colWidth + gap,
                    actionsY + (rowHeight + 3) * 4, colWidth);
        }
        int mapButtonWidth = Math.min(70, Math.max(46, panelWidth / 5));
        int mapButtonX = panelLeft + Math.max(12, panelWidth - 196);
        addRenderableWidget(Button.builder(Component.literal("MAP"), button ->
                        Minecraft.getInstance().gui.setScreen(FlightRadarScreen.forAtcSelection()))
                .bounds(mapButtonX, panelTop + 26, mapButtonWidth, 18).build());
        cameraButton = addRenderableWidget(Button.builder(cameraLabel(), button -> {
                    FlightRadarPayload.Contact target = findContact(selectedEntityId);
                    if (target == null) {
                        status = "Select an aircraft contact first.";
                        return;
                    }
                    FlightLineClient.toggleAtcCameraFollow(selectedEntityId);
                    button.setMessage(cameraLabel());
                    status = FlightLineClient.isFollowingAtcTarget(selectedEntityId)
                            ? "Camera following " + target.name() + ". Select FOLLOW CAM again to stop."
                            : "Camera follow stopped.";
                })
                .bounds(panelLeft + panelWidth - 116, panelTop + 26, 104, 18).build());
        addRenderableWidget(Button.builder(Component.literal("CLOSE"), button -> onClose())
                .bounds(panelLeft + panelWidth - 68, panelTop + panelHeight - 28, 58, 18).build());
    }

    private EditBox numberInput(int x, int y, int boxWidth, String hint, int initialValue) {
        EditBox input = new EditBox(font, x, y, boxWidth, 20, Component.literal(hint));
        input.setHint(Component.literal(hint));
        input.setMaxLength(5);
        input.setValue(String.valueOf(initialValue));
        addRenderableWidget(input);
        return input;
    }

    private void addOrderButton(String label, int order, int x, int y, int buttonWidth) {
        addRenderableWidget(Button.builder(Component.literal(label), button -> sendOrder(order))
                .bounds(x, y, buttonWidth, 20).build());
    }

    private void addOrderButton(String label, int order, int x, int y, int buttonWidth, int buttonHeight) {
        addRenderableWidget(Button.builder(Component.literal(label), button -> sendOrder(order))
                .bounds(x, y, buttonWidth, buttonHeight).build());
    }

    private void addLeadButton(int x, int y, int buttonWidth, int buttonHeight) {
        addRenderableWidget(Button.builder(Component.literal(selectingLead ? "CANCEL LEAD" : "SELECT LEAD"),
                        button -> {
                            selectingLead = !selectingLead;
                            button.setMessage(Component.literal(selectingLead ? "CANCEL LEAD" : "SELECT LEAD"));
                            status = selectingLead ? "Choose an aircraft to follow from the contact list."
                                    : "Follow-target selection cancelled.";
                        })
                .bounds(x, y, buttonWidth, buttonHeight).build());
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
        graphics.fill(0, 0, width, height, 0x30000000);
        var pose = graphics.pose();
        pose.pushMatrix();
        float scale = 0.96f + openProgress * 0.04f;
        pose.translate(width / 2.0f, height / 2.0f);
        pose.scale(scale, scale);
        pose.translate(-width / 2.0f, -height / 2.0f);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121A22);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF4EB8C8);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 25, 0xFF1C2733);
        graphics.centeredText(font, fit("OPERATOR ATC  ·  AIRCRAFT CONTROL", panelWidth - 16),
                panelLeft + panelWidth / 2, panelTop + 8, 0xFF72E6F5);
        List<FlightRadarPayload.Contact> contacts = FlightHud.radarContacts();
        int maxOffset = Math.max(0, contacts.size() - visibleRows);
        contactOffset = Math.max(0, Math.min(contactOffset, maxOffset));
        int shown = Math.min(visibleRows, contacts.size() - contactOffset);
        for (int i = 0; i < shown; i++) {
            FlightRadarPayload.Contact contact = contacts.get(i + contactOffset);
            int rowY = listTop + i * 20;
            boolean selected = contact.entityId() == selectedEntityId;
            boolean lead = contact.entityId() == leadEntityId;
            if (selected || lead) {
                graphics.fill(panelLeft + 9, rowY - 2, panelLeft + 184, rowY + 16,
                        selected ? 0x8052B8C8 : 0x805982AB);
            }
            String role = contact.player() ? "P" : "M";
            String row = String.format(Locale.ROOT, "%s %s  %dKT  %+dFT",
                    role, contact.name(), contact.speedKnots(),
                    contact.altitudeFeet() - FlightHud.radarOwnAltitudeFeet());
            graphics.text(font, fit(row, 174), panelLeft + 13, rowY,
                    contact.player() ? 0xFF80CFFF : 0xFF8EE6AA, true);
        }
        if (contacts.isEmpty()) {
            graphics.text(font, "No aircraft contacts in radar range.", panelLeft + 12, listTop,
                    0xFFB6C8D1, true);
        }
        if (compactDense) {
            graphics.text(font, fit(status, panelWidth - 100), panelLeft + 12,
                    panelTop + panelHeight - 14, 0xFF72E6F5, true);
        } else {
            FlightRadarPayload.Contact selected = findContact(selectedEntityId);
            String selection = selected == null ? "TARGET: NONE" : "TARGET: "
                    + selected.name() + (selected.player() ? " (PLAYER · ATC CONTROL)" : " (AI)");
            graphics.text(font, fit(selection, panelWidth - 24), panelLeft + 12,
                    panelTop + panelHeight - 62, 0xFFD5E0E8, true);
            FlightRadarPayload.Contact lead = findContact(leadEntityId);
            graphics.text(font, fit("FOLLOW LEAD: " + (lead == null ? "not selected" : lead.name()),
                            panelWidth - 24),
                    panelLeft + 12, panelTop + panelHeight - 49, 0xFF9BAAB1, true);
            graphics.text(font, fit(status, panelWidth - 24), panelLeft + 12,
                    panelTop + panelHeight - 36, 0xFF72E6F5, true);
            graphics.text(font, "Click a row to target; scroll over contacts for more.",
                    panelLeft + 12, panelTop + panelHeight - 14, 0xFF71858B, true);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        pose.popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.x() >= panelLeft + 8 && event.x() <= panelLeft + 186
                && event.y() >= listTop && event.y() < listTop + visibleRows * 20) {
            int row = (int) ((event.y() - listTop) / 20) + contactOffset;
            List<FlightRadarPayload.Contact> contacts = FlightHud.radarContacts();
            if (row >= 0 && row < contacts.size()) {
                FlightRadarPayload.Contact contact = contacts.get(row);
                if (selectingLead) {
                    leadEntityId = contact.entityId();
                    selectingLead = false;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= panelLeft + 8 && mouseX <= panelLeft + 186
                && mouseY >= listTop && mouseY <= listTop + visibleRows * 20) {
            contactOffset = Math.max(0, Math.min(
                    Math.max(0, FlightHud.radarContacts().size() - visibleRows),
                    contactOffset + (deltaY < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
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

    private EditBox headingInput() { return java.util.Objects.requireNonNull(headingInput); }
    private EditBox altitudeInput() { return java.util.Objects.requireNonNull(altitudeInput); }
    private EditBox speedInput() { return java.util.Objects.requireNonNull(speedInput); }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
