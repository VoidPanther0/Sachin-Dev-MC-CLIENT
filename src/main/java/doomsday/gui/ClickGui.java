package doomsday.gui;

import doomsday.agent.HookMethods;
import doomsday.core.Category;
import doomsday.core.Config;
import doomsday.core.Module;
import doomsday.core.ModuleManager;
import doomsday.mc.Mc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal immediate-mode panel per Category, drawn through the game's own
 * GuiGraphicsExtractor (see Mc#fill/drawString) so it needs no second GL context.
 * Click hit-testing reads HookMethods' GLFW-polled cursor state, since mouse
 * coordinates aren't available as a hook argument on the Gui#render injection
 * point used here.
 */
public final class ClickGui {

    private static final int ROW_H = 14, PANEL_W = 120, HEADER_H = 16;
    private static final int BG = 0xC0101014, HEADER_BG = 0xE0202028, ROW_ON = 0x8030B050, ROW_OFF = 0x00000000;
    private static final int TEXT = 0xFFFFFFFF;

    private final ModuleManager manager;
    private volatile boolean open = true; // shows right away on inject; Insert still toggles + saves config on close

    public ClickGui(ModuleManager manager) {
        this.manager = manager;
    }

    public boolean isOpen() { return open; }
    public void toggle() {
        open = !open;
        if (!open) Config.save(manager);
    }

    public void renderIfOpen(Object guiGraphics) {
        if (!open) return;
        Mc mc = Mc.get();
        if (mc == null) return;

        Map<Category, List<Module>> byCategory = new EnumMap<>(Category.class);
        for (Module m : manager.modules()) byCategory.computeIfAbsent(m.category(), k -> new ArrayList<>()).add(m);

        HookMethods.Click click = HookMethods.consumeClick();
        // raw GLFW window-pixel cursor -> GUI-scaled space (see Mc#guiScaleX/Y) so hit-testing
        // lines up with the panels below, which are laid out directly in that scaled space
        double scaleX = mc.guiScaleX(), scaleY = mc.guiScaleY();
        double mx = HookMethods.mouseX() / scaleX, my = HookMethods.mouseY() / scaleY;
        double clickX = click != null ? click.x() / scaleX : 0;
        double clickY = click != null ? click.y() / scaleY : 0;

        int panelX = 10;
        for (Category cat : Category.values()) {
            List<Module> mods = byCategory.getOrDefault(cat, List.of());
            if (mods.isEmpty()) continue;

            int panelY = 10;
            int panelH = HEADER_H + mods.size() * ROW_H;
            mc.fill(guiGraphics, panelX, panelY, panelX + PANEL_W, panelY + panelH, BG);
            mc.fill(guiGraphics, panelX, panelY, panelX + PANEL_W, panelY + HEADER_H, HEADER_BG);
            mc.drawString(guiGraphics, cat.name(), panelX + 4, panelY + 4, TEXT);

            int rowY = panelY + HEADER_H;
            for (Module m : mods) {
                boolean hover = inside(mx, my, panelX, rowY, panelX + PANEL_W, rowY + ROW_H);
                int rowColor = m.enabled() ? ROW_ON : (hover ? 0x40FFFFFF : ROW_OFF);
                if (rowColor != 0) mc.fill(guiGraphics, panelX, rowY, panelX + PANEL_W, rowY + ROW_H, rowColor);
                mc.drawString(guiGraphics, m.name(), panelX + 4, rowY + 3, TEXT);

                if (click != null && inside(clickX, clickY, panelX, rowY, panelX + PANEL_W, rowY + ROW_H)) {
                    m.toggle();
                }
                rowY += ROW_H;
            }
            panelX += PANEL_W + 8;
        }
    }

    private static boolean inside(double x, double y, int x1, int y1, int x2, int y2) {
        return x >= x1 && x < x2 && y >= y1 && y < y2;
    }
}
