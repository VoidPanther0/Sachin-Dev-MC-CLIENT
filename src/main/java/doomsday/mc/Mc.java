package doomsday.mc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Central facade over the running Minecraft instance. All lookups go through
 * ReflectAccess so they survive both a raw obfuscated vanilla launch and an
 * already-remapped dev/Fabric environment.
 *
 * Class/member names below are Mojang's official ("mojmap") names, stable
 * since 1.14.4's first official mapping release. 26.1.2 postdates this
 * tool's knowledge cutoff for exact method sets — if a lookup below throws,
 * the exception message carries the live candidate dump; update the name
 * list here (or add a candidate via methodAny) and rebuild. No silent stub.
 */
public final class Mc {

    private static volatile Mc INSTANCE;

    public final ReflectAccess ra;
    private final Object minecraft;
    private final Method getInstance;

    private Mc(String version, ClassLoader loader) {
        MojangMappings mappings = MojangMappings.forVersion(version);
        this.ra = new ReflectAccess(mappings, loader);
        Class<?> mcClass = ra.clazz("net.minecraft.client.Minecraft");
        this.getInstance = ra.method("net.minecraft.client.Minecraft", "getInstance", 0);
        this.minecraft = ra.invoke(getInstance, null);
    }

    public static synchronized Mc init(String version, ClassLoader loader) {
        if (INSTANCE == null) INSTANCE = new Mc(version, loader);
        return INSTANCE;
    }

    public static Mc get() { return INSTANCE; }

    public Object client() { return minecraft; }

    public Object player() {
        Field f = ra.field("net.minecraft.client.Minecraft", "player");
        return ra.get(f, minecraft);
    }

    public Object level() {
        Field f = ra.field("net.minecraft.client.Minecraft", "level");
        return ra.get(f, minecraft);
    }

    public Object gameMode() {
        Field f = ra.field("net.minecraft.client.Minecraft", "gameMode");
        return ra.get(f, minecraft);
    }

    public Object gameRenderer() {
        Field f = ra.field("net.minecraft.client.Minecraft", "gameRenderer");
        return ra.get(f, minecraft);
    }

    public Object camera() {
        Field f = ra.field("net.minecraft.client.renderer.GameRenderer", "mainCamera");
        return ra.get(f, gameRenderer());
    }

    public Object window() {
        Field f = ra.field("net.minecraft.client.Minecraft", "window");
        return ra.get(f, minecraft);
    }

    /** "getWindow" is the pre-26.x name for the raw GLFW handle getter; 26.1.2 renamed it to "handle" */
    public long windowHandle() {
        Method m = ra.methodAny("com.mojang.blaze3d.platform.Window", 0, "getWindow", "handle");
        return (long) ra.invoke(m, window());
    }

    public int screenWidth() {
        Method m = ra.method("com.mojang.blaze3d.platform.Window", "getWidth", 0);
        return (int) ra.invoke(m, window());
    }

    public int screenHeight() {
        Method m = ra.method("com.mojang.blaze3d.platform.Window", "getHeight", 0);
        return (int) ra.invoke(m, window());
    }

    /** Window#getGuiScaledWidth / raw getWidth() ratio. HookMethods polls the cursor straight off
     * GLFW, which answers in raw window pixels — but every draw call below (fill/text) runs through
     * GuiGraphicsExtractor in GUI-scaled space, same space ClickGui lays its panels out in. Without
     * dividing the raw cursor coords by this before hit-testing, clicks land off-row on any display
     * where gui scale != 1 (auto-scale picks 2x+ on most monitors, so: basically always). */
    public double guiScaleX() {
        int raw = screenWidth();
        Method m = ra.methodAny("com.mojang.blaze3d.platform.Window", 0, "getGuiScaledWidth");
        int scaled = (int) ra.invoke(m, window());
        return scaled <= 0 ? 1.0 : (double) raw / scaled;
    }

    public double guiScaleY() {
        int raw = screenHeight();
        Method m = ra.methodAny("com.mojang.blaze3d.platform.Window", 0, "getGuiScaledHeight");
        int scaled = (int) ra.invoke(m, window());
        return scaled <= 0 ? 1.0 : (double) raw / scaled;
    }

    public double[] cameraPos() {
        Method getPos = ra.method("net.minecraft.client.Camera", "getPosition", 0);
        Object vec3 = ra.invoke(getPos, camera());
        return vec3Components(vec3);
    }

    /** Vec3.x/y/z are plain public double fields in mojmap, not getters */
    private double[] vec3Components(Object vec3) {
        double x = (double) ra.get(ra.field("net.minecraft.world.phys.Vec3", "x"), vec3);
        double y = (double) ra.get(ra.field("net.minecraft.world.phys.Vec3", "y"), vec3);
        double z = (double) ra.get(ra.field("net.minecraft.world.phys.Vec3", "z"), vec3);
        return new double[]{x, y, z};
    }

    public float cameraYaw() {
        return (float) ra.invoke(ra.methodAny("net.minecraft.client.Camera", 0, "getYRot"), camera());
    }

    public float cameraPitch() {
        return (float) ra.invoke(ra.methodAny("net.minecraft.client.Camera", 0, "getXRot"), camera());
    }

    public double fov() {
        // Options.fov is an OptionInstance<Integer> in modern mappings; try the simple int-field shape first,
        // fall back to the OptionInstance wrapper's get() accessor.
        try {
            Field f = ra.field("net.minecraft.client.Options", "fov");
            Object val = ra.get(f, options());
            if (val instanceof Number n) return n.doubleValue();
            Method get = findGetter(val);
            Object inner = ra.invoke(get, val);
            return ((Number) inner).doubleValue();
        } catch (RuntimeException e) {
            return 70.0; // vanilla default fov, used only if the option plumbing shape changed
        }
    }

    private Method findGetter(Object optionInstance) {
        for (Method m : optionInstance.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getName().equals("get")) return m;
        }
        throw new IllegalStateException("fov OptionInstance has no no-arg get()");
    }

    public Object options() {
        Field f = ra.field("net.minecraft.client.Minecraft", "options");
        return ra.get(f, minecraft);
    }

    /** entities currently visible for rendering on the client level */
    @SuppressWarnings("unchecked")
    public List<Object> entities() {
        Object level = level();
        if (level == null) return List.of();
        try {
            Method m = ra.method("net.minecraft.client.multiplayer.ClientLevel", "entitiesForRendering", 0);
            Iterable<Object> it = (Iterable<Object>) ra.invoke(m, level);
            List<Object> out = new ArrayList<>();
            for (Object o : it) out.add(o);
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public double[] entityPos(Object entity) {
        Method m = ra.methodAny("net.minecraft.world.entity.Entity", 0, "position");
        Object vec3 = ra.invoke(m, entity);
        return vec3Components(vec3);
    }

    public float entityEyeHeight(Object entity) {
        try {
            Method m = ra.methodAny("net.minecraft.world.entity.Entity", 0, "getEyeHeight");
            return (float) ra.invoke(m, entity);
        } catch (RuntimeException e) {
            return 1.62f;
        }
    }

    public boolean isLivingEntity(Object entity) {
        Class<?> living = ra.clazz("net.minecraft.world.entity.LivingEntity");
        return living.isInstance(entity);
    }

    public void setRotations(float yaw, float pitch) {
        Object p = player();
        Method setYRot = ra.methodAny("net.minecraft.world.entity.Entity", 1, "setYRot");
        Method setXRot = ra.methodAny("net.minecraft.world.entity.Entity", 1, "setXRot");
        ra.invoke(setYRot, p, yaw);
        ra.invoke(setXRot, p, pitch);
    }

    public float[] getRotations() {
        Object p = player();
        float yaw = (float) ra.invoke(ra.methodAny("net.minecraft.world.entity.Entity", 0, "getYRot"), p);
        float pitch = (float) ra.invoke(ra.methodAny("net.minecraft.world.entity.Entity", 0, "getXRot"), p);
        return new float[]{yaw, pitch};
    }

    /** MultiPlayerGameMode#attack — tries both the 1-arg (target) and 2-arg (player, target) overload shapes seen across versions */
    public void attack(Object target) {
        Object gm = gameMode();
        try {
            Method m = ra.method("net.minecraft.client.multiplayer.MultiPlayerGameMode", "attack", 2);
            ra.invoke(m, gm, player(), target);
            return;
        } catch (RuntimeException ignored) {}
        Method m = ra.method("net.minecraft.client.multiplayer.MultiPlayerGameMode", "attack", 1);
        ra.invoke(m, gm, target);
    }

    /** swing(InteractionHand) has always lived on LivingEntity, not the base Entity class — this
     * was targeting the wrong class regardless of version and threw on every call. */
    public void swingHand() {
        Object p = player();
        Method m = ra.methodAny("net.minecraft.world.entity.LivingEntity", 1, "swing");
        Class<?> handClass = ra.clazz("net.minecraft.world.InteractionHand");
        Object mainHand = handClass.getEnumConstants()[0];
        ra.invoke(m, p, mainHand);
    }

    public boolean hasTarget(Object entity) {
        Class<?> entityClass = ra.clazz("net.minecraft.world.entity.Entity");
        return entity != null && entityClass.isInstance(entity);
    }

    // -- 2D drawing via the live GuiGraphicsExtractor handed in by the Gui#extractRenderState
    // hook (26.1.2's HUD pipeline queues draw commands into a GuiRenderState via this extractor
    // instead of drawing straight to a GuiGraphics; its fill/text API mirrors GuiGraphics'
    // old fill/drawString closely enough that only the target class/method names changed) --
    // piggybacks on the game's own renderer instead of a second GL context/classloader, which
    // sidesteps LWJGL's per-thread capability binding being tied to whichever classloader first
    // called GL.createCapabilities() for this thread.

    public void fill(Object guiGraphicsExtractor, int x1, int y1, int x2, int y2, int argb) {
        Method m = ra.method("net.minecraft.client.gui.GuiGraphicsExtractor", "fill", 5);
        ra.invoke(m, guiGraphicsExtractor, x1, y1, x2, y2, argb);
    }

    public void boxOutline(Object guiGraphicsExtractor, double x1, double y1, double x2, double y2, int argb, int thickness) {
        int ix1 = (int) Math.round(x1), iy1 = (int) Math.round(y1);
        int ix2 = (int) Math.round(x2), iy2 = (int) Math.round(y2);
        fill(guiGraphicsExtractor, ix1, iy1, ix2, iy1 + thickness, argb);
        fill(guiGraphicsExtractor, ix1, iy2 - thickness, ix2, iy2, argb);
        fill(guiGraphicsExtractor, ix1, iy1, ix1 + thickness, iy2, argb);
        fill(guiGraphicsExtractor, ix2 - thickness, iy1, ix2, iy2, argb);
    }

    /** text(Font, String, int, int, int) — disambiguated by param[1] since GuiGraphicsExtractor
     * also overloads text() for Component and FormattedCharSequence at the same 5-arg arity */
    public void drawString(Object guiGraphicsExtractor, String text, int x, int y, int argb) {
        Object font = ra.get(ra.field("net.minecraft.client.Minecraft", "font"), minecraft);
        Method m = ra.method("net.minecraft.client.gui.GuiGraphicsExtractor", "text", 5, 1, "String");
        ra.invoke(m, guiGraphicsExtractor, font, text, x, y, argb);
    }
}
