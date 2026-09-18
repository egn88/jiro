package io.github.eegn.jiro.core.select;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reverse map that test selection runs on: production method to the tests that entered it.
 *
 * <p>Rebuilt incrementally. Every time a test runs, whatever it covered replaces what it covered
 * before, so the index tracks the code rather than accumulating stale edges.
 */
public final class CoverageIndex {

    private final Map<String, Set<String>> testsByMethod = new HashMap<>();
    private final Map<String, Set<String>> testsByClass = new HashMap<>();
    private final Map<String, Set<String>> methodsByTest = new HashMap<>();

    public boolean isEmpty() {
        return methodsByTest.isEmpty();
    }

    public Set<String> knownTests() {
        return Set.copyOf(methodsByTest.keySet());
    }

    /** Replaces everything recorded for {@code testId} with this run's observations. */
    public void record(String testId, Collection<String> methodKeys) {
        forget(testId);
        Set<String> methods = new HashSet<>(methodKeys);
        methodsByTest.put(testId, methods);
        for (String methodKey : methods) {
            testsByMethod.computeIfAbsent(methodKey, key -> new HashSet<>()).add(testId);
            testsByClass.computeIfAbsent(ownerOf(methodKey), key -> new HashSet<>()).add(testId);
        }
    }

    /** Drops a test that no longer exists, so deleted tests stop being selected. */
    public void forget(String testId) {
        Set<String> previous = methodsByTest.remove(testId);
        if (previous == null) {
            return;
        }
        for (String methodKey : previous) {
            removeFrom(testsByMethod, methodKey, testId);
            removeFrom(testsByClass, ownerOf(methodKey), testId);
        }
    }

    public Set<String> testsCovering(String methodKey) {
        return testsByMethod.getOrDefault(methodKey, Set.of());
    }

    public Set<String> testsCoveringClass(String internalName) {
        return testsByClass.getOrDefault(internalName, Set.of());
    }

    public int coveredMethodCount() {
        return testsByMethod.size();
    }

    /**
     * Writes the index as one line per test: the test id, a tab, then its covered methods.
     * Plain text on purpose, so a developer wondering why a test was picked can grep for it.
     */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>(methodsByTest.size());
        for (Map.Entry<String, Set<String>> entry : methodsByTest.entrySet()) {
            lines.add(entry.getKey() + '\t' + String.join(",", entry.getValue()));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static CoverageIndex load(Path file) throws IOException {
        CoverageIndex index = new CoverageIndex();
        if (!Files.isRegularFile(file)) {
            return index;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('\t');
            if (separator <= 0) {
                continue;
            }
            String testId = line.substring(0, separator);
            String methods = line.substring(separator + 1);
            index.record(testId, methods.isEmpty() ? List.of() : List.of(methods.split(",")));
        }
        return index;
    }

    private static String ownerOf(String methodKey) {
        int separator = methodKey.indexOf('#');
        return separator < 0 ? methodKey : methodKey.substring(0, separator);
    }

    private static void removeFrom(Map<String, Set<String>> index, String key, String testId) {
        Set<String> tests = index.get(key);
        if (tests != null) {
            tests.remove(testId);
            if (tests.isEmpty()) {
                index.remove(key);
            }
        }
    }
}
