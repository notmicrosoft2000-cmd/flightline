package dev.flightline.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Maps a fixed logical panel onto the available Minecraft GUI area.
 *
 * <p>The wrapper no longer shrinks panels below a readable size just because the Minecraft GUI
 * scale is low. Instead it applies a modest readability boost at GUI Scale 1, then lets screens
 * reflow their contents at the breakpoints reported by {@link #layout()}. Mouse coordinates are
 * always mapped back into logical space so widgets and manual hitboxes stay aligned.
 */
final class ScreenScaler {
    enum Layout {
        COMPACT(420, 260),
        NARROW(640, 380),
        STANDARD(880, 500),
        WIDE(Integer.MAX_VALUE, Integer.MAX_VALUE);

        private final int minWidth;
        private final int minHeight;

        Layout(int minWidth, int minHeight) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
        }

        private boolean fits(int width, int height) {
            return width >= minWidth && height >= minHeight;
        }

        public boolean atLeast(Layout other) {
            return ordinal() >= other.ordinal();
        }
    }

    private final int logicalWidth;
    private final int logicalHeight;
    private float scale = 1.0f;
    private float offsetX;
    private float offsetY;
    private int availableWidth;
    private int availableHeight;
    private Layout layout = Layout.WIDE;

    ScreenScaler(int panelWidth, int panelHeight) {
        this.logicalWidth = panelWidth + 16;
        this.logicalHeight = panelHeight + 16;
    }

    void update(int screenWidth, int screenHeight) {
        availableWidth = Math.max(1, screenWidth);
        availableHeight = Math.max(1, screenHeight);
        layout = layoutFor(availableWidth, availableHeight);

        float guiScale = Math.max(1.0f, Minecraft.getInstance().options.guiScale().get());
        // GUI Scale 1 is the common "everything is tiny" case. Render the panel 1.5x larger in
        // GUI space there; higher GUI scales already produce readable text without help.
        float readabilityCap = guiScale <= 1.0f ? 1.5f : 1.0f;
        float fit = Math.min((float) availableWidth / logicalWidth,
                (float) availableHeight / logicalHeight);
        if (fit >= 0.88f && guiScale > 1.0f) {
            scale = 1.0f;
        } else {
            scale = Math.max(0.68f, Math.min(readabilityCap, fit));
        }
        offsetX = (availableWidth - logicalWidth * scale) / 2.0f;
        offsetY = (availableHeight - logicalHeight * scale) / 2.0f;
    }

    private static Layout layoutFor(int width, int height) {
        Layout[] values = Layout.values();
        for (int i = values.length - 1; i >= 0; i--) {
            if (values[i].fits(width, height)) return values[i];
        }
        return Layout.COMPACT;
    }

    int width() {
        return logicalWidth;
    }

    int height() {
        return logicalHeight;
    }

    int availableWidth() {
        return availableWidth;
    }

    int availableHeight() {
        return availableHeight;
    }

    Layout layout() {
        return layout;
    }

    boolean isCompact() {
        return layout == Layout.COMPACT;
    }

    boolean isNarrow() {
        return layout == Layout.NARROW;
    }

    float scale() {
        return scale;
    }

    /** Logical-space bounds of the entire GUI viewport, including area outside the base panel. */
    float logicalLeft() {
        return -offsetX / scale;
    }

    float logicalTop() {
        return -offsetY / scale;
    }

    float logicalRight() {
        return (availableWidth - offsetX) / scale;
    }

    float logicalBottom() {
        return (availableHeight - offsetY) / scale;
    }

    /** Draws a scrim over the whole viewport, not just the fixed logical panel. */
    void scrim(GuiGraphicsExtractor graphics) {
        graphics.fill(Math.round(logicalLeft()), Math.round(logicalTop()),
                Math.round(logicalRight()), Math.round(logicalBottom()), FlightLineTheme.SCRIM);
    }

    /** Raw mouse coordinate in logical panel space. */
    int mouseX(double screenX) {
        return Math.round(((float) screenX - offsetX) / scale);
    }

    /** Raw mouse coordinate in logical panel space. */
    int mouseY(double screenY) {
        return Math.round(((float) screenY - offsetY) / scale);
    }

    MouseButtonEvent map(MouseButtonEvent event) {
        return new MouseButtonEvent(mouseX(event.x()), mouseY(event.y()), event.buttonInfo());
    }

    /** Drag deltas are in physical GUI pixels and must be divided by the panel scale. */
    double unscaled(double delta) {
        return delta / scale;
    }

    void push(GuiGraphicsExtractor graphics) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(offsetX, offsetY);
        pose.scale(scale, scale);
    }

    void pop(GuiGraphicsExtractor graphics) {
        graphics.pose().popMatrix();
    }
}
