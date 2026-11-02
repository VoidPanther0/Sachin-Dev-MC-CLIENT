package doomsday.core;

public abstract class Module {

    private final String name;
    private final Category category;
    private int keybind; // GLFW key code, 0 = unbound
    private volatile boolean enabled;

    protected Module(String name, Category category, int keybind) {
        this.name = name;
        this.category = category;
        this.keybind = keybind;
    }

    public final String name() { return name; }
    public final Category category() { return category; }
    public final int keybind() { return keybind; }
    public final void setKeybind(int key) { this.keybind = key; }
    public final boolean enabled() { return enabled; }

    public final void toggle() {
        if (enabled) disable(); else enable();
    }

    public final void setEnabled(boolean value) {
        if (value) enable(); else disable();
    }

    private void enable() {
        if (enabled) return;
        enabled = true;
        onEnable();
    }

    private void disable() {
        if (!enabled) return;
        enabled = false;
        onDisable();
    }

    protected void onEnable() {}
    protected void onDisable() {}

    /** called every client tick while enabled */
    public void onTick() {}

    /** called once per frame from the Gui#extractRenderState hook — guiGraphicsExtractor is the live net.minecraft.client.gui.GuiGraphicsExtractor instance */
    public void onRenderWorld(Object guiGraphicsExtractor) {}

    /** called once per frame, after onRenderWorld, same guiGraphicsExtractor — screen-space overlay (click-gui etc) */
    public void onRenderScreen(Object guiGraphicsExtractor) {}
}
