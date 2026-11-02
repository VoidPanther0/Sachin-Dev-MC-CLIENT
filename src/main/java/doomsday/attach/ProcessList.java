// java, doomsday/attach/ProcessList.java, JDK 21, Windows, target: injector GUI's Advanced inject process table
package doomsday.attach;

import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enumerates JVMs visible to the Attach API and decorates each with OS-level detail
 * (user, working-set memory) that the Attach API doesn't expose. ProcessHandle gives
 * pid/user/command cross-platform; working-set memory has no Java API on Windows, so
 * that column comes from a single batched `tasklist` call (one process spawn for the
 * whole table, not one per row) rather than WMI/JNA, keeping this dependency-free.
 */
public final class ProcessList {

    public record Row(String pid, String bits, String name, String user, String memory, boolean isMinecraft) {}

    private ProcessList() {}

    /** javaProcessesOnly=true mirrors the reference tool's "java processes" filter; false lists every PID. */
    public static List<Row> list(boolean javaProcessesOnly) {
        Map<Long, String> memByPid = readWorkingSetMemory();
        List<Row> out = new ArrayList<>();

        if (javaProcessesOnly) {
            for (VirtualMachineDescriptor vmd : com.sun.tools.attach.VirtualMachine.list()) {
                long pid;
                try {
                    pid = Long.parseLong(vmd.id());
                } catch (NumberFormatException e) {
                    continue;
                }
                Optional<ProcessHandle> ph = ProcessHandle.of(pid);
                String name = ph.flatMap(p -> p.info().command())
                        .map(ProcessList::fileName)
                        .orElse(guessNameFromDisplay(vmd.displayName()));
                String user = ph.flatMap(p -> p.info().user()).map(ProcessList::stripDomain).orElse("?");
                String mem = memByPid.getOrDefault(pid, "?");
                out.add(new Row(vmd.id(), "x64", name, user, mem, looksLikeMinecraft(vmd.displayName(), name)));
            }
        } else {
            for (ProcessHandle p : ProcessHandle.allProcesses().toList()) {
                String name = p.info().command().map(ProcessList::fileName).orElse("?");
                String user = p.info().user().map(ProcessList::stripDomain).orElse("?");
                String mem = memByPid.getOrDefault(p.pid(), "?");
                out.add(new Row(String.valueOf(p.pid()), "x64", name, user, mem, looksLikeMinecraft(name, name)));
            }
        }
        return out;
    }

    private static boolean looksLikeMinecraft(String displayName, String procName) {
        String d = (displayName == null ? "" : displayName).toLowerCase();
        String n = (procName == null ? "" : procName).toLowerCase();
        return d.contains("minecraft") || d.contains("fabric") || d.contains("fml")
                || n.equals("javaw.exe") || n.equals("java.exe");
    }

    private static String fileName(String fullPath) {
        int i = Math.max(fullPath.lastIndexOf('\\'), fullPath.lastIndexOf('/'));
        return i >= 0 ? fullPath.substring(i + 1) : fullPath;
    }

    private static String stripDomain(String user) {
        int i = user.lastIndexOf('\\');
        return i >= 0 ? user.substring(i + 1) : user;
    }

    private static String guessNameFromDisplay(String displayName) {
        if (displayName == null || displayName.isBlank()) return "java.exe";
        int sp = displayName.indexOf(' ');
        String head = sp > 0 ? displayName.substring(0, sp) : displayName;
        return head.contains(".") ? "java.exe" : head;
    }

    /** one `tasklist /fo csv` spawn for the whole table — avoids O(n) process spawns for n rows */
    private static Map<Long, String> readWorkingSetMemory() {
        Map<Long, String> out = new HashMap<>();
        try {
            Process proc = new ProcessBuilder("tasklist", "/fo", "csv", "/nh").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), Charset.forName("UTF-8")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    List<String> cols = parseCsvLine(line);
                    if (cols.size() < 5) continue;
                    try {
                        long pid = Long.parseLong(cols.get(1).trim());
                        out.put(pid, cols.get(4).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            // tasklist unavailable (non-Windows, locked down shell, etc) — memory column just reads "?"
        }
        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
