package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class FlightControlsScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 320;
    private final ScreenScaler scaler = new ScreenScaler(PANEL_WIDTH, PANEL_HEIGHT);
    private KeyMapping listeningFor;
    private final Map<KeyMapping, AbstractWidget> bindingButtons = new HashMap<>();
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
        scaler.update(width, height);
        panelWidth = PANEL_WIDTH;
        panelHeight = PANEL_HEIGHT;
        panelLeft = 8;
        panelTop = 8;
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
            var holder = new AbstractWidget[1];
            holder[0] = FlightLineTheme.button(
                    fit(mapping.getTranslatedKeyMessage().getString(), keyWidth - 6),
                    x + columnWidth - keyWidth, y, keyWidth, 16, () -> {
                        listeningFor = mapping;
                        holder[0].setMessage(Component.literal("PRESS KEY"));
                    });
            bindingButtons.put(mapping, holder[0]);
            addRenderableWidget(holder[0]);
        }
        addRenderableWidget(FlightLineTheme.button("PREV",
                panelLeft + 12, panelTop + panelHeight - 25, 52, 16, () -> {
                    page = Math.max(0, page - 1);
                    init(width, height);
                }));
        addRenderableWidget(FlightLineTheme.button("NEXT",
                panelLeft + 70, panelTop + panelHeight - 25, 52, 16, () -> {
                    page = Math.min(pageCount - 1, page + 1);
                    init(width, height);
                }));
        addRenderableWidget(FlightLineTheme.button("DONE",
                panelLeft + panelWidth - 76, panelTop + panelHeight - 25, 64, 16,
                this::onClose));
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
        FlightLineTheme.header(graphics, font, panelLeft + 4, panelTop + 4,
                panelWidth - 8, 24, "07", "FLIGHT LINE CONTROLS", FlightLineTheme.AMBER);
        graphics.text(font, fit(listeningFor == null
                        ? "SELECT A KEY TO REBIND · PAGE " + (page + 1) + "/" + pageCount
                        : "WAITING FOR A KEY · ESC CANCELS · BACKSPACE UNBINDS", panelWidth - 24),
                panelLeft + 12, panelTop + 30,
                listeningFor == null ? FlightLineTheme.MUTED : FlightLineTheme.AMBER, true);
        graphics.horizontalLine(panelLeft + 12, panelLeft + panelWidth - 12,
                panelTop + 41, FlightLineTheme.LINE);
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
                    x, y + 4, FlightLineTheme.TEXT_DIM, true);
        }
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        return super.mouseScrolled(scaler.mouseX(mouseX), scaler.mouseY(mouseY), deltaX, deltaY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(scaler.mouseX(mouseX), scaler.mouseY(mouseY));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listeningFor == null) return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_ESCAPE) {
            AbstractWidget button = bindingButtons.get(listeningFor);
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
        AbstractWidget button = bindingButtons.get(changed);
        if (button != null) button.setMessage(keyLabel(button, changed));
        return true;
    }

    private Component keyLabel(AbstractWidget button, KeyMapping mapping) {
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
            AbstractWidget button = bindingButtons.get(listeningFor);
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
