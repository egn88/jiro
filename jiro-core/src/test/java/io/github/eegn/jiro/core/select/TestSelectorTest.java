package io.github.eegn.jiro.core.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eegn.jiro.core.analyze.ChangeSet;
import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestSelectorTest {

    private final TestSelector selector = new TestSelector();

    @Test
    void withoutAnIndexEverythingRuns() {
        Selection selection = selector.select(
                ChangeSet.empty(), new CoverageIndex(), Set.of("t1"), firewall(Map.of()));

        assertTrue(selection.full(), "an empty index cannot answer any question");
    }

    @Test
    void aChangedMethodSelectsOnlyTheTestsThatRanIt() {
        CoverageIndex index = new CoverageIndex();
        index.record("t1", List.of("a/A#one()V"));
        index.record("t2", List.of("a/B#two()V"));

        Selection selection = selector.select(
                new ChangeSet(Set.of("a/A#one()V"), Set.of(), Set.of(), Set.of()),
                index, Set.of("t1", "t2"), firewall(Map.of()));

        assertFalse(selection.full());
        assertEquals(Set.of("t1"), selection.testIds());
    }

    @Test
    void aChangeTouchingNoCoveredCodeSelectsNothing() {
        CoverageIndex index = new CoverageIndex();
        index.record("t1", List.of("a/A#one()V"));

        Selection selection = selector.select(
                new ChangeSet(Set.of("a/Z#cold()V"), Set.of(), Set.of(), Set.of()),
                index, Set.of("t1"), firewall(Map.of()));

        assertTrue(selection.isEmpty());
    }

    @Test
    void aTestTheIndexHasNeverSeenAlwaysRuns() {
        CoverageIndex index = new CoverageIndex();
        index.record("known", List.of("a/A#one()V"));

        Selection selection = selector.select(
                ChangeSet.empty(), index, Set.of("known", "brandNew"), firewall(Map.of()));

        assertEquals(Set.of("brandNew"), selection.testIds(),
                "dynamic coverage cannot speak for a test that has never run");
    }

    @Test
    void anAbiChangeReachesTestsThroughTheFirewall() {
        CoverageIndex index = new CoverageIndex();
        // t1 never touched Api itself, only its caller.
        index.record("t1", List.of("a/Caller#call()V"));

        Map<String, ClassFingerprint> classes = Map.of(
                "a/Api", new ClassFingerprint("a/Api", "h", Map.of(), Set.of()),
                "a/Caller", new ClassFingerprint("a/Caller", "h", Map.of(), Set.of("a/Api")));

        Selection selection = selector.select(
                new ChangeSet(Set.of(), Set.of("a/Api"), Set.of(), Set.of()),
                index, Set.of("t1"), firewall(classes));

        assertEquals(Set.of("t1"), selection.testIds(),
                "a changed signature can alter code no test entered directly");
    }

    @Test
    void deletedTestsAreNeverRequestedFromTheRunner() {
        CoverageIndex index = new CoverageIndex();
        index.record("gone", List.of("a/A#one()V"));
        index.record("alive", List.of("a/A#one()V"));

        Selection selection = selector.select(
                new ChangeSet(Set.of("a/A#one()V"), Set.of(), Set.of(), Set.of()),
                index, Set.of("alive"), firewall(Map.of()));

        assertEquals(Set.of("alive"), selection.testIds());
    }

    @Test
    void forgettingATestRemovesItFromTheReverseIndex() {
        CoverageIndex index = new CoverageIndex();
        index.record("t1", List.of("a/A#one()V"));
        index.forget("t1");

        assertTrue(index.isEmpty());
        assertTrue(index.testsCovering("a/A#one()V").isEmpty());
    }

    @Test
    void recordingATestAgainReplacesItsPreviousCoverage() {
        CoverageIndex index = new CoverageIndex();
        index.record("t1", List.of("a/A#old()V"));
        index.record("t1", List.of("a/A#new()V"));

        assertTrue(index.testsCovering("a/A#old()V").isEmpty(),
                "stale edges would keep selecting tests for code they no longer touch");
        assertEquals(Set.of("t1"), index.testsCovering("a/A#new()V"));
    }

    private static ClassFirewall firewall(Map<String, ClassFingerprint> classes) {
        return new ClassFirewall(classes);
    }
}
