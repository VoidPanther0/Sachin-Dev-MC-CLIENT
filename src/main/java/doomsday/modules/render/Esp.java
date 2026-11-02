package doomsday.modules.render;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.mc.RenderContext;
import doomsday.util.Keybind;

/**
 * Screen-space box ESP. Drawn through GuiGraphicsExtractor in the HUD-render hook, which
 * runs with no depth test against the 3D scene — the box is always on top, i.e.
 * it already reads through walls without any extra work. Entity bbox is
 * approximated as a fixed 0.6x1.8 (player-sized) box around position(); good
 * enough for a silhouette marker, not pixel-exact against mobs of other sizes.
 */
public final class Esp extends Module {

    private static final double WIDTH = 0.6, HEIGHT = 1.8;
    private static final int COLOR = 0xFFFF3B3B;

    public Esp() {
        super("ESP", Category.RENDER, Keybind.ESP);
    }

    @Override
    public void onRenderWorld(Object guiGraphics) {
        Mc mc = Mc.get();
        if (mc == null) return;
        Object self = mc.player();
        double[] camPos = mc.cameraPos();
        float yaw = mc.cameraYaw(), pitch = mc.cameraPitch();
        double fov = mc.fov();
        int w = mc.screenWidth(), h = mc.screenHeight();

        for (Object e : mc.entities()) {
            if (e == self || !mc.isLivingEntity(e)) continue;
            double[] p = mc.entityPos(e);
            RenderContext.Screen foot = RenderContext.project(camPos, yaw, pitch, fov, w, h, p[0], p[1], p[2]);
            if (foot == null) continue;

            double pixW = WIDTH * foot.scale();
            double pixH = HEIGHT * foot.scale();
            double x1 = foot.x() - pixW / 2, x2 = foot.x() + pixW / 2;
            double y2 = foot.y(), y1 = foot.y() - pixH;
            mc.boxOutline(guiGraphics, x1, y1, x2, y2, COLOR, 1);
        }
    }
}
