package doomsday.modules.movement;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.util.Keybind;

import java.lang.reflect.Field;

/**
 * Flips Player.abilities.mayfly + flying, same two booleans vanilla creative
 * mode sets — vanilla's own input handling then does the ascend/descend on
 * jump/sneak, no movement-packet forging needed. Re-asserted every tick since
 * a server (or the client itself on respawn/gamemode switch) can reset them.
 */
public final class Flight extends Module {

    public Flight() {
        super("Flight", Category.MOVEMENT, Keybind.FLIGHT);
    }

    @Override
    public void onTick() {
        Mc mc = Mc.get();
        if (mc == null) return;
        Object player = mc.player();
        if (player == null) return;
        setFlying(mc, player, true);
    }

    @Override
    protected void onDisable() {
        Mc mc = Mc.get();
        if (mc == null) return;
        Object player = mc.player();
        if (player != null) setFlying(mc, player, false);
    }

    private void setFlying(Mc mc, Object player, boolean value) {
        Field abilitiesField = mc.ra.field("net.minecraft.world.entity.player.Player", "abilities");
        Object abilities = mc.ra.get(abilitiesField, player);
        mc.ra.set(mc.ra.field("net.minecraft.world.entity.player.Abilities", "mayfly"), abilities, value);
        mc.ra.set(mc.ra.field("net.minecraft.world.entity.player.Abilities", "flying"), abilities, value);
    }
}
