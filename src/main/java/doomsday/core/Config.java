package doomsday.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = Paths.get(System.getProperty("user.home"), ".doomsday", "config.json");

    // written by the injector GUI's Settings > Inject settings > GUI keybind dropdown (a plain
    // read-modify-write of this same file from the injector's own process, config.json is the
    // only channel between the two since the injector doesn't share a JVM with the agent), read
    // here at agent startup — ModuleManager#onKey checks against this instead of the old hardcoded
    // doomsday.util.Keybind.GUI_TOGGLE constant, which is now just the fallback default below.
    private static volatile int guiKeybind = doomsday.util.Keybind.GUI_TOGGLE;

    private Config() {}

    public static int guiKeybind() { return guiKeybind; }

    public static void load(ModuleManager mgr) {
        if (!Files.exists(PATH)) return;
        try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return;
            if (root.has("guiKeybind")) guiKeybind = root.get("guiKeybind").getAsInt();
            JsonObject modulesObj = root.getAsJsonObject("modules");
            if (modulesObj == null) return;
            for (String key : modulesObj.keySet()) {
                Module m = mgr.byName(key);
                if (m == null) continue;
                JsonObject entry = modulesObj.getAsJsonObject(key);
                boolean enabled = entry.has("enabled") && entry.get("enabled").getAsBoolean();
                if (entry.has("keybind")) m.setKeybind(entry.get("keybind").getAsInt());
                m.setEnabled(enabled);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[doomsday] config load failed: " + e);
        }
    }

    public static void save(ModuleManager mgr) {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("guiKeybind", guiKeybind);
            JsonObject modulesObj = new JsonObject();
            for (Module m : mgr.modules()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("enabled", m.enabled());
                entry.addProperty("keybind", m.keybind());
                modulesObj.add(m.name(), entry);
            }
            root.add("modules", modulesObj);
            try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException e) {
            System.err.println("[doomsday] config save failed: " + e);
        }
    }
}
