package doomsday.agent;

import doomsday.mc.MojangMappings;
import doomsday.util.Log;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DoomsdayAgent {

    private static final String DEFAULT_VERSION = "26.1.2";

    private DoomsdayAgent() {}

    /** static attach: -javaagent:doomsday-agent.jar[=version] — runs before Minecraft's main(), classes not yet loaded */
    public static void premain(String agentArgs, Instrumentation inst) {
        start(agentArgs, inst, false);
    }

    /** dynamic attach: VirtualMachine.attach(pid).loadAgent(jar, args) — Minecraft is already running, classes already loaded */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        start(agentArgs, inst, true);
    }

    private static void start(String agentArgs, Instrumentation inst, boolean retransformLoaded) {
        // tee System.out/err to ~/.doomsday/agent.log — once this jar is loaded into Minecraft's
        // JVM there's often no visible console for it at all, so this is the only place a
        // transform/hook crash shows up.
        Path logPath = Log.redirectToFile("agent.log");
        System.out.println("[doomsday] agent log: " + logPath);

        try {
            // Dynamic attach already makes this jar's own classes resolvable via the system
            // class loader (confirmed empirically: Transformer/MojangMappings load fine below
            // with no explicit append) — the only real problem is the *game's* class loader,
            // handled in exposeAgentJarToGameLoader() once Minecraft's own loader is known.
            String version = (agentArgs == null || agentArgs.isBlank()) ? DEFAULT_VERSION : agentArgs.trim();
            System.out.println("[doomsday] agent starting, target version=" + version + " retransform=" + retransformLoaded);

            MojangMappings mappings = MojangMappings.forVersion(version);
            Transformer transformer = new Transformer(mappings);
            inst.addTransformer(transformer, true);

            if (retransformLoaded) {
                retransformAlreadyLoaded(inst, mappings);
            }

            // Mc.init() needs Minecraft.getInstance() to return non-null, i.e. the game must have
            // finished bootstrapping. Static premain attaches before main() runs, so defer this.
            Thread initThread = new Thread(() -> waitAndInitFacade(inst, mappings, version), "doomsday-init");
            initThread.setDaemon(true);
            initThread.start();
        } catch (Throwable t) {
            System.err.println("[doomsday] agent start() crashed:");
            t.printStackTrace();
        }
    }

    /**
     * Fabric's KnotClassDelegate.loadClass only delegates an unresolved name to the parent
     * (system) loader if the resource's codesource is in its own validParentCodeSources/
     * codeSources bookkeeping — appendToSystemClassLoaderSearch alone never gets added there
     * (confirmed by disassembling KnotClassDelegate off the live fabric-loader jar), hence
     * "hasn't been exposed to the game ... fabric.classPathGroups". KnotClassDelegate exposes
     * a public addCodeSource(Path) specifically for this: it registers the jar's URL on Knot's
     * own internal URLClassLoader AND adds the path to codeSources, so Knot resolves
     * doomsday.* classes natively — no parent delegation, no group check, ever — same as any
     * other mod jar it loaded at launch. getDelegate() on KnotClassLoader is package-private,
     * reached here via reflection since we're not compiled against fabric-loader.
     */
    private static void exposeAgentJarToGameLoader(ClassLoader gameLoader) throws Exception {
        Path jar = Path.of(DoomsdayAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Method getDelegate = gameLoader.getClass().getDeclaredMethod("getDelegate");
        getDelegate.setAccessible(true);
        Object delegate = getDelegate.invoke(gameLoader);
        Method addCodeSource = delegate.getClass().getMethod("addCodeSource", Path.class);
        addCodeSource.setAccessible(true);
        addCodeSource.invoke(delegate, jar);
        System.out.println("[doomsday] registered " + jar + " as a Knot code source — doomsday.* now resolves "
                + "natively inside the game's own class loader");
    }

    /**
     * Must go through the game's own loader (reflectively — no compile-time doomsday.mc.Mc
     * reference here) rather than a direct Mc.init(...) call, otherwise this 'app'-loaded agent
     * thread would define its own separate copy of Mc/ModuleManager/etc. under the system class
     * loader, split from the copy Knot defines when Minecraft#tick's woven call reaches
     * HookMethods -> ModuleManager -> ClickGui. Two copies means two unsynchronized singletons:
     * modules registered on one ModuleManager.INSTANCE never tick because onTick() fires on the
     * other. Routing init through the same loader keeps exactly one of everything.
     */
    private static void initMcFacadeViaGameLoader(ClassLoader gameLoader, String version) throws Exception {
        Class<?> mcFacade = Class.forName("doomsday.mc.Mc", true, gameLoader);
        Method init = mcFacade.getMethod("init", String.class, ClassLoader.class);
        init.invoke(null, version, gameLoader);
    }

    private static void retransformAlreadyLoaded(Instrumentation inst, MojangMappings mappings) {
        String mcObf = mappings.obfClass("net.minecraft.client.Minecraft");
        String guiObf = mappings.obfClass("net.minecraft.client.gui.Gui");
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.equals("net.minecraft.client.Minecraft") || n.equals(mcObf)
                    || n.equals("net.minecraft.client.gui.Gui") || n.equals(guiObf)) {
                targets.add(c);
            }
        }
        if (targets.isEmpty()) {
            System.err.println("[doomsday] no loaded classes matched for retransform yet — "
                    + "if this agent was attached before the game window opened, the tick/render hooks "
                    + "will still apply once those classes load for the first time.");
            return;
        }
        // Must happen before retransformClasses below, not just before waitAndInitFacade's async
        // loop: the render thread can call the just-woven tick()/render() within the same instant
        // retransformClasses returns, and that call needs doomsday/agent/HookMethods resolvable
        // on the spot. Racing this against the daemon init thread is how the first version of
        // this fix still crashed intermittently.
        try {
            exposeAgentJarToGameLoader(targets.get(0).getClassLoader());
        } catch (Throwable t) {
            System.err.println("[doomsday] failed to expose agent jar to the game's class loader before "
                    + "retransform — the woven tick/render hooks will NoClassDefFoundError on first fire:");
            t.printStackTrace();
        }
        for (Class<?> c : targets) {
            if (!inst.isModifiableClass(c)) continue;
            try {
                inst.retransformClasses(c);
                System.out.println("[doomsday] retransformed already-loaded class " + c.getName());
            } catch (Throwable t) {
                System.err.println("[doomsday] retransform failed for " + c.getName() + ": " + t);
            }
        }
    }

    /**
     * Finds Minecraft's own classloader via Instrumentation (which sees every loader in the
     * JVM, unlike Class.forName from the agent's own loader) — necessary because a Fabric/Forge
     * launch loads game classes on a dedicated launcher classloader, not the system/app one the
     * agent jar was appended to.
     */
    private static void waitAndInitFacade(Instrumentation inst, MojangMappings mappings, String version) {
        String obf = mappings.obfClass("net.minecraft.client.Minecraft");
        boolean loggedFirstFailure = false;
        for (int i = 0; i < 600; i++) { // up to ~60s for the client to bootstrap
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if (n.equals("net.minecraft.client.Minecraft") || (obf != null && n.equals(obf))) {
                    try {
                        ClassLoader gameLoader = c.getClassLoader();
                        exposeAgentJarToGameLoader(gameLoader);
                        initMcFacadeViaGameLoader(gameLoader, version);
                        System.out.println("[doomsday] Mc facade ready (loader=" + gameLoader + ")");
                        return;
                    } catch (Throwable t) {
                        // getInstance() likely still returned null — game constructed the class but hasn't
                        // finished Minecraft's own constructor yet; keep polling, but log the first failure's
                        // cause once so a persistent mapping/reflection mismatch doesn't disappear into 600
                        // identical silent retries.
                        if (!loggedFirstFailure) {
                            loggedFirstFailure = true;
                            System.err.println("[doomsday] Mc.init() attempt " + i + " failed (will keep retrying), cause:");
                            t.printStackTrace();
                        }
                    }
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
        System.err.println("[doomsday] gave up waiting for Minecraft to finish bootstrapping — "
                + "call doomsday.mc.Mc.init(version, loader) manually once it has (e.g. from a module's first onTick)");
    }
}
