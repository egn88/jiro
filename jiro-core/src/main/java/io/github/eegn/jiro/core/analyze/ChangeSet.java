package io.github.eegn.jiro.core.analyze;

import java.util.Set;

/**
 * What actually changed between two snapshots of the output directory, expressed in terms the
 * selector can act on.
 *
 * @param changedMethods    keys of methods whose body differs, matching {@code MethodRegistry.key}
 * @param abiChangedClasses classes whose externally visible shape differs
 * @param addedClasses      classes that did not exist in the previous snapshot
 * @param removedClasses    classes present before and gone now
 */
public record ChangeSet(Set<String> changedMethods,
                        Set<String> abiChangedClasses,
                        Set<String> addedClasses,
                        Set<String> removedClasses) {

    public static ChangeSet empty() {
        return new ChangeSet(Set.of(), Set.of(), Set.of(), Set.of());
    }

    public boolean isEmpty() {
        return changedMethods.isEmpty()
                && abiChangedClasses.isEmpty()
                && addedClasses.isEmpty()
                && removedClasses.isEmpty();
    }

    /** One-line summary for the console banner. */
    public String describe() {
        return changedMethods.size() + " method(s), "
                + abiChangedClasses.size() + " ABI change(s), "
                + addedClasses.size() + " added, "
                + removedClasses.size() + " removed";
    }
}
