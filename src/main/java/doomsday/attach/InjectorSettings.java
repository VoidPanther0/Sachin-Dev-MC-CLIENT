// java, doomsday/attach/InjectorSettings.java, JDK 21, target: injector GUI persisted prefs (~/.doomsday/injector-settings.json)
package doomsday.attach;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Settings the gear icon's two sub-tabs ("Inject settings" / "Loader settings") edit.
 * Separate file from doomsday/core/Config.json: that one is agent-side runtime state
 * (which the injector never directly owns), this one is injector-side behavior.
 */
public final class InjectorSettings {

    public enum Naming { LEGAL, ILLEGAL }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = Paths.get(System.getProperty("user.home"), ".doomsday", "injector-settings.json");

    public String attachMode = "Thread";       // only real mode this tool has: VirtualMachine.attach + loadAgent on a worker thread
    public Naming naming = Naming.ILLEGAL;     // ILLEGAL: stage the agent jar under a randomized temp name before attach
    public String classLoader = "";            // optional: force a specific loader class name in ReflectAccess resolution (advanced/rare)
    public int guiKeybind = doomsday.util.Keybind.GUI_TOGGLE; // GLFW code, mirrored into config.json on save so the agent picks it up
    public String extraJvmArgs = "";           // Loader settings: extra args appended by InstallerFlow's static-attach install

    public static InjectorSettings load() {
        if (!Files.exists(PATH)) return new InjectorSettings();
        try (var r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            InjectorSettings s = GSON.fromJson(r, InjectorSettings.class);
            return s != null ? s : new InjectorSettings();
        } catch (IOException | RuntimeException e) {
            return new InjectorSettings();
        }
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (var w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
            syncGuiKeybindToAgentConfig();
        } catch (IOException e) {
            System.err.println("[doomsday] injector settings save failed: " + e);
        }
    }

    /**
     * config.json (agent-side) is the only channel to the in-game ModuleManager — the injector
     * and the agent never share a JVM — so a plain read-modify-write of its "guiKeybind" field is
     * how the Settings tab's dropdown actually changes the in-game toggle key, picked up at the
     * agent's next Config.load() (i.e. next injection).
     */
    private void syncGuiKeybindToAgentConfig() {
        Path cfgPath = Paths.get(System.getProperty("user.home"), ".doomsday", "config.json");
        try {
            com.google.gson.JsonObject root;
            if (Files.exists(cfgPath)) {
                try (var r = Files.newBufferedReader(cfgPath, StandardCharsets.UTF_8)) {
                    com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseReader(r);
                    root = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new com.google.gson.JsonObject();
                }
            } else {
                Files.createDirectories(cfgPath.getParent());
                root = new com.google.gson.JsonObject();
            }
            root.addProperty("guiKeybind", guiKeybind);
            try (var w = Files.newBufferedWriter(cfgPath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[doomsday] could not sync guiKeybind into config.json: " + e);
        }
    }
}
