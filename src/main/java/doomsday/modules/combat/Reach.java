package doomsday.modules.combat;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.util.Keybind;

/**
 * Extends the client-side attack range check only. Since the 1.9 combat update,
 * vanilla servers independently validate interaction distance server-side
 * (entity.getBlockReach() / NegativeDamagerCheck-style anticheats included) —
 * this helps on legacy/unprotected servers and singleplayer, does nothing
 * against a server that re-validates reach itself.
 */
public final class Reach extends Module {

    private double range = 6.0;

    public Reach() {
        super("Reach", Category.COMBAT, Keybind.REACH);
    }

    @Override
    public void onTick() {
        Mc mc = Mc.get();
        if (mc == null) return;
        Object self = mc.player();
        if (self == null) return;
        double[] selfPos = mc.entityPos(self);

        for (Object e : mc.entities()) {
            if (e == self || !mc.isLivingEntity(e)) continue;
            double[] p = mc.entityPos(e);
            double dx = p[0] - selfPos[0], dy = p[1] - selfPos[1], dz = p[2] - selfPos[2];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= range * range) {
                mc.swingHand();
                mc.attack(e);
                return; // one target per tick, mirrors vanilla single-attack-per-swing pacing
            }
        }
    }
}
