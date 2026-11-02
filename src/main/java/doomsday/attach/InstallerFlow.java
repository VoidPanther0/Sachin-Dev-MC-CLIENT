// java, doomsday/attach/InstallerFlow.java, JDK 21, Windows, target: Install tab -- wires javaagent into a launch path that survives a restart
package doomsday.attach;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Static-attach install: instead of the Inject tab's one-shot dynamic VirtualMachine.attach
 * (lasts until the game closes), this drops a persistent -javaagent:...=version flag into the
 * launcher's own JVM-args storage so every future launch loads the agent at premain() time,
 * before Minecraft's main() runs — needed for anything that only works pre-bootstrap.
 *
 * Two real, distinct mechanisms, picked by the "Install as Forge/Fabric mod" checkbox:
 *  - unchecked: vanilla Mojang launcher's launcher_profiles.json, "javaArgs" per profile.
 *  - checked:   modern Forge/NeoForge's user_jvm_args.txt convention (a plain-text file of extra
 *    JVM flags, one per line, read by the loader's own run script/arg-file plumbing) dropped at
 *    the instance root. Fabric has no equivalent convention — ship the vanilla path for it; a
 *    Fabric user still gets a correct launcher_profiles.json entry since Fabric profiles are
 *    vanilla-launcher profiles under the hood.
 */
public final class InstallerFlow {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private InstallerFlow() {}

    public static List<String> detectVersions(Path minecraftDir) {
        Path versionsDir = minecraftDir.resolve("versions");
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(versionsDir)) return out;
        try (var stream = Files.list(versionsDir)) {
            out.addAll(stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList()));
        } catch (IOException ignored) {}
        return out;
    }

    public static void installVanilla(Path minecraftDir, Path agentJar, String version) throws IOException {
        Path profiles = minecraftDir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            throw new IOException("no launcher_profiles.json under " + minecraftDir
                    + " -- open the vanilla Minecraft launcher at least once first so it creates its profile store");
        }
        JsonObject root = readJson(profiles);
        JsonObject profilesObj = root.has("profiles") ? root.getAsJsonObject("profiles") : new JsonObject();
        if (profilesObj.entrySet().isEmpty()) {
            throw new IOException("launcher_profiles.json has no profiles yet -- create one in the launcher first");
        }

        String flag = javaAgentFlag(agentJar, version);
        int patched = 0;
        for (var entry : profilesObj.entrySet()) {
            JsonObject profile = entry.getValue().getAsJsonObject();
            String existing = profile.has("javaArgs") ? profile.get("javaArgs").getAsString() : "";
            if (existing.contains("doomsday")) {
                existing = stripExistingDoomsdayFlag(existing);
            }
            String merged = existing.isBlank() ? flag : existing.trim() + " " + flag;
            profile.addProperty("javaArgs", merged);
            patched++;
        }
        if (patched == 0) throw new IOException("no profiles patched");
        writeJson(profiles, root);
    }

    public static void installForgeFabric(Path instanceDir, Path agentJar, String version) throws IOException {
        Path argsFile = instanceDir.resolve("user_jvm_args.txt");
        List<String> lines = new ArrayList<>();
        if (Files.exists(argsFile)) {
            for (String line : Files.readAllLines(argsFile, StandardCharsets.UTF_8)) {
                if (!line.contains("doomsday")) lines.add(line);
            }
        } else {
            lines.add("# user_jvm_args.txt -- one JVM argument per line, consumed by Forge/NeoForge's launch args file");
        }
        lines.add(javaAgentFlag(agentJar, version));
        Files.write(argsFile, lines, StandardCharsets.UTF_8);
    }

    private static String javaAgentFlag(Path agentJar, String version) {
        return "-javaagent:" + agentJar.toAbsolutePath() + "=" + version;
    }

    private static String stripExistingDoomsdayFlag(String javaArgs) {
        StringBuilder out = new StringBuilder();
        for (String tok : javaArgs.split("\\s+")) {
            if (!tok.startsWith("-javaagent:") || !tok.contains("doomsday")) {
                if (!out.isEmpty()) out.append(' ');
                out.append(tok);
            }
        }
        return out.toString();
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (var r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var parsed = JsonParser.parseReader(r);
            if (parsed == null || !parsed.isJsonObject()) throw new IOException(path + " is not a JSON object");
            return parsed.getAsJsonObject();
        }
    }

    private static void writeJson(Path path, JsonObject obj) throws IOException {
        Path backup = path.resolveSibling(path.getFileName() + ".doomsday-bak");
        Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        }
    }
}
