import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

public class IsolatedBundles {
    private static final String LOADER_KEY = "upm.fixture.bundleLoader";
    private static Collection<?> load(Object instance, Method method, String path) throws Exception {
        return (Collection<?>) method.invoke(instance, path);
    }

    private static void verify(Collection<?> patches, ClassLoader engine, String sdk) throws Exception {
        if (patches.size() != 2) throw new AssertionError("Expected field and getter patches: " + patches.size());
        Set<String> names = new HashSet<>();
        for (Object patch : patches) {
            if (!engine.loadClass(sdk).isInstance(patch)) throw new AssertionError("Wrong SDK identity");
            names.add((String) patch.getClass().getMethod("getName").invoke(patch));
            if (sdk.startsWith("app.morphe")) {
                Method getBundle = Arrays.stream(patch.getClass().getMethods())
                    .filter(m -> m.getName().startsWith("getBundle$")).findFirst().orElseThrow();
                Object bundle = getBundle.invoke(patch);
                if (bundle == null) throw new AssertionError("Lost bundle provenance");
                if (!"2.0.0".equals(bundle.getClass().getMethod("getPatcherVersion").invoke(bundle)))
                    throw new AssertionError("Lost bundle patcher version");
                for (Object dependency : (Set<?>) patch.getClass().getMethod("getDependencies").invoke(patch)) {
                    if (!bundle.equals(getBundle.invoke(dependency))) throw new AssertionError("Lost dependency provenance");
                }
            }
        }
        if (!names.equals(Set.of("Field fixture", "Getter fixture"))) throw new AssertionError(names);
        ClassLoader bundleLoader = (ClassLoader) System.getProperties().get(LOADER_KEY);
        if (bundleLoader == null || bundleLoader.getParent() != engine) throw new AssertionError("Wrong bundle parent");
        Object payload = bundleLoader.loadClass("fixture.Fixture").getMethod("readLate").invoke(null);
        if (!"late resource".equals(payload)) throw new AssertionError("Bundle resources closed too early");
    }

    public static void main(String[] args) throws Exception {
        try {
            Class.forName(args[3]);
            throw new AssertionError("SDK leaked onto the system classpath");
        } catch (ClassNotFoundException expected) {}
        Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
        try (URLClassLoader engine = new URLClassLoader(
                new URL[]{new File(args[0]).toURI().toURL(), new File(args[1]).toURI().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = engine.loadClass(args[2]);
            Object instance = type.getField("INSTANCE").get(null);
            Method load = type.getMethod("loadBundle", String.class);
            boolean morphe = args[3].startsWith("app.morphe");
            if (morphe) type.getMethod("preload", String.class).invoke(instance, args[4]);
            Collection<?> first = load(instance, load, args[4]);
            verify(first, engine, args[3]);
            Collection<?> second = load(instance, load, args[4]);
            verify(second, engine, args[3]);
            for (Object patch : second) if (first.contains(patch)) throw new AssertionError("Patch instance reused");

            try {
                load(instance, load, args[5]);
                throw new AssertionError("Malformed bundle accepted");
            } catch (InvocationTargetException expected) {
                Throwable cause = expected.getCause();
                while (cause.getCause() != null) cause = cause.getCause();
                if (!(cause instanceof ClassFormatError)) throw expected;
            }
            if (morphe) {
                System.setProperty("upm.fixture.failOnce", "true");
                type.getMethod("preload", String.class).invoke(instance, args[4]);
                verify(load(instance, load, args[4]), engine, args[3]);
                System.setProperty("upm.fixture.strict", "true");
                try {
                    load(instance, load, args[4]);
                    throw new AssertionError("Morphe strict universal-patch validation was bypassed");
                } catch (InvocationTargetException expected) {
                    Throwable cause = expected.getCause();
                    while (cause != null && (cause.getMessage() == null ||
                           !cause.getMessage().contains("Universal patches must be declared with default false")))
                        cause = cause.getCause();
                    if (cause == null) throw expected;
                } finally {
                    System.clearProperty("upm.fixture.strict");
                }
            }
            verify(load(instance, load, args[4]), engine, args[3]);
            System.out.println("PASS: isolated " + (morphe ? "Morphe" : "ReVanced v22") +
                ", fields/getters, fresh instances, lazy resources, errors" +
                (morphe ? ", bundle provenance, strict defaults, preload recovery" : ""));
        } finally {
            System.getProperties().remove(LOADER_KEY);
            System.clearProperty("upm.fixture.failOnce");
            System.clearProperty("upm.fixture.strict");
        }
    }
}
