package doomsday.modules.render;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.mc.RenderContext;
import doomsday.util.Keybind;

/** filled-silhouette variant of Esp — same through-wall screen-space draw, solid translucent fill instead of outline */
public final class Chams extends Module {

    private static final double WIDTH = 0.6, HEIGHT = 1.8;
    private static final int FILL = 0x5000FF4C;
    private static final int OUTLINE = 0xFF00FF4C;

    public Chams() {
        super("Chams", Category.RENDER, Keybind.CHAMS);
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
            int x1 = (int) Math.round(foot.x() - pixW / 2), x2 = (int) Math.round(foot.x() + pixW / 2);
            int y2 = (int) Math.round(foot.y()), y1 = (int) Math.round(foot.y() - pixH);
            mc.fill(guiGraphics, x1, y1, x2, y2, FILL);
            mc.boxOutline(guiGraphics, x1, y1, x2, y2, OUTLINE, 1);
        }
    }
}
