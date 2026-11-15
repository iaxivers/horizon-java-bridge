package dev.horizonlab.bridge.fabric;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which of Knot's jars hold each package, so a class is looked up only in those. Knot's class
 * loader (KnotClassLoader's DynamicURLClassLoader, a URLClassLoader) looked every class up jar by
 * jar over the whole class path: ~330 jars with a big pack's nested ones, for every class the
 * mods load and every class Mixin reads to check types (UMP Java start profile: 16% of it reading
 * class bytes, most of that the scan). Only a jar with the package's directory can hold the
 * class, so asking those (in the class path's order) finds the same one. Directories and jars
 * that can't be read are asked for every package; anything that isn't a class takes the old way.
 * (UMP, 2026-11-15)
 */
public final class HorizonKnotIndex implements Opcodes {
    private HorizonKnotIndex() {}

    /** Returned by {@link #find} for a class no jar has: the loader answers null. */
    public static final URL NONE;

    static {
        URL none = null;
        try {
            none = new URL("file:/horizon-knot-index-none");
        } catch (Exception e) {
            // Unreachable: a constant URL.
        }
        NONE = none;
    }

    private record Jar(URL url, URLClassLoader loader) {}

    private static final class Index {
        final Map<String, List<Jar>> byPackage = new ConcurrentHashMap<>();
        /** Directories and unreadable jars: asked for every class, in their place. */
        final List<Object[]> order = new ArrayList<>();
        volatile boolean anyOpen;
        int count;
    }

    private static final Map<URLClassLoader, Index> INDEXES = new ConcurrentHashMap<>();
    private static final boolean ON = !"0".equals(System.getenv("HORIZON_KNOT_INDEX"));

    /**
     * Knot's DynamicURLClassLoader with findResource answered through the index, defined in place
     * of Fabric's before Knot makes its loader. Nothing happens if it is off or already loaded.
     */
    static void install() {
        if (!ON) return;
        String name = "net/fabricmc/loader/impl/launch/knot/KnotClassLoader$DynamicURLClassLoader";
        try (var in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            if (in == null) return;
            ClassReader reader = new ClassReader(in.readAllBytes());
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    return "java/lang/Object";
                }
            };
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                    if (!method.equals("addURL") || !desc.equals("(Ljava/net/URL;)V")) return mv;
                    return new MethodVisitor(ASM9, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == RETURN) {
                                super.visitVarInsn(ALOAD, 0);
                                super.visitVarInsn(ALOAD, 1);
                                super.visitMethodInsn(INVOKESTATIC, "dev/horizonlab/bridge/fabric/HorizonKnotIndex", "added",
                                        "(Ljava/net/URLClassLoader;Ljava/net/URL;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "findResource", "(Ljava/lang/String;)Ljava/net/URL;", null, null);
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKESTATIC, "dev/horizonlab/bridge/fabric/HorizonKnotIndex", "find",
                            "(Ljava/net/URLClassLoader;Ljava/lang/String;)Ljava/net/URL;", false);
                    mv.visitVarInsn(ASTORE, 2);
                    Label handled = new Label();
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitJumpInsn(IFNONNULL, handled);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKESPECIAL, "java/net/URLClassLoader", "findResource", "(Ljava/lang/String;)Ljava/net/URL;", false);
                    mv.visitInsn(ARETURN);
                    mv.visitLabel(handled);
                    Label found = new Label();
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitFieldInsn(GETSTATIC, "dev/horizonlab/bridge/fabric/HorizonKnotIndex", "NONE", "Ljava/net/URL;");
                    mv.visitJumpInsn(IF_ACMPNE, found);
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(ARETURN);
                    mv.visitLabel(found);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitInsn(ARETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    super.visitEnd();
                }
            }, 0);
            Class<?> host = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClassLoader", false, ClassLoader.getSystemClassLoader());
            MethodHandles.privateLookupIn(host, MethodHandles.lookup()).defineClass(writer.toByteArray());
        } catch (Throwable t) {
            // Fabric's own lookup stays: slower, the same.
            System.err.println("[horizon] Knot class index not installed: " + t);
        }
    }

    /** A URL Knot's loader took: its packages join the index (in the class path's order). */
    public static void added(URLClassLoader self, URL url) {
        Index index = INDEXES.get(self);
        if (index == null) return; // indexed whole on first use
        synchronized (index) {
            add(index, url);
            index.count++;
        }
    }

    /** The class `name`'s URL, {@link #NONE} if no jar has it, null to look it up as before. */
    public static URL find(URLClassLoader self, String name) {
        if (!name.endsWith(".class")) return null;
        Index index = INDEXES.get(self);
        if (index == null) {
            synchronized (HorizonKnotIndex.class) {
                index = INDEXES.get(self);
                if (index == null) {
                    index = new Index();
                    synchronized (index) {
                        for (URL url : self.getURLs()) {
                            add(index, url);
                            index.count++;
                        }
                    }
                    INDEXES.put(self, index);
                }
            }
        }
        int slash = name.lastIndexOf('/');
        String pkg = slash < 0 ? "" : name.substring(0, slash);
        List<Jar> jars = index.byPackage.get(pkg);
        if (index.anyOpen) {
            // Directories in between: the class path's whole order, asking jars only for their packages.
            Object[][] order;
            synchronized (index) {
                order = index.order.toArray(new Object[0][]);
            }
            for (Object[] entry : order) {
                if (entry[1] instanceof Set<?> packages && !packages.contains(pkg)) continue;
                URL found = ((URLClassLoader) entry[0]).findResource(name);
                if (found != null) return found;
            }
            return NONE;
        }
        if (jars == null) return NONE;
        for (Jar jar : jars) {
            URL found = jar.loader.findResource(name);
            if (found != null) return found;
        }
        return NONE;
    }

    private static void add(Index index, URL url) {
        URLClassLoader one = new URLClassLoader(new URL[] {url}, null);
        Set<String> packages = packages(url);
        if (packages == null) {
            index.anyOpen = true;
            index.order.add(new Object[] {one, null});
            return;
        }
        index.order.add(new Object[] {one, packages});
        Jar jar = new Jar(url, one);
        for (String pkg : packages) {
            index.byPackage.compute(pkg, (k, list) -> {
                List<Jar> next = list == null ? new ArrayList<>(1) : new ArrayList<>(list);
                next.add(jar);
                return List.copyOf(next);
            });
        }
    }

    /** A jar's class packages (versioned ones under their plain names); null for anything else. */
    private static Set<String> packages(URL url) {
        try {
            if (!"file".equals(url.getProtocol())) return null;
            File file = new File(url.toURI());
            if (!file.isFile()) return null;
            Set<String> packages = new HashSet<>();
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (!entry.endsWith(".class")) continue;
                    if (entry.startsWith("META-INF/versions/")) {
                        int next = entry.indexOf('/', "META-INF/versions/".length());
                        if (next < 0) continue;
                        entry = entry.substring(next + 1);
                    }
                    int slash = entry.lastIndexOf('/');
                    packages.add(slash < 0 ? "" : entry.substring(0, slash));
                }
            }
            return packages;
        } catch (Exception e) {
            return null;
        }
    }
}
