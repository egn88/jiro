package io.github.eegn.jiro.runner;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Loads the application under test, and the JUnit platform it was built against, in preference to
 * anything the runner itself carries.
 *
 * <p>Child-first is not a preference here, it is a correctness requirement. jiro originally put its
 * own {@code junit-platform-launcher} on the fork's system classpath and let the normal parent-first
 * order apply. Against a project on JUnit Platform 1.10 that meant {@code junit-jupiter-engine}
 * 5.10 ran against platform classes from 1.11 — and the result was not an error. Jupiter registered
 * itself and then discovered <em>zero</em> tests, silently, while Cucumber and ArchUnit carried on
 * working. A tool that quietly runs none of your unit tests is worse than one that crashes.
 *
 * <p>So everything except the JDK and jiro's own probe runtime is resolved locally first. The probe
 * runtime is the one class graph that must be shared: instrumented application classes call into
 * the very same {@code CoverageRecorder} the runner reads back from, and two copies would record
 * into one and report from the other.
 */
final class ApplicationClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** Prefixes that must come from the parent, whatever the application ships. */
    private static final String[] PARENT_FIRST = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "io.github.eegn.jiro.",
    };

    ApplicationClassLoader(URL[] urls, ClassLoader parent) {
        super("jiro-application", urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = isParentFirst(name) ? super.loadClass(name, false) : loadLocallyFirst(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> loadLocallyFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException notOnApplicationPath) {
            return super.loadClass(name, false);
        }
    }

    /**
     * Resources are searched locally first for the same reason as classes: engine discovery goes
     * through {@code META-INF/services}, and the project's declaration of which engines exist has
     * to win over anything on the parent.
     */
    @Override
    public URL getResource(String name) {
        URL local = findResource(name);
        return local != null ? local : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> ordered = new ArrayList<>();
        Collections.list(findResources(name)).forEach(ordered::add);
        ClassLoader parent = getParent();
        if (parent != null) {
            for (URL fromParent : Collections.list(parent.getResources(name))) {
                if (!ordered.contains(fromParent)) {
                    ordered.add(fromParent);
                }
            }
        }
        return Collections.enumeration(ordered);
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
