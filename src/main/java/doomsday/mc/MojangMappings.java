package doomsday.mc;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads Mojang's official (client) ProGuard-style mapping for a given version
 * and exposes deobf-name -> obf-name lookups, so the agent can target real
 * Mojang class/method names even when the launched jar is still obfuscated
 * (a plain vanilla launcher jar, not a dev/Fabric remapped environment).
 *
 * Source: version_manifest_v2.json -> per-version json -> downloads.client_mappings.url
 * (real, documented Mojang endpoint — https://minecraft.wiki/w/Version_manifest.json)
 *
 * Mapping line formats (ProGuard "retrace" style):
 *   some.deobf.ClassName -> a:
 *       int someField -> b
 *       void someMethod(int,float) -> c
 */
public final class MojangMappings {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Pattern CLASS_LINE = Pattern.compile("^([\\w.$]+) -> ([\\w$]+):$");

    private static volatile MojangMappings INSTANCE;

    private final Map<String, String> classDeobfToObf = new HashMap<>();
    private final Map<String, Map<String, String>> memberDeobfToObf = new HashMap<>(); // keyed by deobf class name

    private MojangMappings() {}

    public static synchronized MojangMappings forVersion(String version) {
        if (INSTANCE != null) return INSTANCE;
        MojangMappings m = new MojangMappings();
        try {
            m.load(version);
        } catch (Exception e) {
            System.err.println("[doomsday] mapping load failed for " + version + ": " + e
                    + " -- falling back to direct (unmapped) reflection, which only works against an already-deobfuscated runtime (e.g. dev env / remapped launch).");
        }
        INSTANCE = m;
        return m;
    }

    private void load(String version) throws IOException, InterruptedException {
        Path cache = Paths.get(System.getProperty("user.home"), ".doomsday", "mappings-" + version + ".txt");
        String text;
        if (Files.exists(cache)) {
            text = Files.readString(cache, StandardCharsets.UTF_8);
        } else {
            String manifest = httpGet(MANIFEST_URL);
            String versionUrl = extractJsonUrlForId(manifest, version);
            if (versionUrl == null) {
                throw new IOException("version '" + version + "' not found in version_manifest_v2.json "
                        + "(26.1.2 may use a different manifest id than the display version — check manually and pass the manifest id as the agent arg)");
            }
            String versionJson = httpGet(versionUrl);
            String mappingsUrl = extractField(versionJson, "client_mappings");
            if (mappingsUrl == null) {
                throw new IOException("no client_mappings entry in version json for " + version);
            }
            text = httpGet(mappingsUrl);
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, text, StandardCharsets.UTF_8);
        }
        parse(text);
        System.out.println("[doomsday] loaded mappings for " + version + ": " + classDeobfToObf.size() + " classes");
    }

    /*
     * Member lines look like (ProGuard "retrace" mapping format):
     *   int someField -> b
     *   1:1:void tick():2068:2068 -> a
     * The line-number prefix (if any) is glued to the return type token with
     * no space, so splitting the "left of the last '->'" segment on
     * whitespace and taking the last token always isolates the real name —
     * no need to strip the prefix explicitly.
     */
    private void parse(String text) {
        String currentDeobfClass = null;
        for (String raw : text.split("\n")) {
            String line = raw.stripTrailing();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                Matcher cm = CLASS_LINE.matcher(line);
                if (cm.matches()) {
                    currentDeobfClass = cm.group(1);
                    classDeobfToObf.put(currentDeobfClass, cm.group(2));
                    memberDeobfToObf.put(currentDeobfClass, new HashMap<>());
                }
                continue;
            }
            if (currentDeobfClass == null) continue;

            int arrow = line.lastIndexOf("->");
            if (arrow < 0) continue;
            String left = line.substring(0, arrow).trim();
            String obfName = line.substring(arrow + 2).trim();
            if (obfName.isEmpty()) continue;

            int paren = left.indexOf('(');
            String nameSection = paren >= 0 ? left.substring(0, paren) : left;
            String[] tokens = nameSection.trim().split("\\s+");
            if (tokens.length == 0) continue;
            String deobfName = tokens[tokens.length - 1];
            if (deobfName.isEmpty()) continue;

            memberDeobfToObf.get(currentDeobfClass).putIfAbsent(deobfName, obfName);
        }
    }

    /** @param deobfBinaryName dotted, e.g. "net.minecraft.client.Minecraft" */
    public String obfClass(String deobfBinaryName) {
        return classDeobfToObf.get(deobfBinaryName);
    }

    public String obfMember(String deobfBinaryClass, String deobfMemberName) {
        Map<String, String> members = memberDeobfToObf.get(deobfBinaryClass);
        return members == null ? null : members.get(deobfMemberName);
    }

    public boolean loaded() {
        return !classDeobfToObf.isEmpty();
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
        return resp.body();
    }

    // minimal hand-rolled JSON field extraction to avoid a gson dependency on this hot path's startup;
    // we already depend on gson elsewhere so this could use it too, but keeping this class self-contained
    // means mapping bootstrap works even if gson fails to load this early.
    private static String extractJsonUrlForId(String manifestJson, String id) {
        Pattern p = Pattern.compile("\\{[^{}]*\"id\"\\s*:\\s*\"" + Pattern.quote(id) + "\"[^{}]*\"url\"\\s*:\\s*\"([^\"]+)\"[^{}]*}");
        Matcher m = p.matcher(manifestJson);
        if (m.find()) return m.group(1);
        // id/url order may be swapped
        p = Pattern.compile("\\{[^{}]*\"url\"\\s*:\\s*\"([^\"]+)\"[^{}]*\"id\"\\s*:\\s*\"" + Pattern.quote(id) + "\"[^{}]*}");
        m = p.matcher(manifestJson);
        return m.find() ? m.group(1) : null;
    }

    private static String extractField(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{[^{}]*\"url\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
