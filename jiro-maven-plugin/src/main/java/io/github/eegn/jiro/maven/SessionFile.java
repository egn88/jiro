package io.github.eegn.jiro.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code target/jiro/session.json}: proof that a {@code jiro:dev} session is alive, for clients that
 * must not wait on a session that is not there.
 *
 * <p>{@code status.json} cannot answer that question — it survives the session that wrote it. This
 * file carries the Maven JVM's pid; a client treats the session as live only if the file exists
 * <em>and</em> that pid is running. The file is removed on a clean stop and by a shutdown hook on
 * Ctrl-C; after a {@code kill -9} it lingers, and the pid check is what catches it.
 */
final class SessionFile implements AutoCloseable {

    private final Path file;
    private final RewriteEndpoint rewrite;
    private final Thread cleanup;

    SessionFile(Path stateDirectory, RewriteEndpoint rewrite) {
        this.file = stateDirectory.resolve("session.json");
        this.rewrite = rewrite;
        this.cleanup = new Thread(this::delete, "jiro-session-cleanup");
    }

    void publish() throws IOException {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("pid", ProcessHandle.current().pid());
        session.put("startedAtMillis", System.currentTimeMillis());
        if (rewrite != null) {
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("requests", rewrite.requestsDirectory().toAbsolutePath().toString());
            endpoint.put("responses", rewrite.responsesDirectory().toAbsolutePath().toString());
            session.put("rewrite", endpoint);
        } else {
            session.put("rewrite", null);
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling("session.json.tmp");
        Files.writeString(temporary, RewriteEndpoint.Json.write(session) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Runtime.getRuntime().addShutdownHook(cleanup);
    }

    @Override
    public void close() {
        delete();
        try {
            Runtime.getRuntime().removeShutdownHook(cleanup);
        } catch (IllegalStateException shuttingDown) {
            // already running hooks; ours deletes the file anyway
        }
    }

    private void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort; the pid check covers a file left behind
        }
    }
}
