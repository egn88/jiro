package io.github.eegn.jiro.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Entry point of the jiro JVM agent.
 *
 * <p>Arguments are comma-separated {@code key=value} pairs:
 * <ul>
 *   <li>{@code runtime} &mdash; path to {@code jiro-runtime.jar}; required</li>
 *   <li>{@code includes} &mdash; {@code +}-separated package prefixes to instrument;
 *       defaults to everything not on the built-in exclusion list</li>
 * </ul>
 *
 * <p>Example: {@code -javaagent:jiro-agent.jar=runtime=/x/jiro-runtime.jar,includes=com.acme+org.acme}
 */
public final class JiroAgent {

    private JiroAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        install(arguments, instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        install(arguments, instrumentation);
    }

    private static void install(String arguments, Instrumentation instrumentation) throws Exception {
        Arguments parsed = Arguments.parse(arguments);

        // The recorder has to be reachable from application classes, which the throwaway
        // classloader defines without any view of the agent's own path. Bootstrap is the only
        // place every loader delegates to.
        String runtimeJar = parsed.value("runtime");
        if (runtimeJar == null) {
            throw new IllegalArgumentException("[jiro] agent requires runtime=<path to jiro-runtime.jar>");
        }
        File jar = new File(runtimeJar);
        if (!jar.isFile()) {
            throw new IllegalArgumentException("[jiro] runtime jar not found: " + jar.getAbsolutePath());
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar));

        List<String> includes = splitIncludes(parsed.value("includes"));
        instrumentation.addTransformer(new CoverageTransformer(new InstrumentationFilter(includes)), true);
    }

    private static List<String> splitIncludes(String raw) {
        List<String> prefixes = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            // '+' rather than ',' because ',' already separates the agent's own arguments.
            for (String prefix : raw.split("\\+")) {
                if (!prefix.isBlank()) {
                    prefixes.add(prefix);
                }
            }
        }
        return prefixes;
    }

    private static final class Arguments {
        private final List<String[]> pairs = new ArrayList<>();

        static Arguments parse(String raw) {
            Arguments arguments = new Arguments();
            if (raw == null || raw.isBlank()) {
                return arguments;
            }
            for (String entry : raw.split(",")) {
                int separator = entry.indexOf('=');
                if (separator > 0) {
                    arguments.pairs.add(new String[]{
                            entry.substring(0, separator).trim(),
                            entry.substring(separator + 1).trim()
                    });
                }
            }
            return arguments;
        }

        String value(String key) {
            for (String[] pair : pairs) {
                if (pair[0].equals(key)) {
                    return pair[1];
                }
            }
            return null;
        }
    }
}
