package io.github.eegn.jiro.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which classes carry probes. Instrumenting everything would be correct but slow, and
 * would record edges through the JDK and the test framework that can never be the reason a test
 * needs re-running.
 */
public final class InstrumentationFilter {

    /**
     * Never instrumented, whatever the includes say. jiro's own runtime is on this list because
     * a probe inside the recorder would recurse; the test frameworks are on it because a change
     * to them is a dependency change, which invalidates the whole index anyway.
     */
    private static final String[] ALWAYS_EXCLUDED = {
            "java/", "jdk/", "sun/", "com/sun/", "javax/", "javafx/",
            "io/github/eegn/jiro/",
            "org/objectweb/asm/",
            "org/junit/", "junit/", "org/opentest4j/", "org/apiguardian/",
            "org/mockito/", "net/bytebuddy/", "org/objenesis/",
    };

    /**
     * Infixes that mark a class as generated at runtime rather than compiled from source.
     *
     * <p>Spring CGLIB proxies, Mockito mocks, Hibernate proxies, ByteBuddy auxiliaries, JDK dynamic
     * proxies and lambda classes all reach the transformer like anything else. Instrumenting them
     * costs time and fills the coverage index with keys no fingerprint can ever match — and Mockito
     * names its mocks randomly, so those entries differ on every run. A doubled dollar sign is the
     * reliable marker: the compiler only ever emits a single one for nested classes.
     */
    private static final String[] GENERATED_MARKERS = {
            "$$", "$MockitoMock$", "$HibernateProxy$", "$Proxy", "$ByteBuddy$",
    };

    private final List<String> includedPrefixes;

    /**
     * @param packagePrefixes dot-separated package prefixes to instrument; empty means every
     *                        class that is not explicitly excluded
     */
    public InstrumentationFilter(List<String> packagePrefixes) {
        List<String> internal = new ArrayList<>(packagePrefixes.size());
        for (String prefix : packagePrefixes) {
            String trimmed = prefix.trim();
            if (!trimmed.isEmpty()) {
                internal.add(trimmed.replace('.', '/'));
            }
        }
        this.includedPrefixes = List.copyOf(internal);
    }

    /**
     * @param internalName class name in JVM internal form, e.g. {@code com/example/OrderService}
     */
    public boolean shouldInstrument(String internalName) {
        if (internalName == null || internalName.endsWith("module-info")) {
            return false;
        }
        for (String excluded : ALWAYS_EXCLUDED) {
            if (internalName.startsWith(excluded)) {
                return false;
            }
        }
        for (String generated : GENERATED_MARKERS) {
            if (internalName.contains(generated)) {
                return false;
            }
        }
        if (includedPrefixes.isEmpty()) {
            return true;
        }
        for (String included : includedPrefixes) {
            if (internalName.startsWith(included)) {
                return true;
            }
        }
        return false;
    }
}
