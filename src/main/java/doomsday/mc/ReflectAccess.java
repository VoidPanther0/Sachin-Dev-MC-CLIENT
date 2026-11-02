package doomsday.mc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves classes/fields/methods by their deobfuscated (Mojang official) name,
 * transparently falling back to the obfuscated name via MojangMappings when the
 * direct lookup fails (i.e. we're attached to a raw vanilla-launcher jar).
 *
 * Caches every resolution — reflection + mapping lookups only pay once per symbol.
 */
public final class ReflectAccess {

    private final MojangMappings mappings;
    private final ClassLoader targetLoader;
    private final ConcurrentMap<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Field> fieldCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Method> methodCache = new ConcurrentHashMap<>();

    public ReflectAccess(MojangMappings mappings, ClassLoader targetLoader) {
        this.mappings = mappings;
        this.targetLoader = targetLoader;
    }

    public Class<?> clazz(String deobfBinaryName) {
        return classCache.computeIfAbsent(deobfBinaryName, k -> {
            try {
                return Class.forName(k, false, targetLoader);
            } catch (ClassNotFoundException direct) {
                String obf = mappings.obfClass(k);
                if (obf == null) {
                    throw new IllegalStateException("cannot resolve class " + k + " (not found directly, no mapping entry)", direct);
                }
                try {
                    return Class.forName(obf, false, targetLoader);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("mapped " + k + " -> " + obf + " but obf class still not found", e);
                }
            }
        });
    }

    /** resolves a field by deobf name, trying direct name first, then the mapped obf name, searching the class hierarchy */
    public Field field(String deobfBinaryClass, String deobfFieldName) {
        String key = deobfBinaryClass + "#" + deobfFieldName;
        return fieldCache.computeIfAbsent(key, k -> {
            Class<?> cls = clazz(deobfBinaryClass);
            Field f = findField(cls, deobfFieldName);
            if (f == null) {
                String obf = mappings.obfMember(deobfBinaryClass, deobfFieldName);
                if (obf != null) f = findField(cls, obf);
            }
            if (f == null) {
                throw new IllegalStateException("cannot resolve field " + deobfBinaryClass + "#" + deobfFieldName
                        + " -- dump candidates: " + dumpFields(cls));
            }
            f.setAccessible(true);
            return f;
        });
    }

    /** finds the first declared field (walking up superclasses) whose type is exactly/assignable to the given type */
    public Field fieldByType(String deobfBinaryClass, Class<?> fieldType) {
        Class<?> cls = clazz(deobfBinaryClass);
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new IllegalStateException("no field of type " + fieldType.getName() + " found on " + deobfBinaryClass + " hierarchy");
    }

    /** resolves a method by deobf name, trying direct name first, then the mapped obf name, matching on declared parameter count */
    public Method method(String deobfBinaryClass, String deobfMethodName, int paramCount) {
        String key = deobfBinaryClass + "#" + deobfMethodName + "/" + paramCount;
        return methodCache.computeIfAbsent(key, k -> {
            Class<?> cls = clazz(deobfBinaryClass);
            Method m = findMethod(cls, deobfMethodName, paramCount);
            if (m == null) {
                String obf = mappings.obfMember(deobfBinaryClass, deobfMethodName);
                if (obf != null) m = findMethod(cls, obf, paramCount);
            }
            if (m == null) {
                throw new IllegalStateException("cannot resolve method " + deobfBinaryClass + "#" + deobfMethodName + "/" + paramCount
                        + " -- dump candidates: " + dumpMethods(cls, paramCount));
            }
            m.setAccessible(true);
            return m;
        });
    }

    /**
     * like method(), but also requires the parameter at paramIndex to have this exact simple
     * type name — needed for overload sets that share name + arity (e.g. GuiGraphicsExtractor's
     * several 5-arg text(Font, X, int, int, int) overloads for X in {String, Component,
     * FormattedCharSequence}), where matching by name+count alone picks whichever overload the
     * JVM happens to return first from getDeclaredMethods(), which is unspecified order.
     */
    public Method method(String deobfBinaryClass, String deobfMethodName, int paramCount, int paramIndex, String paramSimpleTypeName) {
        String key = deobfBinaryClass + "#" + deobfMethodName + "/" + paramCount + "@" + paramIndex + ":" + paramSimpleTypeName;
        return methodCache.computeIfAbsent(key, k -> {
            Class<?> cls = clazz(deobfBinaryClass);
            Method m = findMethod(cls, deobfMethodName, paramCount, paramIndex, paramSimpleTypeName);
            if (m == null) {
                String obf = mappings.obfMember(deobfBinaryClass, deobfMethodName);
                if (obf != null) m = findMethod(cls, obf, paramCount, paramIndex, paramSimpleTypeName);
            }
            if (m == null) {
                throw new IllegalStateException("cannot resolve method " + deobfBinaryClass + "#" + deobfMethodName + "/" + paramCount
                        + " with param[" + paramIndex + "]=" + paramSimpleTypeName + " -- dump candidates: " + dumpMethods(cls, paramCount));
            }
            m.setAccessible(true);
            return m;
        });
    }

    /** tries each candidate name in order (across naming convention changes between snapshots), first resolvable one wins */
    public Method methodAny(String deobfBinaryClass, int paramCount, String... candidates) {
        IllegalStateException last = null;
        for (String c : candidates) {
            try {
                return method(deobfBinaryClass, c, paramCount);
            } catch (IllegalStateException e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("no candidates given for " + deobfBinaryClass);
    }

    public Object get(Field f, Object instance) {
        try {
            return f.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public void set(Field f, Object instance, Object value) {
        try {
            f.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public Object invoke(Method m, Object instance, Object... args) {
        try {
            return m.invoke(instance, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("invoke " + m + " failed", e);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount, int paramIndex, String paramSimpleTypeName) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount
                        && m.getParameterTypes()[paramIndex].getSimpleName().equals(paramSimpleTypeName)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String dumpFields(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        for (Field f : cls.getDeclaredFields()) sb.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append(", ");
        return sb.toString();
    }

    private static String dumpMethods(Class<?> cls, int paramCount) {
        StringBuilder sb = new StringBuilder();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterCount() == paramCount) sb.append(m.getName()).append('(').append(paramCount).append("args), ");
        }
        return sb.toString();
    }
}
