package doomsday.modules.misc;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.util.Keybind;

import java.lang.reflect.Method;

/** fires left-click (attack-swing) at a fixed cps while held; right-click (use item) companion toggle is left/right symmetric below */
public final class AutoClicker extends Module {

    private int cps = 12;
    private int tickCounter = 0;

    public AutoClicker() {
        super("AutoClicker", Category.MISC, Keybind.AUTOCLICKER);
    }

    @Override
    public void onTick() {
        Mc mc = Mc.get();
        if (mc == null) return;
        int ticksPerClick = Math.max(1, (int) Math.round(20.0 / cps));
        tickCounter++;
        if (tickCounter < ticksPerClick) return;
        tickCounter = 0;

        try {
            // Minecraft#startAttack() performs the same client-side hit-registration path as a real left-click
            Method startAttack = mc.ra.method("net.minecraft.client.Minecraft", "startAttack", 0);
            mc.ra.invoke(startAttack, mc.client());
        } catch (RuntimeException e) {
            // name drifted for this build — fall back to a direct swing so the module still does *something*
            mc.swingHand();
        }
    }
}
