package io.github.eegn.jiro.core.resume;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides whether a persisted {@link ResumeState} may replace the startup baseline.
 *
 * <p>Errs toward the baseline. Anything the coverage index cannot answer for — another jiro build,
 * another JDK, another dependency jar, a changed resource, a deleted source, options that change
 * what is instrumented or discovered, an index that is not the one the state was written with —
 * is answered with a full run. What survives the check is exactly what an uninterrupted session
 * could have answered: sources whose content moved, which are recompiled and then diffed against
 * the persisted fingerprints, so their tests are selected in the first cycle the same way an edit
 * would select them.
 */
public final class ResumeCheck {

    private ResumeCheck() {
    }

    /** What the filesystem and the session configuration look like right now. */
    public record Current(Map<String, String> environment,
                          List<ClasspathEntry> classpath,
                          Map<String, String> resources,
                          Map<String, String> sources,
                          String indexDigest) {
    }

    /**
     * @param resume          whether the stored state may be used
     * @param reason          why not, or a summary of what resuming will recompile
     * @param changedSources  keys of sources whose content differs from what the stored class
     *                        files were compiled from, or that did not exist then; to be recompiled
     */
    public record Decision(boolean resume, String reason, Set<String> changedSources) {

        static Decision baseline(String reason) {
            return new Decision(false, reason, Set.of());
        }
    }

    public static Decision decide(ResumeState stored, Current current) {
        if (!stored.environment().equals(current.environment())) {
            Set<String> keys = new TreeSet<>(stored.environment().keySet());
            keys.addAll(current.environment().keySet());
            for (String key : keys) {
                String before = stored.environment().get(key);
                String now = current.environment().get(key);
                if (before == null ? now != null : !before.equals(now)) {
                    return Decision.baseline(key + " changed (was " + before + ", now " + now + ")");
                }
            }
        }

        if (!stored.indexDigest().equals(current.indexDigest())) {
            return Decision.baseline("coverage.index does not match the persisted state");
        }

        List<ClasspathEntry> before = stored.classpath();
        List<ClasspathEntry> now = current.classpath();
        if (before.size() != now.size()) {
            return Decision.baseline("test classpath changed (" + before.size() + " → "
                    + now.size() + " entries)");
        }
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).digest().equals(now.get(i).digest())) {
                return Decision.baseline("test classpath changed at " + fileName(now.get(i).path()));
            }
        }

        if (!stored.resources().equals(current.resources())) {
            Set<String> keys = new TreeSet<>(stored.resources().keySet());
            keys.addAll(current.resources().keySet());
            for (String key : keys) {
                if (!java.util.Objects.equals(stored.resources().get(key),
                        current.resources().get(key))) {
                    return Decision.baseline("resource " + key.substring(key.indexOf(':') + 1)
                            + " changed");
                }
            }
        }

        for (String source : new TreeSet<>(stored.sources().keySet())) {
            if (!current.sources().containsKey(source)) {
                // Its class files are still in the output directory and nothing will remove
                // them; the code on the classpath no longer matches the tree.
                return Decision.baseline("source " + source + " was deleted");
            }
        }

        Set<String> changed = new TreeSet<>();
        for (Map.Entry<String, String> source : new TreeMap<>(current.sources()).entrySet()) {
            if (!source.getValue().equals(stored.sources().get(source.getKey()))) {
                changed.add(source.getKey());
            }
        }
        return new Decision(true, changed.isEmpty()
                ? "no source changed while stopped"
                : changed.size() + " source(s) changed while stopped", changed);
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
