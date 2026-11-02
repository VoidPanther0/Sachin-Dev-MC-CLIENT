package doomsday.agent;

import doomsday.mc.MojangMappings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * ASM ClassFileTransformer. Matches on resolved method NAME (via MojangMappings)
 * rather than descriptor substrings, since a matched class's own parameter types
 * (e.g. GuiGraphics) are themselves obfuscated in a raw vanilla launch — only
 * the outer class/method names are worth resolving through the mapping table,
 * the injected call itself only ever needs INVOKESTATIC + ALOAD of a local slot.
 */
public final class Transformer implements ClassFileTransformer {

    private final String minecraftObf;   // slash-form binary name
    private final String tickObf;        // method name
    private final String guiObf;         // slash-form binary name
    private final String renderObf;      // method name

    public Transformer(MojangMappings mappings) {
        this.minecraftObf = resolveClass(mappings, "net.minecraft.client.Minecraft");
        this.tickObf = resolveMember(mappings, "net.minecraft.client.Minecraft", "tick");
        this.guiObf = resolveClass(mappings, "net.minecraft.client.gui.Gui");
        // 26.1.2 split HUD rendering into an extract/submit pipeline: Gui#render(GuiGraphics,
        // DeltaTracker) is gone, replaced by Gui#extractRenderState(GuiGraphicsExtractor,
        // DeltaTracker) — same 2-ref-param void shape, so the hook logic below is unchanged,
        // only the target name and the facade's drawing class (see Mc#fill/drawString) differ.
        this.renderObf = resolveMember(mappings, "net.minecraft.client.gui.Gui", "extractRenderState");
        System.out.println("[doomsday] transformer targets: " + minecraftObf + "#" + tickObf
                + ", " + guiObf + "#" + renderObf);
    }

    private static String resolveClass(MojangMappings m, String deobf) {
        String obf = m.obfClass(deobf);
        return (obf != null ? obf : deobf).replace('.', '/');
    }

    private static String resolveMember(MojangMappings m, String deobfClass, String deobfMember) {
        String obf = m.obfMember(deobfClass, deobfMember);
        return obf != null ? obf : deobfMember;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        boolean isMinecraft = className.equals(minecraftObf);
        boolean isGui = className.equals(guiObf);
        if (!isMinecraft && !isGui) return null;

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
            String targetMethod = isMinecraft ? tickObf : renderObf;
            HookingClassVisitor visitor = new HookingClassVisitor(writer, targetMethod, isMinecraft);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            // visitMethod running isn't proof the hook landed — the name might not exist at all
            // in this build (it didn't: Gui#render became Gui#extractRenderState in 26.1.2, and
            // this logged "patched" every time regardless, silently hooking nothing for weeks).
            if (visitor.hookApplied) {
                System.out.println("[doomsday] patched " + className + "#" + targetMethod);
            } else {
                System.err.println("[doomsday] " + className + " matched for patching but no method named '"
                        + targetMethod + "' with the expected shape was found — hook NOT installed, "
                        + "the game's method was probably renamed/restructured in this version");
            }
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[doomsday] failed to patch " + className + ": " + t);
            t.printStackTrace();
            return null;
        }
    }

    private static final class HookingClassVisitor extends ClassVisitor {
        private final String targetMethod;
        private final boolean tickHook; // true = Minecraft#tick entry hook, false = Gui#extractRenderState exit hook
        boolean hookApplied;

        HookingClassVisitor(ClassVisitor cv, String targetMethod, boolean tickHook) {
            super(Opcodes.ASM9, cv);
            this.targetMethod = targetMethod;
            this.tickHook = tickHook;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean nameMatches = name.equals(targetMethod);
            boolean staticFlag = (access & Opcodes.ACC_STATIC) != 0;
            if (!nameMatches || staticFlag) return mv; // both hooks target instance methods
            if (tickHook) {
                // tick()V exactly — avoid matching an overload that happens to share the name
                if (!descriptor.equals("()V")) return mv;
                hookApplied = true;
                return new TickEntryHook(mv);
            } else {
                // extractRenderState(<GuiGraphicsExtractor-ish>, <DeltaTracker-ish>)V — 2 reference params, void return
                if (!descriptor.endsWith(")V") || countRefParams(descriptor) != 2) return mv;
                hookApplied = true;
                return new RenderExitHook(mv);
            }
        }

        private static int countRefParams(String descriptor) {
            int count = 0;
            int i = 1; // skip '('
            while (descriptor.charAt(i) != ')') {
                char c = descriptor.charAt(i);
                if (c == 'L') {
                    count++;
                    i = descriptor.indexOf(';', i) + 1;
                } else if (c == '[') {
                    count++;
                    i++;
                    while (descriptor.charAt(i) == '[') i++;
                    if (descriptor.charAt(i) == 'L') i = descriptor.indexOf(';', i) + 1; else i++;
                } else {
                    count++;
                    i++;
                }
            }
            return count;
        }
    }

    private static final class TickEntryHook extends MethodVisitor {
        TickEntryHook(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "doomsday/agent/HookMethods", "onTick", "()V", false);
        }
    }

    private static final class RenderExitHook extends MethodVisitor {
        RenderExitHook(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                mv.visitVarInsn(Opcodes.ALOAD, 1); // first param: the GuiGraphicsExtractor instance
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "doomsday/agent/HookMethods", "onRenderScreen",
                        "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
    }
}
