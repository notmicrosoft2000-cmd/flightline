package dev.flightline.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Maps a fixed-size logical screen onto the available Minecraft GUI area.
 * The whole render (including text) is scaled uniformly, and input is mapped back
 * into logical coordinates so widgets still receive correct hover and click positions.
 */
final class ScreenScaler {
    private final int logicalWidth;
    private final int logicalHeight;
    private float scale = 1.0f;
    private float offsetX;
    private float offsetY;

    ScreenScaler(int panelWidth, int panelHeight) {
        this.logicalWidth = panelWidth + 16;
        this.logicalHeight = panelHeight + 16;
    }

    void update(int screenWidth, int screenHeight) {
        scale = Math.min(1.0f, Math.min((float) screenWidth / logicalWidth,
                (float) screenHeight / logicalHeight));
        offsetX = (screenWidth - logicalWidth * scale) / 2.0f;
        offsetY = (screenHeight - logicalHeight * scale) / 2.0f;
    }

    int width() { return logicalWidth; }
    int height() { return logicalHeight; }
    float scale() { return scale; }

    int mouseX(double screenX) { return Math.round(((float) screenX - offsetX) / scale); }
    int mouseY(double screenY) { return Math.round(((float) screenY - offsetY) / scale); }

    MouseButtonEvent map(MouseButtonEvent event) {
        return new MouseButtonEvent(mouseX(event.x()), mouseY(event.y()), event.buttonInfo());
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
