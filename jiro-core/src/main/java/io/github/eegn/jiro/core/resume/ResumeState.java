package io.github.eegn.jiro.core.resume;

import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a later session needs to prove that the persisted coverage index still describes the
 * code on disk, and to work out what changed while jiro was not running.
 *
 * <p>The state describes one instant: the end of the last cycle whose selected tests all reported.
 * At that instant the class files had {@code fingerprints}, they had been compiled from sources
 * with {@code sources}, and every change up to then had been answered by running the tests it
 * selected. A change that happened later is, by construction, a difference against this record.
 *
 * @param environment  what produced the index and must not have moved: jiro build, JDK, and the
 *                     options that shape instrumentation and discovery. Compared for equality.
 * @param classpath    the test classpath minus the project's own output directories, in order
 * @param resources    digests of the non-class files in the output directories, keyed by
 *                     {@code <root index>:<relative path>}
 * @param sources      digests of the sources the class files were compiled from, keyed by path
 *                     relative to the project base directory
 * @param indexDigest  SHA-256 of the {@code coverage.index} written together with this state
 * @param failingTests tests whose last run failed, or that were selected and never reported
 * @param lastCycleId  so {@code cycleId} keeps increasing across restarts
 * @param fingerprints per-class bodies and ABI digests. References are not stored: the firewall
 *                     always runs on a fresh scan, never on a persisted one.
 */
public record ResumeState(Map<String, String> environment,
                          List<ClasspathEntry> classpath,
                          Map<String, String> resources,
                          Map<String, String> sources,
                          String indexDigest,
                          Set<String> failingTests,
                          long lastCycleId,
                          Map<String, ClassFingerprint> fingerprints) {

    public ResumeState {
        environment = Map.copyOf(environment);
        classpath = List.copyOf(classpath);
        resources = Map.copyOf(resources);
        sources = Map.copyOf(sources);
        failingTests = Set.copyOf(failingTests);
        fingerprints = Map.copyOf(fingerprints);
    }
}
