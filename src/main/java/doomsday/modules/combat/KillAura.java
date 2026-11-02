package doomsday.modules.combat;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.util.Keybind;

import java.util.List;

public final class KillAura extends Module {

    private double range = 4.0;
    private int cooldownTicks = 0;

    public KillAura() {
        super("KillAura", Category.COMBAT, Keybind.KILLAURA);
    }

    @Override
    public void onTick() {
        if (cooldownTicks > 0) { cooldownTicks--; return; }
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

        if (best != null) {
            aimAt(mc, selfPos, best);
            mc.swingHand();
            mc.attack(best);
            cooldownTicks = 2; // ~10 cps at 20 tps baseline, tune to taste
        }
    }

    private void aimAt(Mc mc, double[] selfPos, Object target) {
        double[] tp = mc.entityPos(target);
        float eye = mc.entityEyeHeight(target);
        double dx = tp[0] - selfPos[0];
        double dy = (tp[1] + eye * 0.85) - (selfPos[1] + mc.entityEyeHeight(mc.player()));
        double dz = tp[2] - selfPos[2];
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.setRotations(yaw, pitch);
    }
}
