package doomsday.modules.combat;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.util.Keybind;

/** smooth legit-style aimbot — turns toward the closest target each tick instead of snapping, no attack */
public final class Aimbot extends Module {

    private double range = 6.0;
    private double turnSpeed = 0.18; // fraction of the angular delta closed per tick

    public Aimbot() {
        super("Aimbot", Category.COMBAT, Keybind.AIMBOT);
    }

    @Override
    public void onTick() {
        Mc mc = Mc.get();
        if (mc == null) return;
        Object self = mc.player();
        if (self == null) return;
        double[] selfPos = mc.entityPos(self);

        Object best = null;
        double bestDist = range * range;
        for (Object e : mc.entities()) {
            if (e == self || !mc.isLivingEntity(e)) continue;
            double[] p = mc.entityPos(e);
            double dx = p[0] - selfPos[0], dy = p[1] - selfPos[1], dz = p[2] - selfPos[2];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDist) { bestDist = d2; best = e; }
        }
        if (best == null) return;

        double[] tp = mc.entityPos(best);
        float eye = mc.entityEyeHeight(best);
        double dx = tp[0] - selfPos[0];
        double dy = (tp[1] + eye * 0.85) - (selfPos[1] + mc.entityEyeHeight(self));
        double dz = tp[2] - selfPos[2];
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        float[] cur = mc.getRotations();
        float newYaw = cur[0] + (float) wrapDegrees(targetYaw - cur[0]) * (float) turnSpeed;
        float newPitch = cur[1] + (targetPitch - cur[1]) * (float) turnSpeed;
        mc.setRotations(newYaw, newPitch);
    }

    private static double wrapDegrees(double deg) {
        deg = deg % 360.0;
        if (deg >= 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }
}
