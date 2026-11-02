// java, doomsday/attach/UpdateManager.java, JDK 21, target: injector GUI Updates tab + Advanced inject version dropdown
package doomsday.attach;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Local agent-jar cache under ~/.doomsday/versions/ plus an optional remote manifest check.
 * No build pipeline here publishes historical versions anywhere — the only jar guaranteed to
 * exist is the one currently running the injector — so this reads real local state honestly
 * and only reaches out to MANIFEST_URL (unset by default) for anything beyond that.
 */
public final class UpdateManager {

    public record RemoteEntry(String version, String url, long sizeBytes) {}
    public record LocalEntry(String version, Path jar, long sizeBytes, boolean isCurrent) {}

    public static final Path VERSIONS_DIR = Paths.get(System.getProperty("user.home"), ".doomsday", "versions");

    // FILL: point this at a real manifest endpoint (JSON array of {"version","url","sizeBytes"})
    // once one exists — doomsdayclient.com in the reference UI isn't a host this build has
    // credentials or a contract with, so "Check updates" below no-ops to an empty list against
    // the placeholder and surfaces the connection failure instead of pretending to succeed.
    public static final String MANIFEST_URL = "";

    private UpdateManager() {}

    public static List<LocalEntry> listLocal(String currentVersion, Path currentJar) {
        List<LocalEntry> out = new ArrayList<>();
        try {
            Files.createDirectories(VERSIONS_DIR);
            try (var stream = Files.list(VERSIONS_DIR)) {
                for (Path p : stream.filter(f -> f.toString().endsWith(".jar")).toList()) {
                    String v = versionFromFileName(p.getFileName().toString());
                    out.add(new LocalEntry(v, p, sizeOf(p), false));
                }
            }
        } catch (IOException ignored) {}
        if (currentJar != null && Files.exists(currentJar)) {
            out.removeIf(e -> e.version.equals(currentVersion));
            out.add(new LocalEntry(currentVersion, currentJar, sizeOf(currentJar), true));
        }
        out.sort(Comparator.comparing(LocalEntry::version).reversed());
        return out;
    }

    private static long sizeOf(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    private static String versionFromFileName(String fileName) {
        String base = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        int dash = base.lastIndexOf('-');
        return dash >= 0 ? base.substring(dash + 1) : base;
    }

    /** throws on any failure — caller surfaces it, no silent empty-list-as-success */
    public static List<RemoteEntry> checkRemote() throws IOException, InterruptedException {
        if (MANIFEST_URL.isBlank()) {
            throw new IOException("no manifest URL configured — set UpdateManager.MANIFEST_URL");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(MANIFEST_URL)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("manifest fetch failed: HTTP " + resp.statusCode());
        }
        JsonElement root = new Gson().fromJson(resp.body(), JsonElement.class);
        List<RemoteEntry> out = new ArrayList<>();
        if (root != null && root.isJsonArray()) {
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                out.add(new RemoteEntry(
                        o.get("version").getAsString(),
                        o.get("url").getAsString(),
                        o.has("sizeBytes") ? o.get("sizeBytes").getAsLong() : 0));
            }
        }
        return out;
    }

    public static Path download(RemoteEntry entry) throws IOException, InterruptedException {
        Files.createDirectories(VERSIONS_DIR);
        Path dest = VERSIONS_DIR.resolve("doomsday-agent-" + entry.version() + ".jar");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(entry.url())).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
        if (resp.statusCode() / 100 != 2) {
            Files.deleteIfExists(dest);
            throw new IOException("download failed: HTTP " + resp.statusCode());
        }
        return dest;
    }

    public static void delete(LocalEntry entry) throws IOException {
        if (entry.isCurrent()) throw new IOException("can't delete the running build's jar");
        Files.deleteIfExists(entry.jar());
    }

    /** caches the jar this injector is currently running as — so "current build" survives a settings-dir wipe */
    public static void seedCurrentBuild(String version, Path currentJar) {
        if (currentJar == null || !Files.exists(currentJar)) return;
        try {
            Files.createDirectories(VERSIONS_DIR);
            Path dest = VERSIONS_DIR.resolve("doomsday-agent-" + version + ".jar");
            if (!Files.exists(dest)) Files.copy(currentJar, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }
}
