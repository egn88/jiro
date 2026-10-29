package io.github.eegn.jiro.core.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eegn.jiro.core.analyze.ChangeSet;
import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import io.github.eegn.jiro.core.analyze.FingerprintStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumeCheckTest {

    @TempDir
    Path dir;

    private static final Map<String, String> ENV = Map.of(
            "jiro.version", "0.0.2-SNAPSHOT", "jiro.build", "b1", "java.version", "25.0.1");
    private static final List<ClasspathEntry> CP = List.of(
            new ClasspathEntry("/m2/spring.jar", 100, 1, "d-spring"),
            new ClasspathEntry("/m2/junit.jar", 50, 1, "d-junit"));
    private static final Map<String, String> RES = Map.of("0:application.yml", "r1");
    private static final Map<String, String> SRC = Map.of(
            "src/main/java/a/A.java", "s-a", "src/test/java/a/ATest.java", "s-t");

    private static ResumeState stored() {
        return new ResumeState(ENV, CP, RES, SRC, "idx", Set.of(), 7, Map.of());
    }

    private static ResumeCheck.Current current(Map<String, String> env, List<ClasspathEntry> cp,
                                               Map<String, String> res, Map<String, String> src,
                                               String index) {
        return new ResumeCheck.Current(env, cp, res, src, index);
    }

    @Test
    void anUntouchedTreeResumesWithNothingToRecompile() {
        ResumeCheck.Decision decision =
                ResumeCheck.decide(stored(), current(ENV, CP, RES, SRC, "idx"));

        assertTrue(decision.resume(), decision.reason());
        assertTrue(decision.changedSources().isEmpty());
    }

    @Test
    void anotherJdkForcesABaseline() {
        Map<String, String> env = new HashMap<>(ENV);
        env.put("java.version", "21.0.9");

        ResumeCheck.Decision decision =
                ResumeCheck.decide(stored(), current(env, CP, RES, SRC, "idx"));

        assertFalse(decision.resume());
        assertTrue(decision.reason().contains("java.version"), decision.reason());
    }

    @Test
    void anotherJiroBuildForcesABaseline() {
        Map<String, String> env = new HashMap<>(ENV);
        env.put("jiro.build", "b2");

        assertFalse(ResumeCheck.decide(stored(), current(env, CP, RES, SRC, "idx")).resume());
    }

    @Test
    void aMissingEnvironmentKeyForcesABaseline() {
        Map<String, String> env = new HashMap<>(ENV);
        env.remove("jiro.build");

        assertFalse(ResumeCheck.decide(stored(), current(env, CP, RES, SRC, "idx")).resume());
    }

    @Test
    void aChangedDependencyJarForcesABaseline() {
        List<ClasspathEntry> cp = List.of(CP.get(0),
                new ClasspathEntry("/m2/junit.jar", 50, 1, "d-junit-other"));

        ResumeCheck.Decision decision =
                ResumeCheck.decide(stored(), current(ENV, cp, RES, SRC, "idx"));

        assertFalse(decision.resume());
        assertTrue(decision.reason().contains("junit.jar"), decision.reason());
    }

    @Test
    void anAddedOrReorderedJarForcesABaseline() {
        assertFalse(ResumeCheck.decide(stored(), current(ENV,
                List.of(CP.get(0), CP.get(1), new ClasspathEntry("/m2/x.jar", 1, 1, "x")),
                RES, SRC, "idx")).resume());
        assertFalse(ResumeCheck.decide(stored(), current(ENV,
                List.of(CP.get(1), CP.get(0)), RES, SRC, "idx")).resume());
    }

    @Test
    void theSameJarsAtAnotherPathStillResume() {
        List<ClasspathEntry> moved = List.of(
                new ClasspathEntry("/other/spring.jar", 100, 99, "d-spring"),
                new ClasspathEntry("/other/junit.jar", 50, 99, "d-junit"));

        assertTrue(ResumeCheck.decide(stored(), current(ENV, moved, RES, SRC, "idx")).resume());
    }

    @Test
    void aChangedResourceForcesABaseline() {
        ResumeCheck.Decision decision = ResumeCheck.decide(stored(),
                current(ENV, CP, Map.of("0:application.yml", "r2"), SRC, "idx"));

        assertFalse(decision.resume());
        assertTrue(decision.reason().contains("application.yml"), decision.reason());
    }

    @Test
    void anIndexThatIsNotTheOneSavedWithTheStateForcesABaseline() {
        assertFalse(ResumeCheck.decide(stored(), current(ENV, CP, RES, SRC, "other")).resume());
    }

    @Test
    void aSourceDeletedWhileStoppedForcesABaseline() {
        ResumeCheck.Decision decision = ResumeCheck.decide(stored(),
                current(ENV, CP, RES, Map.of("src/main/java/a/A.java", "s-a"), "idx"));

        assertFalse(decision.resume());
        assertTrue(decision.reason().contains("ATest.java"), decision.reason());
    }

    @Test
    void sourcesEditedOrAddedWhileStoppedAreReportedForRecompilation() {
        Map<String, String> src = new HashMap<>(SRC);
        src.put("src/main/java/a/A.java", "s-a-edited");
        src.put("src/main/java/a/New.java", "s-new");

        ResumeCheck.Decision decision =
                ResumeCheck.decide(stored(), current(ENV, CP, RES, src, "idx"));

        assertTrue(decision.resume());
        assertEquals(Set.of("src/main/java/a/A.java", "src/main/java/a/New.java"),
                decision.changedSources());
    }

    @Test
    void bytecodeThatChangedWhileStoppedIsReportedByTheRestoredSnapshot() {
        FingerprintStore store = new FingerprintStore();
        store.restore(Map.of("a/A", new ClassFingerprint("a/A", "abi",
                Map.of("a/A#one()V", "h1", "a/A#two()V", "h2"), Set.of())));

        ChangeSet changes = store.update(Map.of("a/A", new ClassFingerprint("a/A", "abi",
                Map.of("a/A#one()V", "h1", "a/A#two()V", "h2-recompiled"), Set.of())));

        assertEquals(Set.of("a/A#two()V"), changes.changedMethods(),
                "a change made while jiro was stopped must surface as a change, not be trusted");
        assertTrue(changes.addedClasses().isEmpty());
    }

    @Test
    void jarDigestsAreReusedOnlyWhenSizeAndTimestampMatch() throws Exception {
        Path jar = dir.resolve("lib.jar");
        Files.writeString(jar, "one");
        List<ClasspathEntry> first = Inputs.classpath(List.of(jar), List.of());

        // A forged cache entry with the right key proves the digest is reused, not recomputed.
        ClasspathEntry forged = new ClasspathEntry(first.get(0).path(), first.get(0).size(),
                first.get(0).lastModified(), "cached");
        assertEquals("cached", Inputs.classpath(List.of(jar), List.of(forged)).get(0).digest());

        Files.writeString(jar, "two");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(first.get(0).lastModified() + 5_000));
        String rehashed = Inputs.classpath(List.of(jar), List.of(forged)).get(0).digest();
        assertFalse(rehashed.equals("cached") || rehashed.equals(first.get(0).digest()));
    }

    @Test
    void sourceKeysAreRelativeToTheBaseDirectory() throws Exception {
        Path source = dir.resolve("src/main/java/a/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}");

        Map<String, String> sources = Inputs.sources(dir, List.of(dir.resolve("src/main/java")));

        assertEquals(Set.of("src/main/java/a/A.java"), sources.keySet());
        assertEquals(sources.get("src/main/java/a/A.java"), Inputs.sourceDigest(source));
    }

    @Test
    void resourcesExcludeClassFiles() throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("a"));
        Files.writeString(classes.resolve("a/A.class"), "x");
        Files.writeString(classes.resolve("application.yml"), "y");

        assertEquals(Set.of("0:application.yml"), Inputs.resources(List.of(classes)).keySet());
    }
}
