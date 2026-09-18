package io.github.eegn.jiro.core.select;

import java.util.Set;

/**
 * What to run this cycle.
 *
 * @param testIds JUnit Platform unique ids, meaningful only when {@code full} is false
 * @param full    run everything; the index cannot answer this change
 * @param reason  shown to the developer, because a tool that silently decides not to run a test
 *                has to be able to say why
 */
public record Selection(Set<String> testIds, boolean full, String reason) {

    public static Selection everything(String reason) {
        return new Selection(Set.of(), true, reason);
    }

    public static Selection nothing(String reason) {
        return new Selection(Set.of(), false, reason);
    }

    public boolean isEmpty() {
        return !full && testIds.isEmpty();
    }

    public int size() {
        return testIds.size();
    }
}
