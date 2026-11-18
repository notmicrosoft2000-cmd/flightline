package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class FlightControlsScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 320;
    private KeyMapping listeningFor;
    private final Map<KeyMapping, Button> bindingButtons = new HashMap<>();
    private float openProgress;
    private long lastFrameNanos = System.nanoTime();
    private boolean closing;
    private int page;
    private int pageCount;
    private int rowsPerColumn;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public FlightControlsScreen() {
        super(Component.literal("Flight Line Controls"));
    }

    @Override
    protected void init() {
        super.init();
        bindingButtons.clear();
        panelWidth = Math.max(1, Math.min(PANEL_WIDTH, width - 16));
        panelHeight = Math.max(1, Math.min(PANEL_HEIGHT, height - 16));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        rowsPerColumn = Math.max(1, (panelHeight - 78) / 19);
        List<KeyMapping> bindings = FlightLineClient.controlScreenBindings();
        int pageSize = rowsPerColumn * 2;
        pageCount = Math.max(1, (bindings.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        int columnWidth = (panelWidth - 36) / 2;
        int start = page * pageSize;
        int end = Math.min(bindings.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            KeyMapping mapping = bindings.get(i);
            int pageIndex = i - start;
            int column = pageIndex / rowsPerColumn;
            int row = pageIndex % rowsPerColumn;
            int x = panelLeft + 12 + column * (columnWidth + 12);
            int y = panelTop + 43 + row * 19;
            int keyWidth = Math.min(64, Math.max(48, columnWidth / 3));
            Button button = Button.builder(Component.literal(fit(
                    mapping.getTranslatedKeyMessage().getString(), keyWidth - 6)), clicked -> {
                        listeningFor = mapping;
                        clicked.setMessage(Component.literal("PRESS KEY"));
                    })
                    .bounds(x + columnWidth - keyWidth, y, keyWidth, 16).build();
            bindingButtons.put(mapping, button);
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.literal("PREV"), button -> {
                    page = Math.max(0, page - 1);
                    init(width, height);
                })
                .bounds(panelLeft + 12, panelTop + panelHeight - 25, 52, 16).build());
        addRenderableWidget(Button.builder(Component.literal("NEXT"), button -> {
                    page = Math.min(pageCount - 1, page + 1);
                    init(width, height);
                })
                .bounds(panelLeft + 70, panelTop + panelHeight - 25, 52, 16).build());
        addRenderableWidget(Button.builder(Component.literal("DONE"), button -> onClose())
                .bounds(panelLeft + panelWidth - 76, panelTop + panelHeight - 25, 64, 16)
                .build());
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
        float scale = 0.96f + openProgress * 0.04f;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(width / 2.0f, height / 2.0f);
        pose.scale(scale, scale);
        pose.translate(-width / 2.0f, -height / 2.0f);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121A22);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF4EB8C8);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 25, 0xFF1C2733);
        graphics.centeredText(font, "FLIGHT LINE CONTROLS", width / 2, panelTop + 8, 0xFF72E6F5);
        graphics.text(font, fit(listeningFor == null
                        ? "Select a key to rebind · page " + (page + 1) + "/" + pageCount
                        : "Waiting for a key · Esc cancels · Backspace unbinds", panelWidth - 24),
                panelLeft + 12, panelTop + 28, listeningFor == null ? 0xFF9BAAB1 : 0xFFFFCE52, true);
        List<KeyMapping> bindings = FlightLineClient.controlScreenBindings();
        int pageSize = rowsPerColumn * 2;
        int start = page * pageSize;
        int end = Math.min(bindings.size(), start + pageSize);
        int columnWidth = (panelWidth - 36) / 2;
        for (int i = start; i < end; i++) {
            int pageIndex = i - start;
            int column = pageIndex / rowsPerColumn;
            int row = pageIndex % rowsPerColumn;
            int x = panelLeft + 12 + column * (columnWidth + 12);
            int y = panelTop + 46 + row * 19;
            graphics.text(font, fit(bindingName(bindings.get(i)), columnWidth - 72),
                    x, y + 4, 0xFFD5E0E8, true);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        pose.popMatrix();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listeningFor == null) return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_ESCAPE) {
            Button button = bindingButtons.get(listeningFor);
            if (button != null) button.setMessage(keyLabel(button, listeningFor));
            listeningFor = null;
            return true;
        }
        KeyMapping changed = listeningFor;
        listeningFor.setKey(event.key() == InputConstants.KEY_BACKSPACE
                ? InputConstants.UNKNOWN : InputConstants.getKey(event));
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        listeningFor = null;
        Button button = bindingButtons.get(changed);
        if (button != null) button.setMessage(keyLabel(button, changed));
        return true;
    }

    private Component keyLabel(Button button, KeyMapping mapping) {
        return Component.literal(fit(mapping.getTranslatedKeyMessage().getString(), button.getWidth() - 6));
    }

    private String bindingName(KeyMapping mapping) {
        var options = Minecraft.getInstance().options;
        if (mapping == options.keyUp) return "Increase Throttle";
        if (mapping == options.keyDown) return "Decrease Throttle";
        if (mapping == options.keyLeft) return "Rudder Left";
        if (mapping == options.keyRight) return "Rudder Right";
        if (mapping == options.keyInventory) return "Start Engines / Inventory";
        return Component.translatable(mapping.getName()).getString();
    }

    private String fit(String text, int maxWidth) {
        String fitted = text;
        while (!fitted.isEmpty() && font.width(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    @Override
    public void onClose() {
        if (listeningFor != null) {
            Button button = bindingButtons.get(listeningFor);
            if (button != null) button.setMessage(keyLabel(button, listeningFor));
            listeningFor = null;
        } else {
            closing = true;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
