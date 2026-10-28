package io.github.eegn.jiro.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The file protocol in front of the rewrite service, in the same spirit as {@code status.json}:
 * a directory a shell can write to, answered by a file a shell can read.
 *
 * <pre>
 *   target/jiro/rewrite/requests/&lt;id&gt;.yml     written by the client (temp file + rename)
 *   target/jiro/rewrite/processing/&lt;id&gt;.yml   claimed by jiro with an atomic move
 *   target/jiro/rewrite/responses/&lt;id&gt;.json   written by jiro (temp file + atomic move)
 * </pre>
 *
 * <p>A request is an OpenRewrite declarative recipe file. The recipe to run is named in a comment
 * line {@code # jiro-recipe: <name>} anywhere in the file; without one, the file must declare
 * exactly one recipe. The response is {@code {id, ok, recipe, changedFiles, deletedFiles,
 * warnings, error?, millis, ...}}, with paths relative to the module directory.
 *
 * <p>The claim is what makes a client-side timeout safe. A client that gives up deletes its own
 * request file: if the delete succeeds, jiro never saw the request and nothing will be written, so
 * falling back to another tool cannot apply the recipe twice; if the file is already gone, jiro has
 * claimed it and the response will still arrive.
 *
 * <p>The service is created on the first request, or by {@link #prewarm()}, never before: a session
 * that never rewrites anything never resolves, loads or parses anything for it.
 */
final class RewriteEndpoint implements AutoCloseable {

    private static final Pattern RECIPE_HEADER =
            Pattern.compile("^#\\s*jiro-recipe:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    private static final long POLL_MILLIS = 40;

    private final Path requests;
    private final Path processing;
    private final Path responses;
    private final ServiceFactory factory;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final Thread worker;
    private volatile boolean prewarm;
    private volatile boolean closed;
    private Function<Map<String, Object>, Map<String, Object>> service;
    private String loadFailure;

    /** Builds the service. Called at most once, on the worker thread. */
    interface ServiceFactory {
        Function<Map<String, Object>, Map<String, Object>> create() throws Exception;
    }

    RewriteEndpoint(Path stateDirectory, ServiceFactory factory, Consumer<String> info, Consumer<String> warn)
            throws IOException {
        Path root = stateDirectory.resolve("rewrite");
        this.requests = root.resolve("requests");
        this.processing = root.resolve("processing");
        this.responses = root.resolve("responses");
        this.factory = factory;
        this.info = info;
        this.warn = warn;
        // Anything left from a previous session belongs to a client that has long since timed out.
        for (Path directory : List.of(requests, processing, responses)) {
            Files.createDirectories(directory);
            clear(directory);
        }
        this.worker = new Thread(this::loop, "jiro-rewrite");
        this.worker.setDaemon(true);
    }

    Path requestsDirectory() {
        return requests;
    }

    Path responsesDirectory() {
        return responses;
    }

    void start() {
        worker.start();
    }

    /** Loads the service and parses the project in the background, ahead of the first request. */
    void prewarm() {
        prewarm = true;
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }

    private void loop() {
        while (!closed) {
            try {
                if (prewarm) {
                    prewarm = false;
                    warm();
                }
                for (Path request : pending()) {
                    handle(request);
                }
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException stop) {
                return;
            } catch (RuntimeException | IOException | LinkageError failure) {
                warn.accept("[jiro] rewrite endpoint: " + failure);
            }
        }
    }

    private void warm() {
        Function<Map<String, Object>, Map<String, Object>> loaded = service();
        if (loaded == null) {
            return;
        }
        Map<String, Object> result = loaded.apply(Map.of("op", "warm"));
        info.accept("[jiro] rewrite: pre-warmed " + result.get("totalFiles") + " files in "
                + result.get("millis") + "ms");
    }

    private List<Path> pending() throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(requests, "*.yml")) {
            for (Path file : files) {
                found.add(file);
            }
        }
        found.sort(null);
        return found;
    }

    private void handle(Path request) throws IOException {
        String file = request.getFileName().toString();
        String id = file.substring(0, file.length() - ".yml".length());
        Path claimed = processing.resolve(file);
        try {
            Files.move(request, claimed, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException withdrawn) {
            return; // the client gave up and took it back
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        try {
            String yaml = Files.readString(claimed, StandardCharsets.UTF_8);
            Function<Map<String, Object>, Map<String, Object>> loaded = service();
            if (loaded == null) {
                response.put("ok", false);
                response.put("error", "the rewrite service could not be loaded: " + loadFailure);
            } else {
                Map<String, Object> call = new HashMap<>();
                call.put("op", "run");
                call.put("recipeYaml", yaml);
                Matcher header = RECIPE_HEADER.matcher(yaml);
                call.put("recipeName", header.find() ? header.group(1) : null);
                response.putAll(loaded.apply(call));
            }
        } catch (RuntimeException | LinkageError failure) {
            response.put("ok", false);
            response.put("error", failure.toString());
        }
        publish(id, response);
        Files.deleteIfExists(claimed);

        Object changed = response.get("changedFiles");
        int count = changed instanceof List<?> list ? list.size() : 0;
        if (Boolean.TRUE.equals(response.get("ok"))) {
            info.accept("[jiro] rewrite " + id + ": " + count + " file(s) changed in "
                    + response.get("millis") + "ms (" + response.get("reparsedFiles") + " reparsed)");
        } else {
            warn.accept("[jiro] rewrite " + id + " failed: " + response.get("error"));
        }
    }

    private Function<Map<String, Object>, Map<String, Object>> service() {
        if (service == null && loadFailure == null) {
            long startedAt = System.nanoTime();
            try {
                service = factory.create();
                info.accept("[jiro] rewrite: service loaded in "
                        + (System.nanoTime() - startedAt) / 1_000_000 + "ms");
            } catch (Exception | LinkageError failure) {
                loadFailure = failure.toString();
                warn.accept("[jiro] rewrite: could not load the service: " + loadFailure);
            }
        }
        return service;
    }

    private void publish(String id, Map<String, Object> response) throws IOException {
        Path target = responses.resolve(id + ".json");
        Path temporary = responses.resolve(id + ".json.tmp");
        Files.writeString(temporary, Json.write(response) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void clear(Path directory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** Just enough JSON for maps of strings, numbers, booleans and lists of those. */
    static final class Json {
        private Json() {
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            append(out, value);
            return out.toString();
        }

        private static void append(StringBuilder out, Object value) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof Boolean || value instanceof Number) {
                out.append(value);
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            } else if (value instanceof Iterable<?> items) {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, item);
                }
                out.append(']');
            } else {
                string(out, value.toString());
            }
        }

        private static void string(StringBuilder out, String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
