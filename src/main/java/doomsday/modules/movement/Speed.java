package doomsday.modules.movement;

import doomsday.core.Category;
import doomsday.core.Module;
import doomsday.mc.Mc;
import doomsday.util.Keybind;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class Speed extends Module {

    private double multiplier = 1.6;

    public Speed() {
        super("Speed", Category.MOVEMENT, Keybind.SPEED);
    }

    @Override
    public void onTick() {
        Mc mc = Mc.get();
        if (mc == null) return;
        Object player = mc.player();
        if (player == null) return;

        try {
            Method getDelta = mc.ra.methodAny("net.minecraft.world.entity.Entity", 0, "getDeltaMovement");
            Object vec3 = mc.ra.invoke(getDelta, player);

            double x = (double) mc.ra.get(mc.ra.field("net.minecraft.world.phys.Vec3", "x"), vec3);
            double y = (double) mc.ra.get(mc.ra.field("net.minecraft.world.phys.Vec3", "y"), vec3);
            double z = (double) mc.ra.get(mc.ra.field("net.minecraft.world.phys.Vec3", "z"), vec3);

            Class<?> vec3Class = mc.ra.clazz("net.minecraft.world.phys.Vec3");
            Constructor<?> ctor = vec3Class.getDeclaredConstructor(double.class, double.class, double.class);
            ctor.setAccessible(true);
            Object newVec3 = ctor.newInstance(x * multiplier, y, z * multiplier);

            Method setDelta = mc.ra.methodAny("net.minecraft.world.entity.Entity", 1, "setDeltaMovement");
            mc.ra.invoke(setDelta, player, newVec3);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("speed module reflection failed", e);
        }
    }
}
