package io.github.eegn.jiro.core.analyze;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Holds the previous snapshot of the output directory and diffs a fresh scan against it. */
public final class FingerprintStore {

    private Map<String, ClassFingerprint> snapshot = new HashMap<>();

    public boolean isEmpty() {
        return snapshot.isEmpty();
    }

    public Map<String, ClassFingerprint> current() {
        return Map.copyOf(snapshot);
    }

    /**
     * Replaces the held snapshot with {@code fresh} and reports what moved. The first call after
     * startup returns every class as added, which the selector reads as "no baseline" and answers
     * with a full run.
     */
    public ChangeSet update(Map<String, ClassFingerprint> fresh) {
        Set<String> changedMethods = new HashSet<>();
        Set<String> abiChanged = new HashSet<>();
        Set<String> added = new HashSet<>();
        Set<String> removed = new HashSet<>();

        for (Map.Entry<String, ClassFingerprint> entry : fresh.entrySet()) {
            ClassFingerprint before = snapshot.get(entry.getKey());
            ClassFingerprint after = entry.getValue();
            if (before == null) {
                added.add(entry.getKey());
                changedMethods.addAll(after.methodHashes().keySet());
                continue;
            }
            if (!before.abiHash().equals(after.abiHash())) {
                abiChanged.add(entry.getKey());
            }
            for (Map.Entry<String, String> method : after.methodHashes().entrySet()) {
                String previousHash = before.methodHashes().get(method.getKey());
                if (!method.getValue().equals(previousHash)) {
                    changedMethods.add(method.getKey());
                }
            }
            // A method that disappeared is as much a change as one that was edited: anything that
            // exercised it has to be re-run to find out what it does now.
            for (String gone : before.methodHashes().keySet()) {
                if (!after.methodHashes().containsKey(gone)) {
                    changedMethods.add(gone);
                }
            }
        }

        for (String previousName : snapshot.keySet()) {
            if (!fresh.containsKey(previousName)) {
                removed.add(previousName);
            }
        }

        snapshot = new HashMap<>(fresh);
        return new ChangeSet(changedMethods, abiChanged, added, removed);
    }
}
