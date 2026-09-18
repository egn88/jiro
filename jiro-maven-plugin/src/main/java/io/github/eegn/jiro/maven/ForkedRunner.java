package io.github.eegn.jiro.maven;

import io.github.eegn.jiro.runner.Protocol;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Owns the long-lived test JVM and speaks {@link Protocol} to it.
 *
 * <p>Forking rather than running tests inside Maven's own JVM is not just tidiness. The runner has
 * to be able to throw away and rebuild the classloader holding application classes on every cycle,
 * and it has to start under {@code -javaagent}. Neither is possible in a JVM that is already
 * running Maven with the project on its classpath.
 */
final class ForkedRunner implements AutoCloseable {

    private final Options options;
    private ServerSocket serverSocket;
    private Process process;
    private Socket connection;
    private BufferedReader in;
    private PrintWriter out;

    ForkedRunner(Options options) {
        this.options = options;
    }

    /** Fork configuration, assembled by the mojo from the project model. */
    record Options(Path javaExecutable,
                   Path agentJar,
                   Path runtimeJar,
                   Path runnerJar,
                   List<Path> applicationClasspath,
                   List<Path> testRoots,
                   List<String> instrumentedPackages,
                   List<String> jvmArguments,
                   String classNamePattern) {
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());

        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.add("-javaagent:" + options.agentJar()
                + "=runtime=" + options.runtimeJar()
                + ",includes=" + String.join("+", options.instrumentedPackages()));
        command.addAll(options.jvmArguments());
        command.add("-cp");
        command.add(options.runnerJar().toString());
        command.add("io.github.eegn.jiro.runner.RunnerMain");
        command.add("--port");
        command.add(String.valueOf(serverSocket.getLocalPort()));
        command.add("--classpath");
        command.add(join(options.applicationClasspath()));
        command.add("--testRoots");
        command.add(join(options.testRoots()));
        command.add("--classNamePattern");
        command.add(options.classNamePattern());

        ProcessBuilder builder = new ProcessBuilder(command);
        // Test output goes straight to the developer's terminal; the protocol has its own socket
        // precisely so that a stray println cannot corrupt it.
        builder.inheritIO();
        process = builder.start();

        serverSocket.setSoTimeout(60_000);
        connection = serverSocket.accept();
        in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(connection.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Unique ids of every test the platform can currently see. */
    List<String> discover() throws IOException {
        out.println(Protocol.DISCOVER);
        List<String> tests = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null && !line.equals(Protocol.DONE)) {
            if (line.startsWith(Protocol.TEST + " ")) {
                tests.add(line.substring(Protocol.TEST.length() + 1));
            }
        }
        return tests;
    }

    RunReport runAll() throws IOException {
        out.println(Protocol.RUNALL);
        return readReport();
    }

    RunReport run(Iterable<String> uniqueIds) throws IOException {
        List<String> ids = new ArrayList<>();
        uniqueIds.forEach(ids::add);
        out.println(Protocol.RUN + " " + ids.size());
        ids.forEach(out::println);
        out.flush();
        return readReport();
    }

    private RunReport readReport() throws IOException {
        Map<String, String> statuses = new HashMap<>();
        Map<String, Long> durations = new HashMap<>();
        Map<String, String> failures = new HashMap<>();
        Map<String, List<String>> coverage = new HashMap<>();
        List<String> errors = new ArrayList<>();

        String line;
        while ((line = in.readLine()) != null && !line.equals(Protocol.DONE)) {
            if (line.startsWith(Protocol.RESULT + " ")) {
                String[] parts = line.split(" ", 4);
                if (parts.length == 4) {
                    statuses.put(parts[3], parts[1]);
                    durations.put(parts[3], Long.parseLong(parts[2]));
                }
            } else if (line.startsWith(Protocol.COVERAGE + " ")) {
                String[] parts = line.split(" ", 3);
                String methods = parts.length == 3 ? parts[2] : "";
                coverage.put(parts[1], methods.isEmpty() ? List.of() : List.of(methods.split(",")));
            } else if (line.startsWith(Protocol.FAILURE + " ")) {
                String[] parts = line.split(" ", 3);
                if (parts[1].startsWith("<")) {
                    errors.add(parts.length == 3 ? parts[2] : parts[1]);
                } else {
                    failures.put(parts[1], parts.length == 3 ? parts[2] : "");
                }
            }
        }
        if (line == null) {
            errors.add("test JVM closed the connection");
        }

        List<TestResult> results = new ArrayList<>(statuses.size());
        for (Map.Entry<String, String> status : statuses.entrySet()) {
            String uniqueId = status.getKey();
            results.add(new TestResult(
                    uniqueId,
                    status.getValue(),
                    durations.getOrDefault(uniqueId, 0L),
                    failures.get(uniqueId),
                    coverage.getOrDefault(uniqueId, List.of())));
        }
        return new RunReport(results, errors);
    }

    record TestResult(String uniqueId, String status, long millis, String failure,
                      List<String> coveredMethods) {
        boolean passed() {
            return "SUCCESSFUL".equals(status);
        }
    }

    record RunReport(List<TestResult> results, List<String> errors) {
        long failures() {
            return results.stream().filter(result -> !result.passed()).count();
        }
    }

    private static String join(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    @Override
    public void close() {
        try {
            if (out != null) {
                out.println(Protocol.SHUTDOWN);
            }
            if (connection != null) {
                connection.close();
            }
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Shutting down; a failure to close cleanly changes nothing.
        }
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
