package io.github.eegn.jiro.core.select;

import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The static half of selection.
 *
 * <p>Recorded coverage answers "which tests ran this code", which is the right question for a
 * changed method body. It is the wrong question for a changed signature, supertype or annotation:
 * that can change the meaning of code in classes the test never entered, and of code that did not
 * even exist when the coverage was recorded.
 *
 * <p>So for ABI changes jiro falls back to the classic firewall: invert the reference graph and
 * take the transitive closure of everything that mentions the changed class. Coarse, but it only
 * fires on the changes where precision is not available.
 */
public final class ClassFirewall {

    private final Map<String, Set<String>> dependentsOf;

    public ClassFirewall(Map<String, ClassFingerprint> fingerprints) {
        Map<String, Set<String>> inverted = new HashMap<>();
        for (ClassFingerprint fingerprint : fingerprints.values()) {
            for (String referenced : fingerprint.references()) {
                inverted.computeIfAbsent(referenced, key -> new HashSet<>())
                        .add(fingerprint.internalName());
            }
        }
        this.dependentsOf = inverted;
    }

    /**
     * @return {@code seeds} plus every class that reaches one of them through a chain of
     *         references
     */
    public Set<String> closureOf(Set<String> seeds) {
        Set<String> reached = new HashSet<>(seeds);
        Deque<String> pending = new ArrayDeque<>(seeds);
        while (!pending.isEmpty()) {
            for (String dependent : dependentsOf.getOrDefault(pending.poll(), Set.of())) {
                if (reached.add(dependent)) {
                    pending.add(dependent);
                }
            }
        }
        return reached;
    }
}
