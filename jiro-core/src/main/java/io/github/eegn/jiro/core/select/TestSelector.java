package io.github.eegn.jiro.core.select;

import io.github.eegn.jiro.core.analyze.ChangeSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns a {@link ChangeSet} into the set of tests worth running.
 *
 * <p>The rules, in the order they are applied:
 * <ol>
 *   <li>With no coverage recorded yet there is nothing to select from, so everything runs once to
 *       build the baseline.</li>
 *   <li>A test the index has never seen always runs. This is the honest answer to the one question
 *       dynamic coverage cannot answer about itself.</li>
 *   <li>A changed method body selects exactly the tests that entered it.</li>
 *   <li>A changed ABI, or a deleted class, selects every test covering anything in the firewall
 *       closure around it.</li>
 * </ol>
 */
public final class TestSelector {

    public Selection select(ChangeSet changes,
                            CoverageIndex index,
                            Set<String> discoveredTests,
                            ClassFirewall firewall) {
        if (index.isEmpty()) {
            return Selection.everything("no coverage recorded yet, building baseline");
        }

        Set<String> selected = new LinkedHashSet<>();

        Set<String> neverRun = new HashSet<>(discoveredTests);
        neverRun.removeAll(index.knownTests());
        selected.addAll(neverRun);

        for (String changedMethod : changes.changedMethods()) {
            selected.addAll(index.testsCovering(changedMethod));
        }

        Set<String> firewallSeeds = new HashSet<>(changes.abiChangedClasses());
        firewallSeeds.addAll(changes.removedClasses());
        if (!firewallSeeds.isEmpty()) {
            for (String impacted : firewall.closureOf(firewallSeeds)) {
                selected.addAll(index.testsCoveringClass(impacted));
            }
        }

        // A test that was deleted or renamed is still in the index; never ask the runner for it.
        selected.retainAll(discoveredTests);

        if (selected.isEmpty()) {
            return Selection.nothing(changes.isEmpty()
                    ? "no observable change"
                    : "changes touch no covered code (" + changes.describe() + ")");
        }
        return new Selection(selected, false, describe(changes, neverRun.size()));
    }

    private static String describe(ChangeSet changes, int neverRunCount) {
        StringBuilder reason = new StringBuilder(changes.describe());
        if (neverRunCount > 0) {
            reason.append(", ").append(neverRunCount).append(" new test(s)");
        }
        return reason.toString();
    }
}
