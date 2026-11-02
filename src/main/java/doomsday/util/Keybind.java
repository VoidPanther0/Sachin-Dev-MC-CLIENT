package doomsday.util;

/** GLFW key codes (org.lwjgl.glfw.GLFW constants, hardcoded to avoid an lwjgl compile dep in the agent jar) */
public final class Keybind {
    private Keybind() {}
    public static final int GUI_TOGGLE = 260; // GLFW_KEY_INSERT
    public static final int KILLAURA = 82;    // GLFW_KEY_R
    public static final int AIMBOT = 67;      // GLFW_KEY_C
    public static final int SPEED = 86;       // GLFW_KEY_V
    public static final int FLIGHT = 70;      // GLFW_KEY_F
    public static final int ESP = 0;          // unbound by default, toggle via gui
    public static final int CHAMS = 0;
    public static final int REACH = 0;
    public static final int AUTOCLICKER = 75; // GLFW_KEY_K
}
