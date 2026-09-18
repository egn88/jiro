package io.github.eegn.jiro.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main class of the forked test JVM.
 *
 * <p>Started once and kept alive for the whole dev session, which is where the second half of the
 * speed comes from: JIT state, the class data sharing archive and any cached framework context all
 * survive between cycles, so the tenth run of a test costs a fraction of the first.
 *
 * <p>Application classes are loaded through an {@link ApplicationClassLoader} that is discarded and
 * rebuilt each cycle. That is what lets a recompiled class take effect without restarting, and it is
 * also why the application must not be on this JVM's own classpath — if it were, the system loader
 * would answer first and the new bytecode would never be seen.
 *
 * <p>This class references no JUnit type. Everything goes through {@link JUnitBridge} against the
 * project's own platform; see that class for why that is not optional.
 */
public final class RunnerMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        int port = Integer.parseInt(required(options, "port"));
        List<Path> classpath = splitPaths(required(options, "classpath"));
        List<Path> testRoots = splitPaths(required(options, "testRoots"));
        String classNamePattern = options.get("classNamePattern");

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            new RunnerMain(classpath, testRoots, classNamePattern).serve(in, out);
        }
    }

    private final List<Path> classpath;
    private final List<Path> testRoots;
    private final String classNamePattern;

    private RunnerMain(List<Path> classpath, List<Path> testRoots, String classNamePattern) {
        this.classpath = classpath;
        this.testRoots = testRoots;
        this.classNamePattern = classNamePattern;
    }

    private void serve(BufferedReader in, PrintWriter out) throws IOException {
        String command;
        while ((command = in.readLine()) != null) {
            if (command.equals(Protocol.SHUTDOWN)) {
                return;
            } else if (command.equals(Protocol.DISCOVER)) {
                discover(out);
            } else if (command.equals(Protocol.RUNALL)) {
                run(List.of(), true, out);
            } else if (command.startsWith(Protocol.RUN + " ")) {
                int count = Integer.parseInt(command.substring(Protocol.RUN.length() + 1).trim());
                List<String> uniqueIds = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    uniqueIds.add(in.readLine());
                }
                run(uniqueIds, false, out);
            }
        }
    }

    private void discover(PrintWriter out) {
        try (ApplicationClassLoader application = newApplicationLoader()) {
            ClassLoader previous = swapContextClassLoader(application);
            try {
                JUnitBridge junit = new JUnitBridge(application);
                Object launcher = junit.newLauncher();
                Object plan = junit.discover(launcher, requestFor(junit, List.of(), true));
                for (String uniqueId : junit.testUniqueIds(plan)) {
                    out.println(Protocol.TEST + " " + uniqueId);
                }
            } finally {
                swapContextClassLoader(previous);
            }
        } catch (Exception failure) {
            out.println(Protocol.FAILURE + " <discovery> " + Protocol.oneLine(describe(failure)));
        }
        out.println(Protocol.DONE);
    }

    private void run(List<String> uniqueIds, boolean everything, PrintWriter out) {
        long startedAt = System.nanoTime();
        try (ApplicationClassLoader application = newApplicationLoader()) {
            ClassLoader previous = swapContextClassLoader(application);
            List<CoverageBindingListener.TestOutcome> outcomes;
            try {
                JUnitBridge junit = new JUnitBridge(application);
                CoverageBindingListener listener = new CoverageBindingListener(junit);
                junit.execute(junit.newLauncher(),
                        requestFor(junit, uniqueIds, everything),
                        junit.newListenerProxy(application, listener));
                outcomes = listener.outcomes();
            } finally {
                swapContextClassLoader(previous);
            }

            if (io.github.eegn.jiro.runtime.CoverageRecorder.overlapDetected()) {
                // Attributing this run's probes would poison the index for every later cycle.
                out.println(Protocol.FAILURE + " " + Protocol.PARALLEL
                        + " two tests recorded coverage simultaneously;"
                        + " jiro cannot attribute coverage under parallel test execution");
                out.println(Protocol.DONE);
                return;
            }

            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            long perTest = outcomes.isEmpty() ? elapsedMillis : elapsedMillis / outcomes.size();
            for (CoverageBindingListener.TestOutcome outcome : outcomes) {
                out.println(Protocol.RESULT + " " + outcome.status() + " " + perTest + " "
                        + outcome.uniqueId());
                if (outcome.failure() != null) {
                    out.println(Protocol.FAILURE + " " + outcome.uniqueId() + " "
                            + Protocol.oneLine(outcome.failure()));
                }
                out.println(Protocol.COVERAGE + " " + outcome.uniqueId() + " "
                        + String.join(",", outcome.coveredMethods()));
            }
        } catch (Exception failure) {
            out.println(Protocol.FAILURE + " <run> " + Protocol.oneLine(describe(failure)));
        }
        out.println(Protocol.DONE);
    }

    private Object requestFor(JUnitBridge junit, List<String> uniqueIds, boolean everything)
            throws ReflectiveOperationException {
        if (!everything) {
            return junit.requestForUniqueIds(uniqueIds);
        }
        Set<Path> roots = new LinkedHashSet<>();
        for (Path root : testRoots) {
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        return junit.requestForRoots(roots, classNamePattern);
    }

    private ApplicationClassLoader newApplicationLoader() {
        List<URL> urls = new ArrayList<>(classpath.size());
        for (Path entry : classpath) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (IOException malformed) {
                throw new IllegalStateException("Bad classpath entry: " + entry, malformed);
            }
        }
        return new ApplicationClassLoader(urls.toArray(new URL[0]), RunnerMain.class.getClassLoader());
    }

    private static ClassLoader swapContextClassLoader(ClassLoader loader) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        // Engines are found through ServiceLoader on the context classloader, so this is what makes
        // the launcher see the project's own junit-jupiter-engine.
        current.setContextClassLoader(loader);
        return previous;
    }

    /** Reflective failures wrap the interesting exception one or two layers down. */
    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause instanceof java.lang.reflect.InvocationTargetException) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("[jiro] runner requires --" + key);
        }
        return value;
    }

    private static List<Path> splitPaths(String joined) {
        List<Path> paths = new ArrayList<>();
        for (String entry : joined.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                paths.add(Path.of(entry));
            }
        }
        return paths;
    }
}
