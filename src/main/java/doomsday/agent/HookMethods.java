package doomsday.agent;

import doomsday.core.ModuleManager;
import doomsday.mc.Mc;

import java.lang.reflect.Method;

/**
 * Static entry points invoked by bytecode injected into the target JVM.
 * Keep these signatures exactly in sync with what Transformer emits —
 * INVOKESTATIC is resolved by (owner, name, descriptor) at inject time.
 */
public final class HookMethods {

    private HookMethods() {}

    private static final boolean[] keyWasDown = new boolean[400];
    private static boolean leftWasDown = false;

    private static Method glfwGetKey, glfwGetMouseButton, glfwGetCursorPos;
    private static int GLFW_PRESS, GLFW_MOUSE_BUTTON_LEFT;

    public record Click(double x, double y) {}
    private static volatile Click pendingClick = null;
    private static volatile double mouseX = 0, mouseY = 0;

    /** consumes (clears) the last left-click-press this tick, so each click is handled exactly once */
    public static Click consumeClick() {
        Click c = pendingClick;
        pendingClick = null;
        return c;
    }

    public static double mouseX() { return mouseX; }
    public static double mouseY() { return mouseY; }

    /**
     * injected at the top of Minecraft#tick(). Mc.init() runs on a separate async thread
     * (DoomsdayAgent.waitAndInitFacade) and tick() can start firing before it finishes — bail
     * out and retry next tick rather than touch a null Mc.
     */
    public static void onTick() {
        try {
            if (Mc.get() == null) return;
            ModuleManager.get().onTick();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * injected right before the RETURN of Gui#extractRenderState(GuiGraphicsExtractor, DeltaTracker).
     * Input polling lives here, inline, rather than on a separate background thread like earlier
     * Minecraft versions tolerated: this build's GLFW asserts the calling thread is the render
     * thread (Window#defaultErrorCallback -> RenderSystem#assertOnRenderThread) and throws on every
     * single call otherwise — a background poll thread hit that assertion ~60x/sec, which never
     * crashed the game but silently spammed tens of thousands of swallowed exceptions into the log
     * every session and left key/click state unreliable. extractRenderState already runs on the
     * same thread tick() does in this client, so there's no reason for a second thread at all.
     */
    public static void onRenderScreen(Object guiGraphicsExtractor) {
        try {
            if (Mc.get() == null) return;
            pollInput();
            ModuleManager mgr = ModuleManager.get();
            mgr.onRenderWorld(guiGraphicsExtractor);
            mgr.onRenderScreen(guiGraphicsExtractor);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void pollInput() {
        try {
            ensureGlfwBound();
            Mc mc = Mc.get();
            long window = mc.windowHandle();

            for (int key = 0; key < keyWasDown.length; key++) {
                boolean down = ((int) glfwGetKey.invoke(null, window, key)) == GLFW_PRESS;
                if (down && !keyWasDown[key]) {
                    try { ModuleManager.get().onKey(key); } catch (Throwable t) { t.printStackTrace(); }
                }
                keyWasDown[key] = down;
            }

            double[] xBuf = new double[1], yBuf = new double[1];
            glfwGetCursorPos.invoke(null, window, xBuf, yBuf);
            mouseX = xBuf[0];
            mouseY = yBuf[0];
            boolean leftDown = ((int) glfwGetMouseButton.invoke(null, window, GLFW_MOUSE_BUTTON_LEFT)) == GLFW_PRESS;
            if (leftDown && !leftWasDown) {
                pendingClick = new Click(mouseX, mouseY);
            }
            leftWasDown = leftDown;
        } catch (Throwable t) {
            System.err.println("[doomsday] input poll failed: " + t);
            t.printStackTrace();
        }
    }

    private static void ensureGlfwBound() throws ReflectiveOperationException {
        if (glfwGetKey != null) return;
        Mc mc = Mc.get();
        ClassLoader loader = mc.client().getClass().getClassLoader();
        Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", true, loader);
        glfwGetKey = glfw.getMethod("glfwGetKey", long.class, int.class);
        glfwGetMouseButton = glfw.getMethod("glfwGetMouseButton", long.class, int.class);
        glfwGetCursorPos = glfw.getMethod("glfwGetCursorPos", long.class, double[].class, double[].class);
        GLFW_PRESS = glfw.getField("GLFW_PRESS").getInt(null);
        GLFW_MOUSE_BUTTON_LEFT = glfw.getField("GLFW_MOUSE_BUTTON_LEFT").getInt(null);
    }
}
