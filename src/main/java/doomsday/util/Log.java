// java, doomsday/util/Log.java, JDK 21, shared by injector (own JVM) and agent (target JVM)
package doomsday.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tees System.out/System.err into a timestamped file under ~/.doomsday/ and installs a
 * default uncaught-exception handler that also writes there. Point of this: once the agent
 * jar is loaded into Minecraft's JVM, its stdout/stderr either go nowhere (launcher swallows
 * the child console) or scroll off the launcher's tiny log pane — this is the only reliable
 * place to find out why a hook or transform blew up.
 */
public final class Log {

    public static final Path LOG_DIR = Paths.get(System.getProperty("user.home"), ".doomsday");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile PrintStream file;

    private Log() {}

    public static Path redirectToFile(String fileName) {
        try {
            Files.createDirectories(LOG_DIR);
            Path logPath = LOG_DIR.resolve(fileName);
            file = new PrintStream(new FileOutputStream(logPath.toFile(), true), true);

            System.setOut(tee(System.out, file));
            System.setErr(tee(System.err, file));

            line("==== session start, pid=" + ProcessHandle.current().pid() + " ====");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> line("==== process exiting ====")));
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                line("UNCAUGHT on thread '" + t.getName() + "':");
                e.printStackTrace(file);
            });
            return logPath;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void line(String msg) {
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + msg;
        if (file != null) file.println(stamped);
    }

    private static PrintStream tee(PrintStream original, PrintStream secondary) {
        return new PrintStream(new OutputStream() {
            @Override public void write(int b) {
                original.write(b);
                secondary.write(b);
            }

            @Override public void write(byte[] b, int off, int len) {
                original.write(b, off, len);
                secondary.write(b, off, len);
            }

            @Override public void flush() {
                original.flush();
                secondary.flush();
            }
        }, true);
    }
}
