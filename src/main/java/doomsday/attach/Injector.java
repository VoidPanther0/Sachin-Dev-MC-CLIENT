// java, doomsday/attach/Injector.java, JDK 21, standalone CLI/GUI, target: any running Minecraft 26.1.2 JVM (same machine)
package doomsday.attach;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import doomsday.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.List;

/**
 * Attaches to an already-running Minecraft JVM via the JDK Attach API and
 * loads the agent jar through Instrumentation.appendToSystemClassLoaderSearch
 * + agentmain. No -javaagent launch flag required — this is the "injectable"
 * path (doomsday/future-client style) as opposed to a mod-loader payload.
 *
 * Double-click / run with no args -> opens the Inject GUI.
 * Usage: java -jar doomsday.jar [pid|auto] [path/to/doomsday-agent.jar]  (CLI/scripting path)
 */
public final class Injector {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            InjectorGui.launch();
            return;
        }

        Path logPath = Log.redirectToFile("injector-cli.log");
        System.out.println("[doomsday] logging to " + logPath);

        String selfJar = currentJarPath();
        String targetPid = args[0];
        String agentJar = args.length > 1 ? args[1] : selfJar;

        if (agentJar == null) {
            System.err.println("[doomsday] could not resolve agent jar path — pass it explicitly as arg 2");
            System.exit(1);
            return;
        }

        VirtualMachineDescriptor target = "auto".equals(targetPid) ? findMinecraftJvm() : findByPid(targetPid);

        if (target == null) {
            System.err.println("[doomsday] no matching JVM found. running JVMs:");
            for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
                System.err.println("  pid=" + vmd.id() + " display=" + vmd.displayName());
            }
            System.exit(1);
            return;
        }

        doAttach(target, agentJar);
    }

    /** Core attach logic shared by the CLI path and the GUI. Throws on failure — caller decides how to surface it. */
    static void doAttach(VirtualMachineDescriptor target, String agentJar) throws Exception {
        doAttach(target, agentJar, "", InjectorSettings.Naming.LEGAL);
    }

    /**
     * agentArgs lets the GUI pass a target version straight through to DoomsdayAgent#agentmain
     * (same =version suffix the static -javaagent: flag uses). naming ILLEGAL stages the jar
     * under a randomized filename in a temp dir first: VirtualMachine.loadAgent's path ends up
     * briefly visible to anything scanning the target process's loaded-module/open-handle list
     * (window titles never show it, but a jar literally named doomsday-agent-*.jar does) —
     * cheap, real evasion against a human eyeballing Process Explorer; it does nothing against a
     * scanner that hashes bytes rather than reading the filename.
     */
    static void doAttach(VirtualMachineDescriptor target, String agentJar, String agentArgs,
                          InjectorSettings.Naming naming) throws Exception {
        String loadPath = naming == InjectorSettings.Naming.ILLEGAL ? stageUnderRandomName(agentJar) : agentJar;
        System.out.println("[doomsday] attaching to pid=" + target.id() + " (" + target.displayName() + ")"
                + (naming == InjectorSettings.Naming.ILLEGAL ? " [staged as " + loadPath + "]" : ""));
        VirtualMachine vm = VirtualMachine.attach(target);
        try {
            vm.loadAgent(loadPath, agentArgs == null ? "" : agentArgs);
            System.out.println("[doomsday] agent loaded — in-game hook/crash output lands in "
                    + Log.LOG_DIR.resolve("agent.log"));
        } finally {
            vm.detach();
        }
    }

    private static final SecureRandom RNG = new SecureRandom();

    /** copies the agent jar into %TEMP% under a random 16-char name — see doAttach's javadoc above */
    private static String stageUnderRandomName(String agentJar) throws IOException {
        Path src = Path.of(agentJar);
        StringBuilder name = new StringBuilder();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 16; i++) name.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        Path dest = Path.of(System.getProperty("java.io.tmpdir")).resolve(name + ".jar");
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        dest.toFile().deleteOnExit();
        return dest.toAbsolutePath().toString();
    }

    /** reads Implementation-Version off this jar's own manifest (set by build.gradle.kts' shadowJar block); "dev" when running unpackaged from an IDE */
    static String currentVersion() {
        String v = Injector.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    static VirtualMachineDescriptor findByPid(String pid) {
        for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
            if (vmd.id().equals(pid)) return vmd;
        }
        return null;
    }

    static List<VirtualMachineDescriptor> listJvms() {
        return VirtualMachine.list();
    }

    static VirtualMachineDescriptor findMinecraftJvm() {
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        String self = ProcessHandle.current().pid() + "";
        for (VirtualMachineDescriptor vmd : vms) {
            if (vmd.id().equals(self)) continue;
            String name = vmd.displayName().toLowerCase();
            // launcher main classes across vanilla/MultiMC/Prism/forge-installer launches
            if (name.contains("net.minecraft.client.main.main")
                    || name.contains("net.minecraft.launchwrapper")
                    || name.contains("minecraft")
                    || name.contains(".minecraft/")
                    || name.contains("fml") || name.contains("fabric")) {
                return vmd;
            }
        }
        return null;
    }

    static String currentJarPath() {
        try {
            String path = Injector.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            // strip a leading drive-letter slash mangling on windows (e.g. /C:/...)
            if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            return path.endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }
}
