package io.github.eegn.jiro.core.resume;

import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads and writes {@code resume.state}, the file that lets a restarted session skip its baseline.
 *
 * <p>Plain tab-separated text, one record per line, so it can be inspected with {@code grep}. The
 * first line carries the format version and the last line a SHA-256 of everything before it. A file
 * from another format version, a truncated file, or one that fails to parse is reported as
 * {@link Unusable}, which the caller answers with a full baseline: a state that cannot be read
 * completely proves nothing.
 *
 * <p>Writes go to a temporary file that is then moved over the old one, so a session killed
 * mid-write leaves either the previous state or the new one, never a mixture.
 */
public final class ResumeStore {

    /** Bump whenever the layout or the meaning of any field changes. */
    public static final int FORMAT_VERSION = 1;

    private static final String MAGIC = "jiro-resume-state";

    private final Path file;

    public ResumeStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Thrown when the stored state cannot be trusted; the message says why. */
    public static final class Unusable extends Exception {
        public Unusable(String reason) {
            super(reason);
        }
    }

    /** Removes the state, so the next start runs a baseline. Used when a cycle could not finish. */
    public void invalidate() throws IOException {
        Files.deleteIfExists(file);
    }

    public void save(ResumeState state) throws IOException {
        StringBuilder out = new StringBuilder(64 * 1024);
        line(out, MAGIC, Integer.toString(FORMAT_VERSION));
        new TreeMap<>(state.environment()).forEach((key, value) -> line(out, "env", key, value));
        for (ClasspathEntry entry : state.classpath()) {
            line(out, "cp", entry.digest(), Long.toString(entry.size()),
                    Long.toString(entry.lastModified()), entry.path());
        }
        new TreeMap<>(state.resources()).forEach((key, digest) -> line(out, "res", key, digest));
        new TreeMap<>(state.sources()).forEach((path, digest) -> line(out, "src", path, digest));
        line(out, "index", state.indexDigest());
        line(out, "cycle", Long.toString(state.lastCycleId()));
        new TreeSet<>(state.failingTests()).forEach(test -> line(out, "failing", test));
        for (ClassFingerprint fingerprint : new TreeMap<>(state.fingerprints()).values()) {
            line(out, "class", fingerprint.internalName(), fingerprint.abiHash());
            new TreeMap<>(fingerprint.methodHashes())
                    .forEach((method, digest) -> line(out, "method", method, digest));
        }
        line(out, "checksum", Digests.of(out.toString().getBytes(StandardCharsets.UTF_8)));

        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, out, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public ResumeState load() throws Unusable {
        if (!Files.isRegularFile(file)) {
            throw new Unusable("no persisted state");
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            throw new Unusable("state unreadable: " + unreadable.getMessage());
        }

        int checksumAt = text.lastIndexOf("checksum\t");
        if (checksumAt < 0 || (checksumAt > 0 && text.charAt(checksumAt - 1) != '\n')) {
            throw new Unusable("state truncated: no checksum");
        }
        String body = text.substring(0, checksumAt);
        String expected = text.substring(checksumAt + "checksum\t".length()).strip();
        if (!Digests.of(body.getBytes(StandardCharsets.UTF_8)).equals(expected)) {
            throw new Unusable("state corrupt: checksum mismatch");
        }

        Map<String, String> environment = new HashMap<>();
        List<ClasspathEntry> classpath = new ArrayList<>();
        Map<String, String> resources = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        Set<String> failing = new HashSet<>();
        Map<String, ClassFingerprint> fingerprints = new HashMap<>();
        String indexDigest = null;
        long cycle = -1;

        String className = null;
        String abi = null;
        Map<String, String> methods = new HashMap<>();
        boolean headerSeen = false;

        try {
            for (String raw : body.split("\n")) {
                if (raw.isEmpty()) {
                    continue;
                }
                String[] fields = raw.split("\t", -1);
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = unescape(fields[i]);
                }
                if (!headerSeen) {
                    if (!MAGIC.equals(fields[0])) {
                        throw new Unusable("not a jiro resume state");
                    }
                    if (!Integer.toString(FORMAT_VERSION).equals(fields[1])) {
                        throw new Unusable("state format " + fields[1] + ", this jiro reads "
                                + FORMAT_VERSION);
                    }
                    headerSeen = true;
                    continue;
                }
                switch (fields[0]) {
                    case "env" -> environment.put(fields[1], fields[2]);
                    case "cp" -> classpath.add(new ClasspathEntry(fields[4],
                            Long.parseLong(fields[2]), Long.parseLong(fields[3]), fields[1]));
                    case "res" -> resources.put(fields[1], fields[2]);
                    case "src" -> sources.put(fields[1], fields[2]);
                    case "index" -> indexDigest = fields[1];
                    case "cycle" -> cycle = Long.parseLong(fields[1]);
                    case "failing" -> failing.add(fields[1]);
                    case "class" -> {
                        if (className != null) {
                            fingerprints.put(className, new ClassFingerprint(
                                    className, abi, methods, Set.of()));
                        }
                        className = fields[1];
                        abi = fields[2];
                        methods = new HashMap<>();
                    }
                    case "method" -> {
                        if (className == null) {
                            throw new Unusable("state corrupt: method before any class");
                        }
                        methods.put(fields[1], fields[2]);
                    }
                    default -> throw new Unusable("state corrupt: unknown record " + fields[0]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException malformed) {
            throw new Unusable("state corrupt: " + malformed);
        }
        if (className != null) {
            fingerprints.put(className, new ClassFingerprint(className, abi, methods, Set.of()));
        }
        if (!headerSeen || indexDigest == null || cycle < 0) {
            throw new Unusable("state incomplete");
        }
        return new ResumeState(environment, classpath, resources, sources, indexDigest, failing,
                cycle, fingerprints);
    }

    private static void line(StringBuilder out, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append('\t');
            }
            out.append(escape(fields[i]));
        }
        out.append('\n');
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                plain.append(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                plain.append(c);
            }
        }
        return plain.toString();
    }
}
