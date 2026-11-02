package doomsday.core;

import doomsday.gui.ClickGui;
import doomsday.modules.combat.Aimbot;
import doomsday.modules.combat.KillAura;
import doomsday.modules.combat.Reach;
import doomsday.modules.misc.AutoClicker;
import doomsday.modules.movement.Flight;
import doomsday.modules.movement.Speed;
import doomsday.modules.render.Chams;
import doomsday.modules.render.Esp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {

    private static final ModuleManager INSTANCE = new ModuleManager();
    public static ModuleManager get() { return INSTANCE; }

    private final List<Module> modules = new ArrayList<>();
    private final ClickGui clickGui = new ClickGui(this);

    private ModuleManager() {
        register(new KillAura());
        register(new Aimbot());
        register(new Reach());
        register(new Speed());
        register(new Flight());
        register(new Esp());
        register(new Chams());
        register(new AutoClicker());
        Config.load(this);
    }

    private void register(Module m) { modules.add(m); }

    public List<Module> modules() { return Collections.unmodifiableList(modules); }

    public Module byName(String name) {
        for (Module m : modules) if (m.name().equalsIgnoreCase(name)) return m;
        return null;
    }

    public ClickGui clickGui() { return clickGui; }

    /** entry point from the injected tick hook */
    public void onTick() {
        for (Module m : modules) {
            if (m.enabled()) {
                try { m.onTick(); } catch (Throwable t) { t.printStackTrace(); }
            }
        }
    }

    /** entry point from the injected Gui#render hook, before the click-gui draws on top */
    public void onRenderWorld(Object guiGraphics) {
        for (Module m : modules) {
            if (m.enabled()) {
                try { m.onRenderWorld(guiGraphics); } catch (Throwable t) { t.printStackTrace(); }
            }
        }
    }

    /** entry point from the injected Gui#render hook, last — click-gui draws on top of everything */
    public void onRenderScreen(Object guiGraphics) {
        for (Module m : modules) {
            if (m.enabled()) {
                try { m.onRenderScreen(guiGraphics); } catch (Throwable t) { t.printStackTrace(); }
            }
        }
        clickGui.renderIfOpen(guiGraphics);
    }

    /** entry point from the injected key-poll hook — key is a GLFW key code */
    public void onKey(int key) {
        if (key == Config.guiKeybind()) {
            clickGui.toggle();
            return;
        }
        for (Module m : modules) {
            if (m.keybind() == key) m.toggle();
        }
    }
}
