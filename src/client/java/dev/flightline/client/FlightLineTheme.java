package dev.flightline.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Website-derived field-manual palette and square instrument primitives. */
final class FlightLineTheme {
    private FlightLineTheme() {}

    /* ── Website tokens ─────────────────────────────────────────────── */
    static final int VOID       = 0xFF171615;
    static final int COAL       = 0xFF262423;
    static final int STONE      = 0xFF3D3938;
    static final int PAPER      = 0xFFFCF5F1;

    static final int CYAN       = 0xFF36E6FF;
    static final int GREEN      = 0xFF6CC349;
    static final int GREEN_DARK = 0xFF3C8527;
    static final int BLUE       = 0xFF1E6EEA;
    static final int AMBER      = 0xFFFFC42B;
    static final int RED        = 0xFFB83B2E;

    /* ── Derived surface tones ──────────────────────────────────────── */
    static final int PANEL      = 0xFF171615;
    static final int CARD       = 0xFF262423;
    static final int HEADER     = 0xFF262423;
    static final int INSET      = 0xFF121110;
    static final int SCOPE      = 0xFF0C1412;
    static final int LINE       = 0xFF3D3938;
    static final int LINE_SOFT  = 0xFF353130;
    static final int TEXT       = 0xFFFCF5F1;
    static final int TEXT_DIM   = 0xFFD6CDC7;
    static final int MUTED      = 0xFF9A8F89;
    static final int SHADOW     = 0xD4000000;
    static final int SCRIM      = 0xD8171615;

    /* ── Flight-critical hues (semantics preserved) ────────────────── */
    static final int TCAS_RA    = 0xFFFF5148;
    static final int TCAS_TA    = 0xFFFFCE52;
    static final int WARNING    = 0xFFFF4B42;
    static final int WARNING_DARK = 0xFFD83028;
    static final int NORMAL     = 0xFF67E87E;
    static final int CAUTION    = 0xFFFF9E3D;

    /* ── Layout tokens ──────────────────────────────────────────────── */
    private static final int SHADOW_OFFSET = 6;
    private static final int BORDER = 4;

    /* ── Primitives ─────────────────────────────────────────────────── */

    static void scrim(GuiGraphicsExtractor g, int width, int height) {
        g.fill(0, 0, width, height, SCRIM);
    }

    /**
     * Full-screen alarm frame: a tinted edge band and corner brackets. Callers gate this on
     * non-TCAS warnings. No text is drawn here.
     */
    static void alarmFlash(GuiGraphicsExtractor g, int x, int y, int w, int h, float intensity) {
        int alpha = (int) (0.55f * Math.max(0f, Math.min(1f, intensity)) * 255f);
        if (alpha <= 0) return;
        // Website red, not the flight-critical warning red: the alarm is an ambient frame and
        // must not be mistaken for a TCAS/RA symbol or a specific warning label.
        int edge = (alpha << 24) | (RED & 0xFFFFFF);
        int band = Math.max(4, Math.min(14, Math.round(Math.min(w, h) * 0.025f)));
        g.fill(x, y, x + w, y + band, edge);
        g.fill(x, y + h - band, x + w, y + h, edge);
        g.fill(x, y + band, x + band, y + h - band, edge);
        g.fill(x + w - band, y + band, x + w, y + h - band, edge);

        int corner = Math.max(18, band * 3);
        int spine = Math.max(3, band / 2);
        int solidAlpha = Math.min(255, alpha + 90);
        int solid = (solidAlpha << 24) | (RED & 0xFFFFFF);
        g.fill(x, y, x + corner, y + spine, solid);
        g.fill(x, y, x + spine, y + corner, solid);
        g.fill(x + w - corner, y, x + w, y + spine, solid);
        g.fill(x + w - spine, y, x + w, y + corner, solid);
        g.fill(x, y + h - spine, x + corner, y + h, solid);
        g.fill(x, y + h - corner, x + spine, y + h, solid);
        g.fill(x + w - corner, y + h - spine, x + w, y + h, solid);
        g.fill(x + w - spine, y + h - corner, x + w, y + h, solid);
    }

    /** Hard-offset shadow, square border, flat interior. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x + SHADOW_OFFSET, y + SHADOW_OFFSET,
               x + w + SHADOW_OFFSET, y + h + SHADOW_OFFSET, SHADOW);
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + BORDER, y + BORDER, x + w - BORDER, y + h - BORDER, fill);
        g.outline(x, y, w, h, border);
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        panel(g, x, y, w, h, PANEL, STONE);
    }

    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int accent) {
        panel(g, x, y, w, h, CARD, LINE);
        g.fill(x + BORDER, y + BORDER, x + BORDER + 5, y + h - BORDER, accent);
    }

    static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, int border) {
        g.fill(x, y, x + w, y + h, INSET);
        g.outline(x, y, w, h, border);
    }

    static void rule(GuiGraphicsExtractor g, int x1, int x2, int y, int color) {
        g.horizontalLine(x1, x2, y, color);
    }

    /** Chapter-style header: accent tab, uppercase label, underline. */
    static void header(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                       String chapter, String label, int accent) {
        g.fill(x, y, x + w, y + h, HEADER);
        if (!chapter.isEmpty()) {
            int tabW = Math.max(22, font.width(chapter) + 14);
            g.fill(x, y, x + tabW, y + h, accent);
            g.text(font, chapter, x + 7, y + (h - 8) / 2, VOID, false);
            g.text(font, label, x + tabW + 10, y + (h - 8) / 2, TEXT, false);
        } else {
            g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, TEXT);
        }
        g.fill(x, y + h - 2, x + w, y + h, accent);
    }

    /** Small uppercase badge/chip with hard shadow. */
    static void chip(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                     String label, int fg, int bg, boolean active) {
        g.fill(x + 3, y + 3, x + w + 3, y + h + 3, SHADOW);
        g.fill(x, y, x + w, y + h, active ? bg : COAL);
        g.outline(x, y, w, h, active ? fg : LINE);
        g.fill(x + 2, y + 2, x + 5, y + h - 2, active ? fg : LINE);
        g.text(font, fit(font, label, w - 12), x + 9, y + (h - 8) / 2,
               active ? fg : MUTED, false);
    }

    static void chip(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                     String label, int color, boolean active) {
        chip(g, font, x, y, w, h, label, color, CARD, active);
    }

    static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h,
                    float fraction, int fg, int bg) {
        g.fill(x, y, x + w, y + h, bg);
        int fill = Math.max(0, Math.min(w, Math.round(w * Math.max(0f, Math.min(1f, fraction)))));
        if (fill > 0) g.fill(x, y, x + fill, y + h, fg);
        g.outline(x, y, w, h, LINE);
    }

    /** Truncate with an ellipsis so labels never overflow their slot. */
    static String fit(Font font, String value, int maxWidth) {
        if (value == null) return "";
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "...";
        int budget = maxWidth - font.width(ellipsis);
        if (budget <= 0) return ellipsis;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (font.width(out.toString() + value.charAt(i)) > budget) break;
            out.append(value.charAt(i));
        }
        return out + ellipsis;
    }

    /* ── Custom flat button (same bounds + callback as vanilla Button) ─ */

    static AbstractWidget button(String label, int x, int y, int w, int h, Runnable onPress) {
        return new FlatButton(x, y, w, h, Component.literal(label), onPress);
    }

    static AbstractWidget button(Component label, int x, int y, int w, int h, Runnable onPress) {
        return new FlatButton(x, y, w, h, label, onPress);
    }

    static final class FlatButton extends AbstractWidget {
        private final Runnable onPress;

        FlatButton(int x, int y, int w, int h, Component message, Runnable onPress) {
            super(x, y, w, h, message);
            this.onPress = onPress;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (active) onPress.run();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean lit = isHoveredOrFocused() && active;
            int bg = !active ? COAL : lit ? GREEN : CARD;
            int fg = !active ? LINE : lit ? VOID : TEXT;
            g.fill(x + 4, y + 4, x + w + 4, y + h + 4, SHADOW);
            g.fill(x, y, x + w, y + h, bg);
            g.outline(x, y, w, h, !active ? LINE : lit ? VOID : STONE);
            g.fill(x + 2, y + 2, x + 5, y + h - 2, !active ? LINE : lit ? VOID : GREEN);
            Font font = Minecraft.getInstance().font;
            g.centeredText(font, fit(font, getMessage().getString(), w - 14),
                           x + w / 2 + 3, y + (h - 8) / 2, fg);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
