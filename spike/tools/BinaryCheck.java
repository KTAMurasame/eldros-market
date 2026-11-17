// THROWAWAY spike tool. Run with JDK 25: java BinaryCheck.java <plugin.jar> <classpath-file>
// For every field/method/constructor reference from the jar into server API packages, checks that a member with
// the exact same name and descriptor exists in the target API classpath. Source compatibility is not enough:
// a changed return type still compiles from source but fails at runtime with NoSuchMethodError.
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

public class BinaryCheck {

    static final String[] API_PACKAGES = {"org/bukkit/", "io/papermc/", "com/destroystokyo/", "net/kyori/"};

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        List<URL> urls = new ArrayList<>();
        for (String p : Files.readString(Path.of(args[1])).trim().split(java.io.File.pathSeparator)) {
            urls.add(Path.of(p).toUri().toURL());
        }
        ClassLoader api = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());

        Set<String> refs = new TreeSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (var e : java.util.Collections.list(jf.entries())) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                var model = ClassFile.of().parse(jf.getInputStream(e).readAllBytes());
                for (var entry : model.constantPool()) {
                    if (entry instanceof MemberRefEntry m) {
                        String owner = m.owner().asInternalName();
                        for (String pkg : API_PACKAGES) {
                            if (owner.startsWith(pkg)) {
                                String kind = entry instanceof FieldRefEntry ? "F" : "M";
                                refs.add(kind + " " + owner + "." + m.name().stringValue() + " " + m.type().stringValue());
                            }
                        }
                    }
                }
            }
        }

        int missing = 0;
        for (String ref : refs) {
            String[] p = ref.split(" ");
            String owner = p[1].substring(0, p[1].lastIndexOf('.'));
            String name = p[1].substring(p[1].lastIndexOf('.') + 1);
            String desc = p[2];
            String result;
            try {
                Class<?> c = Class.forName(owner.replace('/', '.'), false, api);
                result = p[0].equals("F") ? (hasField(c, name, desc) ? "OK" : "MISSING") : (hasMethod(c, name, desc) ? "OK" : "MISSING");
            } catch (Throwable t) {
                result = "MISSING-CLASS " + t;
            }
            if (!result.equals("OK")) {
                missing++;
            }
            System.out.println(result + "  " + ref);
        }
        System.out.println("SUMMARY refs=" + refs.size() + " missing=" + missing);
    }

    static boolean hasMethod(Class<?> start, String name, String desc) {
        for (Class<?> c : hierarchy(start)) {
            if (name.equals("<init>")) {
                if (c != start) {
                    continue;
                }
                for (var k : c.getDeclaredConstructors()) {
                    if (MethodType.methodType(void.class, k.getParameterTypes()).toMethodDescriptorString().equals(desc)) {
                        return true;
                    }
                }
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasField(Class<?> start, String name, String desc) {
        for (Class<?> c : hierarchy(start)) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && f.getType().descriptorString().equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<Class<?>> hierarchy(Class<?> start) {
        List<Class<?>> out = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            Class<?> c = q.poll();
            if (!seen.add(c)) {
                continue;
            }
            out.add(c);
            if (c.getSuperclass() != null) {
                q.add(c.getSuperclass());
            }
            q.addAll(List.of(c.getInterfaces()));
        }
        return out;
    }
}
