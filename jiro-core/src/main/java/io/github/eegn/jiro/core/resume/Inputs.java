package io.github.eegn.jiro.core.resume;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Digests of the inputs a {@link ResumeState} records, taken from the filesystem as it is now. */
public final class Inputs {

    private Inputs() {
    }

    /**
     * Digests every classpath element, re-using a previous digest when path, size and modification
     * time are all unchanged. Directories are always re-hashed: they are usually a sibling module's
     * output, which is exactly the kind of thing that changes while jiro is stopped.
     */
    public static List<ClasspathEntry> classpath(List<Path> elements, List<ClasspathEntry> previous)
            throws IOException {
        Map<String, ClasspathEntry> cache = new HashMap<>();
        for (ClasspathEntry entry : previous) {
            cache.put(entry.path(), entry);
        }
        List<ClasspathEntry> entries = new ArrayList<>(elements.size());
        for (Path element : elements) {
            String path = element.toAbsolutePath().normalize().toString();
            if (Files.isRegularFile(element)) {
                long size = Files.size(element);
                long modified = Files.getLastModifiedTime(element).toMillis();
                ClasspathEntry known = cache.get(path);
                if (known != null && known.size() == size && known.lastModified() == modified) {
                    entries.add(known);
                } else {
                    entries.add(new ClasspathEntry(path, size, modified, Digests.of(element)));
                }
            } else if (Files.isDirectory(element)) {
                entries.add(new ClasspathEntry(path, -1, -1, directoryDigest(element)));
            } else {
                entries.add(new ClasspathEntry(path, -1, -1, "absent"));
            }
        }
        return entries;
    }

    /** Digest of every {@code .java} file under the roots, keyed by path relative to {@code base}. */
    public static Map<String, String> sources(Path base, List<Path> roots) throws IOException {
        Map<String, String> digests = new HashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path file : walk(root)) {
                if (file.getFileName().toString().endsWith(".java")) {
                    digests.put(sourceKey(base, file), Digests.of(file));
                }
            }
        }
        return digests;
    }

    /** The key a source is stored under: its path relative to the base directory, with '/'. */
    public static String sourceKey(Path base, Path source) {
        Path absolute = source.toAbsolutePath().normalize();
        Path baseDir = base.toAbsolutePath().normalize();
        Path key = absolute.startsWith(baseDir) ? baseDir.relativize(absolute) : absolute;
        return key.toString().replace(java.io.File.separatorChar, '/');
    }

    /** Digest of a single source, or {@code null} if it no longer exists. */
    public static String sourceDigest(Path source) throws IOException {
        return Files.isRegularFile(source) ? Digests.of(source) : null;
    }

    /**
     * Digest of every file under the output directories that is not a class file: the resources
     * Maven copied there. jiro cannot say which tests a changed {@code application.yml} affects,
     * so the check treats any difference as reason for a baseline.
     */
    public static Map<String, String> resources(List<Path> outputRoots) throws IOException {
        Map<String, String> digests = new HashMap<>();
        for (int i = 0; i < outputRoots.size(); i++) {
            Path root = outputRoots.get(i);
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path file : walk(root)) {
                if (!file.getFileName().toString().endsWith(".class")) {
                    String relative = root.relativize(file).toString()
                            .replace(java.io.File.separatorChar, '/');
                    digests.put(i + ":" + relative, Digests.of(file));
                }
            }
        }
        return digests;
    }

    /** SHA-256 of a file's bytes; used for {@code coverage.index}. */
    public static String fileDigest(Path file) throws IOException {
        return Files.isRegularFile(file) ? Digests.of(file) : "absent";
    }

    private static String directoryDigest(Path directory) throws IOException {
        MessageDigest digest = Digests.sha256();
        List<Path> files = new ArrayList<>(walk(directory));
        files.sort(null);
        for (Path file : files) {
            digest.update(directory.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Digests.of(file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<Path> walk(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }
}
